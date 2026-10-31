package org.telegram.messenger.zephyr;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.messenger.zephyr.plugins.PluginSettingsModel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Core-side access to the storage of the "ai_autoreply" plugin (SharedPreferences "zephyr_plugin_ai_autoreply").
 * Every value is the JSON text of a Python value. "chats" is a JSON string that itself holds a JSON object
 * {"account:dialogId": {enabled, account, dialog_id, title, type, fails, ...}}; per-chat mode is "mode_&lt;sk&gt;"
 * (0 auto, 1 draft, 2 friend), where sk = key with ':' -> '_' and '-' -> 'n'; "master" is a boolean.
 * Writes touch several keys in ONE editor commit, so the plugin never sees a half-written state.
 * Every read-modify-write holds {@link PluginSettingsModel#lock}, the lock of all plugin-side writes too: a write
 * of the running plugin cannot land between our read and our write, and the plugin writes "chats" with
 * PluginSettingsModel.putRawIf, so it never overwrites our change either.
 * See ZephyrGram-Plugins/docs/API.md, "ai_autoreply storage format".
 */
public final class ZephyrAutoReplyStore {
    public static final String PLUGIN_ID = "ai_autoreply";
    public static final int MODE_AUTO = 0, MODE_DRAFT = 1, MODE_FRIEND = 2;
    private static final Object LOCK = PluginSettingsModel.lock(PLUGIN_ID);

    public static final class Entry {
        public String key;
        public int account;
        public long dialogId;
        public String title;
        public String type;
        public boolean enabled;
        public int mode;
        /** Paused by the plugin after repeated errors; it switches itself back on after 30 minutes. */
        public boolean autoOff;
    }

    private ZephyrAutoReplyStore() {
    }

    /** The plugin's store; screens register an OnSharedPreferenceChangeListener here to follow the plugin's own writes. */
    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_" + PLUGIN_ID, Context.MODE_PRIVATE);
    }

    /** Keys whose change alters the chat list (entries, on/off, mode, master), written by either side. */
    public static boolean isListKey(String key) {
        return key == null || "chats".equals(key) || "master".equals(key) || key.startsWith("mode_") || key.startsWith("keep_");
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

    /** "all_skip": chats the owner removed; the plugin's "answer everyone" never adds them again. */
    private static JSONArray readSkip(SharedPreferences p) {
        try {
            String raw = p.getString("all_skip", null);
            if (raw == null) return new JSONArray();
            Object v = new JSONTokener(raw).nextValue();
            if (v instanceof JSONArray) return (JSONArray) v;
            if (v instanceof String && !((String) v).isEmpty()) return new JSONArray((String) v);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return new JSONArray();
    }

    private static String skipWith(SharedPreferences p, String key, boolean add) {
        JSONArray in = readSkip(p);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            String k = in.optString(i, null);
            if (k != null && !k.equals(key)) out.put(k);
        }
        if (add) out.put(key);
        return JSONObject.quote(out.toString());
    }

    /** "all_skip" without the given keys (chats that were just added again). */
    private static String skipWithout(SharedPreferences p, Set<String> keys) {
        JSONArray in = readSkip(p);
        JSONArray out = new JSONArray();
        for (int i = 0; i < in.length(); i++) {
            String k = in.optString(i, null);
            if (k != null && !keys.contains(k)) out.put(k);
        }
        return JSONObject.quote(out.toString());
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
                e.autoOff = !e.enabled && c.has("auto_off_ts") && !c.isNull("auto_off_ts");
                e.mode = readInt(p, "mode_" + sk(key), MODE_AUTO);
                out.add(e);
            }
        }
        java.util.Collections.sort(out, (a, b) -> {
            if (a.account != b.account) return a.account < b.account ? -1 : 1;
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
                p.edit().putString("chats", JSONObject.quote(chats.toString())).putString("keep_" + sk(key), String.valueOf(enabled))
                        .putString("all_skip", skipWith(p, key, false)).commit();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        done();
    }

    /**
     * Adds many private chats of one account in ONE commit. Chats that are already known keep their state and mode.
     * ids[i] / titles[i] describe one chat. Returns how many were really added.
     */
    public static int addMissing(int account, long[] ids, String[] titles) {
        int added = 0;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            SharedPreferences.Editor ed = p.edit();
            HashSet<String> addedKeys = new HashSet<>();
            try {
                for (int i = 0; i < ids.length; i++) {
                    String key = chatKey(account, ids[i]);
                    if (chats.has(key)) continue;
                    String title = titles != null && i < titles.length ? titles[i] : null;
                    JSONObject c = new JSONObject();
                    c.put("enabled", true).put("account", account).put("dialog_id", ids[i])
                            .put("title", title == null || title.isEmpty() ? String.valueOf(ids[i]) : title)
                            .put("type", "user").put("fails", 0);
                    chats.put(key, c);
                    ed.putString("keep_" + sk(key), "true");
                    addedKeys.add(key);
                    added++;
                }
                // like upsert: a chat added again is no longer "removed by the owner" (API.md, all_skip)
                if (added > 0) ed.putString("chats", JSONObject.quote(chats.toString()))
                        .putString("all_skip", skipWithout(p, addedKeys)).commit();
            } catch (Throwable t) {
                FileLog.e(t);
                return 0;
            }
        }
        if (added > 0) done();
        return added;
    }

    public static void setEnabled(String key, boolean enabled) {
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            JSONObject c = chats.optJSONObject(key);
            if (c == null) return;
            try {
                c.put("enabled", enabled);
                if (enabled) c.put("fails", 0);
                // The owner's own on/off ends an error pause: with auto_off_ts left in place the plugin would
                // switch a chat paused here back on after 30 minutes.
                c.remove("auto_off_ts");
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
                    .putString("all_skip", skipWith(p, key, true))
                    .remove("mode_" + s).remove("prompt_" + s).remove("keep_" + s).remove("rlang_" + s)
                    .remove("lore_" + s).remove("learned_" + s).commit();
        }
        done();
    }
}
