package org.telegram.messenger.zephyr.plugins;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.KeepAliveJob;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.RequestDelegateTimestamp;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static entry points called from patched Telegram classes. Every entry point is a cheap no-op
 * (one volatile read) while no plugin registered the corresponding hook.
 *
 * Hook names are TL class names: package prefix and "TLRPC$" are stripped, '$' becomes '.'
 * (TLRPC$TL_messages_sendMessage -> "TL_messages_sendMessage",
 * TL_update$TL_updateNewMessage -> "TL_update.TL_updateNewMessage"). A hook registered under the
 * plain class name ("TL_updateNewMessage") matches too.
 */
public final class PluginHooks {

    private PluginHooks() {
    }

    public static final int DEFAULT = 0;
    public static final int CANCEL = 1;
    public static final int MODIFY = 2;
    public static final int MODIFY_FINAL = 3;

    public static final int MSG_NEW = 0;
    public static final int MSG_EDITED = 1;
    public static final int MSG_DELETED = 2;

    public static volatile boolean hasRequestHooks;
    /** Gate for onUpdates / onUpdateArray (update hooks, container hooks and message event hooks). */
    public static volatile boolean hasUpdateHooks;
    public static volatile boolean hasSendHooks;
    public static volatile boolean hasNewMessageHooks;

    /** Value filled by Python (zephyr_host) and read here. */
    public static final class Result {
        public int strategy;
        public TLObject request;
        public TLObject response;
        public TLRPC.TL_error error;
        public TLRPC.Update update;
        public TLRPC.Updates updates;
        public SendMessagesHelper.SendMessageParams params;
    }

    /** Outcome of onRequest. Call site: if (call.cancel) { call.deliverCancel(); return; } use call.request / call.onComplete. */
    public static final class RequestCall {
        public final int account;
        public boolean cancel;
        public TLObject request;
        public RequestDelegate onComplete;
        public RequestDelegateTimestamp onCompleteTimestamp;
        public TLObject cancelResponse;
        public TLRPC.TL_error cancelError;

        RequestCall(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp ts) {
            this.account = account;
            this.request = request;
            this.onComplete = onComplete;
            this.onCompleteTimestamp = ts;
        }

