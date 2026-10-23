package com.space.braveplay;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes a rolling debug log to {@code filesDir/app.log} so problems can always be
 * investigated later, even hours after they happened.
 *
 * <p>Read it with {@code adb shell run-as com.space.braveplay cat files/app.log}
 * or with the "Logs" button inside the app.</p>
 */
public final class Logs {

    private static final String FILE_NAME = "app.log";
    private static final long MAX_BYTES = 512 * 1024L;
    private static final Object LOCK = new Object();

    private static File file;

    private Logs() {
    }

    public static void init(Context context) {
        synchronized (LOCK) {
            if (file == null) {
                file = new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
            }
        }
    }

    public static void i(String tag, String message) {
        write(Log.INFO, tag, message);
    }

    public static void w(String tag, String message) {
        write(Log.WARN, tag, message);
    }

    private static void write(int priority, String tag, String message) {
        Log.println(priority, tag, message);
        synchronized (LOCK) {
            if (file == null) {
                return;
            }
            try {
                if (file.length() > MAX_BYTES) {
                    File old = new File(file.getParentFile(), FILE_NAME + ".1");
                    if (old.exists()) {
                        old.delete();
                    }
                    file.renameTo(old);
                }
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                        .format(new Date())
                        + (priority == Log.WARN ? " W/" : " I/")
                        + tag + ": " + message + "\n";
                FileOutputStream out = new FileOutputStream(file, true);
                try {
                    out.write(line.getBytes("UTF-8"));
                } finally {
                    out.close();
                }
            } catch (Exception ignored) {
                // logging must never break playback
            }
        }
    }

    /** Last {@code maxLines} lines of the log (oldest first). */
    public static String tail(Context context, int maxLines) {
        init(context);
        StringBuilder all = new StringBuilder();
        synchronized (LOCK) {
            if (file == null || !file.exists()) {
                return "(no log yet)";
            }
            try {
                FileInputStream in = new FileInputStream(file);
                try {
                    byte[] buffer = new byte[16384];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        all.append(new String(buffer, 0, read, "UTF-8"));
                    }
                } finally {
                    in.close();
                }
            } catch (Exception e) {
                return "(cannot read log: " + e + ")";
            }
        }
        String[] lines = all.toString().split("\n", -1);
        int start = Math.max(0, lines.length - maxLines);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            if (i > start) {
                out.append('\n');
            }
            out.append(lines[i]);
        }
        return out.length() == 0 ? "(empty log)" : out.toString();
    }
}
