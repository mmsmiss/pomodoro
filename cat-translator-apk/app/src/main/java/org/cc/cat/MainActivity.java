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
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final String TAG = "CatTranslator";
    private static final int REQUEST_ALL = 1003;
    private static final int FILECHOOSER_RESULTCODE = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

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

        // Enable remote debugging (Chrome inspect)
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
                // Inject model files directly into JS memory — no network needed!
                injectModelData();
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

        // Load HTML
        webView.loadUrl("file:///android_asset/index.html");
        Log.d(TAG, "Loading HTML from assets");

        // Request permissions
        requestRuntimePermissions();
    }

    /**
     * Read asset file as String (UTF-8).
     */
    private String readAssetAsString(String path) throws IOException {
        InputStream is = getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toString("UTF-8");
    }

    /**
     * Read asset file as raw bytes.
     */
    private byte[] readAssetBytes(String path) throws IOException {
        InputStream is = getAssets().open(path);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    /**
     * Read model files from assets/model/, combine weight shards,
     * base64 encode everything, and inject into JavaScript globals.
     * The JS side uses tf.io.fromMemory() — zero network access.
     */
    private void injectModelData() {
        try {
            // 1. Read model.json
            String modelJson = readAssetAsString("model/model.json");
            Log.d(TAG, "Model JSON read: " + modelJson.length() + " chars");

            // 2. Find all .bin shard files in assets/model/
            String[] assetFiles = getAssets().list("model");
            java.util.List<String> shards = new java.util.ArrayList<>();
            for (String f : assetFiles) {
                if (f.endsWith(".bin")) {
                    shards.add(f);
                }
            }
            java.util.Collections.sort(shards);
            Log.d(TAG, "Found " + shards.size() + " weight shard(s): " + shards);

            // 3. Concatenate all shards into one byte array
            ByteArrayOutputStream weightStream = new ByteArrayOutputStream();
            for (String shard : shards) {
                byte[] data = readAssetBytes("model/" + shard);
                weightStream.write(data);
                Log.d(TAG, "  Shard " + shard + ": " + (data.length / 1024) + " KB");
            }
            byte[] allWeights = weightStream.toByteArray();

            // 4. Base64 encode the weight bytes
            String weightsB64 = Base64.encodeToString(allWeights, Base64.NO_WRAP);
            Log.d(TAG, "Weights total: " + (allWeights.length / 1024) + " KB, base64: " + (weightsB64.length() / 1024) + " KB");

            // 5. Escape modelJson for safe injection into JS string
            //    modelJson is valid JSON, but we need to escape backticks and ${} for template literals
            String safeJson = modelJson
                .replace("\\", "\\\\")
                .replace("`", "\\`")
                .replace("${", "\\${");

            // 6. Inject into JavaScript (using template literal for the JSON to avoid quote hell)
            String js = "window.__CAT_MODEL_JSON_STR__=`" + safeJson + "`;" +
                        "window.__CAT_WEIGHTS_B64__='" + weightsB64 + "';" +
                        "console.log('Model injected: json='+window.__CAT_MODEL_JSON_STR__.length+' weightsB64='+window.__CAT_WEIGHTS_B64__.length)";

            webView.evaluateJavascript(js, new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    Log.d(TAG, "Model injection complete: " + value);
                }
            });

        } catch (IOException e) {
            Log.e(TAG, "Failed to inject model data: " + e.getMessage());
            // Model not injected — JS will fall back to CDN
            webView.evaluateJavascript(
                "console.warn('Model injection failed, will try CDN fallback')", null);
        }
    }

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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
