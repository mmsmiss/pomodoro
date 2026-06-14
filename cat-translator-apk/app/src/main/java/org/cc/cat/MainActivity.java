package org.cc.cat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;
import android.webkit.ConsoleMessage;
import android.util.Log;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final String TAG = "CatTranslator";
    private static final int REQUEST_ALL = 1003;
    private static final int FILECHOOSER_RESULTCODE = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private ServerSocket modelServer;
    private Thread modelServerThread;
    private int modelServerPort = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        );
        getWindow().setStatusBarColor(0xffffb6c1);
        getWindow().setNavigationBarColor(0xffffd4de);

        // Start embedded HTTP server to serve model files to JS fetch()
        startModelServer();

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error: " + errorCode + " - " + description);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded: " + url);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                Log.d(TAG, "onShowFileChooser called");
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;

                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(Intent.createChooser(intent, "选择照片"),
                                           FILECHOOSER_RESULTCODE);
                } catch (Exception e) {
                    Log.e(TAG, "File chooser error: " + e.getMessage());
                    try {
                        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
                        galleryIntent.setType("image/*");
                        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(galleryIntent, FILECHOOSER_RESULTCODE);
                    } catch (Exception ex) {
                        filePathCallback.onReceiveValue(null);
                        filePathCallback = null;
                    }
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                Log.d(TAG, "onPermissionRequest: " + java.util.Arrays.toString(request.getResources()));
                request.grant(request.getResources());
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "JS [" + cm.messageLevel() + "] " + cm.message());
                return true;
            }
        });

        webView.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        // Expose model server port via JS interface (works before page loads)
        webView.addJavascriptInterface(new ModelServerBridge(modelServerPort), "ModelServer");

        webView.loadUrl("file:///android_asset/index.html");
        Log.d(TAG, "Loading HTML from assets, model port=" + modelServerPort);

        requestRuntimePermissions();
    }

    // ── Embedded HTTP model server ─────────────────────────────────────────
    // Serves model/{model.json, *.bin} on a random local port.
    // JS fetch() connects to a real TCP server — 100% reliable on all devices.

    private void startModelServer() {
        modelServerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    modelServer = new ServerSocket(0); // random port
                    modelServerPort = modelServer.getLocalPort();
                    Log.d(TAG, "Model HTTP server started on port " + modelServerPort);

                    while (!Thread.currentThread().isInterrupted()) {
                        Socket client = modelServer.accept();
                        try {
                            handleModelRequest(client);
                        } catch (Exception e) {
                            Log.w(TAG, "Error handling model request: " + e.getMessage());
                        } finally {
                            try { client.close(); } catch (Exception ignored) {}
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Model server error: " + e.getMessage());
                }
            }
        }, "ModelHttpServer");
        modelServerThread.setDaemon(true);
        modelServerThread.start();

        // Wait up to 2 seconds for port to be assigned
        long deadline = System.currentTimeMillis() + 2000;
        while (modelServerPort == 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }
        if (modelServerPort == 0) {
            Log.e(TAG, "Model server failed to bind within timeout");
        }
    }

    private void handleModelRequest(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();

        // Read first line of HTTP request (raw bytes, no BufferedReader for binary safety)
        StringBuilder line = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && b != '\r' && b != '\n') {
            line.append((char) b);
        }
        // Consume rest of headers (until \r\n\r\n)
        int consecutiveNewlines = 0;
        while (true) {
            b = in.read();
            if (b == -1) break;
            if (b == '\n') {
                consecutiveNewlines++;
                if (consecutiveNewlines >= 2) break; // headers end
            } else if (b != '\r') {
                consecutiveNewlines = 0;
            }
        }

        String firstLine = line.toString();
        Log.d(TAG, "HTTP: " + firstLine);

        // Parse: GET /model/model.json HTTP/1.1
        String path = "/";
        int sp1 = firstLine.indexOf(' ');
        int sp2 = firstLine.indexOf(' ', sp1 + 1);
        if (sp1 >= 0 && sp2 > sp1) {
            path = firstLine.substring(sp1 + 1, sp2);
        }

        // Security: only serve files under model/
        if (!path.startsWith("/model/")) {
            String body = "Not Found";
            byte[] resp = ("HTTP/1.0 404 Not Found\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes();
            out.write(resp);
            out.flush();
            return;
        }

        // Map to assets path: /model/foo.bar → model/foo.bar
        String assetPath = path.substring(1); // remove leading /

        try {
            InputStream assetStream = getAssets().open(assetPath);
            byte[] data = readAllBytes(assetStream);
            assetStream.close();

            // Determine content type
            String contentType;
            if (assetPath.endsWith(".json")) {
                contentType = "application/json";
            } else if (assetPath.endsWith(".bin")) {
                contentType = "application/octet-stream";
            } else {
                contentType = "application/octet-stream";
            }

            Log.d(TAG, "Serving: " + assetPath + " (" + data.length + " bytes, " + contentType + ")");

            // Write minimal HTTP response
            String header = "HTTP/1.0 200 OK\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            out.write(header.getBytes("UTF-8"));
            out.write(data);
            out.flush();

        } catch (IOException e) {
            Log.w(TAG, "Asset not found: " + assetPath);
            String body = "Not Found";
            byte[] resp = ("HTTP/1.0 404 Not Found\r\nContent-Length: " + body.length() + "\r\n\r\n" + body).getBytes();
            out.write(resp);
            out.flush();
        }
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private void stopModelServer() {
        if (modelServer != null) {
            try {
                modelServer.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing model server: " + e.getMessage());
            }
            modelServer = null;
        }
        if (modelServerThread != null) {
            modelServerThread.interrupt();
            modelServerThread = null;
        }
    }

    // ── Permissions & lifecycle ───────────────────────────────────────────

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;

        boolean needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;

        if (needCamera || needAudio) {
            java.util.List<String> perms = new java.util.ArrayList<>();
            if (needCamera) perms.add(Manifest.permission.CAMERA);
            if (needAudio) perms.add(Manifest.permission.RECORD_AUDIO);
            ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), REQUEST_ALL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                Log.w(TAG, "Permission denied: " + permissions[i]);
                final String msg;
                if (Manifest.permission.CAMERA.equals(permissions[i])) {
                    msg = "相机权限被拒，拍照不可用。请到系统设置中开启";
                } else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i])) {
                    msg = "麦克风权限被拒，录音不可用。请到系统设置中开启";
                } else {
                    msg = "权限被拒，部分功能不可用";
                }
                webView.post(() -> webView.evaluateJavascript(
                    "if(typeof showPermissionHint==='function')showPermissionHint('" +
                    msg.replace("'", "\\'") + "')", null));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult: req=" + requestCode + " res=" + resultCode);

        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) {
                Log.w(TAG, "filePathCallback is null");
                return;
            }
            if (resultCode == RESULT_OK && data != null) {
                Uri result = data.getData();
                Log.d(TAG, "File selected: " + result);
                if (result != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            result, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) {
                        Log.w(TAG, "takePersistable failed: " + e.getMessage());
                    }
                    filePathCallback.onReceiveValue(new Uri[]{result});
                } else {
                    filePathCallback.onReceiveValue(null);
                }
            } else {
                Log.d(TAG, "File chooser cancelled or no data");
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) {
                webView.goBack();
            } else {
                moveTaskToBack(true);
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        stopModelServer();
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }

    // ── JS bridge: exposes model server port to JavaScript ─────────────────

    public static class ModelServerBridge {
        private final int port;
        ModelServerBridge(int port) { this.port = port; }

        @android.webkit.JavascriptInterface
        public int getPort() { return port; }

        @android.webkit.JavascriptInterface
        public String getModelUrl() { return "http://127.0.0.1:" + port + "/model/"; }
    }
}
