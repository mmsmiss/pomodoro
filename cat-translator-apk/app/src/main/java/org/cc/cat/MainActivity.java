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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;
import android.webkit.ConsoleMessage;
import android.util.Log;
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
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                // Serve bundled model files via fake HTTPS URL so fetch() works
                if (url.startsWith("https://appassets.androidplatform.net/")) {
                    String assetPath = url.substring("https://appassets.androidplatform.net/".length());
                    try {
                        InputStream is = getAssets().open(assetPath);
                        String mime;
                        String encoding;
                        if (assetPath.endsWith(".json")) {
                            mime = "application/json";
                            encoding = "UTF-8";
                        } else if (assetPath.endsWith(".bin")) {
                            mime = "application/octet-stream";
                            encoding = null;  // binary — MUST be null, never "UTF-8"
                        } else {
                            mime = "application/octet-stream";
                            encoding = null;
                        }
                        Log.d(TAG, "Serving asset: " + assetPath + " (" + mime + ")");
                        return new WebResourceResponse(mime, encoding, is);
                    } catch (IOException e) {
                        Log.w(TAG, "Asset not found: " + assetPath);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

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

        // Load HTML
        webView.loadUrl("file:///android_asset/index.html");
        Log.d(TAG, "Loading HTML from assets");

        // Request permissions
        requestRuntimePermissions();
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
