package com.space.braveplay;

import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Records the audio Brave is playing into AAC/M4A files.
 *
 * <p>Android does not let one app read another app's output directly, but a MediaProjection
 * (granted once by the user) plus {@link AudioPlaybackCaptureConfiguration} does. Only Brave's
 * UID is captured, so notification sounds and our own inaudible keep-alive track are not
 * included.</p>
 *
 * <p>The AudioRecord and encoder run on their own thread. A new file can be started for each
 * song without restarting the microphone.</p>
 */
public final class AacRecorder {

    private static final String TAG = "BravePlayAacRecorder";

    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    private static final int BIT_RATE = 128_000;
    /** 128 kbit/s = 16 000 bytes per second, used to estimate a recording's length. */
    private static final long BYTES_PER_SECOND = BIT_RATE / 8L;
    private static final int READ_BYTES = 4096;

    private final MediaProjection projection;
    private final int[] uids;

    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;

    private final Object lock = new Object();
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int muxerTrack = -1;
    private boolean muxerStarted;
    private File file;
    private long bytes;
    private File lastFile;
    private long lastBytes;

    public AacRecorder(MediaProjection projection, int[] uids) {
        this.projection = projection;
        this.uids = uids == null ? new int[0] : uids;
    }

    /** Opens the capture stream and starts the encoder thread. */
    public boolean start() {
        if (uids.length == 0) {
            Logs.w(TAG, "no Brave UID to capture");
            return false;
        }
        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            Logs.w(TAG, "invalid min buffer size: " + min);
            return false;
        }
        int bufferSize = Math.max(min * 2, READ_BYTES * 2);
        try {
            AudioPlaybackCaptureConfiguration.Builder builder =
                    new AudioPlaybackCaptureConfiguration.Builder(projection);
            for (int uid : uids) {
                builder.addMatchingUid(uid);
            }
            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build();
            record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(builder.build())
                    .build();
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                Logs.w(TAG, "AudioRecord not initialized");
                releaseRecord();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Logs.w(TAG, "AudioRecord did not start");
                releaseRecord();
                return false;
            }
        } catch (Throwable t) {
            Logs.w(TAG, "capture open failed: " + t);
            releaseRecord();
            return false;
        }

        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "brave-music-recorder");
        thread.start();
        Logs.i(TAG, "Brave audio capture started (" + bufferSize + " byte buffer)");
        return true;
    }

    /** Starts writing a new song to the given file. */
    public void startFile(File target) {
        synchronized (lock) {
            if (file != null) {
                finishLocked();
            }
            try {
                MediaFormat format = MediaFormat.createAudioFormat(
                        MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
                format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024);
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                encoder.start();
                muxer = new MediaMuxer(target.getAbsolutePath(),
                        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                muxerTrack = -1;
                muxerStarted = false;
                file = target;
                bytes = 0L;
                Logs.i(TAG, "recording to " + target.getName());
            } catch (Throwable t) {
                Logs.w(TAG, "start file failed: " + t);
                releaseEncoder();
                file = null;
            }
        }
    }

    /** Finishes the current song and leaves the file on disk for the caller to keep/delete. */
    public void endFile() {
        synchronized (lock) {
            finishLocked();
        }
    }

    /** Approximate length of the recording in progress in seconds. */
    public long currentSeconds() {
        synchronized (lock) {
            return bytes / BYTES_PER_SECOND;
        }
    }

    /** Approximate length of the last finished recording in seconds. */
    public long lastSeconds() {
        synchronized (lock) {
            return lastBytes / BYTES_PER_SECOND;
        }
    }

    public long lastBytes() {
        synchronized (lock) {
            return lastBytes;
        }
    }

    public File lastFile() {
        synchronized (lock) {
            return lastFile;
        }
    }

    public boolean isRecording() {
        synchronized (lock) {
            return file != null;
        }
    }

    /** Stops the capture completely. */
    public void stop() {
        running = false;
        try {
            if (record != null) {
                record.stop();
            }
        } catch (Throwable ignored) {
            // not recording
        }
        if (thread != null) {
            try {
                thread.join(3000L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        synchronized (lock) {
            finishLocked();
        }
        releaseRecord();
        Logs.i(TAG, "capture stopped");
    }

    private void readLoop() {
        byte[] pcm = new byte[READ_BYTES];
        while (running) {
            int read;
            try {
                read = record.read(pcm, 0, pcm.length);
            } catch (Throwable t) {
                Logs.w(TAG, "read: " + t);
                break;
            }
            if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                Logs.w(TAG, "capture died (projection stopped?)");
                break;
            }
            if (read <= 0) {
                continue;
            }
            feed(pcm, read);
        }
    }

    private void feed(byte[] pcm, int length) {
        synchronized (lock) {
            if (encoder == null) {
                return;
            }
            int offset = 0;
            while (offset < length) {
                int index;
                try {
                    index = encoder.dequeueInputBuffer(10_000L);
                } catch (Throwable t) {
                    Logs.w(TAG, "input buffer: " + t);
                    return;
                }
                if (index < 0) {
                    return; // encoder is busy; drop the rest of this chunk
                }
                ByteBuffer input = encoder.getInputBuffer(index);
                if (input == null) {
                    return;
                }
                input.clear();
                int chunk = Math.min(input.remaining(), length - offset);
                input.put(pcm, offset, chunk);
                try {
                    encoder.queueInputBuffer(index, 0, chunk,
                            System.nanoTime() / 1000L, 0);
                } catch (Throwable t) {
                    Logs.w(TAG, "queue input: " + t);
                    return;
                }
                offset += chunk;
            }
            drainLocked();
        }
    }

    private void drainLocked() {
        if (encoder == null) {
            return;
        }
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            int index;
            try {
                index = encoder.dequeueOutputBuffer(info, 0L);
            } catch (Throwable t) {
                return;
            }
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
                return;
            }
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted && muxer != null) {
                    try {
                        muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    } catch (Throwable t) {
                        Logs.w(TAG, "muxer start: " + t);
                    }
                }
                continue;
            }
            if (index < 0) {
                continue;
            }
            ByteBuffer output = encoder.getOutputBuffer(index);
            if (output != null && info.size > 0 && muxerStarted
                    && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                try {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    muxer.writeSampleData(muxerTrack, output, info);
                    bytes += info.size;
                } catch (Throwable t) {
                    Logs.w(TAG, "write sample: " + t);
                }
            }
            try {
                encoder.releaseOutputBuffer(index, false);
            } catch (Throwable ignored) {
                // nothing
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    /** Flushes the encoder, closes the file and remembers its size. Called with the lock held. */
    private void finishLocked() {
        if (encoder == null) {
            return;
        }
        try {
            int index = encoder.dequeueInputBuffer(200_000L);
            if (index >= 0) {
                encoder.queueInputBuffer(index, 0, 0, System.nanoTime() / 1000L,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                // Drain whatever the encoder has left.
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                long deadline = System.currentTimeMillis() + 2000L;
                while (System.currentTimeMillis() < deadline) {
                    int out = encoder.dequeueOutputBuffer(info, 100_000L);
                    if (out == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        continue;
                    }
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (!muxerStarted && muxer != null) {
                            muxerTrack = muxer.addTrack(encoder.getOutputFormat());
                            muxer.start();
                            muxerStarted = true;
                        }
                        continue;
                    }
                    if (out < 0) {
                        continue;
                    }
                    ByteBuffer buffer = encoder.getOutputBuffer(out);
                    if (buffer != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        muxer.writeSampleData(muxerTrack, buffer, info);
                        bytes += info.size;
                    }
                    encoder.releaseOutputBuffer(out, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Throwable t) {
            Logs.w(TAG, "flush: " + t);
        }

        lastFile = file;
        lastBytes = bytes;
        try {
            if (muxerStarted && muxer != null) {
                muxer.stop();
            }
        } catch (Throwable t) {
            Logs.w(TAG, "muxer stop: " + t);
        }
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        try {
            if (encoder != null) {
                encoder.stop();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        try {
            if (encoder != null) {
                encoder.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        encoder = null;
        muxer = null;
        muxerStarted = false;
        muxerTrack = -1;
        File finished = file;
        file = null;
        if (finished != null) {
            Logs.i(TAG, "finished " + finished.getName() + " ("
                    + (lastBytes / 1024) + " KB, ~" + (lastBytes / BYTES_PER_SECOND) + "s)");
        }
    }

    private void releaseEncoder() {
        try {
            if (muxer != null) {
                muxer.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        encoder = null;
        muxer = null;
        muxerStarted = false;
        muxerTrack = -1;
    }

    private void releaseRecord() {
        try {
            if (record != null) {
                record.release();
            }
        } catch (Throwable ignored) {
            // nothing
        }
        record = null;
    }
}
