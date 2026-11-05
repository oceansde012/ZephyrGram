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
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
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
    /**
     * Per-chat setting names: the stored key is name + "_" + sk(chatKey). Numbers: mode 0 auto / 1 draft / 2 friend;
     * gender (of the OTHER person) 0 auto / 1 man / 2 woman; rlang 0 as in general / 1 auto / 2 Ukrainian /
     * 3 Russian / 4 the other person's language; react 0 as in general / 1 off / 2 on. Strings: lore (what the
     * owner knows about the person), learned (notes the bot learned), prompt. Booleans: memory, see_media,
     * transcribe_voice (consents, off by default).
     */
    public static final String K_MODE = "mode", K_GENDER = "gender", K_RLANG = "rlang", K_REACT = "react",
            K_LORE = "lore", K_LEARNED = "learned", K_PROMPT = "prompt",
            K_MEMORY = "memory", K_SEE_MEDIA = "see_media", K_VOICE = "transcribe_voice";
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
        /** gender_&lt;sk&gt;, rlang_&lt;sk&gt;, react_&lt;sk&gt; (see {@link #K_GENDER}). */
        public int gender, rlang, react;
    }

    private ZephyrAutoReplyStore() {
    }

    /** The plugin's store; screens register an OnSharedPreferenceChangeListener here to follow the plugin's own writes. */
    public static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_" + PLUGIN_ID, Context.MODE_PRIVATE);
    }

    /** Keys whose change alters the chat list (entries, on/off, mode, master), written by either side. */
    public static boolean isListKey(String key) {
        return key == null || "chats".equals(key) || "master".equals(key) || key.startsWith("mode_") || key.startsWith("keep_")
                || key.startsWith("gender_") || key.startsWith("rlang_") || key.startsWith("react_");
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

    private static boolean readBool(SharedPreferences p, String key, boolean def) {
        try {
            String raw = p.getString(key, null);
            if (raw == null) return def;
            Object v = new JSONTokener(raw).nextValue();
            if (v instanceof Boolean) return (Boolean) v;
            if (v instanceof Number) return ((Number) v).intValue() != 0;
            if (v instanceof String) return "true".equalsIgnoreCase(((String) v).trim()) || "1".equals(((String) v).trim());
        } catch (Throwable ignore) {
        }
        return def;
    }

    private static String readString(SharedPreferences p, String key) {
        try {
            String raw = p.getString(key, null);
            if (raw == null) return "";
            Object v = new JSONTokener(raw).nextValue();
            if (v == null || v == JSONObject.NULL) return "";
            return String.valueOf(v);
        } catch (Throwable ignore) {
            return "";
        }
    }

    /** JSON text of a Python value, as the plugin stores it: numbers and booleans plain, strings quoted. */
    private static String encode(Object value) {
        if (value instanceof Boolean || value instanceof Number) return String.valueOf(value);
        return JSONObject.quote(String.valueOf(value));
    }

    /** Stored key of a per-chat setting: name + "_" + sk(chatKey). */
    public static String chatPrefKey(String name, String chatKey) {
        return name + "_" + sk(chatKey);
    }

    public static int getChatInt(String chatKey, String name, int def) {
        return readInt(prefs(), chatPrefKey(name, chatKey), def);
    }

    public static boolean getChatBool(String chatKey, String name, boolean def) {
        return readBool(prefs(), chatPrefKey(name, chatKey), def);
    }

    /** A per-chat text setting ("" when not set). */
    public static String getChatString(String chatKey, String name) {
        return readString(prefs(), chatPrefKey(name, chatKey));
    }

    /** One per-chat setting. value: Integer, Boolean or String; null removes it (the plugin's default). */
    public static void setChatValue(String chatKey, String name, Object value) {
        setForChats(Collections.singletonList(chatKey), name, value);
    }

    /** Several settings of one chat in ONE commit. Map values as in {@link #setChatValue}. */
    public static void setChatValues(String chatKey, Map<String, Object> values) {
        if (chatKey == null || values == null || values.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences.Editor ed = prefs().edit();
            for (Map.Entry<String, Object> v : values.entrySet()) {
                String pk = chatPrefKey(v.getKey(), chatKey);
                if (v.getValue() == null) ed.remove(pk);
                else ed.putString(pk, encode(v.getValue()));
            }
            ed.commit();
        }
        done();
    }

    /** The same setting for many chats in ONE commit (bulk edit). value as in {@link #setChatValue}. */
    public static void setForChats(Collection<String> chatKeys, String name, Object value) {
        if (chatKeys == null || chatKeys.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences.Editor ed = prefs().edit();
            for (String k : chatKeys) {
                String pk = chatPrefKey(name, k);
                if (value == null) ed.remove(pk);
                else ed.putString(pk, encode(value));
            }
            ed.commit();
        }
        done();
    }

    /** A plain boolean setting of the plugin ("reply_all", "skip_links", "short_msgs", "revive", ...). */
    public static boolean getBool(String name, boolean def) {
        return readBool(prefs(), name, def);
    }

    public static void setBool(String name, boolean value) {
        synchronized (LOCK) {
            prefs().edit().putString(name, String.valueOf(value)).commit();
        }
        done();
    }

    /** The stored entry of one chat, or null when it is not in the list (any more). */
    public static Entry find(String key) {
        if (key == null) return null;
        for (Entry e : load()) if (key.equals(e.key)) return e;
        return null;
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
                String s = sk(key);
                e.mode = readInt(p, "mode_" + s, MODE_AUTO);
                e.gender = readInt(p, "gender_" + s, 0);
                e.rlang = readInt(p, "rlang_" + s, 0);
                e.react = readInt(p, "react_" + s, 0);
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
        setEnabled(Collections.singletonList(key), enabled);
    }

    /** On/off for many chats in ONE commit. */
    public static void setEnabled(Collection<String> keys, boolean enabled) {
        if (keys == null || keys.isEmpty()) return;
        boolean changed = false;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            SharedPreferences.Editor ed = p.edit();
            try {
                for (String key : keys) {
                    JSONObject c = chats.optJSONObject(key);
                    if (c == null) continue;
                    c.put("enabled", enabled);
                    if (enabled) c.put("fails", 0);
                    // The owner's own on/off ends an error pause: with auto_off_ts left in place the plugin would
                    // switch a chat paused here back on after 30 minutes.
                    c.remove("auto_off_ts");
                    ed.putString("keep_" + sk(key), String.valueOf(enabled));
                    changed = true;
                }
                if (changed) ed.putString("chats", JSONObject.quote(chats.toString())).commit();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (changed) done();
    }

    public static void setMode(String key, int mode) {
        synchronized (LOCK) {
            prefs().edit().putString("mode_" + sk(key), String.valueOf(mode)).commit();
        }
        done();
    }

    public static void remove(String key) {
        remove(Collections.singletonList(key));
    }

    /**
     * Removes many chats in ONE commit, with their per-chat settings, and puts them on "all_skip" so "answer
     * everyone" does not add them back (the same keys the plugin's own removal drops).
     */
    public static void remove(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return;
        synchronized (LOCK) {
            SharedPreferences p = prefs();
            JSONObject chats = readChats(p);
            SharedPreferences.Editor ed = p.edit();
            JSONArray skipIn = readSkip(p);
            JSONArray skip = new JSONArray();
            for (int i = 0; i < skipIn.length(); i++) {
                String k = skipIn.optString(i, null);
                if (k != null && !keys.contains(k)) skip.put(k);
            }
            for (String key : keys) {
                chats.remove(key);
                skip.put(key);
                String s = sk(key);
                ed.remove("mode_" + s).remove("prompt_" + s).remove("keep_" + s).remove("rlang_" + s)
                        .remove("react_" + s).remove("lore_" + s).remove("learned_" + s);
            }
            ed.putString("chats", JSONObject.quote(chats.toString()))
                    .putString("all_skip", JSONObject.quote(skip.toString())).commit();
        }
        done();
    }
}
