package org.telegram.messenger.zephyr.plugins;

import java.io.File;

/** Bridge between PluginManager and the embedded Python runtime. */
public interface PluginHost {
    /** Loads and activates a plugin. Must not be called on the main thread. Throws with a user-readable message on failure. */
    void load(String id, File file) throws Exception;

    void unload(String id);

    /** event: "START", "PAUSE", "RESUME" or "STOP". Safe to call from any thread. */
    void dispatchAppEvent(String event);
}
