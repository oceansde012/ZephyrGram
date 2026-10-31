package org.telegram.messenger.zephyr.plugins;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Model + parser for the signed plugin store index (schema 1). Depends only on org.json (no Android classes).
 *
 * Public API:
 *   static final int SCHEMA = 1
 *   static PluginIndex parse(String json) throws JSONException     (also throws JSONException for unsupported schema)
 *   int schema; long sequence; long generatedMs; long expiresMs (0 = never); String repo;
 *   List&lt;IndexEntry&gt; plugins; Set&lt;String&gt; removed;
 *   IndexEntry find(String id)                  -> null if absent or removed
 *   boolean isRemoved(String id)
 *   boolean isExpired(long nowMs)
 *   boolean isRevoked(String id, int versionCode)
 *
 *   IndexEntry: String id, name, author, description, icon, latest; List&lt;String&gt; tags, permissions;
 *               List&lt;VersionEntry&gt; versions (newest first); Set&lt;Integer&gt; revoked; boolean removed;
 *               VersionEntry bestCompatible(String appVersion, int apiLevel)  -> null if none
 *               VersionEntry latestVersion()          -> entry named by "latest" (or versions[0]); may be revoked/incompatible
 *               VersionEntry findVersionCode(int code)
 *   VersionEntry: String version; int versionCode, apiVersion; String minAppVersion, maxAppVersion (null if absent);
 *                 String url, sha256 (lowercase hex), changelog; long size; long publishedMs; boolean revoked;
 *                 boolean isCompatible(String appVersion, int apiLevel)   -> api &lt;= apiLevel && min &lt;= app &lt; max
 *
 * Date fields ("generated", "expires", "published") may be epoch seconds (number) or ISO-8601 UTC strings
 * such as 2026-01-31T12:00:00Z (fractional seconds tolerated).
 */
public final class PluginIndex {

    public static final int SCHEMA = 1;

    public int schema;
    public long sequence;
    public long generatedMs;
    public long expiresMs;
    public String repo;
    public final List<IndexEntry> plugins = new ArrayList<>();
    public final Set<String> removed = new HashSet<>();

    public static final class VersionEntry {
        public String version;
        public int versionCode;
        public int apiVersion = 1;
        public String minAppVersion;
        public String maxAppVersion;
        public String url;
        public String sha256;
        public long size;
        public String changelog;
        public long publishedMs;
        public boolean revoked;

        /** apiVersion supported (<= apiLevel) and minApp <= appVersion < maxApp. Does not look at revoked. */
        public boolean isCompatible(String appVersion, int apiLevel) {
            if (apiVersion > apiLevel) {
                return false;
            }
            SemVer app = SemVer.parse(appVersion);
            if (minAppVersion != null && !minAppVersion.isEmpty()) {
                SemVer min = SemVer.parse(minAppVersion);
                if (min == null || app == null || app.compareTo(min) < 0) {
                    return false;
                }
            }
            if (maxAppVersion != null && !maxAppVersion.isEmpty()) {
                SemVer max = SemVer.parse(maxAppVersion);
                if (max == null || app == null || app.compareTo(max) >= 0) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class IndexEntry {
        public String id;
        public String name;
        public String author;
        public String description;
        public String icon;
        public String latest;
        public final List<String> tags = new ArrayList<>();
        public final List<String> permissions = new ArrayList<>();
        public final List<VersionEntry> versions = new ArrayList<>();
        public final Set<Integer> revoked = new HashSet<>();
        public boolean removed;

        /** Highest non-revoked versionCode whose apiVersion/app range is compatible; null if none. */
        public VersionEntry bestCompatible(String appVersion, int apiLevel) {
            VersionEntry best = null;
            for (int i = 0; i < versions.size(); i++) {
                VersionEntry v = versions.get(i);
                if (v.revoked || revoked.contains(v.versionCode) || !v.isCompatible(appVersion, apiLevel)) {
                    continue;
                }
                if (best == null || v.versionCode > best.versionCode) {
                    best = v;
                }
            }
            return best;
        }

        public VersionEntry latestVersion() {
            if (latest != null) {
                for (int i = 0; i < versions.size(); i++) {
                    if (latest.equals(versions.get(i).version)) {
                        return versions.get(i);
                    }
                }
            }
            return versions.isEmpty() ? null : versions.get(0);
        }

        public VersionEntry findVersionCode(int code) {
            for (int i = 0; i < versions.size(); i++) {
                if (versions.get(i).versionCode == code) {
                    return versions.get(i);
                }
            }
            return null;
        }
    }

    public static PluginIndex parse(String json) throws JSONException {
        JSONObject o = new JSONObject(json);
        PluginIndex idx = new PluginIndex();
        idx.schema = o.optInt("schema", 0);
        if (idx.schema != SCHEMA) {
            throw new JSONException("unsupported index schema " + idx.schema);
        }
        idx.sequence = o.optLong("sequence", -1);
        if (idx.sequence < 0) {
            throw new JSONException("missing sequence");
        }
        idx.generatedMs = parseDate(o.opt("generated"));
        idx.expiresMs = parseDate(o.opt("expires"));
        idx.repo = o.optString("repo", "");

        JSONArray rem = o.optJSONArray("removed");
        if (rem != null) {
            for (int i = 0; i < rem.length(); i++) {
                String id = rem.optString(i, null);
                if (id != null && !id.isEmpty()) {
                    idx.removed.add(id);
                }
            }
        }
        JSONArray arr = o.optJSONArray("plugins");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject po = arr.optJSONObject(i);
                if (po == null) {
                    continue;
                }
                IndexEntry e = parseEntry(po);
                if (e != null) {
                    e.removed = idx.removed.contains(e.id);
                    idx.plugins.add(e);
                }
            }
        }
        return idx;
    }

