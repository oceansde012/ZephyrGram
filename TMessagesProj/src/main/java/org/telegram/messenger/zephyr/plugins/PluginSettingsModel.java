package org.telegram.messenger.zephyr.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Declarative settings screen of one plugin (built from Python create_settings()) plus access to the
 * plugin's persisted values. Values live in SharedPreferences "zephyr_plugin_&lt;id&gt;", each stored as a
 * JSON string so that any Python value round-trips.
 */
public final class PluginSettingsModel {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_SWITCH = 1;
    public static final int TYPE_INPUT = 2;
    public static final int TYPE_SELECTOR = 3;
    public static final int TYPE_TEXT = 4;
    public static final int TYPE_DIVIDER = 5;
    /** Button that opens Telegram's dialog picker; the plugin gets on_pick(dialog_id, title). */
    public static final int TYPE_DIALOG_PICKER = 6;
    /** Button that asks for a @username / t.me link, resolves it and gives on_resolved(dialog_id, title). */
    public static final int TYPE_USERNAME_INPUT = 7;

    public static final class Item {
        public int type;
        /** Unique within the plugin; for TYPE_TEXT it is only used to route clicks. */
        public String key;
        public String text;
        public String subtext;
        /** Drawable resource name or null. */
        public String icon;
        public String hint;
        public String[] options;
        public boolean accent;
        public boolean red;
        public boolean multiline;
        public Object defaultValue;
    }

    public final String pluginId;
    public final List<Item> items = new ArrayList<>();

    private PluginSettingsModel(String pluginId) {
        this.pluginId = pluginId;
    }

    /** Asks the plugin for its settings. Never returns null; an empty model means "no settings". Call off the UI thread if possible. */
    public static PluginSettingsModel load(String pluginId) {
        PluginSettingsModel m = new PluginSettingsModel(pluginId);
        String json = PluginRuntime.INSTANCE.callCreateSettings(pluginId);
        if (json == null || json.isEmpty()) return m;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Item it = new Item();
                it.type = o.optInt("type", TYPE_TEXT);
                it.key = o.optString("key", "item_" + i);
                it.text = o.optString("text", "");
                it.subtext = o.has("subtext") && !o.isNull("subtext") ? o.optString("subtext") : null;
                it.icon = o.has("icon") && !o.isNull("icon") ? o.optString("icon") : null;
                it.hint = o.has("hint") && !o.isNull("hint") ? o.optString("hint") : null;
                it.accent = o.optBoolean("accent");
                it.red = o.optBoolean("red");
                it.multiline = o.optBoolean("multiline");
                JSONArray opts = o.optJSONArray("options");
                if (opts != null) {
                    it.options = new String[opts.length()];
                    for (int j = 0; j < opts.length(); j++) it.options[j] = opts.optString(j);
                }
                it.defaultValue = o.has("default") && !o.isNull("default") ? o.get("default") : null;
                m.items.add(it);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return m;
    }

    // ---------------------------------------------------------------- typed access for UI

    public boolean getBoolean(Item it) {
        Object v = readValue(it);
        return v instanceof Boolean ? (Boolean) v : v instanceof Number && ((Number) v).intValue() != 0;
    }

    public int getInt(Item it) {
        Object v = readValue(it);
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    public String getString(Item it) {
        Object v = readValue(it);
        return v == null || v == JSONObject.NULL ? "" : String.valueOf(v);
    }

    private Object readValue(Item it) {
        String raw = getRaw(pluginId, it.key);
        if (raw != null) {
            try {
                return new org.json.JSONTokener(raw).nextValue();
            } catch (Throwable ignore) {
            }
        }
        return it.defaultValue;
    }

    /** Persists a value chosen in the UI and tells the plugin (on_change). value: Boolean, Integer or String. */
    public void setValue(Item it, Object value) {
        String json = JSONObject.quote(String.valueOf(value));
        if (value instanceof Boolean || value instanceof Number) {
            json = String.valueOf(value);
        }
        putRaw(pluginId, it.key, json);
        PluginRuntime.INSTANCE.callSettingsEvent(pluginId, it.key, "change", json);
    }

    /** Click on a TYPE_TEXT row. */
    public void click(Item it) {
        PluginRuntime.INSTANCE.callSettingsEvent(pluginId, it.key, "click", "null");
    }

    /** TYPE_DIALOG_PICKER / TYPE_USERNAME_INPUT: a chat was chosen or resolved (call on the UI thread). */
    public void picked(Item it, long dialogId, String title) {
        try {
            String json = new JSONObject().put("id", dialogId).put("title", title == null ? "" : title).toString();
            PluginRuntime.INSTANCE.callSettingsEvent(pluginId, it.key, "pick", json);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ---------------------------------------------------------------- raw storage (also used by Python)

    private static SharedPreferences prefs(String pluginId) {
        Context c = ApplicationLoader.applicationContext;
        return c.getSharedPreferences("zephyr_plugin_" + pluginId, Context.MODE_PRIVATE);
    }

    /** @return JSON text of the stored value or null. */
    public static String getRaw(String pluginId, String key) {
        try {
            return prefs(pluginId).getString(key, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void putRaw(String pluginId, String key, String json) {
        try {
            prefs(pluginId).edit().putString(key, json).apply();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void remove(String pluginId, String key) {
        try {
            prefs(pluginId).edit().remove(key).apply();
        } catch (Throwable ignore) {
        }
    }

    /** Wipes all settings of a plugin (uninstall). */
    public static void clear(String pluginId) {
        try {
            prefs(pluginId).edit().clear().apply();
        } catch (Throwable ignore) {
        }
    }
}
