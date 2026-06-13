package org.cc.cat;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.PermissionRequest;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final int REQUEST_CAMERA = 1001;
    private static final int REQUEST_AUDIO = 1002;
    private static final int REQUEST_ALL = 1003;
    private static final int FILECHOOSER_RESULTCODE = 2001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        );
        getWindow().setStatusBarColor(0xff1a1a2e);
        getWindow().setNavigationBarColor(0xff1a1a2e);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowContentAccess(true);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.setWebViewClient(new WebViewClient());

        // WebChromeClient: handles file picker (camera/gallery) + permission requests + getUserMedia
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                filePathCallback = callback;

                Intent intent = fileChooserParams.createIntent();
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
                try {
                    startActivityForResult(Intent.createChooser(intent, "选择照片"),
                                           FILECHOOSER_RESULTCODE);
                } catch (Exception e) {
                    // If no app can handle photo selection, try direct gallery
                    try {
                        Intent galleryIntent = new Intent(Intent.ACTION_PICK);
                        galleryIntent.setType("image/*");
                        startActivityForResult(galleryIntent, FILECHOOSER_RESULTCODE);
                    } catch (Exception ignored) {
                        filePathCallback.onReceiveValue(null);
                        filePathCallback = null;
                    }
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Auto-grant WebView mic/camera permissions (we declare them in manifest)
                String[] resources = request.getResources();
                for (String r : resources) {
                    if (r.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        // Android-level permission is handled by requestRuntimePermissions()
                    }
                }
                request.grant(request.getResources());
            }
        });

        webView.setScrollBarStyle(WebView.SCROLLBARS_INSIDE_OVERLAY);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        webView.loadUrl("file:///android_asset/index.html");

        // Request camera + audio permissions on first launch
        requestRuntimePermissions();
    }

    private void requestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;

        boolean needCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;

        if (needCamera && needAudio) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                REQUEST_ALL);
        } else if (needCamera) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        } else if (needAudio) {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // If denied, show a toast-style hint via JS
        for (int i = 0; i < permissions.length; i++) {
            if (grantResults[i] == PackageManager.PERMISSION_DENIED) {
                String perm = permissions[i];
                String msg;
                if (Manifest.permission.CAMERA.equals(perm)) {
                    msg = "相机权限被拒绝，拍照功能不可用。请到系统设置中开启权限。";
                } else if (Manifest.permission.RECORD_AUDIO.equals(perm)) {
                    msg = "麦克风权限被拒绝，录音功能不可用。请到系统设置中开启权限。";
                } else {
                    msg = "权限被拒绝，部分功能不可用。";
                }
                showJsAlert(msg);
            }
        }
    }

    private void showJsAlert(String msg) {
        webView.post(() -> {
            webView.evaluateJavascript(
                "alert('⚠️ " + msg.replace("'", "\\'") + "')",
                null
            );
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (filePathCallback == null) return;
            if (resultCode == RESULT_OK && data != null) {
                Uri result = data.getData();
                if (result != null) {
                    // Take persistable permission so WebView can read it
                    try {
                        getContentResolver().takePersistableUriPermission(
                            result,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        );
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
