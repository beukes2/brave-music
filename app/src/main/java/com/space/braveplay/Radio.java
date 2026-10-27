package com.space.braveplay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.SystemClock;

/**
 * Chooses what to play next and opens it in Brave.
 *
 * <p>Rule: a song that was played in the last hour is not chosen again. When every song in
 * the list has been played, a new song of the same genre is looked up and added.</p>
 */
public final class Radio {

    private static final String TAG = "BravePlayRadio";

    private static final String[] BRAVE_PACKAGES = {
            "com.brave.browser",
            "com.brave.browser_beta",
            "com.brave.browser_nightly"
    };

    private Radio() {
    }

    /** Writes the new current track to prefs and opens it in Brave. */
    public static void play(Context context, Track track) {
        if (track == null) {
            return;
        }
        SharedPreferences.Editor editor = Library.prefs(context).edit();
        editor.putString(Library.KEY_ID, track.id)
                .putString(Library.KEY_URL, track.url)
                .putString(Library.KEY_TITLE, track.title)
                .putInt(Library.KEY_DURATION, track.durationSec)
                .putLong(Library.KEY_OPEN_AT, SystemClock.uptimeMillis())
                .putLong(Library.KEY_PLAYED_MS, 0L)
                .putLong(Library.KEY_PLAYING_SINCE, 0L)
                .putLong(Library.KEY_SESSION, System.currentTimeMillis())
                .apply();
        Library.recordPlay(context, track.id);
        Logs.i(TAG, "play \"" + track.displayTitle() + "\" (" + track.id + ")");

        // Prefer a song that was already recorded: it plays with the screen off and the
        // phone locked, and needs neither Brave nor the accessibility service.
        if (LocalPlayer.play(context, track)) {
            if (!track.isLocalOnly()) {
                int localSeconds = LocalPlayer.durationSeconds();
                if (localSeconds > 0) {
                    // The saved file includes a little lead-in/out; using its real length
                    // keeps the rotation from cutting the song early. Local-only autosaves
                    // keep their captured length so silence/loops are not played.
                    Library.prefs(context).edit()
                            .putInt(Library.KEY_DURATION, localSeconds).apply();
                }
            }
            pauseBrave(context);
            Logs.i(TAG, "using saved local copy");
            return;
        }
        LocalPlayer.stop();
        if (track.isLocalOnly()) {
            // There is no web URL to fall back to for a video that only exists as a saved
            // file. Never open Brave for it, even if its file disappeared.
            Logs.w(TAG, "local-only track has no playable file: " + track.id);
            return;
        }
        launch(context, track.url);
    }

    /** Stops Brave so it does not play over a saved local copy. */
    private static void pauseBrave(Context context) {
        try {
            MediaSessionManager manager = (MediaSessionManager)
                    context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) {
                return;
            }
            for (MediaController controller : manager.getActiveSessions(
                    new ComponentName(context, MediaListenerService.class))) {
                String pkg = controller.getPackageName();
                if (pkg == null || !pkg.startsWith("com.brave.browser")) {
                    continue;
                }
                PlaybackState state = controller.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    controller.getTransportControls().pause();
                    Logs.i(TAG, "paused Brave before local playback");
                }
                return;
            }
        } catch (Throwable t) {
            Logs.w(TAG, "pause Brave: " + t);
        }
    }

    /**
     * Next track that was not played in the last hour. Blocking (may use the network)
     * so call it from a worker thread.
     */
    public static Track pickNext(Context context, Track current) {
        Track next = Library.nextEligible(context, current);
        if (next != null) {
            return next;
        }
        Logs.i(TAG, "everything played this hour -> discovering something new");
        next = Discover.findSimilar(context, current);
        if (next != null) {
            return next;
        }
        Logs.w(TAG, "discovery failed, falling back to the least recently played song");
        return Library.oldestPlayed(context);
    }

    /** Starts music mode by picking the first eligible song. Blocking (worker thread!). */
    public static Track startFresh(Context context) {
        Track current = Library.current(context);
        Track next = Library.nextEligible(context, current);
        if (next == null) {
            next = Discover.findSimilar(context, current);
        }
        if (next == null) {
            next = Library.oldestPlayed(context);
        }
        return next;
    }

    public static void launch(Context context, String url) {
        if (url == null || url.isEmpty() || url.startsWith("local:")) {
            Logs.w(TAG, "refusing to open a non-web URL: " + url);
            return;
        }
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        view.addCategory(Intent.CATEGORY_BROWSABLE);
        String brave = findBrave(context);
        if (brave != null) {
            view.setPackage(brave);
        }
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(view);
        } catch (Exception e) {
            Logs.w(TAG, "launch failed: " + e);
        }
    }

    private static String findBrave(Context context) {
        PackageManager pm = context.getPackageManager();
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
}
