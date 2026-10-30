package org.telegram.messenger.zephyr.plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Serializes installation/lifecycle work. Source snapshots are archived rather than deleted. */
public final class PluginManager {
    public static final PluginManager INSTANCE = new PluginManager();
    public static final int API_VERSION = 1;
    public static final String PERMISSIONS_REQUIRED = "PERMISSIONS_REQUIRED";
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "PluginManager"));
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, InstalledPlugin> installed = new ConcurrentHashMap<>();
    private volatile boolean initialized;
    private volatile boolean busy;
    private volatile boolean safeMode;
    private volatile boolean startupComplete;

    public interface Listener { void onPluginsChanged(); }
    /** Always delivered on Android's main thread; null error means success. */
    public interface Callback { void onComplete(String error); }

    public static final class InstalledPlugin {
        public final String id, name, version, status, error;
        public final int versionCode;
        public final boolean enabled, developer;
        public final List<String> permissions;
        public final File file;
        public final PluginManifest manifest;
        private InstalledPlugin(PluginManifest manifest, File file, boolean enabled, boolean developer, String status, String error) {
            this.manifest = manifest; this.file = file; this.id = manifest.id; this.name = manifest.name;
            this.version = manifest.version; this.versionCode = manifest.versionCode;
            this.permissions = Collections.unmodifiableList(new ArrayList<>(manifest.permissions));
            this.enabled = enabled; this.developer = developer; this.status = status; this.error = error;
        }
    }

    private PluginManager() {}
    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("zephyr_plugin_manager", Context.MODE_PRIVATE);
    }
    public boolean isBusy() { return busy; }
    public boolean isSafeMode() { return safeMode; }
    public boolean isInitialized() { return startupComplete; }
    public List<InstalledPlugin> getInstalled() {
        List<InstalledPlugin> result = new ArrayList<>(installed.values());
        Collections.sort(result, Comparator.comparing(p -> p.name, String.CASE_INSENSITIVE_ORDER));
        return Collections.unmodifiableList(result);
    }
    public InstalledPlugin getInstalled(String id) { return installed.get(id); }
    public void addListener(Listener listener) { listeners.addIfAbsent(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public boolean isAutoUpdateEnabled() { return prefs().getBoolean("auto_update", false); }
    public void setAutoUpdateEnabled(boolean enabled) {
        prefs().edit().putBoolean("auto_update", enabled).apply();
        if (enabled) { initialize(); PluginRepository.INSTANCE.refresh(null); }
        changed();
    }
    public boolean isDeveloperModeEnabled() { return prefs().getBoolean("developer_mode", false); }
    public void setDeveloperModeEnabled(boolean enabled) { prefs().edit().putBoolean("developer_mode", enabled).apply(); changed(); }

    public synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        PluginRuntime.INSTANCE.setFailureListener((id, reason) -> worker.execute(() -> disableSync(id, "errors", reason)));
        PluginRepository.INSTANCE.addListener(() -> {
            if (!PluginRepository.INSTANCE.isLoading() && PluginRepository.INSTANCE.getIndex() != null) {
                worker.execute(() -> { reconcile(); if (isAutoUpdateEnabled() && !PluginRepository.INSTANCE.isStale()) updateAllSync(); });
            }
        });
        worker.execute(() -> {
            busy = true;
            try {
                PluginRepository.INSTANCE.loadCacheSync();
                SharedPreferences preferences = prefs();
                int early = preferences.getBoolean("boot_pending", false) ? preferences.getInt("early_crashes", 0) + 1 : 0;
                safeMode = early >= 3 || preferences.getBoolean("safe_mode", false);
                String interrupted = preferences.getString("loading_id", null);
                Set<String> ids = new HashSet<>(preferences.getStringSet("installed_ids", Collections.emptySet()));
                boolean anyEnabled = false;
                for (String id : ids) if (preferences.getBoolean(id + ".enabled", false)) anyEnabled = true;
                SharedPreferences.Editor edit = preferences.edit().putInt("early_crashes", early)
                        .putBoolean("boot_pending", anyEnabled).putBoolean("safe_mode", safeMode).remove("loading_id");
                if (interrupted != null && PluginManifest.isValidId(interrupted)) {
                    int crashes = preferences.getInt(interrupted + ".load_crashes", 0) + 1;
                    edit.putInt(interrupted + ".load_crashes", crashes);
                    if (crashes >= 2) edit.putBoolean(interrupted + ".enabled", false).putString(interrupted + ".status", "crash");
                }
                if (!edit.commit()) throw new IOException("Cannot persist plugin startup state");
                for (String id : ids) {
                    if (!PluginManifest.isValidId(id)) continue;
                    try {
                        File file = activeFile(id);
                        byte[] raw = PluginRepository.read(file, PluginManifest.MAX_SOURCE_BYTES);
                        PluginManifest manifest = PluginManifest.parse(PluginRepository.utf8(raw));
                        if (!id.equals(manifest.id) || !PluginRepository.sha256(raw).equals(preferences.getString(id + ".sha256", ""))) {
                            throw new IOException("Installed source checksum mismatch");
                        }
                        boolean enabled = preferences.getBoolean(id + ".enabled", false);
                        String status = preferences.getString(id + ".status", enabled ? "enabled" : "user");
                        InstalledPlugin record = new InstalledPlugin(manifest, file, false,
                                preferences.getBoolean(id + ".developer", false), status, preferences.getString(id + ".error", null));
                        installed.put(id, record);
                        if (!manifest.isCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION)) disableSync(id, "incompatible", null);
                        else if (safeMode) disableSync(id, "safe_mode", null);
                        else if (enabled) enableSync(id);
                    } catch (Exception e) {
                        preferences.edit().putBoolean(id + ".enabled", false).putString(id + ".status", "errors")
                                .putString(id + ".error", PluginRepository.message(e)).commit();
                        PluginManifest display = new PluginManifest();
                        display.id = id;
                        display.name = preferences.getString(id + ".name", id);
                        display.version = preferences.getString(id + ".version", "0.0.0");
                        display.versionCode = preferences.getInt(id + ".version_code", 0);
                        installed.put(id, new InstalledPlugin(display, activeFile(id), false,
                                preferences.getBoolean(id + ".developer", false), "errors", PluginRepository.message(e)));
                    }
                }
                reconcile();
                PluginRuntime.INSTANCE.dispatchAppEvent("START");
            } catch (Exception ignored) { safeMode = true; }
            finally { busy = false; startupComplete = true; changed(); }
            AndroidUtilities.runOnUIThread(() -> worker.execute(() -> prefs().edit().putBoolean("boot_pending", false)
                    .putInt("early_crashes", 0).commit()), 30000);
        });
        PluginRepository.INSTANCE.loadCache();
        if (isAutoUpdateEnabled()) PluginRepository.INSTANCE.refresh(null);
    }

    public PluginIndex.VersionEntry getUpdate(String id) {
        InstalledPlugin current = installed.get(id);
        PluginIndex index = PluginRepository.INSTANCE.getIndex();
        PluginIndex.IndexEntry entry = index == null ? null : index.find(id);
        PluginIndex.VersionEntry version = entry == null ? null : entry.bestCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION);
        return current != null && version != null && version.versionCode > current.versionCode ? version : null;
    }

    public void install(String id, boolean approvePermissions, Callback callback) {
        initialize();
        execute(() -> installSync(id, approvePermissions), callback);
    }

    public void installFromFile(File file, boolean approvePermissions, Callback callback) {
        initialize();
        execute(() -> {
            if (!isDeveloperModeEnabled()) throw new IOException("Developer mode is required for file installation");
            byte[] raw = PluginRepository.read(file, PluginManifest.MAX_SOURCE_BYTES);
            installBytes(raw, null, null, true, approvePermissions);
        }, callback);
    }

    public void setEnabled(String id, boolean enabled, Callback callback) {
        initialize();
        execute(() -> { if (enabled) enableSync(id); else disableSync(id, "user", null); }, callback);
    }

    public void updateAll(Callback callback) {
        initialize();
        execute(() -> {
            String errors = updateAllSync();
            if (errors != null) throw new IOException(errors);
        }, callback);
    }

    public void dispatchAppEvent(String event) { if (initialized) PluginRuntime.INSTANCE.dispatchAppEvent(event); }

    private interface Operation { void run() throws Exception; }
    private void execute(Operation operation, Callback callback) {
        worker.execute(() -> {
            busy = true; changed();
            String failure = null;
            try { operation.run(); } catch (Exception e) { failure = PluginRepository.message(e); }
            finally { busy = false; changed(); }
            final String result = failure;
            if (callback != null) AndroidUtilities.runOnUIThread(() -> callback.onComplete(result));
        });
    }

    private void installSync(String id, boolean approvePermissions) throws Exception {
        if (!PluginManifest.isValidId(id)) throw new IOException("Invalid plugin id");
        PluginRepository repository = PluginRepository.INSTANCE;
        if (repository.isStale()) throw new IOException("Refresh the signed store index before installing");
        PluginIndex.IndexEntry entry = repository.getIndex().find(id);
        PluginIndex.VersionEntry version = entry == null ? null : entry.bestCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION);
        if (version == null) throw new IOException("No compatible plugin version is available");
        InstalledPlugin previous = installed.get(id);
        if (previous != null && version.versionCode <= previous.versionCode) throw new IOException("Plugin is already up to date");
        installBytes(repository.download(id, version), id, version, false, approvePermissions);
    }

    private void installBytes(byte[] raw, String expectedId, PluginIndex.VersionEntry version, boolean developer, boolean approved) throws Exception {
        PluginManifest manifest = PluginManifest.parse(PluginRepository.utf8(raw));
        if (expectedId != null && !expectedId.equals(manifest.id)) throw new IOException("Plugin id differs from signed entry");
        if (!manifest.isCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION)) throw new IOException("Plugin is incompatible with this app version");
        if (version != null && (manifest.versionCode != version.versionCode || !manifest.version.equals(version.version)
                || manifest.apiVersion != version.apiVersion || !same(manifest.minAppVersion, version.minAppVersion)
                || !same(manifest.maxAppVersion, version.maxAppVersion))) throw new IOException("Plugin manifest differs from signed version metadata");
        InstalledPlugin old = installed.get(manifest.id);
        if (old != null && manifest.versionCode <= old.versionCode) throw new IOException("Plugin version rollback is not allowed");
        List<String> previousPermissions = old == null ? Collections.emptyList() : old.permissions;
        if (!approved && !previousPermissions.containsAll(manifest.permissions)) throw new IOException(PERMISSIONS_REQUIRED);
        boolean enable = old == null || old.enabled;
        File active = activeFile(manifest.id);
        File candidate = new File(active.getParentFile(), "candidate-" + System.nanoTime() + ".plugin");
        PluginRepository.writeSynced(candidate, raw);
        File archive = null;
        boolean promoted = false;
        try {
            if (enable) {
                markLoading(manifest.id);
                PluginRuntime.INSTANCE.unload(manifest.id);
                PluginRuntime.INSTANCE.load(manifest.id, candidate);
            }
            if (active.exists()) {
                File dir = new File(PluginRepository.root(), "archive/" + manifest.id);
                PluginRepository.mkdir(dir);
                archive = new File(dir, "previous-" + System.nanoTime() + ".plugin");
                if (!active.renameTo(archive)) throw new IOException("Cannot preserve previous plugin version");
            }
            if (!candidate.renameTo(active)) throw new IOException("Cannot activate downloaded plugin file");
            promoted = true;
            Set<String> ids = new HashSet<>(prefs().getStringSet("installed_ids", Collections.emptySet()));
            ids.add(manifest.id);
            if (!prefs().edit().putStringSet("installed_ids", ids).putString(manifest.id + ".sha256", PluginRepository.sha256(raw))
                    .putBoolean(manifest.id + ".enabled", enable).putBoolean(manifest.id + ".developer", developer)
                    .putString(manifest.id + ".name", manifest.name).putString(manifest.id + ".version", manifest.version)
                    .putInt(manifest.id + ".version_code", manifest.versionCode)
                    .putString(manifest.id + ".status", enable ? "enabled" : "user").remove(manifest.id + ".error")
                    .remove("loading_id").commit()) throw new IOException("Cannot save plugin state");
            installed.put(manifest.id, new InstalledPlugin(manifest, active, enable, developer, enable ? "enabled" : "user", null));
        } catch (Exception e) {
            PluginRuntime.INSTANCE.unload(manifest.id);
            if (promoted && active.exists()) {
                File failed = new File(active.getParentFile(), "failed-" + System.nanoTime() + ".plugin");
                if (!active.renameTo(failed)) throw new IOException("Update failed; cannot preserve failed source", e);
            }
            if (archive != null && archive.exists() && !archive.renameTo(active)) throw new IOException("Update failed; previous version remains in archive", e);
            prefs().edit().remove("loading_id").commit();
            if (old != null && old.enabled) {
                try { PluginRuntime.INSTANCE.load(old.id, old.file); }
                catch (Exception failure) { disableSync(old.id, "errors", PluginRepository.message(failure)); }
            }
            throw e;
        }
    }

    private void enableSync(String id) throws Exception {
        InstalledPlugin record = installed.get(id);
        if (record == null) throw new IOException("Plugin is not installed");
        if (record.enabled) return;
        if (!PluginRuntime.isSupported()) throw new IOException("Python plugins require a supported 64-bit device");
        if (!record.manifest.isCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION)) throw new IOException("Plugin is incompatible with this app version");
        PluginIndex index = PluginRepository.INSTANCE.getIndex();
        if (index != null && (index.isRemoved(id) || index.isRevoked(id, record.versionCode))) throw new IOException("Plugin version was removed or revoked");
        if (record.developer && !isDeveloperModeEnabled()) throw new IOException("Developer mode is required for this plugin");
        if (!PluginRepository.sha256(PluginRepository.read(record.file, PluginManifest.MAX_SOURCE_BYTES)).equals(prefs().getString(id + ".sha256", ""))) {
            throw new IOException("Installed plugin checksum mismatch");
        }
        markLoading(id);
        try {
            PluginRuntime.INSTANCE.load(id, record.file);
            if (!prefs().edit().putBoolean(id + ".enabled", true).putString(id + ".status", "enabled")
                    .putInt(id + ".load_crashes", 0).putBoolean("safe_mode", false).putInt("early_crashes", 0)
                    .remove(id + ".error").remove("loading_id").commit()) throw new IOException("Cannot save plugin state");
            safeMode = false;
            installed.put(id, new InstalledPlugin(record.manifest, record.file, true, record.developer, "enabled", null));
        } catch (Exception e) { disableSync(id, "errors", PluginRepository.message(e)); throw e; }
    }

    private void markLoading(String id) throws IOException {
        if (!prefs().edit().putString("loading_id", id).commit()) throw new IOException("Cannot persist plugin load marker");
    }
    private void disableSync(String id, String status, String error) {
        PluginRuntime.INSTANCE.unload(id);
        InstalledPlugin old = installed.get(id);
        SharedPreferences.Editor edit = prefs().edit().putBoolean(id + ".enabled", false).putString(id + ".status", status);
        if (error == null) edit.remove(id + ".error"); else edit.putString(id + ".error", error);
        if (id.equals(prefs().getString("loading_id", null))) edit.remove("loading_id");
        edit.commit();
        if (old != null) installed.put(id, new InstalledPlugin(old.manifest, old.file, false, old.developer, status, error));
        changed();
    }
    private void reconcile() {
        PluginIndex index = PluginRepository.INSTANCE.getIndex();
        if (index == null) return;
        for (InstalledPlugin record : installed.values()) {
            if (index.isRemoved(record.id)) disableSync(record.id, "removed", null);
            else if (index.isRevoked(record.id, record.versionCode)) disableSync(record.id, "revoked", null);
        }
    }
    private String updateAllSync() {
        List<String> errors = new ArrayList<>();
        for (InstalledPlugin record : getInstalled()) {
            if (record.developer || getUpdate(record.id) == null) continue;
            try { installSync(record.id, false); }
            catch (Exception e) { errors.add(record.name + ": " + PluginRepository.message(e)); }
        }
        changed();
        return errors.isEmpty() ? null : android.text.TextUtils.join("\n", errors);
    }
    private static File activeFile(String id) { return new File(PluginRepository.root(), "plugins/" + id + "/" + id + ".plugin"); }
    private static boolean same(String a, String b) { return a == null || a.isEmpty() ? b == null || b.isEmpty() : a.equals(b); }
    private void changed() {
        AndroidUtilities.runOnUIThread(() -> { for (Listener l : listeners) { try { l.onPluginsChanged(); } catch (Exception ignored) {} } });
    }
}
