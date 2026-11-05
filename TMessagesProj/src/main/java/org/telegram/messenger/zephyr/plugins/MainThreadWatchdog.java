package org.telegram.messenger.zephyr.plugins;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.FileLog;

/**
 * Logs freezes of the UI thread: a stall over {@link #STALL_MS} is written to the app log with the UI thread's
 * stack, and to the plugin's own log when the UI thread was inside a plugin call at that moment. Diagnostics only:
 * it never interrupts anything. One ping per second, so the cost is negligible. Uptime clock: deep sleep is
 * not a freeze.
 */
public final class MainThreadWatchdog {
    static final long STALL_MS = 2000;
    private static final long PING_MS = 1000;
    private static final int MAX_FRAMES = 40;

    /** "pluginId method" while the UI thread is inside a plugin call (set by PluginRuntime.invoke), else null. */
    static volatile String mainCall;
    private static volatile long lastPong;
    /** A ping is queued on the UI thread and has not run yet: do not pile up more during a freeze. */
    private static volatile boolean pending;
    private static boolean started;

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
                if (!pending) { pending = true; main.post(pong); }
                try { Thread.sleep(PING_MS); } catch (InterruptedException e) { return; }
                long now = SystemClock.uptimeMillis();
                long late = now - lastPong;
                if (late > STALL_MS) {
                    if (stallStart == 0) {
                        stallStart = lastPong;
                        stallCall = mainCall;
                        report(late, stallCall);
                    }
                } else if (stallStart != 0) {
                    long total = lastPong - stallStart;
                    String text = "UI thread was frozen for " + total + " ms" + (stallCall != null ? " in plugin call " + stallCall : "");
                    FileLog.w("ZephyrWatchdog: " + text);
                    note(stallCall, text);
                    stallStart = 0;
                    stallCall = null;
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
        String text = sb.toString();
        FileLog.w("ZephyrWatchdog: " + text);
        note(call, text);
    }

    private static void note(String call, String text) {
        if (call == null) return;
        int space = call.indexOf(' ');
        String id = space > 0 ? call.substring(0, space) : call;
        try { PluginRuntime.INSTANCE.logNative(id, "[watchdog] " + text); } catch (Throwable ignore) { }
    }
}
