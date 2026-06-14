package org.cc.cat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {

    private static final String TAG = "CatTranslator";
    private static final int REQUEST_ALL = 1003;
    private static final int FILECHOOSER_RESULTCODE = 2001;
    private static final int CHUNK_SIZE = 256 * 1024; // 256KB per chunk

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Model data — loaded on background thread, served via Bridge
    private volatile String topologyJson;
    private volatile String weightsManifestJson;
    private volatile byte[] allWeights;
    private volatile boolean modelLoaded = false;

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

        // Start background model loading (don't block UI thread)
        new Thread(this::loadModelInBackground, "ModelLoader").start();

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

        webView.addJavascriptInterface(new ModelBridge(), "ModelBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded, model ready=" + modelLoaded);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;
                try {
                    Intent intent = fileChooserParams.createIntent();
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                    startActivityForResult(Intent.createChooser(intent, "选择照片"), FILECHOOSER_RESULTCODE);
                } catch (Exception e) {
                    Log.e(TAG, "File chooser: " + e.getMessage());
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
                request.grant(request.getResources());
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "JS: " + cm.message());
                return true;
            }
        });

        webView.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        webView.loadUrl("file:///android_asset/index.html");
        Log.d(TAG, "Loading HTML");

        requestRuntimePermissions();
    }

    // ── Background model loading ─────────────────────────────────────────

    private void loadModelInBackground() {
        try {
            // Read model.json
            String modelJson = readAssetString("model/model.json");
            Log.d(TAG, "model.json: " + modelJson.length() + " chars");

            // Extract modelTopology (between "modelTopology": and next field)
            int ts = modelJson.indexOf("\"modelTopology\"");
            int tv = modelJson.indexOf(":", ts) + 1;
            int depth = 0, tStart = -1, tEnd = -1;
            for (int i = tv; i < modelJson.length(); i++) {
                char c = modelJson.charAt(i);
                if (c == '{') { if (depth == 0) tStart = i; depth++; }
                else if (c == '}') { depth--; if (depth == 0) { tEnd = i + 1; break; } }
            }
            topologyJson = (tStart >= 0 && tEnd > tStart) ? modelJson.substring(tStart, tEnd) : "{}";

            // Extract weightsManifest (between "weightsManifest": and next field)
            int ws = modelJson.indexOf("\"weightsManifest\"");
            int wv = modelJson.indexOf("[", ws);
            int wDepth = 0, wEnd = -1;
            for (int i = wv; i < modelJson.length(); i++) {
                char c = modelJson.charAt(i);
                if (c == '[') wDepth++;
                else if (c == ']') { wDepth--; if (wDepth == 0) { wEnd = i + 1; break; } }
            }
            weightsManifestJson = (wEnd > wv) ? modelJson.substring(wv, wEnd) : "[]";

            // Read weight shards
            String[] files = getAssets().list("model");
            java.util.List<String> shards = new java.util.ArrayList<>();
            if (files != null) {
                for (String f : files) {
                    if (f.endsWith(".bin")) shards.add(f);
                }
            }
            java.util.Collections.sort(shards);

            ByteArrayOutputStream allBytes = new ByteArrayOutputStream();
            for (String shard : shards) {
                byte[] data = readAssetBytes("model/" + shard);
                allBytes.write(data);
                Log.d(TAG, "  " + shard + ": " + (data.length / 1024) + " KB");
            }
            allWeights = allBytes.toByteArray();
            Log.d(TAG, "Weights: " + (allWeights.length / 1024 / 1024) + " MB, shards=" + shards.size());

            modelLoaded = true;
            Log.d(TAG, "Model ready");

        } catch (Exception e) {
            Log.e(TAG, "Model load failed: " + e.getMessage(), e);
            // modelLoaded stays false — JS handles gracefully
        }
    }

    // ── JS Bridge ─────────────────────────────────────────────────────────
    // All model data flows through this bridge. No evaluateJavascript for
    // large data — avoids the 10KB-ish size limit and escaping nightmares.

    public class ModelBridge {
        @JavascriptInterface
        public boolean isReady() {
            return modelLoaded && allWeights != null && topologyJson != null;
        }

        @JavascriptInterface
        public String getTopologyJson() {
            return topologyJson != null ? topologyJson : "{}";
        }

        @JavascriptInterface
        public String getWeightsManifest() {
            return weightsManifestJson != null ? weightsManifestJson : "[]";
        }

        @JavascriptInterface
        public int getWeightsTotalLen() {
            return allWeights != null ? allWeights.length : 0;
        }

        @JavascriptInterface
        public String getWeightsChunk(int startByte) {
            try {
                if (allWeights == null) return "";
                int len = Math.min(CHUNK_SIZE, allWeights.length - startByte);
                if (len <= 0) return "";
                byte[] chunk = new byte[len];
                System.arraycopy(allWeights, startByte, chunk, 0, len);
                return Base64.encodeToString(chunk, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.w(TAG, "Chunk err @" + startByte + ": " + e.getMessage());
                return "";
            }
        }
    }

    // ── Asset helpers ─────────────────────────────────────────────────────

    private String readAssetString(String path) throws IOException {
        return new String(readAssetBytes(path), "UTF-8");
    }

    private byte[] readAssetBytes(String path) throws IOException {
        InputStream is = getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
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
                if (Manifest.permission.CAMERA.equals(permissions[i]))
                    msg = "相机权限被拒，拍照不可用";
                else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]))
                    msg = "麦克风权限被拒，录音不可用";
                else
                    msg = "权限被拒";
                webView.post(() -> webView.evaluateJavascript(
                    "if(typeof showPermissionHint==='function')showPermissionHint('" +
                    msg.replace("'", "\\'") + "')", null));
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) return;
            if (resultCode == RESULT_OK && data != null) {
                Uri result = data.getData();
                if (result != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            result, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception ignored) {}
                    filePathCallback.onReceiveValue(new Uri[]{result});
                } else {
                    filePathCallback.onReceiveValue(null);
                }
            } else {
                filePathCallback.onReceiveValue(null);
            }
            filePathCallback = null;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (webView.canGoBack()) webView.goBack();
            else moveTaskToBack(true);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
