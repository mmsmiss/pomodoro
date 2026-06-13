package org.cc.pomodoro;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

/**
 * JavaScript ↔ Android bridge.
 * Exposed to the WebView as "PomodoroBridge".
 */
public class PomodoroBridge {

    private static final String CHANNEL_ID = "pomodoro_timer";
    private static final int NOTIFY_ID = 1;

    private final Activity activity;
    private final Vibrator vibrator;
    private final NotificationManager notificationManager;
    private boolean screenOn = false;

    public PomodoroBridge(Activity activity) {
        this.activity = activity;
        this.vibrator = (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
        this.notificationManager = (NotificationManager) activity.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Vibrate for the given duration in milliseconds.
     */
    @JavascriptInterface
    public void vibrate(int ms) {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        } catch (Exception ignored) {
            // Vibration permission may be denied
        }
    }

    /**
     * Keep the screen on (prevent auto-lock during timer).
     */
    @JavascriptInterface
    public void keepScreenOn(final boolean enable) {
        activity.runOnUiThread(() -> {
            screenOn = enable;
            if (enable) {
                activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
    }

    /**
     * Send a local notification (shown in the Android notification shade).
     */
    @JavascriptInterface
    public void sendNotification(final String title, final String body) {
        try {
            NotificationCompat.Builder builder = new NotificationCompat.Builder(activity, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setVibrate(new long[]{0, 200, 200, 200});

            NotificationManagerCompat.from(activity).notify(NOTIFY_ID, builder.build());
        } catch (SecurityException ignored) {
            // Notification permission not granted (Android 13+)
            // Silently fail — user can grant permission in Settings
        }
    }
}
