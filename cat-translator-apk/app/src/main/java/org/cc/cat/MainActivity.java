package org.cc.cat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

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

        // ── Copy HTML + model + TF.js to internal storage (same origin) ──
        // All files go to getFilesDir()/app/ so fetch() works same-origin.
        // Runs synchronously on main thread — model is ~13MB, takes ~200ms.
        try {
            prepareAppDir();
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare app dir: " + e.getMessage(), e);
        }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "Page loaded: " + url);
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
                    try {
                        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                        i.setType("image/*");
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(i, FILECHOOSER_RESULTCODE);
                    } catch (Exception ex) {
                        if (filePathCallback != null) {
                            filePathCallback.onReceiveValue(null);
                            filePathCallback = null;
                        }
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

        // Load from internal storage — same origin as model files
        File appDir = new File(getFilesDir(), "app");
        webView.loadUrl("file://" + appDir.getAbsolutePath() + "/index.html");
        Log.d(TAG, "Loading from: " + appDir.getAbsolutePath());

        requestRuntimePermissions();
    }

    // ── Copy everything to one directory ─────────────────────────────────

    private void prepareAppDir() throws IOException {
        File appDir = new File(getFilesDir(), "app");
        appDir.mkdirs();

        // 1. Copy HTML
        copyAsset("index.html", new File(appDir, "index.html"));

        // 2. Copy TF.js
        copyAsset("tf.min.js", new File(appDir, "tf.min.js"));

        // 3. Copy model.json
        File modelDir = new File(appDir, "model");
        modelDir.mkdirs();
        copyAsset("model/model.json", new File(modelDir, "model.json"));

        // 4. Concatenate .bin shards into single weights.bin
        File weightsFile = new File(modelDir, "weights.bin");
        FileOutputStream os = new FileOutputStream(weightsFile);
        String[] files = getAssets().list("model");
        java.util.List<String> shards = new java.util.ArrayList<>();
        if (files != null) {
            for (String f : files) {
                if (f.endsWith(".bin")) shards.add(f);
            }
        }
        java.util.Collections.sort(shards);
        long total = 0;
        for (String shard : shards) {
            byte[] data = readAssetBytes("model/" + shard);
            os.write(data);
            total += data.length;
        }
        os.close();
        Log.d(TAG, "App dir ready. weights.bin: " + (total / 1024 / 1024) + " MB, shards=" + shards.size());
    }

    private void copyAsset(String assetPath, File dest) throws IOException {
        byte[] data = readAssetBytes(assetPath);
        FileOutputStream os = new FileOutputStream(dest);
        os.write(data);
        os.close();
    }

    private byte[] readAssetBytes(String path) throws IOException {
        InputStream is = getAssets().open(path);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    // ── Permissions & lifecycle ───────────────────────────────────────────

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean needCam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        if (needCam || needMic) {
            java.util.List<String> p = new java.util.ArrayList<>();
            if (needCam) p.add(Manifest.permission.CAMERA);
            if (needMic) p.add(Manifest.permission.RECORD_AUDIO);
            ActivityCompat.requestPermissions(this, p.toArray(new String[0]), REQUEST_ALL);
        }
    }

    @Override
    public void onRequestPermissionsResult(int rq, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(rq, perms, grants);
        for (int i = 0; i < perms.length; i++) {
            if (grants[i] == PackageManager.PERMISSION_DENIED) {
                final String m = Manifest.permission.CAMERA.equals(perms[i])
                    ? "相机权限被拒" : "麦克风权限被拒";
                webView.post(() -> webView.evaluateJavascript(
                    "alert('" + m.replace("'", "\\'") + "')", null));
            }
        }
    }

    @Override
    protected void onActivityResult(int rq, int rc, Intent data) {
        super.onActivityResult(rq, rc, data);
        if (rq == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) return;
            if (rc == RESULT_OK && data != null && data.getData() != null) {
                Uri u = data.getData();
                try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) {}
                filePathCallback.onReceiveValue(new Uri[]{u});
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
