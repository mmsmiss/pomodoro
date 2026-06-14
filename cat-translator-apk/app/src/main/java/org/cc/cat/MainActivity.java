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
    private static final int CHUNK_SIZE = 192 * 1024; // 192KB per chunk

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // Preloaded model data
    private String modelTopologyJson;
    private String weightsManifestJson;
    private byte[] allWeights;

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

        // ── Preload model data from assets ──
        preloadModelData();

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

        // Expose model data bridge to JavaScript
        webView.addJavascriptInterface(new ModelBridge(), "ModelBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded: " + url);
                // Inject model metadata once page is ready
                injectModelMeta();
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

        webView.loadUrl("file:///android_asset/index.html");
        Log.d(TAG, "Loading HTML from assets");

        requestRuntimePermissions();
    }

    // ── Model preloading ──────────────────────────────────────────────────
    // Reads ALL model files from assets into memory at startup.
    // JS receives data through the ModelBridge interface — zero fetch(), zero network.

    private void preloadModelData() {
        try {
            // 1. Read model.json
            String modelJson = readAssetString("model/model.json");
            Log.d(TAG, "model.json: " + modelJson.length() + " chars");

            // 2. Extract modelTopology and weightsManifest
            // Simple JSON parsing to avoid adding a JSON library dependency
            int topoStart = modelJson.indexOf("\"modelTopology\"");
            int topoValStart = modelJson.indexOf(":", topoStart) + 1;
            // Navigate to the topology object (nested braces)
            int braceCount = 0, topoObjStart = -1, topoObjEnd = -1;
            for (int i = topoValStart; i < modelJson.length(); i++) {
                char c = modelJson.charAt(i);
                if (c == '{') { if (braceCount == 0) topoObjStart = i; braceCount++; }
                else if (c == '}') { braceCount--; if (braceCount == 0) { topoObjEnd = i + 1; break; } }
            }
            if (topoObjStart >= 0 && topoObjEnd > topoObjStart) {
                modelTopologyJson = modelJson.substring(topoObjStart, topoObjEnd);
            }

            int wmStart = modelJson.indexOf("\"weightsManifest\"");
            int wmValStart = modelJson.indexOf("[", wmStart);
            int wmBracket = 0, wmEnd = -1;
            for (int i = wmValStart; i < modelJson.length(); i++) {
                char c = modelJson.charAt(i);
                if (c == '[') wmBracket++;
                else if (c == ']') { wmBracket--; if (wmBracket == 0) { wmEnd = i + 1; break; } }
            }
            if (wmEnd > wmValStart) {
                weightsManifestJson = modelJson.substring(wmValStart, wmEnd);
            }

            // 3. Read all .bin shard files
            String[] files = getAssets().list("model");
            java.util.List<String> shards = new java.util.ArrayList<>();
            for (String f : files) {
                if (f.endsWith(".bin")) shards.add(f);
            }
            java.util.Collections.sort(shards);

            ByteArrayOutputStream allBytes = new ByteArrayOutputStream();
            for (String shard : shards) {
                byte[] data = readAssetBytes("model/" + shard);
                allBytes.write(data);
                Log.d(TAG, "  " + shard + ": " + (data.length / 1024) + " KB");
            }
            allWeights = allBytes.toByteArray();
            Log.d(TAG, "Weights total: " + (allWeights.length / 1024 / 1024) + " MB");

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model: " + e.getMessage());
        }
    }

    private void injectModelMeta() {
        if (modelTopologyJson == null || weightsManifestJson == null || allWeights == null) {
            Log.e(TAG, "Model data not loaded, skipping injection");
            return;
        }
        // Escape backticks and template expression for safe injection
        String safeTopo = modelTopologyJson
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("${", "\\${");
        String safeWM = weightsManifestJson
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("${", "\\${");

        String js = "window.__MODEL_TOPO__=`" + safeTopo + "`;" +
                    "window.__MODEL_WM__=`" + safeWM + "`;" +
                    "window.__MODEL_WEIGHTS_LEN__=" + allWeights.length + ";" +
                    "console.log('Model meta injected: topo='+window.__MODEL_TOPO__.length+' wm='+window.__MODEL_WM__.length+' weightsLen='+window.__MODEL_WEIGHTS_LEN__)";

        webView.evaluateJavascript(js, v -> Log.d(TAG, "Model meta injection: " + v));
    }

    // ── JS Bridge: delivers model weight data in chunks ───────────────────

    public class ModelBridge {
        @JavascriptInterface
        public boolean isReady() {
            return allWeights != null && allWeights.length > 0;
        }

        @JavascriptInterface
        public String getWeightsChunk(int startByte) {
            try {
                int len = Math.min(CHUNK_SIZE, allWeights.length - startByte);
                if (len <= 0) return "";
                byte[] chunk = new byte[len];
                System.arraycopy(allWeights, startByte, chunk, 0, len);
                return Base64.encodeToString(chunk, Base64.NO_WRAP);
            } catch (Exception e) {
                Log.w(TAG, "Chunk error at " + startByte + ": " + e.getMessage());
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
                    msg = "相机权限被拒，拍照不可用。请到系统设置中开启";
                else if (Manifest.permission.RECORD_AUDIO.equals(permissions[i]))
                    msg = "麦克风权限被拒，录音不可用。请到系统设置中开启";
                else
                    msg = "权限被拒，部分功能不可用";
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
            if (filePathCallback == null) { Log.w(TAG, "filePathCallback is null"); return; }
            if (resultCode == RESULT_OK && data != null) {
                Uri result = data.getData();
                Log.d(TAG, "File selected: " + result);
                if (result != null) {
                    try {
                        getContentResolver().takePersistableUriPermission(
                            result, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    } catch (Exception e) { Log.w(TAG, "takePersistable failed: " + e.getMessage()); }
                    filePathCallback.onReceiveValue(new Uri[]{result});
                } else { filePathCallback.onReceiveValue(null); }
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
