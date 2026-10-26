package com.space.braveplay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Foreground service that records the song Brave is playing into the app's private storage.
 *
 * <p>The user grants screen/audio capture once per app process. The service then follows the
 * radio's current track: every new song gets its own AAC file, and a file is only kept when
 * enough of the song was captured (a skipped song is discarded because it would be a
 * fragment). Saved songs are played by {@link LocalPlayer} instead of Brave.</p>
 */
public final class RecorderService extends Service {

    private static final String TAG = "BravePlayRecorderSvc";

    private static final String CHANNEL = "brave_music_saving";
    private static final int NOTIFICATION_ID = 42;
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_DATA = "data";

    /** A song must have at least this much captured to be worth keeping. */
    private static final long MIN_KEEP_SECONDS = 30L;
    /** ... and this fraction of the known song length. */
    private static final double MIN_KEEP_FRACTION = 0.70;

    private static final String[] BRAVE_PACKAGES = {
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
    };

    private static RecorderService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private AacRecorder recorder;
    private String fileId = "";
    private int fileDuration;
    private String fileTitle = "";
    private Track selected;
    private String pendingId = "";
    private Track pendingTrack;
    private long pendingSince;
    private boolean partialStart;
    private boolean userPaused;
    private String lastNotificationText = "";

    public static boolean isRunning() {
        return instance != null;
    }

