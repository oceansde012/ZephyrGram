package org.telegram.messenger.zephyr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.zephyr.plugins.PluginManager;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Core-side access to the storage of the "ai_autoreply" plugin (SharedPreferences "zephyr_plugin_ai_autoreply").
 * Every value is the JSON text of a Python value. "chats" is a JSON string that itself holds a JSON object
 * {"account:dialogId": {enabled, account, dialog_id, title, type, fails, ...}}; per-chat mode is "mode_&lt;sk&gt;"
 * (0 auto, 1 draft, 2 friend), where sk = key with ':' -> '_' and '-' -> 'n'; "master" is a boolean.
 * Writes touch several keys in ONE editor commit, so the plugin never sees a half-written state.
 * See ZephyrGram-Plugins/docs/API.md, "ai_autoreply storage format".
 */
public final class ZephyrAutoReplyStore {
    public static final String PLUGIN_ID = "ai_autoreply";
    public static final int MODE_AUTO = 0, MODE_DRAFT = 1, MODE_FRIEND = 2;
    private static final Object LOCK = new Object();

    public static final class Entry {
        public String key;
        public int account;
        public long dialogId;
        public String title;
        public String type;
        public boolean enabled;
        public int mode;
    }

    private ZephyrAutoReplyStore() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_" + PLUGIN_ID, Context.MODE_PRIVATE);
    }

    public static String chatKey(int account, long dialogId) {
        return account + ":" + dialogId;
    }

    private static String sk(String key) {
        return key.replace(":", "_").replace("-", "n");
    }

    private static JSONObject readChats(SharedPreferences p) {
        try {
            String raw = p.getString("chats", null);
            if (raw == null) return new JSONObject();
            Object v = new JSONTokener(raw).nextValue();
            if (v instanceof JSONObject) return (JSONObject) v;
            if (v instanceof String && !((String) v).isEmpty()) return new JSONObject((String) v);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return new JSONObject();
    }

    private static int readInt(SharedPreferences p, String key, int def) {
        try {
            String raw = p.getString(key, null);
            if (raw == null) return def;
            Object v = new JSONTokener(raw).nextValue();
            if (v instanceof Number) return ((Number) v).intValue();
            if (v instanceof String) return Integer.parseInt(((String) v).trim());
        } catch (Throwable ignore) {
        }
        return def;
    }

    public static boolean isMaster() {
        try {
            String raw = prefs().getString("master", null);
            if (raw == null) return true;
            Object v = new JSONTokener(raw).nextValue();
            return v instanceof Boolean ? (Boolean) v : !(v instanceof Number) || ((Number) v).intValue() != 0;
        } catch (Throwable t) {
            return true;
        }
    }

    public static List<Entry> load() {
        ArrayList<Entry> out = new ArrayList<>();
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            Iterator<String> it = chats.keys();
            while (it.hasNext()) {
                String key = it.next();
                JSONObject c = chats.optJSONObject(key);
                if (c == null) continue;
                Entry e = new Entry();
                e.key = key;
                e.dialogId = c.optLong("dialog_id", 0);
                e.account = c.optInt("account", 0);
                if (e.dialogId == 0) {
                    int colon = key.indexOf(':');
                    try {
                        e.account = Integer.parseInt(key.substring(0, colon));
                        e.dialogId = Long.parseLong(key.substring(colon + 1));
                    } catch (Throwable ignore) {
                        continue;
                    }
                }
                e.title = c.optString("title", key);
                e.type = c.optString("type", e.dialogId > 0 ? "user" : "group");
                e.enabled = c.optBoolean("enabled", false);
                e.mode = readInt(p, "mode_" + sk(key), MODE_AUTO);
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (a, b) -> {
            if (a.enabled != b.enabled) return a.enabled ? -1 : 1;
            return String.valueOf(a.title).compareToIgnoreCase(String.valueOf(b.title));
        });
        return out;
    }

    public static int countActive() {
        int n = 0;
        for (Entry e : load()) if (e.enabled) n++;
        return n;
    }

    public static boolean isInstalledAndEnabled() {
        PluginManager.InstalledPlugin p = PluginManager.INSTANCE.getInstalled(PLUGIN_ID);
        return p != null && p.enabled;
    }

    private static void done() {
        try {
            PluginManager.INSTANCE.notifySettingsChanged(PLUGIN_ID);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void setMaster(boolean value) {
        synchronized (LOCK) {
            prefs().edit().putString("master", String.valueOf(value)).commit();
        }
        done();
    }

    /** Adds (or re-enables) a chat. */
    public static void upsert(int account, long dialogId, String title, String type, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            String key = chatKey(account, dialogId);
            try {
                JSONObject c = chats.optJSONObject(key);
                if (c == null) c = new JSONObject();
                c.put("enabled", enabled).put("account", account).put("dialog_id", dialogId)
                        .put("title", title == null ? String.valueOf(dialogId) : title).put("type", type).put("fails", 0);
                c.remove("auto_off_ts");
                chats.put(key, c);
                p.edit().putString("chats", JSONObject.quote(chats.toString())).putString("keep_" + sk(key), String.valueOf(enabled)).commit();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        done();
    }

    public static void setEnabled(String key, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            JSONObject c = chats.optJSONObject(key);
            if (c == null) return;
            try {
                c.put("enabled", enabled);
                if (enabled) {
                    c.put("fails", 0);
                    c.remove("auto_off_ts");
                }
                p.edit().putString("chats", JSONObject.quote(chats.toString())).putString("keep_" + sk(key), String.valueOf(enabled)).commit();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        done();
    }

    public static void setMode(String key, int mode) {
        synchronized (LOCK) {
            prefs().edit().putString("mode_" + sk(key), String.valueOf(mode)).commit();
        }
        done();
    }

    public static void remove(String key) {
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            chats.remove(key);
            String s = sk(key);
            p.edit().putString("chats", JSONObject.quote(chats.toString()))
                    .remove("mode_" + s).remove("prompt_" + s).remove("keep_" + s).remove("rlang_" + s).commit();
        }
        done();
    }
}
