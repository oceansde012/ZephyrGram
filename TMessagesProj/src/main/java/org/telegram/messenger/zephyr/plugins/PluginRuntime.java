package org.telegram.messenger.zephyr.plugins;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Lazy embedded Python host. Plugin code is trusted code, not a security sandbox. */
public final class PluginRuntime implements PluginHost {
    public static final PluginRuntime INSTANCE = new PluginRuntime();
    public static final int API_VERSION = 1;

    public interface FailureListener {
        void onPluginFailed(String id, String reason);
    }

    private final Object pythonLock = new Object();
    private final ConcurrentHashMap<String, PluginManifest> loaded = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<Long>> errors = new ConcurrentHashMap<>();
    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ZephyrPlugin");
        t.setDaemon(true);
        return t;
    });
    private volatile FailureListener failureListener;
    private volatile PyObject host;

    private PluginRuntime() { }

    public static boolean isSupported() {
        return android.os.Process.is64Bit();
    }

    public void setFailureListener(FailureListener listener) {
        failureListener = listener;
    }

    public static File getDataDirectory(String id) {
        if (!PluginManifest.isValidId(id)) throw new IllegalArgumentException("Invalid plugin id");
        return new File(ApplicationLoader.applicationContext.getFilesDir(), "zephyr_plugins/data/" + id);
    }

    private PyObject startPython() {
        if (host == null) {
            if (!isSupported()) throw new IllegalStateException("Plugins need a 64-bit Android process");
            synchronized (PluginSmoke.class) {
                if (!Python.isStarted()) {
                    Python.start(new AndroidPlatform(ApplicationLoader.applicationContext));
                }
            }
            host = Python.getInstance().getModule("zephyr_host");
        }
        return host;
    }

    @Override
    public void load(String id, File file) throws Exception {
        if (!PluginManifest.isValidId(id) || !file.isFile()) {
            throw new IllegalArgumentException("Invalid plugin file");
        }
        byte[] source;
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > PluginManifest.MAX_SOURCE_BYTES) {
                    throw new IllegalArgumentException("Plugin is larger than 512 KB");
                }
                out.write(buffer, 0, count);
            }
            source = out.toByteArray();
        }
        PluginManifest manifest = PluginManifest.parse(new String(source, StandardCharsets.UTF_8));
        if (!id.equals(manifest.id)) throw new IllegalArgumentException("Plugin id does not match its file");
        if (!manifest.isCompatible(BuildConfig.ZEPHYR_VERSION, API_VERSION)) {
            throw new IllegalArgumentException("Plugin is incompatible with this version of ZephyrGram");
        }
        synchronized (pythonLock) {
            unload(id);
            File data = getDataDirectory(id);
            if (!data.isDirectory() && !data.mkdirs()) throw new IllegalStateException("Cannot create plugin data directory");
            loaded.put(id, manifest);
            errors.remove(id);
            String previous = PluginHooks.getCurrentPlugin();
            PluginHooks.setCurrentPlugin(id);
            try {
                startPython().callAttr("load_plugin", id, file.getAbsolutePath(), data.getAbsolutePath());
            } catch (Throwable t) {
                log(id, "Load failed: " + t);
                unload(id);
                throw new Exception("Cannot load " + id + ": " + t.getMessage(), t);
            } finally {
                PluginHooks.setCurrentPlugin(previous);
            }
        }
    }

    @Override
    public void unload(String id) {
        synchronized (pythonLock) {
            if (host != null && loaded.containsKey(id)) {
                String previous = PluginHooks.getCurrentPlugin();
                PluginHooks.setCurrentPlugin(id);
                try {
                    host.callAttr("unload_plugin", id);
                } catch (Throwable t) {
                    log(id, "Unload failed: " + t);
                } finally {
                    PluginHooks.setCurrentPlugin(previous);
                }
            }
            loaded.remove(id);
            errors.remove(id);
            PluginHooks.removePlugin(id);
            PluginMenus.removePlugin(id);
        }
    }

    @Override
    public void dispatchAppEvent(String event) {
        if (loaded.isEmpty()) return;
        background.execute(() -> {
            for (String id : loaded.keySet()) invoke(id, "on_app_event", event);
        });
    }

    public boolean isLoaded(String id) { return loaded.containsKey(id); }

    public void requirePermission(String id, String permission) {
        PluginManifest m = loaded.get(id);
        if (m == null || !m.hasPermission(permission)) {
            throw new SecurityException("Plugin " + id + " needs permission " + permission);
        }
    }

    private PyObject invoke(String id, String method, Object... arguments) {
        if (!loaded.containsKey(id)) return null;
        {
            // Chaquopy owns the GIL. Do not hold the lifecycle lock across plugin I/O:
            // background work must not block unrelated hooks or the UI thread.
            if (!loaded.containsKey(id) || host == null) return null;
            String previous = PluginHooks.getCurrentPlugin();
            PluginHooks.setCurrentPlugin(id);
            long started = SystemClock.elapsedRealtime();
            try {
                return host.callAttr("invoke", id, method, arguments);
            } catch (Throwable t) {
                reportError(id, t.toString());
                return null;
            } finally {
                long elapsed = SystemClock.elapsedRealtime() - started;
                if (elapsed > 200) log(id, "Slow callback " + method + ": " + elapsed + " ms");
                PluginHooks.setCurrentPlugin(previous);
            }
        }
    }

    public void reportError(String id, String message) {
        log(id, message);
        PluginManifest generation = loaded.get(id);
        if (generation == null) return;
        ArrayDeque<Long> window = errors.computeIfAbsent(id, ignored -> new ArrayDeque<>());
        boolean disable;
        synchronized (window) {
            long now = SystemClock.elapsedRealtime();
            while (!window.isEmpty() && now - window.peekFirst() > 60000) window.removeFirst();
            window.addLast(now);
            disable = window.size() >= 5;
        }
        if (disable) {
          synchronized (pythonLock) {
            if (!loaded.remove(id, generation)) return;
            // Stop dispatching immediately; the manager persists the disabled state off this thread.
            PluginHooks.removePlugin(id);
            PluginMenus.removePlugin(id);
            background.execute(() -> {
                synchronized (pythonLock) {
                    // An explicit re-enable/update may have loaded a fresh generation already.
                    if (loaded.containsKey(id)) return;
                    if (host != null) {
                        try { host.callAttr("unload_plugin", id); } catch (Throwable t) { FileLog.e(t); }
                    }
                }
                FailureListener listener = failureListener;
                if (listener != null) listener.onPluginFailed(id, "Disabled after repeated plugin errors");
            });
          }
        }
    }

    public synchronized void log(String id, String message) {
        if (!PluginManifest.isValidId(id)) return;
        try {
            File dir = getDataDirectory(id);
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(new File(dir, "log.txt"), true)) {
                out.write((System.currentTimeMillis() + " " + message + "\n").getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static PluginHooks.Result result(String id, String method, Object... args) {
        PyObject value = INSTANCE.invoke(id, method, args);
        if (value == null) return null;
        try { return value.toJava(PluginHooks.Result.class); }
        catch (Throwable t) { INSTANCE.reportError(id, "Invalid hook result: " + t); return null; }
    }

    public static PluginHooks.Result callPreRequest(String id, String name, int account, TLObject request) {
        return result(id, "pre_request_hook", name, account, request);
    }
    public static PluginHooks.Result callPostRequest(String id, String name, int account, TLObject response, TLRPC.TL_error error) {
        return result(id, "post_request_hook", name, account, response, error);
    }
    public static PluginHooks.Result callUpdate(String id, String name, int account, TLRPC.Update update) {
        return result(id, "on_update_hook", name, account, update);
    }
    public static PluginHooks.Result callUpdates(String id, String name, int account, TLRPC.Updates updates) {
        return result(id, "on_updates_hook", name, account, updates);
    }
    public static PluginHooks.Result callSendMessage(String id, int account, SendMessagesHelper.SendMessageParams params) {
        return result(id, "on_send_message_hook", account, params);
    }
    public static void callMessageEvent(String id, int kind, int account, MessageObject message) {
        INSTANCE.invoke(id, kind == PluginHooks.MSG_EDITED ? "on_message_edited" : "on_new_message", account, message);
    }
    public static void callMessagesDeleted(String id, int account, long dialogId, ArrayList<Integer> ids) {
        INSTANCE.invoke(id, "on_messages_deleted", account, dialogId, ids);
    }
    public static boolean callMenuCondition(String id, String key, int account, long dialogId, MessageObject message, BaseFragment fragment) {
        PyObject value = INSTANCE.invoke(id, "__menu_condition__", key, account, dialogId, message, fragment);
        return value != null && value.toBoolean();
    }
    public static void callMenuClick(String id, String key, int account, long dialogId, MessageObject message, BaseFragment fragment) {
        INSTANCE.invoke(id, "__menu_click__", key, account, dialogId, message, fragment);
    }
    public String callCreateSettings(String id) {
        PyObject value = invoke(id, "__create_settings__");
        return value == null ? "[]" : value.toString();
    }
    public void callSettingsEvent(String id, String key, String event, String valueJson) {
        invoke(id, "__settings_event__", key, event, valueJson);
    }

    /** Python passes generation-bound wrappers, so queued callbacks from unloaded plugins are ignored. */
    private void runCallback(String id, PyObject callback, Object... args) {
        if (!loaded.containsKey(id)) return;
        {
            if (host == null || !loaded.containsKey(id)) return;
            String previous = PluginHooks.getCurrentPlugin();
            PluginHooks.setCurrentPlugin(id);
            try { host.callAttr("run_callback", id, callback, args); }
            catch (Throwable t) { reportError(id, t.toString()); }
            finally { PluginHooks.setCurrentPlugin(previous); }
        }
    }

    public void runOnUi(String id, PyObject callback, long delayMs) {
        AndroidUtilities.runOnUIThread(() -> runCallback(id, callback), Math.max(0, delayMs));
    }
    public void runInBackground(String id, PyObject callback) {
        background.execute(() -> runCallback(id, callback));
    }
    public int selectedAccount() { return UserConfig.selectedAccount; }
    public String appVersion() { return BuildConfig.ZEPHYR_VERSION; }
    public Context context() { return ApplicationLoader.applicationContext; }

    public void copyToClipboard(String id, String text) {
        if (!loaded.containsKey(id)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (!loaded.containsKey(id)) return;
            ClipboardManager clipboard = (ClipboardManager) context().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("ZephyrGram", text));
        });
    }
    public void bulletin(String id, String text) {
        if (!loaded.containsKey(id)) return;
        AndroidUtilities.runOnUIThread(() -> {
            if (loaded.containsKey(id)) Toast.makeText(context(), text, Toast.LENGTH_SHORT).show();
        });
    }
    public int sendRequest(String id, TLObject request, PyObject callback, int account) {
        requirePermission(id, "network.requests");
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Invalid account");
        PluginHooks.skipHooks(request);
        return ConnectionsManager.getInstance(account).sendRequest(request,
                (response, error) -> { if (callback != null) runCallback(id, callback, response, error); });
    }
    public void sendText(String id, long dialogId, String text, int account) {
        requirePermission(id, "messages.send");
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Invalid account");
        AndroidUtilities.runOnUIThread(() -> {
            if (!loaded.containsKey(id)) return;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(text, dialogId);
            PluginHooks.skipHooks(params);
            SendMessagesHelper.getInstance(account).sendMessage(params);
        });
    }
}
