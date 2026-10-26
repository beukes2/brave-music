package com.space.braveplay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.PowerManager;

import java.io.File;

/**
 * Plays a saved song from the app's private storage.
 *
 * <p>Unlike Brave, this needs no accessibility service, no browser and no lock-screen
 * tricks: once a song is saved it plays with the screen off and the phone locked. It also
 * owns a partial wake lock while playing so the CPU does not go to sleep mid-song.</p>
 */
public final class LocalPlayer {

    private static final String TAG = "BravePlayLocalPlayer";

    private static MediaPlayer player;
    private static String activeId = "";
    private static boolean completed;
    private static Runnable onComplete;

    private LocalPlayer() {
    }

    /** Called by the control service when a local track finished, on the main thread. */
    public static void setOnComplete(Runnable callback) {
        onComplete = callback;
    }

    public static boolean isActive() {
        synchronized (LocalPlayer.class) {
            return player != null;
        }
    }

    public static boolean isPlaying() {
        synchronized (LocalPlayer.class) {
            if (player == null) {
                return false;
            }
            try {
                return player.isPlaying();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public static String activeId() {
        synchronized (LocalPlayer.class) {
            return activeId;
        }
    }

    /** True when the file reached its end; the control service advances to the next song. */
    public static boolean hasCompleted() {
        synchronized (LocalPlayer.class) {
            return completed;
        }
    }

    /** Length of the saved file in seconds (0 when unknown). */
    public static int durationSeconds() {
        synchronized (LocalPlayer.class) {
            if (player == null) {
                return 0;
            }
            try {
                return player.getDuration() / 1000;
            } catch (Throwable t) {
                return 0;
            }
        }
    }

    /** Starts the saved file for this track. Returns false when there is no usable file. */
    public static boolean play(Context context, Track track) {
        if (track == null) {
            return false;
        }
        File file = LocalStore.file(context, track.id);
        if (!file.isFile() || file.length() < 24 * 1024) {
            return false;
        }
        synchronized (LocalPlayer.class) {
            stopLocked();
            MediaPlayer mp = null;
            try {
                mp = new MediaPlayer();
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mp.setDataSource(file.getAbsolutePath());
                mp.setWakeMode(context.getApplicationContext(),
                        PowerManager.PARTIAL_WAKE_LOCK);
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override
                    public void onCompletion(MediaPlayer completedPlayer) {
                        Logs.i(TAG, "local track finished: " + activeId);
                        completed = true;
                        Runnable callback = onComplete;
                        if (callback != null) {
                            callback.run();
                        }
                    }
                });
                mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                    @Override
                    public boolean onError(MediaPlayer failed, int what, int extra) {
                        Logs.w(TAG, "local player error " + what + "/" + extra);
                        return false;
                    }
                });
                mp.prepare();
                mp.start();
                player = mp;
                activeId = track.id;
                completed = false;
                Logs.i(TAG, "playing local file " + file.getName()
                        + " (" + (file.length() / 1024) + " KB)");
                return true;
            } catch (Throwable t) {
                Logs.w(TAG, "local play failed: " + t);
                if (mp != null) {
                    try {
                        mp.release();
                    } catch (Throwable ignored) {
                        // nothing
                    }
                }
                return false;
            }
        }
    }

    public static void pause() {
        synchronized (LocalPlayer.class) {
            if (player == null) {
                return;
            }
            try {
                if (player.isPlaying()) {
                    player.pause();
                    Logs.i(TAG, "local paused");
                }
            } catch (Throwable t) {
                Logs.w(TAG, "local pause: " + t);
            }
        }
    }

    public static void resume() {
        synchronized (LocalPlayer.class) {
            if (player == null) {
                return;
            }
            try {
                if (!player.isPlaying()) {
                    player.start();
                    completed = false;
                    Logs.i(TAG, "local resumed");
                }
            } catch (Throwable t) {
                Logs.w(TAG, "local resume: " + t);
            }
        }
    }

    public static void stop() {
        synchronized (LocalPlayer.class) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        if (player == null) {
            return;
        }
        String id = activeId;
        try {
            player.stop();
        } catch (Throwable ignored) {
            // already stopped
        }
        try {
            player.release();
        } catch (Throwable ignored) {
            // nothing
        }
        player = null;
        activeId = "";
        completed = false;
        Logs.i(TAG, "local stopped: " + id);
    }
}
