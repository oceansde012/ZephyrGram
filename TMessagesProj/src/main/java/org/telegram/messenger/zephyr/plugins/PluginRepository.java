package org.telegram.messenger.zephyr.plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Signed store transport. Cached snapshots are reverified; expired snapshots are display-only. */
public final class PluginRepository {
    public static final PluginRepository INSTANCE = new PluginRepository();
    public static final String REPO = "oceansde012/ZephyrGram-Plugins";
    private static final String BASE = "https://raw.githubusercontent.com/" + REPO + "/index/";
    /** Owner public keys (32 raw Ed25519 bytes); empty deliberately fails closed. */
    public static final byte[][] PUBLIC_KEYS = new byte[][]{
        new byte[]{(byte) 0xc1, (byte) 0x5e, (byte) 0x61, (byte) 0xaf, (byte) 0x54, (byte) 0xfc, (byte) 0x1a, (byte) 0xd8, (byte) 0xc8, (byte) 0x7b, (byte) 0x96, (byte) 0x9c, (byte) 0xc0, (byte) 0x80, (byte) 0xdb, (byte) 0x21, (byte) 0x09, (byte) 0xf7, (byte) 0xb7, (byte) 0x9b, (byte) 0xba, (byte) 0x73, (byte) 0x5b, (byte) 0x2f, (byte) 0x50, (byte) 0xba, (byte) 0xfb, (byte) 0xd7, (byte) 0x5b, (byte) 0x2e, (byte) 0x1f, (byte) 0xc3}
    };
    private static final String FALLBACK_BASE = "https://cdn.jsdelivr.net/gh/" + REPO + "@index/";
    private static final int MAX_INDEX_BYTES = 2 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PluginRepository"));
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile PluginIndex index;
    private volatile String error;
    private volatile boolean loading;
    private boolean cacheLoaded;

    public interface Listener { void onRepositoryChanged(); }
    /** Invoked on Android's main thread; a failed refresh may still return the previous cached index. */
    public interface Callback { void onComplete(PluginIndex index, String error); }

