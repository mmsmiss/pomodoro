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
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // WebViewAssetLoader: official AndroidX API for serving assets
        // Maps https://appassets.androidplatform.net/assets/ → app assets/
        // Maps https://appassets.androidplatform.net/model/  → app assets/model/
        // Intercepts at the Chromium engine level — fetch(), XHR, <script>, <img> ALL work
        final WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/model/",  new WebViewAssetLoader.AssetsPathHandler(this))
            .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                    android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request);
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return assetLoader.shouldInterceptRequest(url);
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

        // CRITICAL: load the page via WebViewAssetLoader URL, NOT file://
        // This ensures the page origin is https://, so fetch() and <script src="">
        // both resolve through the AssetLoader.
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
        Log.d(TAG, "Loading via AssetLoader");

        requestRuntimePermissions();
    }

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
                    ? "相机权限被拒，拍照不可用" : "麦克风权限被拒，录音不可用";
                webView.post(() -> webView.evaluateJavascript(
                    "if(typeof showPermissionHint==='function')showPermissionHint('" +
                    m.replace("'", "\\'") + "')", null));
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