        /** Answers the caller as if the server had replied (supplied response or error -1000 PLUGIN_CANCELLED). */
        public void deliverCancel() {
            TLObject resp = cancelResponse;
            TLRPC.TL_error err = cancelError;
            if (resp == null && err == null) {
                err = cancelledError();
            }
            try {
                if (onComplete != null) {
                    onComplete.run(resp, err);
                }
                else if (onCompleteTimestamp != null) {
                    onCompleteTimestamp.run(resp, err, 0);
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
    }

    public static TLRPC.TL_error cancelledError() {
        TLRPC.TL_error e = new TLRPC.TL_error();
        e.code = -1000;
        e.text = "PLUGIN_CANCELLED";
        return e;
    }

    // ---------------------------------------------------------------- thread-local plugin

    private static final ThreadLocal<String> currentPlugin = new ThreadLocal<>();

    /** Id of the plugin whose Python code is currently running on this thread, or null. */
    public static String getCurrentPlugin() {
        return currentPlugin.get();
    }

    public static void setCurrentPlugin(String id) {
        currentPlugin.set(id);
    }

    // ---------------------------------------------------------------- registry

    static final class Hook {
        final String pluginId;
        final String pattern;
        final boolean substring;
        final int priority;
        final boolean hasPre;
        final boolean hasPost;
        final long seq;

        Hook(String pluginId, String pattern, boolean substring, int priority, boolean hasPre, boolean hasPost, long seq) {
            this.pluginId = pluginId;
            this.pattern = pattern;
            this.substring = substring;
            this.priority = priority;
            this.hasPre = hasPre;
            this.hasPost = hasPost;
            this.seq = seq;
        }
    }

    private static final Hook[] NO_HOOKS = new Hook[0];
    private static final Comparator<Hook> ORDER = (a, b) -> {
        if (a.priority != b.priority) return a.priority > b.priority ? -1 : 1;
        return Long.compare(a.seq, b.seq);
    };

    private static final class Snapshot {
        final HashMap<String, Hook[]> exact = new HashMap<>();
        Hook[] substr = NO_HOOKS;
        final ConcurrentHashMap<String, Hook[]> cache = new ConcurrentHashMap<>();
    }

    private static final class Registry {
        private final ArrayList<Hook> all = new ArrayList<>();
        private volatile Snapshot snap = new Snapshot();

        synchronized void add(Hook h) {
            all.removeIf(old -> old.pluginId.equals(h.pluginId) && old.pattern.equals(h.pattern) && old.substring == h.substring);
            all.add(h);
            rebuild();
        }

        synchronized void removePlugin(String pluginId) {
            if (all.removeIf(h -> h.pluginId.equals(pluginId))) rebuild();
        }

        synchronized void remove(String pluginId, String pattern) {
            if (all.removeIf(h -> h.pluginId.equals(pluginId) && h.pattern.equals(pattern))) rebuild();
        }

        boolean isEmpty() {
            Snapshot snapshot = snap;
            return snapshot.exact.isEmpty() && snapshot.substr.length == 0;
        }

        private void rebuild() {
            Snapshot s = new Snapshot();
            ArrayList<Hook> sub = new ArrayList<>();
            HashMap<String, ArrayList<Hook>> ex = new HashMap<>();
            for (Hook h : all) {
                if (h.substring) {
                    sub.add(h);
                } else {
                    ArrayList<Hook> l = ex.get(h.pattern);
                    if (l == null) ex.put(h.pattern, l = new ArrayList<>());
                    l.add(h);
                }
            }
            for (Map.Entry<String, ArrayList<Hook>> e : ex.entrySet()) {
                s.exact.put(e.getKey(), e.getValue().toArray(new Hook[0]));
            }
            s.substr = sub.toArray(new Hook[0]);
            snap = s;
        }

        Hook[] match(String full, String simple) {
            Snapshot s = snap;
            Hook[] cached = s.cache.get(full);
            if (cached != null) return cached;
            ArrayList<Hook> out = null;
            Hook[] a = s.exact.get(full);
            Hook[] b = simple.equals(full) ? null : s.exact.get(simple);
            if (a != null || b != null || s.substr.length > 0) {
                out = new ArrayList<>();
                if (a != null) out.addAll(Arrays.asList(a));
                if (b != null) out.addAll(Arrays.asList(b));
                for (Hook h : s.substr) {
                    if (full.contains(h.pattern)) out.add(h);
                }
            }
            Hook[] res;
            if (out == null || out.isEmpty()) {
                res = NO_HOOKS;
            } else {
                Collections.sort(out, ORDER);
                res = out.toArray(new Hook[0]);
            }
            if (s.cache.size() < 4096) s.cache.put(full, res);
            return res;
        }
    }

    private static final Registry requestHooks = new Registry();
    private static final Registry updateHooks = new Registry();
    private static final Registry updatesHooks = new Registry();

    private static final class SimpleHook {
        final String pluginId;
        final int priority;
        final long seq;

        SimpleHook(String pluginId, int priority, long seq) {
            this.pluginId = pluginId;
            this.priority = priority;
            this.seq = seq;
        }
    }

    private static final Object simpleLock = new Object();
    private static volatile SimpleHook[] sendList = new SimpleHook[0];
    private static volatile SimpleHook[] newMsgList = new SimpleHook[0];
    private static volatile SimpleHook[] editedMsgList = new SimpleHook[0];
    private static volatile SimpleHook[] deletedMsgList = new SimpleHook[0];
    private static long seqCounter;

    private static long nextSeq() {
        synchronized (simpleLock) {
            return ++seqCounter;
        }
    }

    private static final ConcurrentHashMap<Class<?>, String[]> nameCache = new ConcurrentHashMap<>();

    /** @return {fullName, simpleName} */
    public static String[] namesOf(Class<?> c) {
        String[] n = nameCache.get(c);
        if (n == null) {
            String full = c.getName();
            int dot = full.lastIndexOf('.');
            if (dot >= 0) full = full.substring(dot + 1);
            if (full.startsWith("TLRPC$")) full = full.substring(6);
            full = full.replace('$', '.');
            int d2 = full.lastIndexOf('.');
            n = new String[]{full, d2 >= 0 ? full.substring(d2 + 1) : full};
            nameCache.put(c, n);
        }
        return n;
    }

    // ---------------------------------------------------------------- registration (called from Python)

    public static synchronized void addRequestHook(String pluginId, String name, boolean substring, int priority, boolean hasPre, boolean hasPost) {
        requestHooks.add(new Hook(pluginId, name, substring, priority, hasPre, hasPost, nextSeq()));
        hasRequestHooks = true;
    }

    public static synchronized void removeRequestHook(String pluginId, String name) {
        requestHooks.remove(pluginId, name);
        hasRequestHooks = !requestHooks.isEmpty();
    }

    public static synchronized void addUpdateHook(String pluginId, String name, boolean substring, int priority) {
        updateHooks.add(new Hook(pluginId, name, substring, priority, true, false, nextSeq()));
        refreshUpdateFlags();
    }

    public static synchronized void addUpdatesHook(String pluginId, String name, boolean substring, int priority) {
        updatesHooks.add(new Hook(pluginId, name, substring, priority, true, false, nextSeq()));
        refreshUpdateFlags();
    }

    public static synchronized void addSendMessageHook(String pluginId, int priority) {
        sendList = addSimple(sendList, pluginId, priority);
        hasSendHooks = true;
    }

    /** kind: MSG_NEW, MSG_EDITED, MSG_DELETED */
    public static synchronized void addMessageHook(String pluginId, int kind, int priority) {
        switch (kind) {
            case MSG_NEW:
                newMsgList = addSimple(newMsgList, pluginId, priority);
                break;
            case MSG_EDITED:
                editedMsgList = addSimple(editedMsgList, pluginId, priority);
                break;
            default:
                deletedMsgList = addSimple(deletedMsgList, pluginId, priority);
                break;
        }
        refreshUpdateFlags();
    }

    private static SimpleHook[] addSimple(SimpleHook[] list, String pluginId, int priority) {
        synchronized (simpleLock) {
            for (SimpleHook h : list) {
                if (h.pluginId.equals(pluginId)) return list;
            }
            SimpleHook[] n = Arrays.copyOf(list, list.length + 1);
            n[list.length] = new SimpleHook(pluginId, priority, ++seqCounter);
            Arrays.sort(n, (a, b) -> a.priority != b.priority ? (a.priority > b.priority ? -1 : 1) : Long.compare(a.seq, b.seq));
            return n;
        }
    }

    private static SimpleHook[] removeSimple(SimpleHook[] list, String pluginId) {
        synchronized (simpleLock) {
            ArrayList<SimpleHook> n = new ArrayList<>();
            for (SimpleHook h : list) {
                if (!h.pluginId.equals(pluginId)) n.add(h);
            }
            return n.size() == list.length ? list : n.toArray(new SimpleHook[0]);
        }
    }

    /** Removes everything a plugin registered (called on unload / auto-disable). */
    public static synchronized void removePlugin(String pluginId) {
        requestHooks.removePlugin(pluginId);
        updateHooks.removePlugin(pluginId);
        updatesHooks.removePlugin(pluginId);
        sendList = removeSimple(sendList, pluginId);
        newMsgList = removeSimple(newMsgList, pluginId);
        editedMsgList = removeSimple(editedMsgList, pluginId);
        deletedMsgList = removeSimple(deletedMsgList, pluginId);
        hasRequestHooks = !requestHooks.isEmpty();
        hasSendHooks = sendList.length > 0;
        refreshUpdateFlags();
    }

    private static void refreshUpdateFlags() {
        hasNewMessageHooks = newMsgList.length > 0;
        hasUpdateHooks = !updateHooks.isEmpty() || !updatesHooks.isEmpty() || newMsgList.length > 0
                || editedMsgList.length > 0 || deletedMsgList.length > 0;
    }

    // ---------------------------------------------------------------- skip sets (plugin-originated work)

    private static final Map<Object, Boolean> skip = Collections.synchronizedMap(new WeakHashMap<>());

    /** Marks a request / SendMessageParams so that hooks ignore it once (used for plugin-sent traffic). */
    public static void skipHooks(Object o) {
        if (o != null) skip.put(o, Boolean.TRUE);
    }

    private static boolean consumeSkip(Object o) {
        return !skip.isEmpty() && skip.remove(o) != null;
    }

    // ---------------------------------------------------------------- requests

    public static RequestCall onRequest(int account, TLObject request, RequestDelegate onComplete) {
        return onRequest(account, request, onComplete, null);
    }

    public static RequestCall onRequest(int account, TLObject request, RequestDelegate onComplete, RequestDelegateTimestamp onCompleteTimestamp) {
        RequestCall call = new RequestCall(account, request, onComplete, onCompleteTimestamp);
        if (!hasRequestHooks || request == null || consumeSkip(request)) {
            return call;
        }
        try {
            String[] names = namesOf(request.getClass());
            final String name = names[0];
            final Hook[] hooks = requestHooks.match(name, names[1]);
            if (hooks.length == 0) return call;

            for (Hook h : hooks) {
                if (!h.hasPre) continue;
                Result r = PluginRuntime.callPreRequest(h.pluginId, name, account, call.request);
                if (r == null || r.strategy == DEFAULT) continue;
                if (r.strategy == CANCEL) {
                    call.cancel = true;
                    call.cancelResponse = r.response;
                    call.cancelError = r.error;
                    return call;
                }
                if (r.request != null) call.request = r.request;
                if (r.strategy == MODIFY_FINAL) break;
            }

            boolean anyPost = false;
            for (Hook h : hooks) {
                if (h.hasPost) {
                    anyPost = true;
                    break;
                }
            }
            if (anyPost) {
                final RequestDelegate origin = call.onComplete;
                final RequestDelegateTimestamp originTs = call.onCompleteTimestamp;
                if (origin != null) {
                    call.onComplete = (response, error) -> {
                        TLObject[] resp = {response};
                        TLRPC.TL_error[] err = {error};
                        runPost(hooks, name, account, resp, err);
                        origin.run(resp[0], err[0]);
                    };
                }
                if (origin == null && originTs == null) {
                    // Preserve ConnectionsManager's no-delegate Updates handling after post hooks.
                    call.onComplete = (response, error) -> {
                        TLObject[] resp = {response};
                        TLRPC.TL_error[] err = {error};
                        runPost(hooks, name, account, resp, err);
                        if (err[0] == null && resp[0] instanceof TLRPC.Updates) {
                            KeepAliveJob.finishJob();
                            AccountInstance.getInstance(account).getMessagesController().processUpdates((TLRPC.Updates) resp[0], false);
                        }
                    };
                }
                if (originTs != null) {
                    call.onCompleteTimestamp = (response, error, time) -> {
                        TLObject[] resp = {response};
                        TLRPC.TL_error[] err = {error};
                        runPost(hooks, name, account, resp, err);
                        originTs.run(resp[0], err[0], time);
                    };
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return call;
    }

    private static void runPost(Hook[] hooks, String name, int account, TLObject[] resp, TLRPC.TL_error[] err) {
        try {
            for (Hook h : hooks) {
                if (!h.hasPost) continue;
                Result r = PluginRuntime.callPostRequest(h.pluginId, name, account, resp[0], err[0]);
                if (r == null || r.strategy == DEFAULT) continue;
                if (r.strategy == CANCEL) {
                    resp[0] = r.response;
                    err[0] = r.error != null ? r.error : (r.response == null ? cancelledError() : null);
                    return;
                }
                resp[0] = r.response;
                err[0] = r.error;
                if (r.strategy == MODIFY_FINAL) return;
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    // ---------------------------------------------------------------- updates

    // Retried Telegram queue objects must not run mutating Python hooks or message events twice.
    // Weak keys retain neither update containers nor accepted message payloads for the app lifetime.
    private static final Map<TLRPC.Updates, Set<Integer>> seenContainers = new WeakHashMap<>();
    private static final Map<TLRPC.Update, Set<Integer>> acceptedUpdates = new WeakHashMap<>();
    private static final Map<TLRPC.Message, Set<Integer>> acceptedMessages = new WeakHashMap<>();
    private static final Map<TLRPC.Update, Map<Integer, UpdateOutcome>> transformedUpdates = new WeakHashMap<>();

    private static final class UpdateOutcome {
        final boolean cancelled;
        // null means unchanged, avoiding a strong value -> weak key reference cycle.
        final TLRPC.Update replacement;
        UpdateOutcome(TLRPC.Update original, TLRPC.Update result) {
            cancelled = result == null;
            replacement = result == original ? null : result;
        }
        TLRPC.Update value(TLRPC.Update original) { return cancelled ? null : replacement == null ? original : replacement; }
    }

    private static <T> boolean firstSeen(Map<T, Set<Integer>> seen, T object, int scope) {
        synchronized (seen) { return seen.computeIfAbsent(object, key -> new HashSet<>()).add(scope); }
    }

    /** Call only after MessagesController accepted the filtered updates. */
    public static void onAcceptedUpdateArray(int account, ArrayList<TLRPC.Update> updates) {
        if (!hasUpdateHooks || updates == null) return;
        for (TLRPC.Update update : updates) {
            if (update != null && firstSeen(acceptedUpdates, update, account)) dispatchMessageEvents(account, update);
        }
    }

    /**
     * @return the (possibly replaced) container, or null if a plugin cancelled it.
     */
    public static TLRPC.Updates onUpdates(int account, TLRPC.Updates updates) {
        if (!hasUpdateHooks || updates == null || consumeSkip(updates)) return updates;
        if (!firstSeen(seenContainers, updates, account)) return updates;
        try {
            String[] names = namesOf(updates.getClass());
            Hook[] hooks = updatesHooks.match(names[0], names[1]);
            TLRPC.Updates cur = updates;
            for (Hook h : hooks) {
                Result r = PluginRuntime.callUpdates(h.pluginId, names[0], account, cur);
                if (r == null || r.strategy == DEFAULT) continue;
                if (r.strategy == CANCEL) {
                    // Dropping a whole container would also drop its pts/seq advancement and
                    // repeatedly request the same difference. Container cancellation is unsupported.
                    FileLog.e("Plugin container cancellation ignored to preserve Telegram update counters");
                    continue;
                }
                if (r.updates != null) cur = r.updates;
                if (r.strategy == MODIFY_FINAL) break;
            }
            if (cur != updates) firstSeen(seenContainers, cur, account);
            return cur;
        } catch (Throwable t) {
            FileLog.e(t);
            return updates;
        }
    }

    /**
     * Applies update hooks. Accepted message events are dispatched separately. The input list is never mutated; the same
     * instance is returned when nothing changed.
     */
    public static ArrayList<TLRPC.Update> onUpdateArray(int account, ArrayList<TLRPC.Update> updates) {
        if (!hasUpdateHooks || updates == null || updates.isEmpty()) return updates;
        ArrayList<TLRPC.Update> out = null;
        try {
            final boolean anyUpdateHooks = !updateHooks.isEmpty();
            for (int i = 0, n = updates.size(); i < n; i++) {
                TLRPC.Update orig = updates.get(i);
                TLRPC.Update cur = orig;
                if (anyUpdateHooks && orig != null) {
                    synchronized (transformedUpdates) {
                        Map<Integer, UpdateOutcome> byAccount = transformedUpdates.computeIfAbsent(orig, key -> new HashMap<>());
                        UpdateOutcome previous = byAccount.get(account);
                        if (previous != null) cur = previous.value(orig);
                        else {
                            // Mark before calling Python so reentrant processing cannot recurse forever.
                            byAccount.put(account, new UpdateOutcome(orig, orig));
                            String[] names = namesOf(orig.getClass());
                            Hook[] hooks = updateHooks.match(names[0], names[1]);
                            for (Hook h : hooks) {
                                Result r = PluginRuntime.callUpdate(h.pluginId, names[0], account, cur);
                                if (r == null || r.strategy == DEFAULT) continue;
                                if (r.strategy == CANCEL) { cur = null; break; }
                                if (r.update != null) cur = r.update;
                                if (r.strategy == MODIFY_FINAL) break;
                            }
                            byAccount.put(account, new UpdateOutcome(orig, cur));
                        }
                    }
                }
                if (cur != orig && out == null) {
                    out = new ArrayList<>(n);
                    for (int j = 0; j < i; j++) out.add(updates.get(j));
                }
                if (cur != null) {
                    if (out != null) out.add(cur);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return updates;
        }
        return out != null ? out : updates;
    }

    private static void dispatchMessageEvents(int account, TLRPC.Update u) {
        SimpleHook[] list;
        int kind;
        TLRPC.Message msg = null;
        if (u instanceof TL_update.TL_updateNewMessage) {
            list = newMsgList;
            kind = MSG_NEW;
            msg = ((TL_update.TL_updateNewMessage) u).message;
        } else if (u instanceof TL_update.TL_updateNewChannelMessage) {
            list = newMsgList;
            kind = MSG_NEW;
            msg = ((TL_update.TL_updateNewChannelMessage) u).message;
        } else if (u instanceof TL_update.TL_updateEditMessage) {
            list = editedMsgList;
            kind = MSG_EDITED;
            msg = ((TL_update.TL_updateEditMessage) u).message;
        } else if (u instanceof TL_update.TL_updateEditChannelMessage) {
            list = editedMsgList;
            kind = MSG_EDITED;
            msg = ((TL_update.TL_updateEditChannelMessage) u).message;
        } else if (u instanceof TL_update.TL_updateDeleteMessages) {
            list = deletedMsgList;
            kind = MSG_DELETED;
            if (list.length == 0) return;
            ArrayList<Integer> ids = ((TL_update.TL_updateDeleteMessages) u).messages;
            for (SimpleHook h : list) {
                PluginRuntime.callMessagesDeleted(h.pluginId, account, 0L, ids);
            }
            return;
        } else if (u instanceof TL_update.TL_updateDeleteChannelMessages) {
            list = deletedMsgList;
            if (list.length == 0) return;
            TL_update.TL_updateDeleteChannelMessages d = (TL_update.TL_updateDeleteChannelMessages) u;
            for (SimpleHook h : list) {
                PluginRuntime.callMessagesDeleted(h.pluginId, account, -d.channel_id, d.messages);
            }
            return;
        } else {
            return;
        }
        if (list.length == 0 || msg == null) return;
        if (!firstSeen(acceptedMessages, msg, account * 4 + kind)) return;
        MessageObject mo;
        try {
            mo = new MessageObject(account, msg, false, false);
        } catch (Throwable t) {
            FileLog.e(t);
            return;
        }
        for (SimpleHook h : list) {
            PluginRuntime.callMessageEvent(h.pluginId, kind, account, mo);
        }
    }

    // ---------------------------------------------------------------- send

    /** @return params to send (possibly modified / replaced) or null if a plugin cancelled sending. */
    public static SendMessagesHelper.SendMessageParams onSendMessage(int account, SendMessagesHelper.SendMessageParams params) {
        if (!hasSendHooks || params == null || consumeSkip(params)) return params;
        try {
            SendMessagesHelper.SendMessageParams cur = params;
            for (SimpleHook h : sendList) {
                Result r = PluginRuntime.callSendMessage(h.pluginId, account, cur);
                if (r == null || r.strategy == DEFAULT) continue;
                if (r.strategy == CANCEL) return null;
                if (r.params != null) cur = r.params;
                if (r.strategy == MODIFY_FINAL) break;
            }
            return cur;
        } catch (Throwable t) {
            FileLog.e(t);
            return params;
        }
    }

    /** Dispatches a message obtained by other means (e.g. a locally generated incoming message). */
    public static void onNewMessage(int account, TLRPC.Message message) {
        if (!hasNewMessageHooks || message == null) return;
        if (!firstSeen(acceptedMessages, message, account * 4 + MSG_NEW)) return;
        try {
            MessageObject mo = new MessageObject(account, message, false, false);
            for (SimpleHook h : newMsgList) {
                PluginRuntime.callMessageEvent(h.pluginId, MSG_NEW, account, mo);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    static List<String> debugDump() {
        ArrayList<String> l = new ArrayList<>();
        l.add("requestHooks=" + hasRequestHooks + " updateHooks=" + hasUpdateHooks + " sendHooks=" + hasSendHooks);
        return l;
    }
}