    private PluginRepository() {}
    public PluginIndex getIndex() { return index; }
    public String getLastError() { return error; }
    public boolean isLoading() { return loading; }
    public boolean isStale() { return index == null || index.isExpired(System.currentTimeMillis()); }
    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_repository", Context.MODE_PRIVATE);
    }

    static File root() {
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "zephyr_plugins");
    }

    public void loadCache() {
        worker.execute(() -> { synchronized (this) { loadCacheLocked(); } changed(); });
    }

    /** Blocking cache verification for startup, before any installed code runs. */
    public synchronized PluginIndex loadCacheSync() {
        loadCacheLocked();
        return index;
    }

    public void refresh(Callback callback) {
        worker.execute(() -> {
            String failure = null;
            loading = true;
            changed();
            try { refreshSync(); }
            catch (Exception e) { failure = message(e); error = failure; FileLog.e("PluginRepository: refresh failed", e); }
            finally { loading = false; changed(); }
            final String result = failure;
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onComplete(index, result));
        });
    }

    /** Blocking; call only on a background thread. A failed refresh never replaces trusted state. */
    public synchronized PluginIndex refreshSync() throws Exception {
        loadCacheLocked();
        if (PUBLIC_KEYS.length == 0) throw new IOException("Plugin store signing key is not configured");
        byte[] raw, sig;
        PluginIndex next;
        try {
            raw = fetch(BASE + "index.json", MAX_INDEX_BYTES);
            sig = fetch(BASE + "index.json.sig", 1024);
            next = verifySignedIndex(raw, sig, PUBLIC_KEYS);
        } catch (Exception primary) {
            FileLog.w("PluginRepository: primary index fetch failed, trying fallback: " + primary.getClass().getName());
            // Fallback mirror; the signature is verified exactly the same way, so the CDN is untrusted transport only.
            try {
                raw = fetch(FALLBACK_BASE + "index.json", MAX_INDEX_BYTES);
                sig = fetch(FALLBACK_BASE + "index.json.sig", 1024);
                next = verifySignedIndex(raw, sig, PUBLIC_KEYS);
            } catch (Exception fallback) { throw primary; }
        }
        long now = System.currentTimeMillis();
        if (next.isExpired(now)) throw new IOException("Plugin index has expired");
        String hash = sha256(raw);
        long highest = prefs().getLong("highest_sequence", -1);
        if (next.sequence < highest) throw new IOException("Plugin index rollback was rejected");
        if (next.sequence == highest && !hash.equals(prefs().getString("highest_hash", ""))) {
            throw new IOException("Conflicting plugin index sequence was rejected");
        }
        JSONObject envelope = new JSONObject();
        envelope.put("index", Base64.encodeToString(raw, Base64.NO_WRAP));
        envelope.put("signature", Base64.encodeToString(sig, Base64.NO_WRAP));
        File cache = new File(root(), "index-cache");
        mkdir(cache);
        String name = "index-" + next.sequence + "-" + hash + "-" + System.nanoTime() + ".json";
        writeSynced(new File(cache, name), envelope.toString().getBytes(StandardCharsets.UTF_8));
        if (!prefs().edit().putLong("highest_sequence", next.sequence).putString("highest_hash", hash)
                .putString("cache_file", name).commit()) throw new IOException("Cannot persist trusted index sequence");
        index = next;
        error = null;
        // Keep only the snapshot that is now trusted; every refresh would otherwise leave a new file behind.
        try {
            File[] old = cache.listFiles();
            if (old != null) for (File f : old) if (!f.getName().equals(name)) f.delete();
        } catch (Throwable ignored) { }
        return next;
    }

    private void loadCacheLocked() {
        if (cacheLoaded) return;
        cacheLoaded = true;
        String name = prefs().getString("cache_file", null);
        if (name == null) return;
        try {
            if (!name.matches("index-[0-9]+-[0-9a-f]{64}-[0-9]+\\.json")) throw new IOException("Invalid cache path");
            JSONObject envelope = new JSONObject(utf8(read(new File(new File(root(), "index-cache"), name), MAX_INDEX_BYTES * 2)));
            byte[] raw = Base64.decode(envelope.getString("index"), Base64.DEFAULT);
            byte[] sig = Base64.decode(envelope.getString("signature"), Base64.DEFAULT);
            PluginIndex cached = verifySignedIndex(raw, sig, PUBLIC_KEYS);
            if (cached.sequence != prefs().getLong("highest_sequence", -1)
                    || !sha256(raw).equals(prefs().getString("highest_hash", ""))) {
                throw new IOException("Saved plugin index does not match trusted sequence");
            }
            index = cached;
        } catch (Exception e) { error = message(e); FileLog.e("PluginRepository: cached index rejected", e); }
    }

    /** Signature is checked before parsing. Public for independent trust-chain tests. */
    public static PluginIndex verifySignedIndex(byte[] raw, byte[] signatureText, byte[][] keys) throws Exception {
        if (raw == null || raw.length == 0 || raw.length > MAX_INDEX_BYTES) throw new IOException("Invalid plugin index size");
        if (keys == null || keys.length == 0) throw new IOException("Plugin store signing key is not configured");
        if (signatureText == null || signatureText.length > 1024) throw new IOException("Invalid index signature size");
        byte[] signature;
        try { signature = Base64.decode(utf8(signatureText).trim(), Base64.DEFAULT); }
        catch (Exception e) { throw new IOException("Invalid index signature encoding"); }
        boolean trusted = false;
        if (signature.length == 64) for (byte[] key : keys) {
            if (key != null && key.length == 32 && Ed25519Verify.verify(raw, signature, key)) { trusted = true; break; }
        }
        if (!trusted) throw new IOException("Plugin index signature verification failed");
        PluginIndex parsed = PluginIndex.parse(utf8(raw));
        if (!REPO.equals(parsed.repo)) throw new IOException("Unexpected plugin repository");
        long now = System.currentTimeMillis();
        if (parsed.sequence <= 0 || parsed.generatedMs <= 0 || parsed.expiresMs <= parsed.generatedMs
                || parsed.generatedMs > now + 5 * 60 * 1000L) throw new IOException("Invalid plugin index dates or sequence");
        Set<String> ids = new HashSet<>();
        for (PluginIndex.IndexEntry entry : parsed.plugins) {
            if (!ids.add(entry.id)) throw new IOException("Duplicate plugin id");
            Set<Integer> versions = new HashSet<>();
            for (PluginIndex.VersionEntry v : entry.versions) {
                if (v.versionCode <= 0 || !versions.add(v.versionCode) || v.apiVersion <= 0
                        || v.size <= 0 || v.size > PluginManifest.MAX_SOURCE_BYTES || !v.sha256.matches("[0-9a-f]{64}")) {
                    throw new IOException("Invalid plugin version metadata");
                }
                validateDownloadUrl(entry.id, v.url);
            }
        }
        return parsed;
    }

    /** Uses only a currently trusted, unexpired entry; rejects redirects and unpinned URLs. */
    public byte[] download(String id, PluginIndex.VersionEntry version) throws Exception {
        PluginIndex trusted = index;
        if (trusted == null || trusted.isExpired(System.currentTimeMillis())) throw new IOException("A fresh signed index is required");
        PluginIndex.IndexEntry entry = trusted.find(id);
        PluginIndex.VersionEntry allowed = entry == null ? null : entry.findVersionCode(version.versionCode);
        if (allowed == null || allowed.revoked || entry.revoked.contains(allowed.versionCode)
                || !allowed.sha256.equals(version.sha256) || !allowed.url.equals(version.url)) throw new IOException("Plugin version is not trusted");
        validateDownloadUrl(id, allowed.url);
        byte[] raw = fetch(allowed.url, PluginManifest.MAX_SOURCE_BYTES);
        if (raw.length != allowed.size || !sha256(raw).equals(allowed.sha256)) throw new IOException("Plugin download checksum or size mismatch");
        return raw;
    }

    private static void validateDownloadUrl(String id, String value) throws IOException {
        String prefix = "https://raw.githubusercontent.com/" + REPO + "/";
        if (value == null || !value.startsWith(prefix)
                || !value.substring(prefix.length()).matches("[0-9a-f]{40}/plugins/" + id + "/" + id + "\\.plugin")) {
            throw new IOException("Plugin URL is not pinned to the trusted repository");
        }
    }

    private static byte[] fetch(String value, int limit) throws IOException {
        URL url = new URL(value);
        if (!"https".equals(url.getProtocol()) || !("raw.githubusercontent.com".equals(url.getHost()) || "cdn.jsdelivr.net".equals(url.getHost()))
                || url.getUserInfo() != null || url.getPort() != -1) throw new IOException("Unexpected download host");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", "ZephyrGram-Plugins/1");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (connection.getResponseCode() != 200) throw new IOException("Plugin server returned HTTP " + connection.getResponseCode());
            if (connection.getContentLengthLong() > limit) throw new IOException("Download exceeds size limit");
            try (InputStream in = connection.getInputStream()) { return read(in, limit); }
        } finally { connection.disconnect(); }
    }

    static byte[] read(File file, int limit) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return read(in, limit); }
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = input.read(buffer)) != -1) {
            if (n > limit - out.size()) throw new IOException("File exceeds size limit");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static String sha256(byte[] bytes) throws Exception {
        StringBuilder out = new StringBuilder(64);
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create plugin directory");
    }
    static void writeSynced(File file, byte[] bytes) throws IOException {
        if (file.exists()) throw new IOException("Refusing to overwrite an existing plugin snapshot");
        mkdir(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); output.getFD().sync(); }
    }
    static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
    private void changed() {
        AndroidUtilities.runOnUIThread(() -> { for (Listener l : listeners) { try { l.onRepositoryChanged(); } catch (Exception ignored) {} } });
    }
}
