package org.telegram.messenger.zephyr.plugins;

import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Support for the on_inline_result_send hook (inline.hook): describes a picked BotInlineResult to Python as JSON,
 * and builds the "send as own message" copy (no query_id / bot params, so SendMessagesHelper sends an ordinary
 * messages.sendMedia / sendMessage instead of messages.sendInlineBotResult and the message carries no via_bot).
 */
final class PluginInlineResults {

    private PluginInlineResults() { }

    private static String messageKind(TLRPC.BotInlineMessage m) {
        if (m instanceof TLRPC.TL_botInlineMessageMediaAuto) return "media_auto";
        if (m instanceof TLRPC.TL_botInlineMessageText) return "text";
        if (m instanceof TLRPC.TL_botInlineMessageMediaGeo) return "geo";
        if (m instanceof TLRPC.TL_botInlineMessageMediaVenue) return "venue";
        if (m instanceof TLRPC.TL_botInlineMessageMediaContact) return "contact";
        if (m instanceof TLRPC.TL_botInlineMessageMediaInvoice) return "invoice";
        if (m instanceof TLRPC.TL_botInlineMessageMediaWebPage) return "webpage";
        if (m instanceof TLRPC.TL_botInlineMessageRichMessage) return "rich";
        return "other";
    }

    /** Same cache file name SendMessagesHelper.prepareSendingBotContextResult uses for external content. */
    static File cachedContentFile(TLRPC.BotInlineResult result) {
        if (result == null || result.content == null || TextUtils.isEmpty(result.content.url)) return null;
        String ext = ImageLoader.getHttpUrlExtension(result.content.url, null);
        if (TextUtils.isEmpty(ext)) {
            ext = FileLoader.getExtensionByMimeType(result.content.mime_type);
        } else {
            ext = "." + ext;
        }
        File dir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
        return dir == null ? null : new File(dir, Utilities.MD5(result.content.url) + ext);
    }

    private static File localFile(int account, TLRPC.BotInlineResult result) {
        try {
            if (result.document instanceof TLRPC.TL_document || result.photo instanceof TLRPC.TL_photo) {
                FileLoader loader = FileLoader.getInstance(account);
                org.telegram.tgnet.TLObject attach = result.document instanceof TLRPC.TL_document ? result.document
                        : FileLoader.getClosestPhotoSizeWithSize(result.photo.sizes, org.telegram.messenger.AndroidUtilities.getPhotoSize());
                if (attach == null) return null;
                File f = loader.getPathToAttach(attach, false);
                if (f == null || !f.exists()) f = loader.getPathToAttach(attach, true);
                return f != null && f.exists() ? f : null;
            }
            File f = cachedContentFile(result);
            return f != null && f.exists() ? f : null;
        } catch (Throwable t) {
            FileLog.e(t);
            return null;
        }
    }

    /**
     * Whether the result can be re-sent as an ordinary message: Telegram-hosted photo/document, external non-photo
     * content that is already in the local cache (uploaded from that file), or plain text. Never for games, other message kinds, or secret chats.
     */
    static boolean canSendAsOwn(int account, TLRPC.BotInlineResult result, long dialogId) {
        if (result == null || DialogObject.isEncryptedDialog(dialogId) || "game".equals(result.type)) return false;
        if (result.send_message instanceof TLRPC.TL_botInlineMessageText) {
            return !TextUtils.isEmpty(result.send_message.message);
        }
        if (!(result.send_message instanceof TLRPC.TL_botInlineMessageMediaAuto)) return false;
        if (result instanceof TLRPC.TL_botInlineMediaResult) {
            return result.document instanceof TLRPC.TL_document || result.document == null && result.photo instanceof TLRPC.TL_photo;
        }
        if (result.content == null) return false;
        // External "photo" results are re-fetched over HTTP from the bot's URL by the photo pipeline (it keeps the URL as
        // path), which would expose the user's IP to the bot: not offered; send_media(file_path, kind="photo") works.
        switch (result.type == null ? "" : result.type) {
            case "audio": case "voice": case "file": case "video": case "sticker": case "gif":
                File f = cachedContentFile(result);
                return f != null && f.exists();
            default:
                return false;
        }
    }

