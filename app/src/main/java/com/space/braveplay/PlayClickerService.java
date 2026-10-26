package com.space.braveplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Music keeper for Brave.
 *
 * While "MUSIC ON" is active it:
 *  - taps "Tap to unmute" / "Play" as soon as YouTube shows the player, so videos start
 *    with sound instead of muted autoplay,
 *  - plays the playlist one song after another; a song played in the last hour is skipped,
 *  - when every link has been played, asks YouTube for another song of the same genre,
 *  - checks every 20 s whether music is actually playing and, if not, reloads the page or
 *    reopens the current video so the stream never silently stops,
 *  - keeps media volume audible and Bluetooth on.
 *
 * While the toggle is off it does nothing at all: no taps, no reloads, no launches.
 */
public class PlayClickerService extends AccessibilityService {

    private static final String TAG = "BravePlayClicker";

    private static final long TICK_MS = 5_000L;
    private static final long HEARTBEAT_MS = 20_000L;
    private static final long BLUETOOTH_RETRY_MS = 60_000L;
    private static final long CLICK_COOLDOWN_MS = 1_000L;
    private static final long LOAD_GRACE_MS = 8_000L;
    /** A brand-new page needs longer: it may be loading and the startup scans run up to 25 s. */
    private static final long COLD_LOAD_GRACE_MS = 30_000L;
    private static final long MIN_ACTION_DELAY_MS = 20_000L;
    private static final long MAX_ACTION_DELAY_MS = 90_000L;
    /** How long playback may be silent before the keeper reloads/reopens. */
    private static final long SILENCE_TRIGGER_MS = 8_000L;

    /** Scans after a page load; the player shows up somewhere in this window. */
    private static final long[] STARTUP_SCANS_MS = {800L, 2000L, 4000L, 7000L, 12000L, 18000L, 25000L};

    private static final String[] UNMUTE_LABELS = {"tap to unmute", "unmute", "unmute video"};
    private static final String[] PLAY_LABELS = {"play video", "play"};
    private static final String[] RELOAD_LABELS = {"reload this page", "reload"};
    private static final String[] DISMISS_LABELS = {
            "accept all", "reject all", "not now", "no thanks", "dismiss"
    };

