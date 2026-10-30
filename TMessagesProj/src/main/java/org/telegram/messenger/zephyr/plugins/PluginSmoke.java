package org.telegram.messenger.zephyr.plugins;

import android.content.Context;

import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/** Minimal check that the embedded Python runtime starts. Must not be called from the UI thread. */
public class PluginSmoke {

    private PluginSmoke() {
    }

    public static String pythonVersion(Context context) {
        synchronized (PluginSmoke.class) {
            if (!Python.isStarted()) {
                Python.start(new AndroidPlatform(context.getApplicationContext()));
            }
        }
        return Python.getInstance().getModule("zephyr_smoke").callAttr("version").toString();
    }
}