    static String describe(int account, TLRPC.BotInlineResult result, HashMap<String, String> params, long dialogId,
                           MessageObject replyToMsg, MessageObject replyToTopMsg, int scheduleDate) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", result.id == null ? "" : result.id);
        o.put("query_id", result.query_id);
        o.put("type", result.type == null ? "" : result.type);
        o.put("kind", result instanceof TLRPC.TL_botInlineMediaResult ? "media" : result.content != null ? "external" : "none");
        o.put("message_kind", messageKind(result.send_message));
        o.put("title", result.title == null ? "" : result.title);
        o.put("description", result.description == null ? "" : result.description);
        o.put("url", result.url == null ? "" : result.url);
        o.put("content_url", result.content != null && result.content.url != null ? result.content.url : "");
        o.put("content_mime", result.content != null && result.content.mime_type != null ? result.content.mime_type : "");
        o.put("content_size", result.content != null ? result.content.size : 0);
        o.put("thumb_url", result.thumb != null && result.thumb.url != null ? result.thumb.url : "");
        TLRPC.Document doc = result.document instanceof TLRPC.TL_document ? result.document : null;
        o.put("document_id", doc != null ? doc.id : 0);
        o.put("document_mime", doc != null && doc.mime_type != null ? doc.mime_type : "");
        o.put("file_name", doc != null ? String.valueOf(FileLoader.getDocumentFileName(doc)) : "");
        o.put("size", doc != null ? doc.size : result.content != null ? result.content.size : 0);
        o.put("photo_id", result.photo instanceof TLRPC.TL_photo ? result.photo.id : 0);
        int[] wh = MessageObject.getInlineResultWidthAndHeight(result);
        o.put("width", wh[0]);
        o.put("height", wh[1]);
        o.put("duration", MessageObject.getInlineResultDuration(result));
        File local = localFile(account, result);
        o.put("file_path", local != null ? local.getAbsolutePath() : JSONObject.NULL);
        TLRPC.BotInlineMessage m = result.send_message;
        o.put("caption", m != null && m.message != null ? m.message : "");
        o.put("has_entities", m != null && m.entities != null && !m.entities.isEmpty());
        o.put("has_reply_markup", m != null && m.reply_markup != null);
        long botId = params != null ? Utilities.parseLong(params.get("bot")) : 0;
        o.put("bot_id", botId);
        o.put("bot_username", params != null && params.get("bot_name") != null ? params.get("bot_name") : "");
        o.put("dialog_id", dialogId);
        o.put("reply_to", replyToMsg != null ? replyToMsg.getId() : 0);
        o.put("top_msg_id", replyToTopMsg != null ? replyToTopMsg.getId() : 0);
        o.put("scheduled", scheduleDate);
        o.put("secret", DialogObject.isEncryptedDialog(dialogId));
        o.put("can_send_as_own", canSendAsOwn(account, result, dialogId));
        return o.toString();
    }

    /**
     * Copy of the result for an ordinary send: same media/text, reply markup dropped (users cannot send inline
     * keyboards), caption replaced when caption != null (entities then cleared). The original cached result is never
     * mutated. Call only after canSendAsOwn.
     */
    static TLRPC.BotInlineResult ownCopy(TLRPC.BotInlineResult result, String caption) {
        TLRPC.BotInlineResult copy = result instanceof TLRPC.TL_botInlineMediaResult
                ? new TLRPC.TL_botInlineMediaResult() : new TLRPC.TL_botInlineResult();
        copy.flags = result.flags;
        copy.id = result.id;
        copy.type = result.type;
        copy.photo = result.photo;
        copy.document = result.document;
        copy.title = result.title;
        copy.description = result.description;
        copy.url = result.url;
        copy.thumb = result.thumb;
        copy.content = result.content;
        copy.query_id = result.query_id;
        TLRPC.BotInlineMessage source = result.send_message;
        TLRPC.BotInlineMessage message;
        if (source instanceof TLRPC.TL_botInlineMessageText) {
            message = new TLRPC.TL_botInlineMessageText();
            message.no_webpage = source.no_webpage;
            message.invert_media = source.invert_media;
        } else {
            message = new TLRPC.TL_botInlineMessageMediaAuto();
            message.invert_media = source.invert_media;
        }
        boolean replace = caption != null && !(source instanceof TLRPC.TL_botInlineMessageText && caption.isEmpty());
        message.message = replace ? caption : (source.message == null ? "" : source.message);
        message.entities = replace || source.entities == null ? new ArrayList<>() : new ArrayList<>(source.entities);
        message.reply_markup = null;
        int flags = source.flags & ~4; // FLAG_2: reply_markup
        flags = message.entities.isEmpty() ? (flags & ~2) : (flags | 2); // FLAG_1: entities
        message.flags = flags;
        copy.send_message = message;
        return copy;
    }
}
