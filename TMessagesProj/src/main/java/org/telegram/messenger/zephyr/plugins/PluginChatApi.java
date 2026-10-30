package org.telegram.messenger.zephyr.plugins;

import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
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

import java.io.File;
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
        return aiComplete(id, messagesJson, system, model, temperature, timeoutSec, null);
    }

    /**
     * Same as above; imagesJson is an optional JSON array of base64 JPEG strings attached to the LAST user message
     * (its content becomes an OpenAI content array with text + image_url data URLs).
     */
    public static String aiComplete(String id, String messagesJson, String system, String model, double temperature, int timeoutSec, String imagesJson) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "ai.complete");
        notMainThread();
        JSONArray in = new JSONArray(messagesJson);
        JSONArray images = imagesJson == null || imagesJson.isEmpty() ? null : new JSONArray(imagesJson);
        if (images != null && images.length() > 4) throw new IllegalArgumentException("At most 4 images");
        long imageChars = 0;
        if (images != null) {
            for (int i = 0; i < images.length(); i++) {
                String s = images.optString(i, "");
                if (s.startsWith("data:")) { s = s.substring(s.indexOf(',') + 1); images.put(i, s); }
                imageChars += s.length();
            }
            if (imageChars > 1600000) throw new IllegalArgumentException("Images are larger than 1.6 MB (base64)");
        }
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
        if (images != null && images.length() > 0) {
            int last = -1;
            for (int i = out.length() - 1; i >= 0; i--) {
                if ("user".equals(out.getJSONObject(i).optString("role"))) { last = i; break; }
            }
            if (last >= 0) {
                JSONObject lm = out.getJSONObject(last);
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "text").put("text", lm.optString("content", "")));
                for (int i = 0; i < images.length(); i++) {
                    String b64 = images.optString(i, "");
                    if (b64.isEmpty()) continue;
                    parts.put(new JSONObject().put("type", "image_url")
                            .put("image_url", new JSONObject().put("url", "data:image/jpeg;base64," + b64)));
                }
                lm.put("content", parts);
            }
        }
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

    // ------------------------------------------------------------------ explicit chat signals

    /** Never resolve/fetch a new peer just to send a typing/read signal. */
    private static TLRPC.InputPeer signalPeer(int account, long dialogId) {
        if (dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) return null;
        MessagesController mc = MessagesController.getInstance(account);
        if (dialogId > 0 && dialogId != UserConfig.getInstance(account).getClientUserId()
                && mc.getUser(dialogId) == null) return null;
        if (dialogId < 0 && mc.getChat(-dialogId) == null) return null;
        TLRPC.InputPeer peer = mc.getInputPeer(dialogId);
        return peer instanceof TLRPC.TL_inputPeerEmpty ? null : peer;
    }

    private static boolean ghostBlocksSignal(boolean typing) {
        org.telegram.messenger.zephyr.ZephyrConfig.load();
        return typing ? org.telegram.messenger.zephyr.ZephyrConfig.ghostNoTyping
                : org.telegram.messenger.zephyr.ZephyrConfig.ghostNoRead;
    }

    /** One typing/cancel request; false when suppressed/unsupported, true means queued, not acknowledged. */
    public static boolean setTyping(String id, long dialogId, int account, boolean cancel) {
        PluginRuntime.INSTANCE.requirePermission(id, "network.requests");
        checkAccount(account);
        if (!PluginRuntime.INSTANCE.isLoaded(id) || ghostBlocksSignal(true)) return false;
        TLRPC.InputPeer peer = signalPeer(account, dialogId);
        if (peer == null || peer instanceof TLRPC.TL_inputPeerSelf) return false;
        if (dialogId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
            if (ChatObject.isChannel(chat) && !chat.megagroup) return false;
        }
        TLRPC.TL_messages_setTyping request = new TLRPC.TL_messages_setTyping();
        request.peer = peer;
        request.action = cancel ? new TLRPC.TL_sendMessageCancelAction() : new TLRPC.TL_sendMessageTypingAction();
        // Runtime skips plugin hooks; ConnectionsManager still enforces ZephyrGhost,
        // including a setting change between the check above and the network dispatch.
        return PluginRuntime.INSTANCE.sendRequest(id, request, null, account) > 0;
    }

    /** Explicit receipt only, up to maxId. Never invoked by media/voice fetching. */
    public static boolean markRead(String id, long dialogId, int maxId, int account) {
        PluginRuntime.INSTANCE.requirePermission(id, "network.requests");
        checkAccount(account);
        // Telegram treats zero as "all history". Require a deliberate positive message boundary.
        if (maxId <= 0) throw new IllegalArgumentException("max_id must be a positive message id");
        if (!PluginRuntime.INSTANCE.isLoaded(id) || ghostBlocksSignal(false)) return false;
        TLRPC.InputPeer peer = signalPeer(account, dialogId);
        if (peer == null) return false;
        MessagesController mc = MessagesController.getInstance(account);
        org.telegram.tgnet.TLObject request;
        if (dialogId < 0 && ChatObject.isChannel(mc.getChat(-dialogId))) {
            TLRPC.TL_channels_readHistory channel = new TLRPC.TL_channels_readHistory();
            channel.channel = MessagesController.getInputChannel(peer);
            channel.max_id = maxId;
            request = channel;
        } else {
            TLRPC.TL_messages_readHistory history = new TLRPC.TL_messages_readHistory();
            history.peer = peer;
            history.max_id = maxId;
            request = history;
        }
        PluginHooks.skipHooks(request);
        return ConnectionsManager.getInstance(account).sendRequest(request, (response, error) -> {
            if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {
                TLRPC.TL_messages_affectedMessages result = (TLRPC.TL_messages_affectedMessages) response;
                mc.processNewDifferenceParams(-1, result.pts, -1, result.pts_count);
            }
        }) > 0;
    }

    // ------------------------------------------------------------------ stickers

    /** Snapshot of recent -> favorite -> (optionally) installed-set stickers, taken on the UI thread. */
    private static ArrayList<TLRPC.Document> stickerCandidates(int account, boolean includeSets) throws Exception {
        final MediaDataController mdc = MediaDataController.getInstance(account);
        final ArrayList<TLRPC.Document> out = new ArrayList<>();
        final Runnable collect = () -> {
            try {
                if (mdc.getRecentStickersNoCopy(MediaDataController.TYPE_IMAGE).isEmpty()) {
                    mdc.loadRecents(MediaDataController.TYPE_IMAGE, false, true, false);
                }
                if (mdc.getRecentStickersNoCopy(MediaDataController.TYPE_FAVE).isEmpty()) {
                    mdc.loadRecents(MediaDataController.TYPE_FAVE, false, true, false);
                }
                out.addAll(mdc.getRecentStickersNoCopy(MediaDataController.TYPE_IMAGE));
                out.addAll(mdc.getRecentStickersNoCopy(MediaDataController.TYPE_FAVE));
                if (includeSets) {
                    ArrayList<TLRPC.TL_messages_stickerSet> sets = mdc.getStickerSets(MediaDataController.TYPE_IMAGE);
                    if (sets != null) {
                        for (TLRPC.TL_messages_stickerSet s : sets) {
                            if (s != null && s.documents != null) out.addAll(s.documents);
                        }
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            collect.run();
        } else {
            final CountDownLatch latch = new CountDownLatch(1);
            AndroidUtilities.runOnUIThread(() -> {
                try { collect.run(); } finally { latch.countDown(); }
            });
            if (!latch.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("UI thread is busy");
        }
        return out;
    }

    private static String stickerSetTitle(int account, TLRPC.Document d) {
        if (d.attributes == null) return "";
        for (TLRPC.DocumentAttribute a : d.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeSticker && a.stickerset instanceof TLRPC.TL_inputStickerSetID) {
                TLRPC.TL_messages_stickerSet set = MediaDataController.getInstance(account).getStickerSetById(a.stickerset.id);
                if (set != null && set.set != null && set.set.title != null) return set.set.title;
            }
        }
        return "";
    }

    /**
     * Recent stickers (falling back to favorites), newest first, from the in-memory cache; if the cache has not
     * been loaded yet a background load is started and the result may be empty. JSON array of
     * {id, emoji, set_title, mime, is_animated}.
     */
    public static String getRecentStickers(String id, int limit, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
        checkAccount(account);
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));
        MediaDataController mdc = MediaDataController.getInstance(account);
        ArrayList<TLRPC.Document> all = stickerCandidates(account, false);
        JSONArray array = new JSONArray();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        for (TLRPC.Document d : all) {
            if (d == null || d instanceof TLRPC.TL_documentEmpty || d.id == 0 || !seen.add(d.id)) continue;
            array.put(new JSONObject()
                    .put("id", d.id)
                    .put("emoji", mdc.getEmojiForSticker(d.id))
                    .put("set_title", stickerSetTitle(account, d))
                    .put("mime", d.mime_type == null ? "" : d.mime_type)
                    .put("is_animated", MessageObject.isAnimatedStickerDocument(d, true) || MessageObject.isVideoSticker(d)));
            if (array.length() >= limit) break;
        }
        return array.toString();
    }

    /**
     * Sends a sticker found by document id among recent/favorite/installed stickers. Same marking as send_text
     * (hooks skipped; ZephyrGhost still applies at the network layer). Returns false if the sticker was not found
     * or the dialog is a secret chat. Blocking until lookup finishes: call from a worker thread.
     */
    public static boolean sendSticker(String id, long dialogId, long stickerId, int replyTo, int account) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "messages.send");
        checkAccount(account);
        if (DialogObject.isEncryptedDialog(dialogId)) return false;
        TLRPC.Document found = null;
        for (TLRPC.Document d : stickerCandidates(account, true)) {
            if (d != null && d.id == stickerId && d instanceof TLRPC.TL_document) { found = d; break; }
        }
        if (found == null) return false;
        MessageObject reply = null;
        if (replyTo > 0) {
            TLRPC.Message m = null;
            if (Looper.myLooper() != Looper.getMainLooper()) {
                ArrayList<TLRPC.Message> msgs = loadFromStorage(account, dialogId, 1, replyTo);
                if (!msgs.isEmpty()) m = msgs.get(0);
            }
            if (m == null) {
                m = new TLRPC.TL_message();
                m.id = replyTo;
                m.peer_id = MessagesController.getInstance(account).getPeer(dialogId);
                m.dialog_id = dialogId;
            }
            reply = new MessageObject(account, m, false, false);
        }
        final TLRPC.TL_document doc = (TLRPC.TL_document) found;
        final MessageObject replyFinal = reply;
        AndroidUtilities.runOnUIThread(() -> {
            if (!PluginRuntime.INSTANCE.isLoaded(id)) return;
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    doc, null, null, dialogId, replyFinal, null, null, null, null, null, true, 0, 0, 0, null, null, false);
            PluginHooks.skipHooks(params);
            SendMessagesHelper.getInstance(account).sendMessage(params);
        });
        return true;
    }

    // ------------------------------------------------------------------ media for vision

    private static final int MAX_MEDIA_B64 = 400 * 1024;
    private static final long MAX_VIDEO_FRAME_FILE = 20L * 1024 * 1024;
    private static final long MAX_DECODE_FILE = 25L * 1024 * 1024;

    private static android.graphics.Bitmap.CompressFormat jpegFormat() {
        return android.graphics.Bitmap.CompressFormat.JPEG;
    }

    /** Scales to longest side <= maxSide, flattens alpha onto white, JPEG q~75; shrinks until base64 <= 400 KB. */
    private static String encodeJpeg(android.graphics.Bitmap src, int maxSide) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        int side = maxSide;
        int[] qualities = {75, 60, 45};
        for (int attempt = 0; attempt < 4; attempt++) {
            android.graphics.Bitmap scaled = null;
            android.graphics.Bitmap flat = null;
            try {
                float k = Math.min(1f, (float) side / Math.max(src.getWidth(), src.getHeight()));
                int w = Math.max(1, Math.round(src.getWidth() * k));
                int h = Math.max(1, Math.round(src.getHeight() * k));
                flat = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas = new android.graphics.Canvas(flat);
                canvas.drawColor(android.graphics.Color.WHITE);
                android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG | android.graphics.Paint.ANTI_ALIAS_FLAG);
                canvas.drawBitmap(src, null, new android.graphics.Rect(0, 0, w, h), paint);
                for (int q : qualities) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
                    flat.compress(jpegFormat(), q, bos);
                    byte[] bytes = bos.toByteArray();
                    if (bytes.length * 4L / 3 + 4 <= MAX_MEDIA_B64) {
                        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
                    }
                }
            } catch (Throwable t) {
                FileLog.e(t);
            } finally {
                if (flat != null) flat.recycle();
                if (scaled != null) scaled.recycle();
            }
            side = Math.max(96, side * 3 / 4);
        }
        return null;
    }

    private static String encodeFile(File file, int maxSide) {
        try {
            if (file == null || !file.exists() || file.length() <= 0 || file.length() > MAX_DECODE_FILE) return null;
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (longest / (sample * 2) >= maxSide) sample *= 2;
            android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bmp == null) return null;
            try {
                return encodeJpeg(bmp, maxSide);
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String encodeBytes(byte[] bytes, int maxSide) {
        try {
            if (bytes == null || bytes.length == 0) return null;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp == null) return null;
            try {
                return encodeJpeg(bmp, maxSide);
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String encodeStripped(TLRPC.PhotoSize ps, int maxSide) {
        try {
            android.graphics.Bitmap bmp = org.telegram.messenger.ImageLoader.getStrippedPhotoBitmap(ps.bytes, null);
            if (bmp == null) return null;
            try {
                return encodeJpeg(bmp, maxSide);
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    private static String encodeVideoFrame(File file, int maxSide) {
        if (file == null || !file.exists() || file.length() <= 0 || file.length() > MAX_VIDEO_FRAME_FILE) return null;
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            long durMs = 0;
            try {
                String s = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (s != null) durMs = Long.parseLong(s);
            } catch (Throwable ignore) { }
            android.graphics.Bitmap bmp = r.getFrameAtTime(durMs * 1000L / 2, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (bmp == null) return null;
            try {
                return encodeJpeg(bmp, maxSide);
            } finally {
                bmp.recycle();
            }
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignore) { }
        }
    }

    private static boolean fileReady(File f) {
        return f != null && f.exists() && f.length() > 0;
    }

    /** Polls until one of the files exists or the deadline passes. */
    private static File waitForFile(long deadline, File... candidates) throws InterruptedException {
        while (true) {
            for (File f : candidates) if (fileReady(f)) return f;
            if (System.currentTimeMillis() >= deadline) return null;
            Thread.sleep(150);
        }
    }

    private static boolean usableSize(TLRPC.PhotoSize s) {
        return s != null && !(s instanceof TLRPC.TL_photoStrippedSize) && !(s instanceof TLRPC.TL_photoPathSize)
                && !(s instanceof TLRPC.TL_photoSizeEmpty) && s.w > 0 && s.h > 0;
    }

    /** Smallest size with longest side >= want (and <= 1280); else the largest <= 1280; else the smallest overall. */
    private static TLRPC.PhotoSize pickSize(ArrayList<TLRPC.PhotoSize> sizes, int want, boolean allowAbove) {
        if (sizes == null) return null;
        TLRPC.PhotoSize best = null, largestBelow = null, smallest = null;
        for (TLRPC.PhotoSize s : sizes) {
            if (!usableSize(s)) continue;
            int side = Math.max(s.w, s.h);
            if (smallest == null || side < Math.max(smallest.w, smallest.h)) smallest = s;
            if (side > 1280) continue;
            if (largestBelow == null || side > Math.max(largestBelow.w, largestBelow.h)) largestBelow = s;
            if (side >= want && (best == null || side < Math.max(best.w, best.h))) best = s;
        }
        if (best != null) return best;
        if (largestBelow != null) return largestBelow;
        return allowAbove ? smallest : null;
    }

    /** Returns JPEG base64 of a PhotoSize (cached bytes, local file, or a short download). Null on failure. */
    private static String sizeToJpeg(int account, TLRPC.PhotoSize ps, TLRPC.Photo photo, TLRPC.Document doc,
                                     Object parent, int maxSide, long deadline) throws InterruptedException {
        if (ps == null) return null;
        if (ps instanceof TLRPC.TL_photoStrippedSize) return encodeStripped(ps, maxSide);
        if (ps.bytes != null && ps.bytes.length > 0) {
            String r = encodeBytes(ps.bytes, maxSide);
            if (r != null) return r;
        }
        if (ps.location == null) return null;
        FileLoader fl = FileLoader.getInstance(account);
        File f = fl.getPathToAttach(ps, null, false, false);
        if (!fileReady(f)) {
            ImageLocation loc = doc != null ? ImageLocation.getForDocument(ps, doc) : ImageLocation.getForPhoto(ps, photo);
            if (loc == null) return null;
            fl.loadFile(loc, parent, null, FileLoader.PRIORITY_NORMAL, 0);
            f = waitForFile(deadline, f);
            if (f == null) {
                try { fl.cancelLoadFile(ps); } catch (Throwable ignore) { }
                return null;
            }
        }
        return encodeFile(f, maxSide);
    }

    private static String stickerAlt(TLRPC.Document d) {
        if (d == null || d.attributes == null) return "";
        for (TLRPC.DocumentAttribute a : d.attributes) {
            if (a instanceof TLRPC.TL_documentAttributeSticker) return a.alt == null ? "" : a.alt;
        }
        return "";
    }

    private static String mediaJsonOrNull(int account, long dialogId, int messageId, int maxSide, int timeoutS) throws Exception {
        final long deadline = System.currentTimeMillis() + timeoutS * 1000L;
        ArrayList<TLRPC.Message> found = loadFromStorage(account, dialogId, 1, messageId);
        if (found.isEmpty()) return null;
        TLRPC.Message msg = found.get(0);
        if (msg.media == null || msg.media instanceof TLRPC.TL_messageMediaEmpty) return null;
        boolean secret = DialogObject.isEncryptedDialog(dialogId);
        MessageObject mo = null;
        try {
            mo = new MessageObject(account, msg, false, false);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        final Object parent = mo != null ? mo : msg;
        FileLoader fl = FileLoader.getInstance(account);

        String type = "other", emoji = "", setTitle = "", mime = "", imageB64 = null, imageKind = null;
        int duration = 0, width = 0, height = 0;

        if (msg.media instanceof TLRPC.TL_messageMediaPhoto) {
            TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) msg.media).photo;
            type = "photo";
            mime = "image/jpeg";
            if (photo != null && photo.sizes != null) {
                TLRPC.PhotoSize best = pickSize(photo.sizes, maxSide, true);
                if (best != null) {
                    width = best.w;
                    height = best.h;
                }
                for (TLRPC.PhotoSize s : photo.sizes) {
                    if (usableSize(s) && Math.max(s.w, s.h) > Math.max(width, height) && Math.max(s.w, s.h) <= 20000) {
                        width = s.w;
                        height = s.h;
                    }
                }
                if (!secret && best != null) imageB64 = sizeToJpeg(account, best, photo, null, parent, maxSide, deadline);
                if (imageB64 != null) {
                    imageKind = "photo";
                } else if (!secret) {
                    for (TLRPC.PhotoSize s : photo.sizes) {
                        if (s instanceof TLRPC.TL_photoStrippedSize) {
                            imageB64 = encodeStripped(s, maxSide);
                            if (imageB64 != null) { imageKind = "thumb"; break; }
                        }
                    }
                }
            }
        } else if (msg.media instanceof TLRPC.TL_messageMediaDocument && ((TLRPC.TL_messageMediaDocument) msg.media).document instanceof TLRPC.TL_document) {
            TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) msg.media).document;
            mime = doc.mime_type == null ? "" : doc.mime_type;
            if (doc.attributes != null) {
                for (TLRPC.DocumentAttribute a : doc.attributes) {
                    if (a instanceof TLRPC.TL_documentAttributeVideo) {
                        duration = (int) Math.round(a.duration);
                        width = a.w;
                        height = a.h;
                    } else if (a instanceof TLRPC.TL_documentAttributeAudio) {
                        duration = (int) Math.round(a.duration);
                    } else if (a instanceof TLRPC.TL_documentAttributeImageSize && width == 0) {
                        width = a.w;
                        height = a.h;
                    }
                }
            }
            boolean sticker = MessageObject.isStickerDocument(doc) || MessageObject.isVideoSticker(doc)
                    || MessageObject.isAnimatedStickerDocument(doc, true);
            if (sticker) {
                emoji = stickerAlt(doc);
                setTitle = stickerSetTitle(account, doc);
                boolean animated = MessageObject.isAnimatedStickerDocument(doc, true) || MessageObject.isVideoSticker(doc)
                        || "application/x-tgsticker".equals(mime) || "video/webm".equals(mime);
                if (animated) {
                    type = "animated_sticker";
                } else {
                    type = "sticker";
                    if (!secret) {
                        File f = fl.getPathToAttach(doc, null, false, false);
                        File f2 = fl.getPathToAttach(doc, null, true, false);
                        if (!fileReady(f) && !fileReady(f2)) {
                            fl.loadFile(doc, parent, FileLoader.PRIORITY_NORMAL, 0);
                            f = waitForFile(deadline, f, f2);
                        } else {
                            f = fileReady(f) ? f : f2;
                        }
                        if (f != null) imageB64 = encodeFile(f, maxSide);
                        if (imageB64 != null) imageKind = "sticker";
                    }
                }
            } else if (MessageObject.isVoiceDocument(doc)) {
                type = "voice";
            } else if (MessageObject.isMusicDocument(doc)) {
                type = "audio";
            } else if (MessageObject.isGifDocument(doc) || MessageObject.isNewGifDocument(doc)) {
                type = "gif";
            } else if (MessageObject.isVideoDocument(doc) || MessageObject.isRoundVideoDocument(doc)) {
                type = "video";
            } else {
                type = "document";
            }
            if (!secret && (type.equals("gif") || type.equals("video"))) {
                File vf = fl.getPathToAttach(doc, null, false, false);
                if ((vf == null || !fileReady(vf)) && msg.attachPath != null && !msg.attachPath.isEmpty()) vf = new File(msg.attachPath);
                if (fileReady(vf)) {
                    imageB64 = encodeVideoFrame(vf, maxSide);
                    if (imageB64 != null) imageKind = "frame";
                }
                if (imageB64 == null && doc.thumbs != null) {
                    TLRPC.PhotoSize thumb = pickSize(doc.thumbs, maxSide, true);
                    if (thumb != null) imageB64 = sizeToJpeg(account, thumb, null, doc, parent, maxSide, deadline);
                    if (imageB64 == null) {
                        for (TLRPC.PhotoSize s : doc.thumbs) {
                            if (s instanceof TLRPC.TL_photoStrippedSize) { imageB64 = encodeStripped(s, maxSide); if (imageB64 != null) break; }
                        }
                    }
                    if (imageB64 != null) imageKind = "thumb";
                }
            } else if (!secret && type.equals("document") && mime.startsWith("image/") && !mime.equals("image/gif")) {
                File f = fl.getPathToAttach(doc, null, false, false);
                if (fileReady(f)) {
                    imageB64 = encodeFile(f, maxSide);
                    if (imageB64 != null) imageKind = "photo";
                }
            }
        } else {
            return new JSONObject().put("type", "other").put("caption", msg.message == null ? "" : msg.message)
                    .put("emoji", "").put("set_title", "").put("mime", "").put("duration", 0).put("width", 0).put("height", 0)
                    .put("image_b64", JSONObject.NULL).put("image_kind", JSONObject.NULL).toString();
        }
        return new JSONObject()
                .put("type", type)
                .put("caption", msg.message == null ? "" : msg.message)
                .put("emoji", emoji)
                .put("set_title", setTitle)
                .put("mime", mime)
                .put("duration", duration)
                .put("width", width)
                .put("height", height)
                .put("image_b64", imageB64 == null ? JSONObject.NULL : imageB64)
                .put("image_kind", imageB64 == null || imageKind == null ? JSONObject.NULL : imageKind)
                .toString();
    }

    /**
     * Describes the media of a cached message for vision models (chat.read). JSON object text or null (message not
     * found, no media, or any failure). Reads the local database, downloads only small files (photo size, thumbs,
     * static stickers); never downloads videos. Downloading does not send read receipts. Blocking, worker thread only.
     */
    public static String getMessageMedia(String id, long dialogId, int messageId, int account, int maxSide, int timeoutS) {
        try {
            PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
            checkAccount(account);
            notMainThread();
            maxSide = Math.max(128, Math.min(1280, maxSide));
            timeoutS = Math.max(1, Math.min(60, timeoutS));
            return mediaJsonOrNull(account, dialogId, messageId, maxSide, timeoutS);
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    // ------------------------------------------------------------------ voice / transcription

    private static final long MAX_VOICE_BYTES = 2500L * 1024;

    private static String voiceError(String code) throws Exception {
        return new JSONObject().put("error", code).toString();
    }

    /**
     * Voice/audio message payload for speech-to-text (chat.read). JSON text: {type, is_voice, duration, mime, size,
     * audio_b64} or {error}; round videos return the get_message_media shape with type "round_video". Blocking.
     */
    public static String getVoice(String id, long dialogId, int messageId, int account, int timeoutS, int maxSeconds) {
        try {
            PluginRuntime.INSTANCE.requirePermission(id, "chat.read");
            checkAccount(account);
            notMainThread();
            timeoutS = Math.max(1, Math.min(120, timeoutS));
            maxSeconds = Math.max(1, Math.min(3600, maxSeconds));
            final long deadline = System.currentTimeMillis() + timeoutS * 1000L;
            ArrayList<TLRPC.Message> found = loadFromStorage(account, dialogId, 1, messageId);
            if (found.isEmpty()) return null;
            TLRPC.Message msg = found.get(0);
            if (!(msg.media instanceof TLRPC.TL_messageMediaDocument) || !(((TLRPC.TL_messageMediaDocument) msg.media).document instanceof TLRPC.TL_document)) {
                return voiceError("not_audio");
            }
            TLRPC.Document doc = ((TLRPC.TL_messageMediaDocument) msg.media).document;
            if (MessageObject.isRoundVideoDocument(doc)) {
                String media = mediaJsonOrNull(account, dialogId, messageId, 768, Math.min(timeoutS, 20));
                if (media == null) return null;
                JSONObject o = new JSONObject(media);
                o.put("type", "round_video").put("is_voice", false);
                return o.toString();
            }
            boolean voice = MessageObject.isVoiceDocument(doc);
            if (!voice && !MessageObject.isMusicDocument(doc)) return voiceError("not_audio");
            if (DialogObject.isEncryptedDialog(dialogId)) return voiceError("secret");
            int duration = 0;
            if (doc.attributes != null) {
                for (TLRPC.DocumentAttribute a : doc.attributes) {
                    if (a instanceof TLRPC.TL_documentAttributeAudio) duration = (int) Math.round(a.duration);
                }
            }
            if (duration > maxSeconds) return voiceError("too_long");
            if (doc.size > MAX_VOICE_BYTES) return voiceError("too_big");
            MessageObject mo = null;
            try { mo = new MessageObject(account, msg, false, false); } catch (Throwable t) { FileLog.e(t); }
            Object parent = mo != null ? mo : msg;
            FileLoader fl = FileLoader.getInstance(account);
            File f = fl.getPathToAttach(doc, null, false, false);
            if (!fileReady(f) && msg.attachPath != null && !msg.attachPath.isEmpty() && fileReady(new File(msg.attachPath))) {
                f = new File(msg.attachPath);
            }
            if (!fileReady(f)) {
                fl.loadFile(doc, parent, FileLoader.PRIORITY_NORMAL, 0);
                f = waitForFile(deadline, f);
                if (f == null) {
                    try { fl.cancelLoadFile(doc); } catch (Throwable ignore) { }
                    return voiceError("download_failed");
                }
            }
            if (f.length() > MAX_VOICE_BYTES) return voiceError("too_big");
            byte[] data = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) off += n;
                if (off != data.length) return voiceError("download_failed");
            }
            return new JSONObject()
                    .put("type", voice ? "voice" : "audio")
                    .put("is_voice", voice)
                    .put("duration", duration)
                    .put("mime", doc.mime_type == null ? "" : doc.mime_type)
                    .put("size", data.length)
                    .put("audio_b64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP))
                    .toString();
        } catch (SecurityException e) {
            throw e;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /** Speech-to-text via the user's AI server (ai.complete). Blocking, worker thread only. Throws on failure. */
    public static String aiTranscribe(String id, String audioB64, String mime, String language) throws Exception {
        PluginRuntime.INSTANCE.requirePermission(id, "ai.complete");
        notMainThread();
        String b = audioB64 == null ? "" : audioB64;
        if (b.startsWith("data:")) b = b.substring(b.indexOf(',') + 1);
        if (b.isEmpty() || b.length() > 4200000) throw new IllegalArgumentException("Audio is empty or larger than ~3 MB");
        byte[] audio = android.util.Base64.decode(b, android.util.Base64.DEFAULT);
        if (!AI_SLOTS.tryAcquire(60, TimeUnit.SECONDS)) throw new java.io.IOException("Too many concurrent AI requests");
        try {
            return ZephyrAiClient.transcribeBlocking(audio, mime, language, 60);
        } finally {
            AI_SLOTS.release();
        }
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