    private static final String[] BRAVE_PACKAGES = {
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
    };

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;
    private PowerManager.WakeLock screenWake;
    private long lastClickAt;
    private long nextActionAt;
    private long actionDelay = MIN_ACTION_DELAY_MS;
    private long lastBluetoothAttempt;
    private long lastHeartbeatAt;
    private long silentSince;
    private long lastTransportPlayAt;
    private boolean transportAttempted;
    private long lastLockedLogAt;
    private long lastBackgroundScanLog;
    private boolean quickSettingsBusy;
    private boolean advancing;
    private boolean userPaused;
    private boolean startedSinceOpen;
    private String trackedUrl = "";
    private long playedMs;
    private long playingSince;
    private ContentObserver volumeObserver;
    private MediaSession mediaSession;
    private String lastSessionTrack = "";
    private final SilentAudio silentAudio = new SilentAudio();
    private MediaSessionManager mediaSessionManager;
    private MediaSessionManager.OnMediaKeyEventSessionChangedListener sessionChangedListener;
    private long lastBumpAt;
    private long lastBravePauseAt;
    private final ArrayDeque<String> previousUrls = new ArrayDeque<String>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Runs once a second while MUSIC ON. The recorder uses this to start at the beginning
     * of a song (the 5 s control tick would miss too much of the intro).
     */
    private final Runnable recorderTick = new Runnable() {
        @Override
        public void run() {
            if (!isMusicOn()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            boolean local = LocalPlayer.isActive();
            boolean playing = isMusicPlaying();
            RecorderService.onPlayback(playing && !local, userPaused,
                    currentPositionMs(playing, now));
            handler.postDelayed(this, 1000L);
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isMusicOn()) {
                return;
            }
            try {
                controlTick();
            } catch (Throwable t) {
                Logs.w(TAG, "tick: " + t);
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
                    if (Library.KEY_MUSIC_ON.equals(key)) {
                        syncWakeLock();
                        if (isMusicOn()) {
                            userPaused = false;
                            transportAttempted = false;
                            ensureMediaSession();
                            ensureAudibleVolume();
                            ensureBluetoothOn();
                            KeepAliveService.start(PlayClickerService.this);
                            startTicker();
                            resumeCurrentIfLocal();
                            nextActionAt = 0;
                            actionDelay = MIN_ACTION_DELAY_MS;
                            scanSoon(STARTUP_SCANS_MS);
                        } else {
                            stopTicker();
                            releaseMediaSession();
                            KeepAliveService.stop(PlayClickerService.this);
                            RecorderService.onMusicOff();
                            LocalPlayer.stop();
                        }
                    } else if (Library.KEY_URL.equals(key)) {
                        nextActionAt = 0;
                        actionDelay = MIN_ACTION_DELAY_MS;
                        resetProgress(sharedPreferences.getString(Library.KEY_URL, ""));
                        scanSoon(STARTUP_SCANS_MS);
                    } else if (Library.KEY_SESSION.equals(key)) {
                        // Replaying the same URL also needs a clean progress count.
                        nextActionAt = 0;
                        actionDelay = MIN_ACTION_DELAY_MS;
                        resetProgress(sharedPreferences.getString(Library.KEY_URL, ""));
                        scanSoon(STARTUP_SCANS_MS);
                    }
                }
            };

    /** Unlock/turn-on instantly retries playback instead of waiting for the backoff. */
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                // A pause is often sent when the car/headset disconnects. Coming back should
                // start the radio again instead of staying paused forever.
                Logs.i(TAG, "bluetooth connected");
                if (isMusicOn() && userPaused) {
                    userPaused = false;
                    nextActionAt = 0L;
                    actionDelay = MIN_ACTION_DELAY_MS;
                    silentSince = 0L;
                    transportAttempted = false;
                    lastTransportPlayAt = 0L;
                    forwardToBrave(true);
                    scanSoon(STARTUP_SCANS_MS);
                }
                return;
            }
            Logs.i(TAG, "screen event: " + action);
            if (Intent.ACTION_SCREEN_ON.equals(action)
                    || Intent.ACTION_USER_PRESENT.equals(action)) {
                nextActionAt = 0L;
                actionDelay = MIN_ACTION_DELAY_MS;
                silentSince = 0L;
                transportAttempted = false;
                lastTransportPlayAt = 0L;
                scanSoon(STARTUP_SCANS_MS);
            }
        }
    };

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            if (!isMusicOn() || userPaused || LocalPlayer.isActive()) {
                return;
            }
            if (isBraveForeground() || (keyguardLocked() && hasBraveWindow())) {
                scanAndClick(!isMusicPlaying());
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        Logs.init(this);
        prefs = Library.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        registerVolumeObserver();
        registerScreenReceiver();
        syncWakeLock();
        Library.cleanupShorts(this);
        LocalPlayer.setOnComplete(new Runnable() {
            @Override
            public void run() {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isMusicOn()) {
                            advance("local song finished");
                        }
                    }
                });
            }
        });
        playedMs = prefs.getLong(Library.KEY_PLAYED_MS, 0L);
        trackedUrl = prefs.getString(Library.KEY_URL, "");
        if (isMusicOn()) {
            userPaused = false;
            transportAttempted = false;
            ensureMediaSession();
            ensureAudibleVolume();
            ensureBluetoothOn();
            KeepAliveService.start(this);
            startTicker();
            resumeCurrentIfLocal();
        }
        scanSoon(STARTUP_SCANS_MS);
        Logs.i(TAG, "connected, music=" + isMusicOn()
                + " track=" + Urls.videoId(trackedUrl)
                + " notificationAccess=" + hasNotificationAccess());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence pkgName = event.getPackageName();
        if (pkgName == null || !pkgName.toString().startsWith("com.brave.browser")) {
            return;
        }
        scanSoon(new long[]{900L});
    }

    @Override
    public void onInterrupt() {
        // nothing to interrupt
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        stopTicker();
        releaseWakeLock();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
            // not registered
        }
        try {
            if (screenWake != null && screenWake.isHeld()) {
                screenWake.release();
            }
        } catch (Exception ignored) {
            // nothing
        }
        releaseMediaSession();
        LocalPlayer.setOnComplete(null);
        LocalPlayer.stop();
        executor.shutdownNow();
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        Logs.i(TAG, "service destroyed");
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
                // nothing
            }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- control loop

    /**
     * Runs every 5 s: keeps the stream alive, rotates finished songs, forwards the
     * play/pause state to the car and writes a heartbeat to the log every 20 s.
     */
    private void controlTick() {
        ensureAudibleVolume();
        ensureBluetoothOn();

        String url = prefs.getString(Library.KEY_URL, "");
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (!url.equals(trackedUrl)) {
            resetProgress(url);
        }

        long now = SystemClock.uptimeMillis();
        boolean playing = isMusicPlaying();
        boolean local = LocalPlayer.isActive();
        boolean braveForeground = isBraveForeground();
        if (local) {
            pauseBraveIfPlaying(now);
        }

        if (playing) {
            silentSince = 0L;
            startedSinceOpen = true;
            transportAttempted = false;
            lastTransportPlayAt = 0L;
        } else {
            if (silentSince == 0L) {
                silentSince = now;
            }
            // Fold the playing time before a pause so the pause itself is not counted later.
            if (playingSince != 0L) {
                playedMs += now - playingSince;
                playingSince = 0L;
                prefs.edit().putLong(Library.KEY_PLAYED_MS, playedMs).apply();
            }
        }

        updateMediaSession(playing, now);

        if (advancing) {
            return;
        }

        // The user paused from the car/headset: stay paused, do not fight it.
        if (userPaused && !playing) {
            heartbeat(now, playing, url);
            return;
        }
        if (playing) {
            userPaused = false;
        }

        // End screen ("Replay") means the song is over - move on instead of restarting it.
        if (!playing && !local && braveForeground && hasReplayButton()) {
            advance("replay shown");
            return;
        }

        // Muted playback still has an audio track; always clear the mute overlay. While the
        // keyguard is up, try Brave's window behind it (a locked page still plays audio).
        if (!local && (braveForeground || (keyguardLocked() && hasBraveWindow()))
                && scanAndClick(!playing)) {
            actionDelay = MIN_ACTION_DELAY_MS;
            nextActionAt = 0L;
        }

        if (playing) {
            actionDelay = MIN_ACTION_DELAY_MS;
            nextActionAt = 0L;
        }

        rotationTick(playing, now);

        if (!playing && !advancing) {
            recoverSilence(url, now);
        }

        heartbeat(now, playing, url);
    }

    private void heartbeat(long now, boolean playing, String url) {
        if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
            lastHeartbeatAt = now;
            Logs.i(TAG, "tick: playing=" + playing
                    + " heard=" + (effectivePlayedMs(playing, now) / 1000L) + "s/"
                    + currentDurationSeconds() + "s"
                    + " silent=" + (silentSince == 0L ? 0L : (now - silentSince) / 1000L) + "s"
                    + (keyguardLocked() ? " locked=true" : "")
                    + (userPaused ? " userPaused=true" : "")
                    + " track=" + Urls.videoId(url));
        }
    }

    /** Silence for more than a few seconds: reload or reopen until sound is back. */
    private void recoverSilence(String url, long now) {
        if (userPaused) {
            return;
        }
        // A saved copy needs no browser, no accessibility and no lock-screen tricks; just
        // resume it if it stalled. Its completion callback advances the rotation.
        if (LocalPlayer.isActive()) {
            if (!LocalPlayer.isPlaying() && !LocalPlayer.hasCompleted()) {
                Logs.i(TAG, "silent -> local resume");
                LocalPlayer.resume();
            }
            return;
        }
        long openedAt = prefs.getLong(Library.KEY_OPEN_AT, 0L);
        long grace = startedSinceOpen ? LOAD_GRACE_MS : COLD_LOAD_GRACE_MS;
        if (now - openedAt < grace) {
            return; // page is still loading, the startup scans keep trying
        }
        if (silentSince == 0L || now - silentSince < SILENCE_TRIGGER_MS) {
            return;
        }

        // Ask Brave's own media session to play first: this works even with the screen
        // locked and needs no UI. A user pause returned above, so this is a stall only.
        if (!transportAttempted) {
            if (resumeViaBrave()) {
                transportAttempted = true;
                lastTransportPlayAt = now;
                return;
            }
        }
        if (transportAttempted && now - lastTransportPlayAt < 12_000L) {
            return; // give the play command a moment to take effect
        }

        boolean locked = keyguardLocked();
        if (locked && now - lastLockedLogAt > 60_000L) {
            lastLockedLogAt = now;
            Logs.w(TAG, "silent while locked (" + (now - silentSince) / 1000L
                    + "s): trying to recover behind the lock screen");
        }
        if (now < nextActionAt) {
            return;
        }
        long spacing = locked ? Math.max(actionDelay, 120_000L) : actionDelay;
        nextActionAt = now + spacing;
        if (!locked) {
            actionDelay = Math.min(actionDelay * 2, MAX_ACTION_DELAY_MS);
        }

        if ((isBraveForeground() || (locked && hasBraveWindow())) && tapReload()) {
            prefs.edit().putLong(Library.KEY_OPEN_AT, now).apply();
            silentSince = now;
            startedSinceOpen = false;
            transportAttempted = false;
            Logs.w(TAG, "silent -> reloaded page" + (locked ? " (locked)" : ""));
            scanSoon(STARTUP_SCANS_MS);
            return;
        }

        silentSince = now;
        startedSinceOpen = false;
        transportAttempted = false;
        Logs.w(TAG, "silent -> reopening " + url + (locked ? " (locked)" : ""));
        wakeScreen();
        relaunch(url);
        scanSoon(STARTUP_SCANS_MS);
    }

    /** Un-pauses a stalled page through Brave's media session (works with the screen locked). */
    private boolean resumeViaBrave() {
        MediaController brave = braveController();
        if (brave == null) {
            return false;
        }
        try {
            PlaybackState state = brave.getPlaybackState();
            int value = state == null ? PlaybackState.STATE_NONE : state.getState();
            if (value == PlaybackState.STATE_NONE) {
                return false;
            }
            if (value == PlaybackState.STATE_PLAYING && state.getPlaybackSpeed() > 0f) {
                return false;
            }
            Logs.i(TAG, "silent -> media session play (state=" + value + ")");
            brave.getTransportControls().play();
            return true;
        } catch (Throwable t) {
            Logs.w(TAG, "media session play: " + t);
            return false;
        }
    }

    /** When a saved copy is playing, keep Brave paused so two songs do not overlap. */
    private void pauseBraveIfPlaying(long now) {
        if (now - lastBravePauseAt < 15_000L) {
            return;
        }
        lastBravePauseAt = now;
        MediaController brave = braveController();
        if (brave == null) {
            return;
        }
        try {
            PlaybackState state = brave.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                brave.getTransportControls().pause();
                Logs.i(TAG, "paused Brave while a saved copy plays");
            }
        } catch (Throwable t) {
            Logs.w(TAG, "pause Brave: " + t);
        }
    }

    /**
     * A cold page will not autoplay sound while the screen is off. Only used when the stream
     * already died and reopening is the only way back.
     */    @SuppressWarnings("deprecation")
    private boolean screenOffAndLocked() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) {
                return false;
            }
            return keyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean keyguardLocked() {
        try {
            android.app.KeyguardManager keyguard =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            return keyguard != null && keyguard.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private void wakeScreen() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm == null || pm.isInteractive()) {
                return;
            }
            if (screenOffAndLocked()) {
                // Waking would only show the lock screen; nothing to gain.
                Logs.w(TAG, "screen off but locked: cannot start a cold page");
                return;
            }
            if (screenWake == null) {
                screenWake = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "BravePlay:screen");
                screenWake.setReferenceCounted(false);
            }
            screenWake.acquire(45_000L);
            Logs.i(TAG, "screen woken to restart playback");
        } catch (Throwable t) {
            Logs.w(TAG, "wake screen: " + t);
        }
    }

    // ---------------------------------------------------------------- rotation

    /** Accumulates real playing time and advances finished songs. */
    private void rotationTick(boolean playing, long now) {
        if (playing) {
            if (playingSince == 0L) {
                playingSince = now;
            }
        } else if (playingSince != 0L) {
            playedMs += now - playingSince;
            playingSince = 0L;
            prefs.edit().putLong(Library.KEY_PLAYED_MS, playedMs).apply();
        }

        long effective = effectivePlayedMs(playing, now);
        int durationSec = currentDurationSeconds();

        if (durationSec > 0) {
            long durationMs = durationSec * 1000L;
            if (effective >= durationMs + 2000L) {
                advance("song finished (" + durationSec + "s)");
                return;
            }
            if (!playing && effective >= durationMs - 5000L) {
                advance("song ended");
                return;
            }
        }

        if (!playing && !LocalPlayer.isActive() && isBraveForeground() && hasReplayButton()) {
            advance("replay shown");
        }
    }

    private long effectivePlayedMs(boolean playing, long now) {
        return playedMs + (playing && playingSince != 0L ? now - playingSince : 0L);
    }

    /** The saved file's real length while it plays; YouTube's length otherwise. */
    private int currentDurationSeconds() {
        if (LocalPlayer.isActive()) {
            int local = LocalPlayer.durationSeconds();
            if (local > 0) {
                return local;
            }
        }
        return prefs.getInt(Library.KEY_DURATION, 0);
    }

    /**
     * Playback position for the recorder. The app's own counter resets when the
     * accessibility service reconnects, so prefer Brave's real position when the media
     * session is showing the current song. A position well above zero means the recorder
     * is starting mid-song and must not keep that fragment as a "complete" copy.
     */
    private long currentPositionMs(boolean playing, long now) {
        long position = effectivePlayedMs(playing, now);
        MediaController brave = braveController();
        if (brave == null) {
            return position;
        }
        try {
            PlaybackState state = brave.getPlaybackState();
            if (state == null || state.getState() != PlaybackState.STATE_PLAYING) {
                return position;
            }
            MediaMetadata meta = brave.getMetadata();
            Track current = Library.current(this);
            if (meta == null || current == null) {
                return position;
            }
            String mediaId = meta.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            if ((mediaId != null && mediaId.equals(current.id))
                    || titlesMatch(title, current.title)) {
                return Math.max(position, state.getPosition());
            }
        } catch (Throwable t) {
            Logs.w(TAG, "position: " + t);
        }
        return position;
    }

    private boolean titlesMatch(String fromBrave, String fromLibrary) {
        if (fromBrave == null || fromLibrary == null) {
            return false;
        }
        String a = fromBrave.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        String b = fromLibrary.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (a.length() < 4 || b.length() < 4) {
            return false;
        }
        return a.equals(b) || a.contains(b) || b.contains(a);
    }

    private void resetProgress(String url) {
        trackedUrl = url == null ? "" : url;
        playedMs = 0L;
        playingSince = 0L;
        silentSince = 0L;
        userPaused = false;
        startedSinceOpen = false;
        transportAttempted = false;
        lastTransportPlayAt = 0L;
        prefs.edit().putLong(Library.KEY_PLAYED_MS, 0L).apply();
        Track track = Library.findByUrl(this, trackedUrl);
        if (track == null) {
            track = Library.current(this);
        }
        Logs.i(TAG, "now playing: " + (track == null ? Urls.videoId(trackedUrl)
                : "\"" + track.displayTitle() + "\" (" + track.id + ")"));
        RecorderService.onTrackSelected(this, track);
    }

    /** Picks the next song off the UI thread and starts it. */
    private void advance(final String reason) {
        if (advancing) {
            return;
        }
        advancing = true;
        playedMs = 0L;
        playingSince = 0L;
        prefs.edit().putLong(Library.KEY_PLAYED_MS, 0L).apply();

        Track current = Library.current(this);
        if (current != null) {
            pushPrevious(current.url);
        }
        Logs.i(TAG, "advancing: " + reason + (current == null ? ""
                : " (after \"" + current.displayTitle() + "\")"));

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Track next = null;
                try {
                    Track current = Library.current(PlayClickerService.this);
                    next = Radio.pickNext(PlayClickerService.this, current);
                } catch (Throwable t) {
                    Logs.w(TAG, "pickNext: " + t);
                }
                final Track chosen = next;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        advancing = false;
                        if (chosen == null) {
                            Logs.w(TAG, "nothing to play; retrying in 10 s");
                            nextActionAt = SystemClock.uptimeMillis() + 10_000L;
                            return;
                        }
                        Radio.play(PlayClickerService.this, chosen);
                        resetProgress(chosen.url);
                        scanSoon(STARTUP_SCANS_MS);
                    }
                });
            }
        });
    }

    private void pushPrevious(String url) {
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (!previousUrls.isEmpty() && previousUrls.peekLast().equals(url)) {
            return;
        }
        previousUrls.addLast(url);
        while (previousUrls.size() > 20) {
            previousUrls.removeFirst();
        }
    }

    /** Steering-wheel "next". */
    private void nextFromUser() {
        if (!isMusicOn()) {
            return;
        }
        advance("media button next");
    }

    /** Steering-wheel "previous": plays the song that was playing before. */
    private void previousFromUser() {
        if (!isMusicOn()) {
            return;
        }
        String url = previousUrls.pollLast();
        if (url == null) {
            Logs.i(TAG, "media button previous: no history, restarting current");
            return;
        }
        Track track = Library.findByUrl(this, url);
        if (track == null) {
            track = new Track(Urls.videoId(url), url, "", "", 0);
        }
        Logs.i(TAG, "media button previous -> \"" + track.displayTitle() + "\"");
        Radio.play(this, track);
        resetProgress(track.url);
        scanSoon(STARTUP_SCANS_MS);
    }

    // ---------------------------------------------------------------- media session

    /** Owns the media buttons so the car's next/previous buttons drive our rotation. */
    private void ensureMediaSession() {
        if (mediaSession != null) {
            return;
        }
        try {
            mediaSession = new MediaSession(this, "BraveMusic");
            mediaSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                    | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            mediaSession.setCallback(new MediaSession.Callback() {
                @Override
                public void onSkipToNext() {
                    Logs.i(TAG, "media button: next");
                    nextFromUser();
                }

                @Override
                public void onSkipToPrevious() {
                    Logs.i(TAG, "media button: previous");
                    previousFromUser();
                }

                @Override
                public void onPlay() {
                    Logs.i(TAG, "media button: play");
                    userPaused = false;
                    forwardToBrave(true);
                }

                @Override
                public void onPause() {
                    Logs.i(TAG, "media button: pause");
                    userPaused = true;
                    forwardToBrave(false);
                }

                @Override
                public void onStop() {
                    Logs.i(TAG, "media button: stop");
                    userPaused = true;
                    forwardToBrave(false);
                }
            });
            mediaSession.setActive(true);
            lastSessionTrack = "";
            updateMediaSession(isMusicPlaying(), SystemClock.uptimeMillis());
            // Only start the silent track when we can also read Brave's real state; otherwise
            // isMusicActive() would be true forever and silence would never be detected.
            if (hasNotificationAccess()) {
                silentAudio.start();
                registerMediaKeySessionListener();
            } else {
                Logs.w(TAG, "no notification access: media buttons will go to Brave");
            }
            Logs.i(TAG, "media session created");
        } catch (Throwable t) {
            Logs.w(TAG, "media session: " + t);
            mediaSession = null;
        }
    }

    private void releaseMediaSession() {
        unregisterMediaKeySessionListener();
        silentAudio.stop();
        if (mediaSession == null) {
            return;
        }
        try {
            mediaSession.setActive(false);
            mediaSession.release();
        } catch (Exception ignored) {
            // nothing
        }
        mediaSession = null;
        Logs.i(TAG, "media session released");
    }

    /**
     * Android hands media keys to the session of the app that is producing audio. Brave reloads
     * its player for every video, which moves the keys back to Brave, so watch that and reclaim.
     */
    private void registerMediaKeySessionListener() {
        if (Build.VERSION.SDK_INT < 34 || sessionChangedListener != null) {
            return;
        }
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        if (mediaSessionManager == null) {
            return;
        }
        sessionChangedListener = new MediaSessionManager.OnMediaKeyEventSessionChangedListener() {
            @Override
            public void onMediaKeyEventSessionChanged(String packageName,
                    MediaSession.Token sessionToken) {
                Logs.i(TAG, "media key session -> " + packageName);
                if (isMusicOn() && packageName != null
                        && !packageName.equals(getPackageName())) {
                    bumpSilentTrack("keys moved to " + packageName);
                }
            }
        };
        try {
            mediaSessionManager.addOnMediaKeyEventSessionChangedListener(
                    new java.util.concurrent.Executor() {
                        @Override
                        public void execute(Runnable command) {
                            handler.post(command);
                        }
                    }, sessionChangedListener);
            Logs.i(TAG, "media key session listener registered");
        } catch (Throwable t) {
            Logs.w(TAG, "media key session listener: " + t);
            sessionChangedListener = null;
        }
    }

    private void unregisterMediaKeySessionListener() {
        if (mediaSessionManager != null && sessionChangedListener != null) {
            try {
                mediaSessionManager.removeOnMediaKeyEventSessionChangedListener(
                        sessionChangedListener);
            } catch (Throwable ignored) {
                // nothing
            }
        }
        sessionChangedListener = null;
    }

    private void bumpSilentTrack(String why) {
        long now = SystemClock.uptimeMillis();
        if (now - lastBumpAt < 10_000L) {
            return;
        }
        lastBumpAt = now;
        Logs.i(TAG, "reclaiming media buttons (" + why + ")");
        silentAudio.bump();
    }

    private void updateMediaSession(boolean playing, long now) {
        if (mediaSession == null) {
            return;
        }
        try {
            Track current = Library.current(this);
            if (current != null && !current.id.equals(lastSessionTrack)) {
                lastSessionTrack = current.id;
                MediaMetadata.Builder meta = new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, current.displayTitle())
                        .putString(MediaMetadata.METADATA_KEY_ARTIST, current.displayAuthor());
                if (current.durationSec > 0) {
                    int duration = currentDurationSeconds();
                    meta.putLong(MediaMetadata.METADATA_KEY_DURATION,
                            (duration > 0 ? duration : current.durationSec) * 1000L);
                }
                mediaSession.setMetadata(meta.build());
            }
            long position = effectivePlayedMs(playing, now);
            PlaybackState state = new PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
                            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP
                            | PlaybackState.ACTION_SKIP_TO_NEXT
                            | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                    .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            position, playing ? 1f : 0f, SystemClock.elapsedRealtime())
                    .build();
            mediaSession.setPlaybackState(state);
        } catch (Throwable t) {
            Logs.w(TAG, "media session update: " + t);
        }
    }

    /** Sends play/pause to Brave, which actually owns the audio. */
    private void forwardToBrave(boolean play) {
        if (LocalPlayer.isActive()) {
            if (play) {
                LocalPlayer.resume();
            } else {
                LocalPlayer.pause();
            }
            Logs.i(TAG, "forwarded " + (play ? "play" : "pause") + " to local player");
            return;
        }
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (manager != null) {
                List<MediaController> sessions = manager.getActiveSessions(
                        new ComponentName(this, MediaListenerService.class));
                for (MediaController controller : sessions) {
                    String pkg = controller.getPackageName();
                    if (pkg != null && pkg.startsWith("com.brave.browser")) {
                        if (play) {
                            controller.getTransportControls().play();
                        } else {
                            controller.getTransportControls().pause();
                        }
                        Logs.i(TAG, "forwarded " + (play ? "play" : "pause") + " to " + pkg);
                        return;
                    }
                }
            }
        } catch (Throwable t) {
            Logs.w(TAG, "forward to Brave failed (" + t + ") - notification access missing?");
        }

        // Fallback: tap the player button when Brave is on screen.
        if (isBraveForeground()) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                String wanted = play ? "play" : "pause";
                for (AccessibilityNodeInfo node : flatten(root)) {
                    if (wanted.equals(lower(node.getContentDescription()))
                            || wanted.equals(lower(node.getText()))) {
                        if (clickNode(node)) {
                            Logs.i(TAG, "fallback tapped " + wanted);
                            return;
                        }
                    }
                }
            }
        }
        Logs.w(TAG, "play/pause could not be forwarded");
    }

    private boolean hasNotificationAccess() {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return false;
            }
            manager.getActiveSessions(new ComponentName(this, MediaListenerService.class));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** True when YouTube's end screen ("Replay") is on screen. */
    private boolean hasReplayButton() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        CharSequence pkgName = root.getPackageName();
        if (pkgName == null || !pkgName.toString().startsWith("com.brave.browser")) {
            return false;
        }
        for (AccessibilityNodeInfo node : flatten(root)) {
            if (isReplayLabel(lower(node.getText()))
                    || isReplayLabel(lower(node.getContentDescription()))) {
                return true;
            }
        }
        return false;
    }

    private boolean isReplayLabel(String value) {
        return "replay".equals(value) || "watch again".equals(value)
                || "replay video".equals(value);
    }

    /** Runs scanTask after each delay (one-shot, no loops). */
    private void scanSoon(long[] delays) {
        if (!isMusicOn()) {
            return;
        }
        handler.removeCallbacks(scanTask);
        for (long delay : delays) {
            handler.postDelayed(scanTask, delay);
        }
    }

    // ---------------------------------------------------------------- tapping

    /** Taps dismiss/play/unmute if one of them is visible. */
    private boolean scanAndClick(boolean allowPlay) {
        AccessibilityNodeInfo root = braveRoot();
        if (root == null) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) {
            return false;
        }

        boolean behindLock = keyguardLocked();
        List<AccessibilityNodeInfo> nodes = flatten(root);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, DISMISS_LABELS) && isVisible(node, screenHeight, behindLock)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Logs.i(TAG, "dismissed dialog");
                    return true;
                }
            }
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, UNMUTE_LABELS) && isVisible(node, screenHeight, behindLock)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Logs.i(TAG, "tapped unmute" + (behindLock ? " (locked)" : ""));
                    return true;
                }
            }
        }

        // Tapping "Play" while the video is already playing toggles it to PAUSE. That is
        // exactly what silently killed the radio several times, so only do it while silent.
        if (allowPlay) {
            for (AccessibilityNodeInfo node : nodes) {
                if (matches(node, PLAY_LABELS) && isVisible(node, screenHeight, behindLock)) {
                    if (clickNode(node)) {
                        lastClickAt = SystemClock.uptimeMillis();
                        Logs.i(TAG, "tapped play" + (behindLock ? " (locked)" : ""));
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Hits Brave's toolbar reload button, which reloads in the same tab. */
    private boolean tapReload() {
        AccessibilityNodeInfo root = braveRoot();
        if (root == null) {
            return false;
        }
        boolean behindLock = keyguardLocked();
        List<AccessibilityNodeInfo> nodes = flatten(root);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, RELOAD_LABELS) && isVisible(node, screenHeight, behindLock)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Logs.i(TAG, "tapped reload" + (behindLock ? " (locked)" : ""));
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Brave's node tree: normally the active window, or its window behind the lock screen.
     * A page that is not on top can still be driven with accessibility actions.
     */
    private AccessibilityNodeInfo braveRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (isBraveRoot(root)) {
            return root;
        }
        if (!keyguardLocked()) {
            return null;
        }
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo candidate = window.getRoot();
                if (isBraveRoot(candidate)) {
                    long now = SystemClock.uptimeMillis();
                    if (now - lastBackgroundScanLog > 30_000L) {
                        lastBackgroundScanLog = now;
                        Logs.i(TAG, "using Brave window behind the lock screen");
                    }
                    return candidate;
                }
            }
        } catch (Throwable t) {
            Logs.w(TAG, "brave window: " + t);
        }
        return null;
    }

    private boolean isBraveRoot(AccessibilityNodeInfo root) {
        if (root == null || root.getPackageName() == null) {
            return false;
        }
        return root.getPackageName().toString().startsWith("com.brave.browser");
    }

    private boolean hasBraveWindow() {
        return braveRoot() != null;
    }

    /** MUSIC ON: if the current song is saved, play the local copy instead of waiting for Brave. */
    private void resumeCurrentIfLocal() {
        if (LocalPlayer.isActive()) {
            return;
        }
        Track current = Library.current(this);
        if (current != null && LocalStore.has(this, current.id)) {
            // Starting a fresh player must also start a fresh progress count, otherwise a
            // song that was paused/stopped would be cut short by the old elapsed time.
            resetProgress(prefs.getString(Library.KEY_URL, ""));
            Logs.i(TAG, "resuming saved copy after reconnect");
            LocalPlayer.play(this, current);
        }
    }

    private boolean isMusicPlaying() {
        if (LocalPlayer.isActive()) {
            return LocalPlayer.isPlaying();
        }
        // Our own silent keep-alive track would make isMusicActive() permanently true, so ask
        // Brave's own media session what it is doing instead.
        MediaController brave = braveController();
        if (brave != null) {
            try {
                PlaybackState state = brave.getPlaybackState();
                if (state != null) {
                    int value = state.getState();
                    if (value == PlaybackState.STATE_PLAYING) {
                        // Chromium sometimes keeps STATE_PLAYING and reports speed 0 while paused.
                        return state.getPlaybackSpeed() > 0f;
                    }
                    return value == PlaybackState.STATE_BUFFERING;
                }
                return false;
            } catch (Throwable t) {
                Logs.w(TAG, "brave playback state: " + t);
            }
        }
        if (hasNotificationAccess()) {
            // Brave is not running (or has no media session): that is silence, not playback.
            return false;
        }
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            return audio != null && audio.isMusicActive();
        } catch (Exception e) {
            return false;
        }
    }

    /** Brave's own media session, or {@code null} when notification access is missing. */
    private MediaController braveController() {
        try {
            MediaSessionManager manager =
                    (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return null;
            }
            for (MediaController controller : manager.getActiveSessions(
                    new ComponentName(this, MediaListenerService.class))) {
                String pkg = controller.getPackageName();
                if (pkg != null && pkg.startsWith("com.brave.browser")) {
                    return controller;
                }
            }
        } catch (Throwable ignored) {
            // no notification access
        }
        return null;
    }

    private boolean isBraveForeground() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) {
            return false;
        }
        return root.getPackageName().toString().startsWith("com.brave.browser");
    }

    private void relaunch(String url) {
        prefs.edit().putLong(Library.KEY_OPEN_AT, SystemClock.uptimeMillis()).apply();
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        String brave = findBrave();
        if (brave != null) {
            view.setPackage(brave);
        }
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(view);
        } catch (Exception e) {
            Logs.w(TAG, "relaunch failed: " + e);
        }
    }

    private String findBrave() {
        PackageManager pm = getPackageManager();
        for (String pkg : BRAVE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
                // next
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    private boolean isMusicOn() {
        if (prefs == null) {
            prefs = Library.prefs(this);
        }
        return prefs.getBoolean(Library.KEY_MUSIC_ON, false);
    }

    private void syncWakeLock() {
        if (isMusicOn()) {
            acquireWakeLock();
        } else {
            releaseWakeLock();
        }
    }

    @SuppressWarnings("deprecation")
    private void acquireWakeLock() {
        try {
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BravePlay:music");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Logs.w(TAG, "wake lock: " + e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
            // nothing
        }
    }

    /** Autoplay can start at volume 0 on some phones; make sure it is audible. */
    private void ensureAudibleVolume() {
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (audio == null) {
                return;
            }
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int current = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (current == 0 && max > 0) {
                int target = Math.max(1, (int) (max * 0.7f));
                try {
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0);
                } catch (SecurityException e) {
                    // Do-not-disturb can block volume changes.
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE, 0);
                }
                Logs.i(TAG, "volume raised to " + target + "/" + max);
            }
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, 0);
                Logs.i(TAG, "media stream unmuted");
            }
        } catch (Exception e) {
            Logs.w(TAG, "volume: " + e);
        }
    }

    /** Watches for the volume being dropped to 0 so it can be restored immediately. */
    private void registerVolumeObserver() {
        try {
            volumeObserver = new ContentObserver(handler) {
                @Override
                public void onChange(boolean selfChange) {
                    if (isMusicOn()) {
                        ensureAudibleVolume();
                    }
                }
            };
            String[] keys = {"volume_music", "volume_music_speaker", "volume_music_headset",
                    "volume_music_headphones", "volume_music_bt_a2dp"};
            for (String key : keys) {
                getContentResolver().registerContentObserver(
                        Settings.System.getUriFor(key), false, volumeObserver);
            }
        } catch (Exception e) {
            Logs.w(TAG, "volume observer: " + e);
        }
    }

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
        handler.removeCallbacks(recorderTick);
        handler.post(recorderTick);
    }

    private void stopTicker() {
        handler.removeCallbacks(ticker);
        handler.removeCallbacks(recorderTick);
    }

    private void registerScreenReceiver() {
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(screenReceiver, filter);
            }
        } catch (Exception e) {
            Logs.w(TAG, "screen receiver: " + e);
        }
    }

    /** Bluetooth stays on while music mode is active. */
    @SuppressWarnings("deprecation")
    private void ensureBluetoothOn() {
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            if (adapter == null || adapter.isEnabled()) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (now - lastBluetoothAttempt < BLUETOOTH_RETRY_MS) {
                return;
            }
            lastBluetoothAttempt = now;

            boolean enabled = false;
            try {
                enabled = adapter.enable();
            } catch (Exception e) {
                Logs.w(TAG, "bluetooth enable: " + e);
            }
            Logs.i(TAG, "bluetooth enable() -> " + enabled);
            if (!enabled) {
                toggleBluetoothTile();
            }
        } catch (Exception e) {
            Logs.w(TAG, "bluetooth: " + e);
        }
    }

    /** Fallback for Android 13+: flip the Bluetooth quick-settings tile. */
    private void toggleBluetoothTile() {
        if (quickSettingsBusy) {
            return;
        }
        quickSettingsBusy = true;
        try {
            performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        } catch (Exception e) {
            quickSettingsBusy = false;
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    AccessibilityNodeInfo root = getRootInActiveWindow();
                    if (root != null) {
                        for (AccessibilityNodeInfo node : flatten(root)) {
                            String text = lower(node.getText());
                            String desc = lower(node.getContentDescription());
                            boolean hit = (text != null && text.contains("bluetooth"))
                                    || (desc != null && desc.contains("bluetooth"));
                            if (hit && clickNode(node)) {
                                Logs.i(TAG, "bluetooth tile tapped");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Logs.w(TAG, "bluetooth tile: " + e);
                } finally {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                performGlobalAction(GLOBAL_ACTION_BACK);
                            } catch (Exception ignored) {
                                // nothing
                            }
                            quickSettingsBusy = false;
                        }
                    }, 900);
                }
            }
        }, 1200);
    }

    private List<AccessibilityNodeInfo> flatten(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<AccessibilityNodeInfo>();
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<AccessibilityNodeInfo>();
        queue.add(root);
        int guard = 0;
        while (!queue.isEmpty() && guard++ < 4000) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            out.add(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    queue.add(child);
                }
            }
        }
        return out;
    }

    private boolean matches(AccessibilityNodeInfo node, String[] labels) {
        String text = lower(node.getText());
        String desc = lower(node.getContentDescription());
        String viewId = lower(node.getViewIdResourceName());
        for (String label : labels) {
            if (label.equals(text) || label.equals(desc)) {
                return true;
            }
            if (!label.contains(" ") && viewId != null && viewId.endsWith(":" + label)) {
                return true;
            }
        }
        return false;
    }

    private String lower(CharSequence value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        return value.toString().trim().toLowerCase(Locale.ROOT);
    }

    private boolean isVisible(AccessibilityNodeInfo node, int screenHeight, boolean behindLock) {
        // Nodes in a window behind the keyguard often report visibleToUser=false even
        // though accessibility actions on them still work.
        if (!behindLock && !node.isVisibleToUser()) {
            return false;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        return bounds.top < screenHeight;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true;
        }
        AccessibilityNodeInfo clickable = node;
        for (int depth = 0; depth < 4 && clickable != null; depth++) {
            if (clickable.isClickable()
                    && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            clickable = clickable.getParent();
        }
        if (keyguardLocked()) {
            // Never blind-tap the lock screen: the coordinates would hit the keyguard, not
            // Brave, and could dismiss notifications or press PIN keys.
            return false;
        }
        return tapCenter(node);
    }

    private boolean tapCenter(AccessibilityNodeInfo node) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return false;
        }
        Path path = new Path();
        path.moveTo(bounds.centerX(), bounds.centerY());
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 40))
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Exception e) {
            return false;
        }
    }

    static boolean isEnabled(Context context) {
        String flat = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        ComponentName component = new ComponentName(context, PlayClickerService.class);
        if (flat == null) {
            return false;
        }
        for (String entry : flat.split(":")) {
            if (entry.equalsIgnoreCase(component.flattenToString())
                    || entry.equalsIgnoreCase(component.flattenToShortString())) {
                return true;
            }
        }
        return false;
    }
}
