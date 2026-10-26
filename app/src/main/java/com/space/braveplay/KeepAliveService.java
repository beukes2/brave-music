package com.space.braveplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * Keeps the process at foreground importance while MUSIC ON is active.
 *
 * <p>Honor/Huawei power management has a habit of freezing background apps (and killing
 * Brave) after a while. A visible foreground service is the only supported way to tell the
 * system that this app is doing something important, and it also keeps the accessibility
 * control loop running so the radio can recover by itself.</p>
 */
public final class KeepAliveService extends Service {

    private static final String TAG = "BravePlayKeepAlive";
    private static final String CHANNEL = "brave_music_on";
    private static final int NOTIFICATION_ID = 41;

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, KeepAliveService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            // Starting a foreground service from the background is restricted on new
            // Android versions; the toggle from MainActivity is the reliable path.
            Logs.w(TAG, "start: " + t);
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, KeepAliveService.class));
        } catch (Throwable t) {
            Logs.w(TAG, "stop: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            Logs.w(TAG, "startForeground: " + t);
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // nothing
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            NotificationChannel channel = new NotificationChannel(CHANNEL,
                    "Brave Music active", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while MUSIC ON keeps the radio alive");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        } catch (Throwable t) {
            Logs.w(TAG, "channel: " + t);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Brave Music is on")
                .setContentText("Keeping the radio and car buttons alive")
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW);
        return builder.build();
    }
}