    private static IndexEntry parseEntry(JSONObject po) {
        String id = po.optString("id", "");
        if (!PluginManifest.isValidId(id)) {
            return null;
        }
        IndexEntry e = new IndexEntry();
        e.id = id;
        e.name = po.optString("name", id);
        e.author = po.optString("author", "");
        e.description = po.optString("description", "");
        e.icon = po.has("icon") && !po.isNull("icon") ? po.optString("icon", null) : null;
        e.latest = po.has("latest") && !po.isNull("latest") ? po.optString("latest", null) : null;
        readStrings(po.optJSONArray("tags"), e.tags);
        readStrings(po.optJSONArray("permissions"), e.permissions);
        JSONArray rev = po.optJSONArray("revoked");
        if (rev != null) {
            for (int i = 0; i < rev.length(); i++) {
                e.revoked.add(rev.optInt(i, -1));
            }
        }
        JSONArray vs = po.optJSONArray("versions");
        if (vs != null) {
            for (int i = 0; i < vs.length(); i++) {
                JSONObject vo = vs.optJSONObject(i);
                if (vo == null) {
                    continue;
                }
                VersionEntry v = new VersionEntry();
                v.version = vo.optString("version", "");
                v.versionCode = vo.optInt("versionCode", -1);
                v.apiVersion = vo.optInt("apiVersion", 1);
                v.minAppVersion = optStr(vo, "minAppVersion");
                v.maxAppVersion = optStr(vo, "maxAppVersion");
                v.url = vo.optString("url", "");
                String sha = vo.optString("sha256", "");
                v.sha256 = sha.toLowerCase(Locale.ROOT);
                v.size = vo.optLong("size", -1);
                v.changelog = vo.optString("changelog", "");
                v.publishedMs = parseDate(vo.opt("published"));
                if (v.versionCode < 0 || v.url.isEmpty() || v.sha256.length() != 64 || v.size <= 0
                        || !SemVer.isValid(v.version)) {
                    continue; // unusable entry
                }
                v.revoked = e.revoked.contains(v.versionCode);
                e.versions.add(v);
            }
        }
        return e;
    }

    private static String optStr(JSONObject o, String k) {
        if (!o.has(k) || o.isNull(k)) {
            return null;
        }
        String s = o.optString(k, "");
        return s.isEmpty() ? null : s;
    }

    private static void readStrings(JSONArray a, List<String> out) {
        if (a == null) {
            return;
        }
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i, null);
            if (s != null) {
                out.add(s);
            }
        }
    }

    /** Epoch seconds (number) or ISO-8601 UTC string -> ms; 0 on null/unparseable. */
    static long parseDate(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return 0;
        }
        if (v instanceof Number) {
            return ((Number) v).longValue() * 1000L;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(s) * 1000L;
        } catch (NumberFormatException ignore) {
        }
        try {
            // normalise: drop fractional seconds, "Z"/offset -> handled below
            String t = s;
            int dot = t.indexOf('.');
            if (dot > 0) {
                int end = dot + 1;
                while (end < t.length() && Character.isDigit(t.charAt(end))) {
                    end++;
                }
                t = t.substring(0, dot) + t.substring(end);
            }
            if (t.endsWith("Z")) {
                t = t.substring(0, t.length() - 1) + "+0000";
            } else if (t.length() > 6 && t.charAt(t.length() - 3) == ':'
                    && (t.charAt(t.length() - 6) == '+' || t.charAt(t.length() - 6) == '-')) {
                t = t.substring(0, t.length() - 3) + t.substring(t.length() - 2);
            }
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(t).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    public IndexEntry find(String id) {
        if (id == null || removed.contains(id)) {
            return null;
        }
        for (int i = 0; i < plugins.size(); i++) {
            if (plugins.get(i).id.equals(id)) {
                return plugins.get(i);
            }
        }
        return null;
    }

    public boolean isRemoved(String id) {
        return removed.contains(id);
    }

    public boolean isExpired(long nowMs) {
        return expiresMs > 0 && nowMs > expiresMs;
    }

    public boolean isRevoked(String id, int versionCode) {
        for (int i = 0; i < plugins.size(); i++) {
            IndexEntry e = plugins.get(i);
            if (e.id.equals(id)) {
                return e.revoked.contains(versionCode);
            }
        }
        return false;
    }

    /** Non-removed entries, in index order (unmodifiable view). */
    public List<IndexEntry> visiblePlugins() {
        List<IndexEntry> r = new ArrayList<>();
        for (int i = 0; i < plugins.size(); i++) {
            if (!plugins.get(i).removed) {
                r.add(plugins.get(i));
            }
        }
        return Collections.unmodifiableList(r);
    }
}
