package org.telegram.messenger.zephyr.plugins;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;

/**
 * Logs freezes of the UI thread: a stall over {@link #STALL_MS} is written with the UI thread's stack to an
 * always-on journal (files/zephyr/watchdog.log, last {@link #MAX_ENTRIES} entries, at most {@link #MAX_BYTES}),
 * to the app log (when logging is on) and to the plugin's own log when the UI thread was inside a plugin call.
 * Diagnostics only: it never interrupts anything. One ping per second while the app is in the foreground;
 * uptime clock, so deep sleep is not a freeze.
 */
public final class MainThreadWatchdog {
    static final long STALL_MS = 2000;
    private static final long PING_MS = 1000;
    private static final int MAX_FRAMES = 40;
    static final int MAX_ENTRIES = 30;
    static final int MAX_BYTES = 64 * 1024;
    private static final String SEPARATOR = "----\n";

    /** "pluginId method" while the UI thread is inside a plugin call (set by PluginRuntime.invoke), else null. */
    static volatile String mainCall;
    private static volatile long lastPong;
    /** A ping is queued on the UI thread and has not run yet: do not pile up more during a freeze. */
    private static volatile boolean pending;
    private static boolean started;
    private static final Object fileLock = new Object();

    private MainThreadWatchdog() { }

    public static synchronized void start() {
        if (started) return;
        started = true;
        final Handler main = new Handler(Looper.getMainLooper());
        final Runnable pong = () -> { lastPong = SystemClock.uptimeMillis(); pending = false; };
        lastPong = SystemClock.uptimeMillis();
        Thread thread = new Thread(() -> {
            long stallStart = 0;
            String stallCall = null;
            while (true) {
                try { Thread.sleep(PING_MS); } catch (InterruptedException e) { return; }
                long now = SystemClock.uptimeMillis();
                if (!pending) {
                    if (stallStart != 0) {
                        long total = lastPong - stallStart;
                        String text = "UI thread was frozen for " + total + " ms" + (stallCall != null ? " in plugin call " + stallCall : "");
                        record(stallCall, text);
                        stallStart = 0;
                        stallCall = null;
                    }
                    // In the background nothing draws, so nothing can freeze visibly: do not wake the UI thread.
                    if (ApplicationLoader.mainInterfacePaused) continue;
                    pending = true;
                    lastPong = now;
                    main.post(pong);
                } else if (stallStart == 0 && now - lastPong > STALL_MS) {
                    stallStart = lastPong;
                    stallCall = mainCall;
                    report(now - lastPong, stallCall);
                }
            }
        }, "ZephyrMainWatchdog");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void report(long late, String call) {
        StringBuilder sb = new StringBuilder("UI thread frozen for ").append(late).append(" ms");
        if (call != null) sb.append(" in plugin call ").append(call);
        sb.append(", stack:");
        StackTraceElement[] stack = Looper.getMainLooper().getThread().getStackTrace();
        for (int i = 0; i < stack.length && i < MAX_FRAMES; i++) sb.append("\n  at ").append(stack[i]);
        record(call, sb.toString());
    }

    private static void record(String call, String text) {
        FileLog.w("ZephyrWatchdog: " + text);
        String version;
        try { version = org.telegram.messenger.BuildConfig.ZEPHYR_VERSION; } catch (Throwable t) { version = "?"; }
        append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " v" + version + " " + text + "\n");
        if (call == null) return;
        int space = call.indexOf(' ');
        String id = space > 0 ? call.substring(0, space) : call;
        try { PluginRuntime.INSTANCE.logNative(id, "[watchdog] " + text); } catch (Throwable ignore) { }
    }

    private static File journal() {
        return new File(new File(ApplicationLoader.applicationContext.getFilesDir(), "zephyr"), "watchdog.log");
    }

    /** Appends one entry and keeps only the newest {@link #MAX_ENTRIES} within {@link #MAX_BYTES}. */
    private static void append(String entry) {
        synchronized (fileLock) {
            try {
                File file = journal();
                File dir = file.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                ArrayList<String> entries = split(readFile(file));
                entries.add(entry.length() > MAX_BYTES / 2 ? entry.substring(0, MAX_BYTES / 2) + "\n" : entry);
                int bytes = 0;
                for (String e : entries) bytes += e.length() + SEPARATOR.length();
                while (entries.size() > MAX_ENTRIES || (bytes > MAX_BYTES && entries.size() > 1)) {
                    bytes -= entries.remove(0).length() + SEPARATOR.length();
                }
                StringBuilder out = new StringBuilder();
                for (String e : entries) out.append(e).append(SEPARATOR);
                File tmp = new File(dir, "watchdog.log.tmp");
                try (FileOutputStream stream = new FileOutputStream(tmp)) {
                    stream.write(out.toString().getBytes(StandardCharsets.UTF_8));
                }
                if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file); }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    /** The journal, newest entry last, or "" when there were no freezes. */
    public static String readJournal() {
        synchronized (fileLock) {
            try { return readFile(journal()); } catch (Throwable t) { return ""; }
        }
    }

    private static String readFile(File file) throws java.io.IOException {
        if (!file.isFile()) return "";
        byte[] data = new byte[(int) Math.min(file.length(), MAX_BYTES * 2L)];
        int read = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
        }
        return new String(data, 0, read, StandardCharsets.UTF_8);
    }

    private static ArrayList<String> split(String text) {
        ArrayList<String> entries = new ArrayList<>();
        for (String part : text.split(SEPARATOR, -1)) if (!part.isEmpty()) entries.add(part);
        return entries;
    }
}
