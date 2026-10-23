package com.space.braveplay;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Inaudible looping audio track.
 *
 * <p>Android hands media buttons (car steering wheel, headset) to the media session of the
 * app that is currently <em>producing audio</em>. Brave produces the audio, so its session
 * would always win and our next/previous handling would never be called. Playing silence
 * from our own UID puts us into the same list; whoever started its player last comes first,
 * so crashing the track again ("bump") reclaims the buttons when Brave reloads a video.</p>
 *
 * <p>The track is silent, does not request audio focus, so Brave keeps playing normally.</p>
 */
public final class SilentAudio {

    private static final String TAG = "SilentAudio";
    private static final int SAMPLE_RATE = 8000;

    private AudioTrack track;
    private Thread writer;
    private volatile boolean running;

    public void start() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
            try {
                int min = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int size = Math.max(min, SAMPLE_RATE);
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(size)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
                writer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        byte[] silence = new byte[1024];
                        while (running) {
                            AudioTrack current = track;
                            if (current == null) {
                                break;
                            }
                            int written = current.write(silence, 0, silence.length);
                            if (written < 0) {
                                break;
                            }
                        }
                    }
                }, "brave-music-silence");
                writer.setDaemon(true);
                writer.start();
                Logs.i(TAG, "silent keep-alive started");
            } catch (Throwable t) {
                Logs.w(TAG, "start failed: " + t);
                stop();
            }
        }
    }

    public void stop() {
        synchronized (this) {
            if (!running && track == null) {
                return;
            }
            running = false;
            AudioTrack current = track;
            track = null;
            if (current != null) {
                try {
                    current.pause();
                    current.flush();
                } catch (Exception ignored) {
                    // best effort
                }
                try {
                    current.release();
                } catch (Exception ignored) {
                    // best effort
                }
            }
            writer = null;
            Logs.i(TAG, "silent keep-alive stopped");
        }
    }

    /** Stops and immediately restarts, which moves us to the top of Android's last-played list. */
    public void bump() {
        stop();
        start();
    }
}
