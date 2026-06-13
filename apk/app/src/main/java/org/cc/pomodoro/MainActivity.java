package org.cc.pomodoro;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {

    private static final String CHANNEL_ID = "pomodoro_timer";
    private static final int NOTIFICATION_REQUEST_CODE = 1001;
    private WebView webView;
    private PomodoroBridge bridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full immersion: no title bar, dark status bar
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
        );
        getWindow().setStatusBarColor(0xff1e1e2e);
        getWindow().setNavigationBarColor(0xff1e1e2e);

        // Create notification channel (Android 8.0+)
        createNotificationChannel();

        // Set up WebView
        webView = new WebView(this);
        setContentView(webView);

        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setDatabaseEnabled(true);
        ws.setGeolocationEnabled(false);
        ws.setCacheMode(WebSettings.LOAD_DEFAULT);

        // Keep the WebView inside the app (no external browser)
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient());

        // Expose native features to JavaScript
        bridge = new PomodoroBridge(this);
        webView.addJavascriptInterface(bridge, "PomodoroBridge");

        // Hide scrollbar
        webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        // Load the bundled HTML
        webView.loadUrl("file:///android_asset/index.html");

        // Request notification permission on Android 13+
        requestNotificationPermission();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // Minimize app instead of quitting
            moveTaskToBack(true);
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

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "计时提醒",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("番茄钟计时结束通知");
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_REQUEST_CODE
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // Notification permission result — app continues regardless
    }
}
