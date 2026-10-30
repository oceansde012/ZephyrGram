package org.telegram.messenger.zephyr.plugins;

import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.zephyr.ZephyrAiClient;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Chat helpers behind zephyr.client (ai_complete, get_recent_messages, get_dialog_info, reply/draft).
 * Permissions are checked by the Python layer and re-checked here. Results are JSON strings.
 * Blocking methods must be called from a worker thread, never from the UI thread.
 */
public final class PluginChatApi {
    private static final Semaphore AI_SLOTS = new Semaphore(3);
    private static final int MAX_LIMIT = 100;

    private PluginChatApi() { }

    private static void checkAccount(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IllegalArgumentException("Invalid account");
    }

    private static void notMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Blocking API must not be called on the UI thread; use run_in_bg");
        }
    }

    // ------------------------------------------------------------------ AI

    /** messagesJson: [{"role","content"}]. Returns the assistant text; throws on failure. */
    public static String aiComplete(String id, String messagesJson, String system, String model, double temperature, int timeoutSec) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "ai.complete");
        notMainThread();
        JSONArray in = new JSONArray(messagesJson);
        JSONArray out = new JSONArray();
        if (system != null && !system.isEmpty()) {
            out.put(new JSONObject().put("role", "system").put("content", system));
        }
        int total = 0;
        for (int i = 0; i < in.length(); i++) {
            JSONObject m = in.getJSONObject(i);
            String content = m.optString("content", "");
            total += content.length();
            out.put(new JSONObject().put("role", m.optString("role", "user")).put("content", content));
        }
        if (total > 200000) throw new IllegalArgumentException("Messages are larger than 200000 characters");
        int timeout = Math.max(1, Math.min(300, timeoutSec));
        if (!AI_SLOTS.tryAcquire(timeout, TimeUnit.SECONDS)) {
            throw new java.io.IOException("Too many concurrent AI requests");
        }
        try {
            return ZephyrAiClient.completeBlocking(out, model, temperature, timeout);
        } finally {
            AI_SLOTS.release();
        }
    }

    // ------------------------------------------------------------------ messages

    private static long fromId(TLRPC.Message m, long dialogId, int account) {
        if (m.from_id != null) return DialogObject.getPeerDialogId(m.from_id);
        if (m.out) return UserConfig.getInstance(account).getClientUserId();
        return dialogId;
    }

    private static JSONObject messageJson(int account, TLRPC.Message m, long dialogId) throws Exception {
        long from = fromId(m, dialogId, account);
        TLRPC.User u = from > 0 ? MessagesController.getInstance(account).getUser(from) : null;
        JSONObject o = new JSONObject();
        o.put("id", m.id);
        o.put("dialog_id", dialogId);
        o.put("from_id", from);
        o.put("out", m.out);
        o.put("date", m.date);
        o.put("text", m.message == null ? "" : m.message);
        o.put("is_bot", u != null && u.bot);
        o.put("is_secret", DialogObject.isEncryptedDialog(dialogId));
        o.put("is_service", m instanceof TLRPC.TL_messageService);
        o.put("reply_to", m.reply_to != null && m.reply_to.reply_to_msg_id != 0 ? m.reply_to.reply_to_msg_id : JSONObject.NULL);
        o.put("has_media", m.media != null && !(m.media instanceof TLRPC.TL_messageMediaEmpty));
        return o;
    }

    /** Used by zephyr.client.message_to_dict. */
    public static String messageToJson(MessageObject mo) throws Exception {
        return messageJson(mo.currentAccount, mo.messageOwner, mo.getDialogId()).toString();
    }

    private static ArrayList<TLRPC.Message> loadFromStorage(int account, long dialogId, int limit, int mid) throws Exception {
        notMainThread();
        final MessagesStorage storage = MessagesStorage.getInstance(account);
        final ArrayList<TLRPC.Message> result = new ArrayList<>();
        final CountDownLatch latch = new CountDownLatch(1);
        storage.getStorageQueue().postRunnable(() -> {
            SQLiteCursor cursor = null;
            try {
                String sql = mid != 0
                        ? String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d AND mid = %d", dialogId, mid)
                        : String.format(Locale.US, "SELECT data FROM messages_v2 WHERE uid = %d ORDER BY date DESC, mid DESC LIMIT %d", dialogId, limit);
                cursor = storage.getDatabase().queryFinalized(sql);
                while (cursor.next()) {
                    NativeByteBuffer data = cursor.byteBufferValue(0);
                    if (data == null) continue;
                    try {
                        TLRPC.Message message = TLRPC.Message.TLdeserialize(data, data.readInt32(false), false);
                        if (message != null) {
                            message.readAttachPath(data, UserConfig.getInstance(account).clientUserId);
                            message.dialog_id = dialogId;
                            result.add(message);
                        }
                    } finally {
                        data.reuse();
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (cursor != null) cursor.dispose();
                latch.countDown();
            }
        });
        if (!latch.await(10, TimeUnit.SECONDS)) throw new java.io.IOException("Message storage is busy");
        return result;
    }

    /** Newest first, from the local database cache. Never hits the network. */
    public static String getRecentMessages(String id, long dialogId, int limit, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
        checkAccount(account);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        ArrayList<TLRPC.Message> messages = loadFromStorage(account, dialogId, limit, 0);
        JSONArray array = new JSONArray();
        for (TLRPC.Message m : messages) array.put(messageJson(account, m, dialogId));
        return array.toString();
    }

    public static String getDialogInfo(String id, long dialogId, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
        checkAccount(account);
        return dialogInfo(account, dialogId).toString();
    }

    /** Cached info of a dialog; no permission check (also used by the owner's own UI). */
    public static JSONObject dialogInfo(int account, long dialogId) throws Exception {
        MessagesController mc = MessagesController.getInstance(account);
        JSONObject o = new JSONObject();
        o.put("id", dialogId);
        String type;
        String title = "";
        String username = null;
        boolean bot = false;
        if (DialogObject.isEncryptedDialog(dialogId)) {
            type = "secret";
            TLRPC.EncryptedChat ec = mc.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            TLRPC.User user = ec == null ? null : mc.getUser(ec.user_id);
            if (user != null) {
                title = UserObject.getUserName(user);
                username = UserObject.getPublicUsername(user);
                bot = user.bot;
            }
        } else if (DialogObject.isUserDialog(dialogId)) {
            type = "user";
            TLRPC.User user = mc.getUser(dialogId);
            if (user != null) {
                title = UserObject.getUserName(user);
                username = UserObject.getPublicUsername(user);
                bot = user.bot;
            }
        } else {
            TLRPC.Chat chat = mc.getChat(-dialogId);
            type = chat != null && ChatObject.isChannelAndNotMegaGroup(chat) ? "channel" : "group";
            if (chat != null) {
                title = chat.title == null ? "" : chat.title;
                username = ChatObject.getPublicUsername(chat);
            }
        }
        o.put("title", title);
        o.put("type", type);
        o.put("is_bot", bot);
        o.put("username", username == null || username.isEmpty() ? JSONObject.NULL : username);
        return o;
    }

    // ------------------------------------------------------------------ search / resolve

    /** Local cache only: dialogs from the chat list whose title or username contains the query. */
    public static String searchDialogs(String id, String query, int limit, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
        checkAccount(account);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("@")) q = q.substring(1);
        JSONArray array = new JSONArray();
        ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(MessagesController.getInstance(account).getAllDialogs());
        for (TLRPC.Dialog d : dialogs) {
            if (d == null || d.id == 0) continue;
            JSONObject o = dialogInfo(account, d.id);
            String title = o.optString("title", "");
            String username = o.isNull("username") ? "" : o.optString("username", "");
            if (title.isEmpty() && username.isEmpty()) continue;
            if (!q.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(q) && !username.toLowerCase(Locale.ROOT).contains(q)) continue;
            array.put(new JSONObject().put("id", d.id).put("title", title).put("type", o.optString("type"))
                    .put("username", o.isNull("username") ? JSONObject.NULL : username));
            if (array.length() >= limit) break;
        }
        return array.toString();
    }

    /** "@name", "name", "t.me/name", "https://t.me/name/123" -> "name" or null when it is not a public username. */
    public static String normalizeUsername(String input) {
        if (input == null) return null;
        String s = input.trim();
        String lower = s.toLowerCase(Locale.ROOT);
        for (String prefix : new String[]{"https://", "http://"}) {
            if (lower.startsWith(prefix)) { s = s.substring(prefix.length()); lower = s.toLowerCase(Locale.ROOT); }
        }
        for (String host : new String[]{"t.me/", "telegram.me/", "telegram.dog/"}) {
            if (lower.startsWith(host)) { s = s.substring(host.length()); break; }
        }
        if (s.startsWith("@")) s = s.substring(1);
        int cut = s.length();
        for (char c : new char[]{'/', '?', '#', ' '}) {
            int i = s.indexOf(c);
            if (i >= 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut);
        return s.matches("[A-Za-z][A-Za-z0-9_]{3,31}") ? s : null;
    }

    /**
     * Resolves a public username to {id,title,type,is_bot,username}; null if it does not exist.
     * Checks the local cache first, then asks Telegram (contacts.resolveUsername). Blocking, worker thread only.
     * No permission check: callers are the permission-checked API below and the owner's own settings UI.
     */
    public static JSONObject resolveUsernameBlocking(int account, String input) throws Exception {
        notMainThread();
        String name = normalizeUsername(input);
        if (name == null) return null;
        MessagesController mc = MessagesController.getInstance(account);
        org.telegram.tgnet.TLObject cached = mc.getUserOrChat(name);
        if (cached instanceof TLRPC.User) return dialogInfo(account, ((TLRPC.User) cached).id);
        if (cached instanceof TLRPC.Chat) return dialogInfo(account, -((TLRPC.Chat) cached).id);
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = name;
        final org.telegram.tgnet.TLObject[] response = new org.telegram.tgnet.TLObject[1];
        final TLRPC.TL_error[] error = new TLRPC.TL_error[1];
        final CountDownLatch latch = new CountDownLatch(1);
        ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> {
            response[0] = res;
            error[0] = err;
            latch.countDown();
        });
        if (!latch.await(20, TimeUnit.SECONDS)) throw new java.io.IOException("Username lookup timed out");
        if (response[0] instanceof TLRPC.TL_contacts_resolvedPeer) {
            TLRPC.TL_contacts_resolvedPeer peer = (TLRPC.TL_contacts_resolvedPeer) response[0];
            mc.putUsers(peer.users, false);
            mc.putChats(peer.chats, false);
            MessagesStorage.getInstance(account).putUsersAndChats(peer.users, peer.chats, true, true);
            return dialogInfo(account, DialogObject.getPeerDialogId(peer.peer));
        }
        String text = error[0] == null ? "" : String.valueOf(error[0].text);
        if (text.contains("USERNAME_NOT_OCCUPIED") || text.contains("USERNAME_INVALID")) return null;
        throw new java.io.IOException(text.isEmpty() ? "Username lookup failed" : text);
    }

    /** Network use: asks Telegram for unknown usernames. Returns JSON object text or null if not found. */
    public static String resolveUsername(String id, String username, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
        checkAccount(account);
        notMainThread();
        JSONObject o = resolveUsernameBlocking(account, username);
        return o == null ? null : o.toString();
    }

    // ------------------------------------------------------------------ sending

    /** Same path as PluginRuntime.sendText (hooks skipped; ZephyrGhost still applies at the network layer). */
    public static void sendText(String id, long dialogId, String text, int replyTo, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "messages.send");
        checkAccount(account);
        MessageObject reply = null;
        if (replyTo > 0) {
            TLRPC.Message m = null;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                ArrayList<TLRPC.Message> found = loadFromStorage(account, dialogId, 1, replyTo);
                if (!found.isEmpty()) m = found.get(0);
            }
            if (m == null) {
                m = new TLRPC.TL_message();
                m.id = replyTo;
                m.peer_id = MessagesController.getInstance(account).getPeer(dialogId);
                m.dialog_id = dialogId;
            }
            reply = new MessageObject(account, m, false, false);
        }
        final MessageObject replyFinal = reply;
        AndroidUtilities.runOnUIThread(() -> {
            if (!PluginRuntime.INSTANCE.isLoaded(id)) return;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    text, dialogId, replyFinal, null, null, true, null, null, null, true, 0, 0, null, false);
            PluginHooks.skipHooks(params);
            SendMessagesHelper.getInstance(account).sendMessage(params);
        });
    }

    public static void setDraft(String id, long dialogId, String text, int account) {
        PluginRuntime.INSTANCE.requirePermission(id, "messages.send");
        checkAccount(account);
        AndroidUtilities.runOnUIThread(() -> {
            if (!PluginRuntime.INSTANCE.isLoaded(id)) return;
            MediaDataController.getInstance(account).saveDraft(dialogId, 0, text == null ? "" : text, null, null, false, 0L);
        });
    }
}
