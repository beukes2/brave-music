package com.space.braveplay;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadata;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Foreground service that saves whatever is playing in Brave.
 *
 * <p>Capture is independent of MUSIC ON. Once the user has granted the one-time screen/audio
 * capture permission, the service watches Brave's media session. Every video it sees is written
 * to its own AAC file: radio songs keep their library id, any other video becomes a
 * {@code local:auto-...} library entry after it has been saved. A file is kept when it is long
 * enough (and, when the length is known, at least 70% complete); skipped songs and partial
 * starts are discarded.</p>
 */
public final class RecorderService extends Service {

    private static final String TAG = "BravePlayRecorderSvc";

    private static final String CHANNEL = "brave_music_saving";
    private static final int NOTIFICATION_ID = 42;
    private static final String EXTRA_RESULT_CODE = "result_code";
    private static final String EXTRA_DATA = "data";

    /** A song/video must have at least this much captured to be worth keeping. */
    private static final long MIN_KEEP_SECONDS = 30L;
    /** ... and this fraction of the known length. */
    private static final double MIN_KEEP_FRACTION = 0.70;

    /** How often Brave's media session is inspected; independent of the radio's 5 s tick. */
    private static final long OBSERVE_MS = 1_000L;
    /** Wait for playback before opening a file, so the previous page's tail is not captured. */
    private static final long START_GRACE_MS = 3_000L;
    /**
     * Brave/YouTube often starts a page muted and the radio taps unmute a few seconds later.
     * If the first sound is captured within this window, the copy is still worth keeping; the
     * 70% rule below decides whether enough of the video was actually watched.
     */
    private static final long PARTIAL_START_MS = 20_000L;
    /** A paused video is closed after this long, so silence does not fill the file. */
    private static final long PAUSE_IDLE_MS = 2 * 60_000L;
    /** Unknown-length video paused/stopped for this long: close the file and keep it. */
    private static final long UNKNOWN_IDLE_MS = 3 * 60_000L;
    /** Hard stop for an unknown/live stream that never reports a position change. */
    private static final long MAX_FILE_SECONDS = 2 * 60 * 60L;

    private static final String[] BRAVE_PACKAGES = {
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
    };

    private static RecorderService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaProjection projection;
    private AacRecorder recorder;
    private PowerManager.WakeLock wakeLock;

    /** The file currently being written. Empty while idle. */
    private String fileId = "";
    private int fileDuration;
    private String fileTitle = "";
    private Track fileTrack;
    private boolean partialStart;

    /** Identity whose file has already been closed, so it is not restarted at the end. */
    private String finishedId = "";

    /** Key/material of the video Brave is showing, used to detect a change. */
    private String observedKey = "";
    private long observedPosition;
    private long lastPositionMs;
    private long lastPositionChangeAt;
    private boolean observedPlaying;

    /** A new video seen and waiting for playback before its file is opened. */
    private String pendingId = "";
    private Track pendingTrack;
    private long pendingSince;
    private long pendingStartPosition;

