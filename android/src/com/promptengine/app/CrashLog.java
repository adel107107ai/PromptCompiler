package com.promptengine.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Records an uncaught crash into the app's own storage so it can be shown and copied on the next
 * launch.
 *
 * The app cannot read another process's logcat entries on a non-rooted phone, so when a crash
 * cannot be reproduced at a desk the only reliable report is the one the app takes of itself.
 */
public final class CrashLog {

    private static final String TAG = "PromptEngine";
    private static final String FILE = "last_crash.txt";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static volatile boolean installed;

    private CrashLog() {
    }

    /** Idempotent: safe to call from every activity's onCreate. */
    public static void install(final Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable failure) {
                try {
                    write(app, thread, failure);
                } catch (Throwable ignored) {
                    // A crash handler must never make things worse.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, failure);
                }
            }
        });
    }

    private static void write(Context context, Thread thread, Throwable failure) {
        StringWriter stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));

        String version = "?";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable ignored) {
        }

        String report = "time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "\napp: " + version
                + "\ndevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nandroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                + "\nthread: " + thread.getName()
                + "\nfree storage: " + (new Store(context).freeMegabytes()) + " MB"
                + "\n\n" + stack.toString();

        Log.e(TAG, report);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(new File(context.getFilesDir(), FILE));
            out.write(report.getBytes(UTF8));
            out.flush();
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** The last recorded crash, or null when there is none. */
    public static String read(Context context) {
        File file = new File(context.getFilesDir(), FILE);
        if (!file.exists()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), UTF8);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void clear(Context context) {
        try {
            new File(context.getFilesDir(), FILE).delete();
        } catch (Throwable ignored) {
        }
    }
}
