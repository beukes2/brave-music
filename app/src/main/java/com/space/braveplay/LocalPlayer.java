package com.space.braveplay;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
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
    private static AudioManager audioManager;
    private static boolean resumeOnFocusGain;
    private static boolean duckedByFocus;
    private static boolean focusPaused;

    private static final AudioManager.OnAudioFocusChangeListener focusListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int change) {
                    synchronized (LocalPlayer.class) {
                        if (player == null) {
                            return;
                        }
                        switch (change) {
                            case AudioManager.AUDIOFOCUS_LOSS:
                                resumeOnFocusGain = false;
                                focusPaused = true;
                                pauseLocked();
                                Logs.i(TAG, "audio focus lost - local paused");
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                                resumeOnFocusGain = isPlayingLocked();
                                focusPaused = true;
                                pauseLocked();
                                Logs.i(TAG, "audio focus transient loss - local paused");
                                break;
                            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                                duckedByFocus = true;
                                try {
                                    player.setVolume(0.2f, 0.2f);
                                } catch (Throwable ignored) {
                                    // best effort
                                }
                                Logs.i(TAG, "audio focus ducked - local quieter");
                                break;
                            case AudioManager.AUDIOFOCUS_GAIN:
                                if (duckedByFocus) {
                                    duckedByFocus = false;
                                    try {
                                        player.setVolume(1f, 1f);
                                    } catch (Throwable ignored) {
                                        // best effort
                                    }
                                }
                                focusPaused = false;
                                if (resumeOnFocusGain) {
                                    resumeOnFocusGain = false;
                                    requestFocusLocked();
                                    try {
                                        player.start();
                                        completed = false;
                                        Logs.i(TAG, "audio focus regained - local resumed");
                                    } catch (Throwable t) {
                                        Logs.w(TAG, "focus resume: " + t);
                                    }
                                }
                                break;
                            default:
                                break;
                        }
                    }
                }
            };

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

    /** True while playback is paused because another app or a car prompt owns audio focus. */
    public static boolean isFocusPaused() {
        synchronized (LocalPlayer.class) {
            return focusPaused;
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
        AudioManager manager = (AudioManager) context.getApplicationContext()
                .getSystemService(Context.AUDIO_SERVICE);
        synchronized (LocalPlayer.class) {
            stopLocked();
            audioManager = manager;
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
                requestFocusLocked();
                mp.start();
                player = mp;
                activeId = track.id;
                completed = false;
                resumeOnFocusGain = false;
                duckedByFocus = false;
                focusPaused = false;
                Logs.i(TAG, "playing local file " + file.getName()
                        + " (" + (file.length() / 1024) + " KB)");
                return true;
            } catch (Throwable t) {
                Logs.w(TAG, "local play failed: " + t);
                abandonFocusLocked();
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
            focusPaused = false;
            pauseLocked();
            abandonFocusLocked();
        }
    }

    public static void resume() {
        synchronized (LocalPlayer.class) {
            if (player == null) {
                return;
            }
            try {
                if (!player.isPlaying()) {
                    requestFocusLocked();
                    focusPaused = false;
                    player.start();
                    completed = false;
                    Logs.i(TAG, "local resumed");
                }
            } catch (Throwable t) {
                Logs.w(TAG, "local resume: " + t);
            }
        }
    }

    private static void pauseLocked() {
        try {
            if (player.isPlaying()) {
                player.pause();
                Logs.i(TAG, "local paused");
            }
        } catch (Throwable t) {
            Logs.w(TAG, "local pause: " + t);
        }
    }

    private static boolean isPlayingLocked() {
        try {
            return player != null && player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private static void requestFocusLocked() {
        if (audioManager == null) {
            return;
        }
        try {
            int result = audioManager.requestAudioFocus(focusListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Logs.w(TAG, "audio focus not granted (" + result + ")");
            }
        } catch (Throwable t) {
            Logs.w(TAG, "request audio focus: " + t);
        }
    }

    private static void abandonFocusLocked() {
        resumeOnFocusGain = false;
        duckedByFocus = false;
        focusPaused = false;
        if (audioManager == null) {
            return;
        }
        try {
            audioManager.abandonAudioFocus(focusListener);
        } catch (Throwable t) {
            Logs.w(TAG, "abandon audio focus: " + t);
        }
    }

    public static void stop() {
        synchronized (LocalPlayer.class) {
            stopLocked();
        }
    }

    private static void stopLocked() {
        abandonFocusLocked();
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