    public static void start(Context context, int resultCode, Intent data) {
        if (context == null || data == null || resultCode != Activity.RESULT_OK) {
            return;
        }
        try {
            Intent intent = new Intent(context, RecorderService.class);
            intent.putExtra(EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(EXTRA_DATA, data);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Logs.w(TAG, "start: " + t);
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        Library.prefs(context).edit()
                .putBoolean(Library.KEY_RECORD_LOCAL, false).apply();
        try {
            context.stopService(new Intent(context, RecorderService.class));
        } catch (Throwable t) {
            Logs.w(TAG, "stop: " + t);
        }
    }

    /** Called by the accessibility service when the radio switches songs. */
    public static void onTrackSelected(Context context, Track track) {
        RecorderService service = instance;
        if (service != null) {
            service.setTrack(track);
        }
    }

    /** MUSIC OFF: close and discard anything that was still being captured. */
    public static void onMusicOff() {
        RecorderService service = instance;
        if (service != null) {
            service.finishCurrent(false);
        }
    }

    /**
     * Called every control tick. {@code bravePlaying} is false while a saved copy is playing
     * (there is nothing to capture) and {@code paused} means the user paused deliberately, so
     * the partial file is thrown away.
     */
    public static void onPlayback(boolean bravePlaying, boolean paused, long positionMs) {
        RecorderService service = instance;
        if (service != null) {
            service.setPlayback(bravePlaying, paused, positionMs);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Logs.init(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Saving music locally"));
        } catch (Throwable t) {
            Logs.w(TAG, "startForeground: " + t);
        }

        if (projection != null) {
            // Already capturing; a second consent token is not needed.
            return START_NOT_STICKY;
        }
        if (intent == null) {
            Logs.w(TAG, "restarted without a projection token");
            stopSelf();
            return START_NOT_STICKY;
        }
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent data = getProjectionData(intent);
        if (resultCode != Activity.RESULT_OK || data == null) {
            Logs.w(TAG, "no capture consent");
            stopSelf();
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            Logs.w(TAG, "no MediaProjectionManager");
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            projection = manager.getMediaProjection(resultCode, data);
        } catch (Throwable t) {
            Logs.w(TAG, "getMediaProjection: " + t);
            projection = null;
        }
        if (projection == null) {
            Logs.w(TAG, "projection denied");
            stopSelf();
            return START_NOT_STICKY;
        }
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Logs.w(TAG, "capture stopped by the system");
                finishCurrent(false);
                stopSelf();
            }
        }, handler);

        int[] uids = braveUids();
        if (uids.length == 0) {
            Logs.w(TAG, "Brave is not installed");
            stopSelf();
            return START_NOT_STICKY;
        }
        recorder = new AacRecorder(projection, uids);
        if (!recorder.start()) {
            Logs.w(TAG, "could not start audio capture");
            stopSelf();
            return START_NOT_STICKY;
        }

        Library.prefs(this).edit()
                .putBoolean(Library.KEY_RECORD_LOCAL, true).apply();
        Logs.i(TAG, "recorder started, saving to " + LocalStore.dir(this));
        setTrack(Library.current(this));
        handler.postDelayed(watchdog, 15_000L);
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private Intent getProjectionData(Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                return intent.getParcelableExtra(EXTRA_DATA, Intent.class);
            }
            return intent.getParcelableExtra(EXTRA_DATA);
        } catch (Throwable t) {
            Logs.w(TAG, "projection data: " + t);
            return null;
        }
    }

    @Override
    public void onDestroy() {
        Logs.i(TAG, "recorder service stopping");
        handler.removeCallbacksAndMessages(null);
        finishCurrent(false);
        if (recorder != null) {
            recorder.stop();
            recorder = null;
        }
        if (projection != null) {
            try {
                projection.stop();
            } catch (Throwable ignored) {
                // nothing
            }
            projection = null;
        }
        instance = null;
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
            // nothing
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- recording

    private void setTrack(Track track) {
        if (recorder == null) {
            return;
        }
        String newId = track == null ? "" : track.id;
        if (newId.equals(fileId) || newId.equals(pendingId)) {
            selected = track;
            return;
        }
        finishCurrent(true);
        selected = track;
        pendingId = "";
        pendingTrack = null;
        if (track == null) {
            return;
        }
        if (LocalStore.has(this, track.id)) {
            Logs.i(TAG, "already saved locally, not recording: " + track.id);
            return;
        }
        if (track.durationSec <= 0) {
            Logs.i(TAG, "live stream, not saving: " + track.id);
            return;
        }
        if (userPaused) {
            Logs.i(TAG, "user paused, not recording: " + track.id);
            return;
        }
        // Wait until Brave actually plays the new song: starting at selection time would
        // capture the previous song's tail during the page load.
        pendingId = track.id;
        pendingTrack = track;
        pendingSince = android.os.SystemClock.uptimeMillis();
        Logs.i(TAG, "will record when playback starts: " + track.id);
    }

    private void setPlayback(boolean bravePlaying, boolean paused, long positionMs) {
        userPaused = paused;
        if (paused) {
            // A user pause means the rest of the song is missing: do not keep the fragment,
            // and do not restart mid-song either.
            pendingId = "";
            pendingTrack = null;
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "pause -> discarding partial recording");
                finishCurrent(false);
            }
            return;
        }
        if (bravePlaying && fileId.isEmpty() && !pendingId.isEmpty()
                && android.os.SystemClock.uptimeMillis() - pendingSince >= 3000L) {
            startPending(positionMs);
        }
    }

    private void startPending(long positionMs) {
        if (recorder == null || pendingTrack == null) {
            pendingId = "";
            pendingTrack = null;
            return;
        }
        Track track = pendingTrack;
        pendingId = "";
        pendingTrack = null;
        if (LocalStore.has(this, track.id) || track.durationSec <= 0) {
            return;
        }
        // If recording begins well into the song (consent granted mid-song, service
        // reconnect), the result would be an incomplete file. Record it but do not keep it.
        partialStart = positionMs > 8000L;
        if (partialStart) {
            Logs.i(TAG, "partial start at " + (positionMs / 1000L)
                    + "s -> will discard " + track.id);
        }
        LocalStore.prune(this);
        File part = LocalStore.part(this, track.id);
        if (part.exists()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        recorder.startFile(part);
        fileId = track.id;
        fileDuration = track.durationSec;
        fileTitle = track.displayTitle();
        updateNotification();
    }

    private void finishCurrent(boolean mayKeep) {
        if (fileId.isEmpty()) {
            return;
        }
        String id = fileId;
        int duration = fileDuration;
        fileId = "";
        if (recorder == null) {
            LocalStore.discard(this, id);
            return;
        }
        recorder.endFile();
        long seconds = recorder.lastSeconds();
        long bytes = recorder.lastBytes();
        long need = Math.max(MIN_KEEP_SECONDS, (long) (duration * MIN_KEEP_FRACTION));
        boolean keep = mayKeep && !partialStart && seconds >= need;
        if (keep) {
            boolean saved = LocalStore.commit(this, id);
            Logs.i(TAG, (saved ? "saved " : "could not save ") + id
                    + " (" + seconds + "s, " + (bytes / (1024 * 1024)) + " MB)");
            if (saved) {
                lastNotificationText = "";
                updateNotification();
            }
        } else {
            LocalStore.discard(this, id);
            Logs.i(TAG, "discarded " + id + " (" + seconds + "s of "
                    + duration + "s, need " + need + "s"
                    + (partialStart ? ", partial start" : "") + ")");
        }
        partialStart = false;
    }

    /** Safety net: if the radio stopped advancing, close a file that is far too long. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            if (!fileId.isEmpty()) {
                long current = recorder.currentSeconds();
                if (fileDuration > 0 && current > fileDuration + 60L) {
                    Logs.i(TAG, "watchdog: " + fileId + " is " + current + "s, closing");
                    finishCurrent(true);
                }
            }
            handler.postDelayed(this, 15_000L);
        }
    };

    private int[] braveUids() {
        PackageManager manager = getPackageManager();
        List<Integer> uids = new ArrayList<Integer>();
        for (String pkg : BRAVE_PACKAGES) {
            try {
                uids.add(manager.getPackageInfo(pkg, 0).applicationInfo.uid);
            } catch (PackageManager.NameNotFoundException ignored) {
                // not installed
            }
        }
        int[] out = new int[uids.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = uids.get(i);
        }
        return out;
    }

    // ---------------------------------------------------------------- notification

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
                    "Brave Music saving", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while songs are saved for offline playback");
            channel.setShowBadge(false);
            channel.setSound(null, null);
            manager.createNotificationChannel(channel);
        } catch (Throwable t) {
            Logs.w(TAG, "channel: " + t);
        }
    }

    private void updateNotification() {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            String text = fileId.isEmpty()
                    ? "Saving music locally"
                    : "Saving: " + fileTitle;
            if (text.equals(lastNotificationText)) {
                return;
            }
            lastNotificationText = text;
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Throwable t) {
            Logs.w(TAG, "notify: " + t);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 1, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL);
        } else {
            //noinspection deprecation
            builder = new Notification.Builder(this);
        }
        return builder.setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Brave Music is saving songs")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
