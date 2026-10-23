package com.space.braveplay;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private static final long WATCHDOG_MS = 20_000L;
    private static final long TICK_MS = 5_000L;
    private static final long BLUETOOTH_RETRY_MS = 60_000L;
    private static final long CLICK_COOLDOWN_MS = 1_000L;
    private static final long LOAD_GRACE_MS = 30_000L;
    private static final long MIN_ACTION_DELAY_MS = 20_000L;
    private static final long MAX_ACTION_DELAY_MS = 300_000L;

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
    private long lastClickAt;
    private long nextActionAt;
    private long actionDelay = MIN_ACTION_DELAY_MS;
    private long lastBluetoothAttempt;
    private boolean quickSettingsBusy;
    private boolean advancing;
    private String trackedUrl = "";
    private long playedMs;
    private long playingSince;
    private ContentObserver volumeObserver;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!isMusicOn()) {
                return;
            }
            try {
                ensureAudibleVolume();
                rotationTick();
            } catch (Throwable t) {
                Log.w(TAG, "ticker: " + t);
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
                            ensureAudibleVolume();
                            ensureBluetoothOn();
                            startTicker();
                            nextActionAt = 0;
                            actionDelay = MIN_ACTION_DELAY_MS;
                            scanSoon(STARTUP_SCANS_MS);
                        } else {
                            stopTicker();
                        }
                    } else if (Library.KEY_URL.equals(key)) {
                        nextActionAt = 0;
                        actionDelay = MIN_ACTION_DELAY_MS;
                        resetProgress(sharedPreferences.getString(Library.KEY_URL, ""));
                        scanSoon(STARTUP_SCANS_MS);
                    }
                }
            };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            try {
                watchdogTick();
            } catch (Throwable t) {
                Log.w(TAG, "watchdog: " + t);
            }
            handler.postDelayed(this, WATCHDOG_MS);
        }
    };

    private final Runnable scanTask = new Runnable() {
        @Override
        public void run() {
            if (!isMusicOn()) {
                return;
            }
            if (isBraveForeground()) {
                scanAndClick();
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        prefs = Library.prefs(this);
        prefs.registerOnSharedPreferenceChangeListener(prefListener);
        registerVolumeObserver();
        syncWakeLock();
        playedMs = prefs.getLong(Library.KEY_PLAYED_MS, 0L);
        trackedUrl = prefs.getString(Library.KEY_URL, "");
        if (isMusicOn()) {
            ensureAudibleVolume();
            ensureBluetoothOn();
            startTicker();
        }
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
        scanSoon(STARTUP_SCANS_MS);
        Log.i(TAG, "connected, music=" + isMusicOn());
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
        executor.shutdownNow();
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        if (volumeObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(volumeObserver);
            } catch (Exception ignored) {
                // nothing
            }
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- watchdog

    private void watchdogTick() {
        if (!isMusicOn() || advancing) {
            return;
        }
        ensureAudibleVolume();
        ensureBluetoothOn();
        String url = prefs.getString(Library.KEY_URL, "");
        if (TextUtils.isEmpty(url)) {
            return;
        }
        long now = SystemClock.uptimeMillis();

        boolean braveForeground = isBraveForeground();

        // End screen ("Replay") means the song is over - move on instead of restarting it.
        if (!isMusicPlaying() && braveForeground && hasReplayButton()) {
            advance("replay shown");
            return;
        }

        // Muted playback still has an audio track; always clear the mute overlay.
        if (braveForeground && scanAndClick()) {
            actionDelay = MIN_ACTION_DELAY_MS;
            nextActionAt = 0;
            return;
        }

        if (isMusicPlaying()) {
            actionDelay = MIN_ACTION_DELAY_MS;
            nextActionAt = 0;
            return;
        }

        long openedAt = prefs.getLong(Library.KEY_OPEN_AT, 0L);
        if (now - openedAt < LOAD_GRACE_MS) {
            // Page is probably still loading; the startup scans will keep trying.
            return;
        }

        if (now < nextActionAt) {
            return;
        }
        nextActionAt = now + actionDelay;
        actionDelay = Math.min(actionDelay * 2, MAX_ACTION_DELAY_MS);

        if (braveForeground && tapReload()) {
            prefs.edit().putLong(Library.KEY_OPEN_AT, SystemClock.uptimeMillis()).apply();
            Log.i(TAG, "not playing -> reloaded page");
            scanSoon(STARTUP_SCANS_MS);
            return;
        }

        Log.i(TAG, "not playing -> reopening " + url);
        relaunch(url);
        scanSoon(STARTUP_SCANS_MS);
    }

    // ---------------------------------------------------------------- rotation

    /** Called every 5 s: accumulates real playing time and advances finished songs. */
    private void rotationTick() {
        String url = prefs.getString(Library.KEY_URL, "");
        if (TextUtils.isEmpty(url)) {
            return;
        }
        if (!url.equals(trackedUrl)) {
            resetProgress(url);
        }
        if (advancing) {
            return;
        }

        long now = SystemClock.uptimeMillis();
        boolean playing = isMusicPlaying();
        if (playing) {
            if (playingSince == 0L) {
                playingSince = now;
            }
        } else if (playingSince != 0L) {
            playedMs += now - playingSince;
            playingSince = 0L;
            prefs.edit().putLong(Library.KEY_PLAYED_MS, playedMs).apply();
        }

        long effective = playedMs + (playing && playingSince != 0L ? now - playingSince : 0L);
        int durationSec = prefs.getInt(Library.KEY_DURATION, 0);

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

        if (!playing && isBraveForeground() && hasReplayButton()) {
            advance("replay shown");
        }
    }

    private void resetProgress(String url) {
        trackedUrl = url == null ? "" : url;
        playedMs = 0L;
        playingSince = 0L;
        prefs.edit().putLong(Library.KEY_PLAYED_MS, 0L).apply();
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
        Log.i(TAG, "advancing: " + reason);

        executor.execute(new Runnable() {
            @Override
            public void run() {
                Track next = null;
                try {
                    Track current = Library.current(PlayClickerService.this);
                    next = Radio.pickNext(PlayClickerService.this, current);
                } catch (Throwable t) {
                    Log.w(TAG, "pickNext: " + t);
                }
                final Track chosen = next;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        advancing = false;
                        if (chosen == null) {
                            Log.w(TAG, "nothing to play; retrying later");
                            nextActionAt = SystemClock.uptimeMillis() + 30_000L;
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
    private boolean scanAndClick() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        CharSequence pkgName = root.getPackageName();
        if (pkgName == null || !pkgName.toString().startsWith("com.brave.browser")) {
            return false;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastClickAt < CLICK_COOLDOWN_MS) {
            return false;
        }

        List<AccessibilityNodeInfo> nodes = flatten(root);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;

        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, DISMISS_LABELS) && isVisible(node, screenHeight)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Log.i(TAG, "dismissed dialog");
                    return true;
                }
            }
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, UNMUTE_LABELS) && isVisible(node, screenHeight)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Log.i(TAG, "tapped unmute");
                    return true;
                }
            }
        }

        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, PLAY_LABELS) && isVisible(node, screenHeight)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    Log.i(TAG, "tapped play");
                    return true;
                }
            }
        }
        return false;
    }

    /** Hits Brave's toolbar reload button, which reloads in the same tab. */
    private boolean tapReload() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            return false;
        }
        List<AccessibilityNodeInfo> nodes = flatten(root);
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        for (AccessibilityNodeInfo node : nodes) {
            if (matches(node, RELOAD_LABELS) && isVisible(node, screenHeight)) {
                if (clickNode(node)) {
                    lastClickAt = SystemClock.uptimeMillis();
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isMusicPlaying() {
        try {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            return audio != null && audio.isMusicActive();
        } catch (Exception e) {
            return false;
        }
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
            Log.w(TAG, "relaunch failed: " + e);
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
            Log.w(TAG, "wake lock: " + e);
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
                Log.i(TAG, "volume raised to " + target + "/" + max);
            }
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE, 0);
                Log.i(TAG, "media stream unmuted");
            }
        } catch (Exception e) {
            Log.w(TAG, "volume: " + e);
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
            Log.w(TAG, "volume observer: " + e);
        }
    }

    private void startTicker() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void stopTicker() {
        handler.removeCallbacks(ticker);
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
                Log.w(TAG, "bluetooth enable: " + e);
            }
            Log.i(TAG, "bluetooth enable() -> " + enabled);
            if (!enabled) {
                toggleBluetoothTile();
            }
        } catch (Exception e) {
            Log.w(TAG, "bluetooth: " + e);
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
                                Log.i(TAG, "bluetooth tile tapped");
                                break;
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "bluetooth tile: " + e);
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

    private boolean isVisible(AccessibilityNodeInfo node, int screenHeight) {
        if (!node.isVisibleToUser()) {
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