    private String lastMetaLogKey = "";
    private long lastMetaLogAt;
    private long lastErrorLogAt;
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
        try {
            context.stopService(new Intent(context, RecorderService.class));
        } catch (Throwable t) {
            Logs.w(TAG, "stop: " + t);
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
            startForeground(NOTIFICATION_ID, buildNotification("Capture on \u2013 videos you play are saved"));
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
        Library.removeLocalOnlyMissing(this);
        acquireWakeLock();
        handler.post(observer);
        handler.postDelayed(watchdog, 15_000L);
        Logs.i(TAG, "capture started, saving to " + LocalStore.dir(this));
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
        releaseWakeLock();
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

    // ---------------------------------------------------------------- observing Brave

    private final Runnable observer = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            try {
                observeOnce();
            } catch (Throwable t) {
                Logs.w(TAG, "observe: " + t);
            }
            handler.postDelayed(this, OBSERVE_MS);
        }
    };

    private void observeOnce() {
        long now = SystemClock.uptimeMillis();

        // A saved copy played by this app means Brave is deliberately paused. Close anything
        // still open, but do not stop watching: MUSIC OFF does not affect capture either.
        if (LocalPlayer.isActive()) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "local playback active -> closing Brave recording");
                finishCurrent(true);
            }
            clearPending();
            observedKey = "";
            observedPlaying = false;
            return;
        }

        MediaController controller = braveController();
        if (controller == null) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "Brave media session gone -> closing " + fileId);
                finishCurrent(true);
            }
            clearPending();
            observedKey = "";
            observedPlaying = false;
            return;
        }

        PlaybackState state;
        MediaMetadata meta;
        try {
            state = controller.getPlaybackState();
            meta = controller.getMetadata();
        } catch (Throwable t) {
            logError("read Brave media", t);
            return;
        }

        int stateValue = state == null ? PlaybackState.STATE_NONE : state.getState();
        float speed = state == null ? 0f : state.getPlaybackSpeed();
        long position = state == null ? 0L : state.getPosition();
        if (position < 0L) {
            position = 0L;
        }
        boolean playing = (stateValue == PlaybackState.STATE_PLAYING && speed > 0f)
                || stateValue == PlaybackState.STATE_BUFFERING;
        observedPlaying = playing;

        String title = meta == null ? null
                : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = meta == null ? ""
                : safe(meta.getString(MediaMetadata.METADATA_KEY_ARTIST));
        long durationMs = meta == null ? 0L
                : meta.getLong(MediaMetadata.METADATA_KEY_DURATION);
        String mediaId = meta == null ? null
                : meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        String mediaUri = meta == null ? null
                : meta.getString(MediaMetadata.METADATA_KEY_MEDIA_URI);

        if (!usableTitle(title)) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "Brave metadata cleared -> closing " + fileId);
                finishCurrent(true);
            }
            clearPending();
            observedKey = "";
            if (now - lastMetaLogAt >= 30_000L) {
                lastMetaLogAt = now;
                Logs.i(TAG, "Brave media has no usable title yet (title=\"" + title + "\")");
            }
            return;
        }

        Track known = null;
        if (mediaId != null && !mediaId.isEmpty()) {
            known = Library.findById(this, mediaId);
        }
        if (known == null) {
            known = Library.findByTitle(this, title);
        }

        Track track;
        int targetDuration;
        String key;
        if (known != null) {
            targetDuration = known.durationSec;
            if (targetDuration <= 0 && durationMs > 0L) {
                targetDuration = (int) (durationMs / 1000L);
            }
            track = targetDuration == known.durationSec
                    ? known
                    : new Track(known.id, known.url, known.title, known.author, targetDuration);
            key = known.id;
        } else {
            targetDuration = durationMs > 0L ? (int) (durationMs / 1000L) : 0;
            title = title.trim();
            String id = autoId(title, artist);
            track = new Track(id, "local:" + id, title, artist, targetDuration);
            key = id;
        }

        if (!key.equals(lastMetaLogKey) || now - lastMetaLogAt >= 30_000L) {
            lastMetaLogKey = key;
            lastMetaLogAt = now;
            Logs.i(TAG, "Brave media: title=\"" + title + "\" artist=\"" + artist + "\""
                    + " id=" + mediaId + " uri=" + mediaUri
                    + " duration=" + durationMs + "ms pos=" + position + "ms"
                    + " state=" + stateValue + " speed=" + speed
                    + (known == null ? " (not in library)" : " (" + key + ")"));
        }

        // The built-in lofi stream reports no length. Saving an endless stream would fill the
        // cache, so known live entries are skipped; an unknown *video* is still recorded.
        if (known != null && targetDuration <= 0) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "known live stream -> closing " + fileId);
                finishCurrent(true);
            }
            clearPending();
            observedKey = key;
            return;
        }

        if (LocalStore.has(this, key)) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "file appeared for " + key + " -> closing " + fileId);
                finishCurrent(true);
            }
            clearPending();
            observedKey = key;
            return;
        }

        if (!key.equals(observedKey)) {
            if (!fileId.isEmpty()) {
                Logs.i(TAG, "Brave switched to \"" + track.displayTitle()
                        + "\" -> closing " + fileId);
                finishCurrent(true);
            }
            clearPending();
            finishedId = "";
            observedKey = key;
            observedPosition = position;
            lastPositionMs = position;
            lastPositionChangeAt = now;
            if (playing) {
                queuePending(track, position, now);
            } else {
                Logs.i(TAG, "track seen, waiting for playback: " + key);
            }
            return;
        }

        // Same video: keep the position high-water mark for unknown-length videos and watch
        // for the end / a pause.
        if (position > lastPositionMs) {
            lastPositionMs = position;
        }
        if (Math.abs(position - observedPosition) > 500L) {
            observedPosition = position;
            lastPositionChangeAt = now;
        }

        // YouTube often publishes the length a few seconds after playback begins. Adopt it
        // so the 70% rule and end detection work for this video too.
        if (!fileId.isEmpty() && fileDuration <= 0 && durationMs > 0L) {
            fileDuration = (int) (durationMs / 1000L);
            if (fileTrack != null) {
                fileTrack = new Track(fileTrack.id, fileTrack.url, fileTrack.title,
                        fileTrack.author, fileDuration);
            }
            Logs.i(TAG, "length now known for " + fileId + ": " + fileDuration + "s");
        }
        if (!pendingId.isEmpty() && durationMs > 0L
                && (pendingTrack == null
                || pendingTrack.durationSec < (int) (durationMs / 1000L))) {
            pendingTrack = track;
        }

        if (fileId.isEmpty()) {
            if (key.equals(finishedId)) {
                return;
            }
            if (!playing) {
                return;
            }
            if (pendingId.isEmpty()) {
                queuePending(track, position, now);
                return;
            }
            if (now - pendingSince < START_GRACE_MS) {
                return;
            }
            startPending(pendingTrack == null ? track : pendingTrack, position, now);
            return;
        }

        // The video length is known: when Brave pauses at the end, close immediately instead
        // of waiting for the watchdog.
        if (!playing && fileDuration > 0
                && lastPositionMs >= fileDuration * 1000L - 2_000L) {
            Logs.i(TAG, "video reached its end -> closing " + fileId);
            finishCurrent(true);
            return;
        }

        // A looping page can restart at 0 without ever reporting a pause. The high-water
        // position tells us the first pass finished, so keep one copy instead of the loop.
        if (playing && fileDuration > 0 && position < 3_000L
                && lastPositionMs >= fileDuration * 1000L - 5_000L) {
            Logs.i(TAG, "video restarted at the beginning -> closing " + fileId);
            finishCurrent(true);
            return;
        }

        // A long pause should not fill the rest of the file with silence.
        if (!playing && now - lastPositionChangeAt > PAUSE_IDLE_MS) {
            Logs.i(TAG, "video paused for a while -> closing " + fileId);
            finishCurrent(true);
        }
    }

    private void queuePending(Track track, long positionMs, long now) {
        pendingId = track == null ? "" : track.id;
        pendingTrack = track;
        pendingSince = now;
        pendingStartPosition = positionMs;
    }

    private void startPending(Track track, long positionMs, long now) {
        if (recorder == null || track == null) {
            clearPending();
            return;
        }
        String id = track.id;
        // Keep a duration that arrived after the track was first seen.
        if (pendingTrack != null && pendingTrack.durationSec > track.durationSec) {
            track = pendingTrack;
        }
        long startedAt = Math.max(pendingStartPosition, positionMs);
        clearPending();
        if (LocalStore.has(this, id)) {
            return;
        }
        if (track.durationSec <= 0 && !track.isLocalOnly()) {
            Logs.i(TAG, "live stream, not saving: " + id);
            return;
        }
        partialStart = startedAt > PARTIAL_START_MS;
        if (partialStart) {
            Logs.i(TAG, "partial start at " + (startedAt / 1000L)
                    + "s -> will discard " + id);
        }
        LocalStore.prune(this);
        File part = LocalStore.part(this, id);
        if (part.exists()) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
        recorder.startFile(part);
        fileId = id;
        fileDuration = track.durationSec;
        fileTitle = track.displayTitle();
        fileTrack = track;
        lastPositionMs = Math.max(lastPositionMs, positionMs);
        lastPositionChangeAt = now;
        updateNotification();
        Logs.i(TAG, "recording to " + part.getName() + " (\"" + fileTitle + "\", "
                + (fileDuration > 0 ? fileDuration + "s" : "unknown length") + ")");
    }

    private void clearPending() {
        pendingId = "";
        pendingTrack = null;
    }

    /**
     * Closes the open file. {@code mayKeep} is false when the service is stopping; otherwise
     * complete-enough files are committed and unknown-length videos are added to the library.
     */
    private void finishCurrent(boolean mayKeep) {
        if (fileId.isEmpty()) {
            return;
        }
        String id = fileId;
        int duration = fileDuration;
        String title = fileTitle;
        Track track = fileTrack;
        long watchedMs = lastPositionMs;
        boolean partial = partialStart;

        fileId = "";
        fileDuration = 0;
        fileTitle = "";
        fileTrack = null;
        partialStart = false;

        if (recorder == null) {
            LocalStore.discard(this, id);
            return;
        }
        recorder.endFile();
        long seconds = recorder.lastSeconds();
        long bytes = recorder.lastBytes();
        // The file can be longer than the content (pauses/loops). Judge completeness by the
        // furthest position Brave reported, not by the raw file length.
        long watchedSeconds = lastPositionMs > 0L ? lastPositionMs / 1000L : seconds;
        boolean keep = mayKeep && !partial && seconds >= MIN_KEEP_SECONDS;
        long need = MIN_KEEP_SECONDS;
        if (duration > 0) {
            need = Math.max(MIN_KEEP_SECONDS, (long) (duration * MIN_KEEP_FRACTION));
            keep = keep && watchedSeconds >= need;
        }

        if (keep) {
            boolean saved = LocalStore.commit(this, id);
            if (saved) {
                int finalDuration = duration;
                if (finalDuration <= 0) {
                    long hint = watchedMs > 0L ? watchedMs / 1000L : seconds;
                    finalDuration = (int) Math.max(1L, hint);
                }
                if (track != null && track.isLocalOnly()
                        && Library.findById(this, id) == null) {
                    Library.add(this, new Track(id, "local:" + id, track.title,
                            track.author, finalDuration));
                    Logs.i(TAG, "added autosaved video to the library: \"" + title
                            + "\" (" + finalDuration + "s)");
                }
                Logs.i(TAG, "saved " + id + " (" + seconds + "s, "
                        + (bytes / (1024 * 1024)) + " MB)");
                lastNotificationText = "";
                updateNotification();
            } else {
                Logs.w(TAG, "could not save " + id);
            }
        } else {
            LocalStore.discard(this, id);
            Logs.i(TAG, "discarded " + id + " (watched " + watchedSeconds + "s, file "
                    + seconds + "s of "
                    + (duration > 0 ? duration + "s" : "unknown length")
                    + ", need " + need + "s"
                    + (partial ? ", partial start" : "") + ")");
        }
        finishedId = id;
        lastPositionMs = 0L;
        lastPositionChangeAt = SystemClock.uptimeMillis();
    }

    /** Safety net: close a file that is far too long or stuck on a paused unknown video. */
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (recorder == null) {
                return;
            }
            if (!fileId.isEmpty()) {
                long current = recorder.currentSeconds();
                long now = SystemClock.uptimeMillis();
                if (fileDuration > 0 && current > fileDuration + 60L) {
                    Logs.i(TAG, "watchdog: " + fileId + " is " + current + "s, closing");
                    finishCurrent(true);
                } else if (fileDuration <= 0 && !observedPlaying
                        && now - lastPositionChangeAt > UNKNOWN_IDLE_MS) {
                    Logs.i(TAG, "watchdog: unknown-length " + fileId
                            + " paused, closing");
                    finishCurrent(true);
                } else if (current > MAX_FILE_SECONDS) {
                    Logs.i(TAG, "watchdog: " + fileId + " is too long, closing");
                    finishCurrent(true);
                }
            }
            handler.postDelayed(this, 15_000L);
        }
    };

    private MediaController braveController() {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return null;
            }
            // Brave keeps a media session per tab, and old tabs can stay in the list with
            // the same title. Prefer the most recently updated playing session that has a
            // real title; a generic "YouTube" session is a placeholder and can otherwise
            // hide the video that is actually playing.
            MediaController playingTitled = null;
            long playingTitledAt = Long.MIN_VALUE;
            MediaController playingAny = null;
            long playingAnyAt = Long.MIN_VALUE;
            MediaController titled = null;
            long titledAt = Long.MIN_VALUE;
            MediaController any = null;
            long anyAt = Long.MIN_VALUE;
            for (MediaController controller : manager.getActiveSessions(
                    new ComponentName(this, MediaListenerService.class))) {
                String pkg = controller.getPackageName();
                if (pkg == null || !pkg.startsWith("com.brave.browser")) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                long updated = state == null ? 0L : state.getLastPositionUpdateTime();
                boolean playing = state != null
                        && state.getState() == PlaybackState.STATE_PLAYING
                        && state.getPlaybackSpeed() > 0f;
                MediaMetadata meta = controller.getMetadata();
                String title = meta == null ? null
                        : meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                boolean hasTitle = usableTitle(title);
                if (playing && hasTitle && updated >= playingTitledAt) {
                    playingTitled = controller;
                    playingTitledAt = updated;
                }
                if (playing && updated >= playingAnyAt) {
                    playingAny = controller;
                    playingAnyAt = updated;
                }
                if (hasTitle && updated >= titledAt) {
                    titled = controller;
                    titledAt = updated;
                }
                if (updated >= anyAt) {
                    any = controller;
                    anyAt = updated;
                }
            }
            if (playingTitled != null) {
                return playingTitled;
            }
            if (titled != null) {
                return titled;
            }
            return playingAny != null ? playingAny : any;
        } catch (Throwable t) {
            logError("Brave media sessions", t);
            return null;
        }
    }

    private void logError(String what, Throwable t) {
        long now = SystemClock.uptimeMillis();
        if (now - lastErrorLogAt >= 60_000L) {
            lastErrorLogAt = now;
            Logs.w(TAG, what + ": " + t);
        }
    }

    private static boolean usableTitle(String title) {
        if (title == null || title.trim().isEmpty()) {
            return false;
        }
        String value = title.trim().toLowerCase(Locale.ROOT);
        return !("youtube".equals(value)
                || "m.youtube.com".equals(value)
                || "www.youtube.com".equals(value)
                || "brave".equals(value)
                || "new tab".equals(value)
                || "about:blank".equals(value));
    }

    private static String autoId(String title, String artist) {
        String source = normalize(title) + "|" + normalize(artist);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("auto-");
            for (byte value : hash) {
                sb.append(Character.forDigit((value >> 4) & 0xf, 16));
                sb.append(Character.forDigit(value & 0xf, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "auto-" + Integer.toHexString(source.hashCode());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

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

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm == null) {
                    return;
                }
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BravePlay:recorder");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable t) {
            Logs.w(TAG, "wake lock: " + t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
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
                    "Brave Music capture", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shown while videos played in Brave are saved locally");
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
                    ? "Capture on \u2013 videos you play are saved"
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
                .setContentTitle("Brave Music capture is on")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .build();
    }
}
