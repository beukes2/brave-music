package com.space.braveplay;

import android.content.Context;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Songs recorded from Brave and saved as AAC files inside the app's private storage.
 *
 * <p>Keyed by YouTube video id, so the normal playlist/history logic keeps working: if a
 * file exists for a track it is played locally, otherwise Brave is used and the recorder
 * saves it for next time.</p>
 */
public final class LocalStore {

    private static final String DIR = "music";
    private static final long MAX_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final long MIN_FILE_BYTES = 24 * 1024;

    private LocalStore() {
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** Finished, playable file for a track. */
    public static File file(Context context, String id) {
        return new File(dir(context), id + ".m4a");
    }

    /** File that is still being recorded. */
    public static File part(Context context, String id) {
        File tmp = new File(dir(context), "tmp");
        if (!tmp.exists()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.mkdirs();
        }
        return new File(tmp, id + ".m4a");
    }

    public static boolean has(Context context, String id) {
        if (id == null || id.isEmpty()) {
            return false;
        }
        File file = file(context, id);
        return file.isFile() && file.length() >= MIN_FILE_BYTES;
    }

    public static long size(Context context, String id) {
        File file = file(context, id);
        return file.isFile() ? file.length() : 0L;
    }

    /** Moves a finished recording into place. Returns true when it is playable. */
    public static boolean commit(Context context, String id) {
        File source = part(context, id);
        if (!source.isFile() || source.length() < MIN_FILE_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return false;
        }
        File target = file(context, id);
        if (target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }
        boolean moved = source.renameTo(target);
        if (!moved) {
            //noinspection ResultOfMethodCallIgnored
            source.delete();
            return false;
        }
        return target.isFile() && target.length() >= MIN_FILE_BYTES;
    }

    public static void discard(Context context, String id) {
        //noinspection ResultOfMethodCallIgnored
        part(context, id).delete();
    }

    public static int count(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isFile() && file.length() >= MIN_FILE_BYTES) {
                count++;
            }
        }
        return count;
    }

    public static long totalBytes(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return 0L;
        }
        long total = 0L;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        return total;
    }

    /** Deletes the oldest files until the cache is under the cap. */
    public static void prune(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        long total = 0L;
        for (File file : files) {
            if (file.isFile()) {
                total += file.length();
            }
        }
        if (total <= MAX_BYTES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File file : files) {
            if (total <= MAX_BYTES) {
                break;
            }
            if (!file.isFile()) {
                continue;
            }
            long length = file.length();
            if (file.delete()) {
                total -= length;
                Logs.i("BravePlayLocal", "pruned " + file.getName() + " ("
                        + (length / (1024 * 1024)) + " MB)");
            }
        }
    }
}
