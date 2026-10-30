/*
 * Extra message context menu items (forward without author, save to Saved Messages, copy photo, details).
 * Behaviour ported from exteraGram (exteraSquad, @immat0x1), GPLv2. Modified for ZephyrGram.
 * Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import android.text.TextUtils;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;

/** Menu entries use ids in [ID_BASE, ID_BASE + 3]; plugin menu ids start at 0x5A000, so there is no overlap. */
public final class ZephyrMessageMenu {

    private ZephyrMessageMenu() {
    }

    public static final int ID_FORWARD_NO_AUTHOR = 0x5C001;
    public static final int ID_SAVE = 0x5C002;
    public static final int ID_COPY_PHOTO = 0x5C003;
    public static final int ID_DETAILS = 0x5C004;
    public static final int ID_ASK_AI = 0x5C005;

    public static boolean isItemId(int id) {
        return id >= ID_FORWARD_NO_AUTHOR && id <= ID_ASK_AI;
    }

    /** Appends the enabled extra items to the message menu lists (all three lists grow in lockstep). */
    public static void addItems(ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group,
                                ArrayList<Integer> icons, ArrayList<CharSequence> items, ArrayList<Integer> options) {
        if (chat == null || message == null || message.messageOwner == null || message.getId() <= 0
                || message.isSponsored() || message.isEphemeral() || message.contentType != 0) {
            return;
        }
        ZephyrConfig.load();
        final boolean regularChat = chat.getChatMode() == ChatActivity.MODE_DEFAULT;
        final boolean noforwards = chat.isPeerNoForwards() || message.messageOwner.noforwards;
        if (regularChat && !noforwards && message.canForwardMessage()) {
            if (ZephyrConfig.showForwardNoAuthor) {
                items.add(LocaleController.getString(R.string.ZephyrForwardNoAuthor));
                options.add(ID_FORWARD_NO_AUTHOR);
                icons.add(R.drawable.msg_forward);
            }
            final long self = UserConfig.getInstance(chat.getCurrentAccount()).getClientUserId();
            if (ZephyrConfig.showSaveMessage && chat.getDialogId() != self) {
                items.add(LocaleController.getString(R.string.ZephyrSaveToSaved));
                options.add(ID_SAVE);
                icons.add(R.drawable.msg_saved);
            }
        }
        final MessageObject photoMessage = group != null && group.findPrimaryMessageObject() != null ? group.findPrimaryMessageObject() : message;
        if (ZephyrConfig.showCopyPhoto && !noforwards && photoMessage.isPhoto() && !photoMessage.needDrawBluredPreview()) {
            items.add(LocaleController.getString(R.string.ZephyrCopyPhoto));
            options.add(ID_COPY_PHOTO);
            icons.add(R.drawable.msg_copy);
        }
        if (!TextUtils.isEmpty(askAiText(message))) {
            items.add(LocaleController.getString(R.string.ZephyrAiAsk));
            options.add(ID_ASK_AI);
            icons.add(R.drawable.msg_bot);
        }
        if (ZephyrConfig.showMessageDetails) {
            items.add(LocaleController.getString(R.string.ZephyrMessageDetails));
            options.add(ID_DETAILS);
            icons.add(R.drawable.msg_info);
        }
    }

    private static String askAiText(MessageObject message) {
        CharSequence text = message.messageOwner != null && !TextUtils.isEmpty(message.messageOwner.message) ? message.messageOwner.message : message.messageText;
        return text == null ? "" : text.toString().trim();
    }

    public static void onClick(int id, ChatActivity chat, MessageObject message, MessageObject.GroupedMessages group) {
        if (chat == null || message == null || chat.getParentActivity() == null) {
            return;
        }
        final ArrayList<MessageObject> list = new ArrayList<>();
        if (group != null && !group.messages.isEmpty()) {
            list.addAll(group.messages);
        } else {
            list.add(message);
        }
        final int account = chat.getCurrentAccount();
        switch (id) {
            case ID_FORWARD_NO_AUTHOR:
                forwardWithoutAuthor(chat, account, list);
                break;
            case ID_SAVE:
                SendMessagesHelper.getInstance(account).sendMessage(list, UserConfig.getInstance(account).getClientUserId(), false, false, true, 0, 0);
                BulletinFactory.of(chat).createSimpleBulletin(R.raw.saved_messages, LocaleController.getString(R.string.ZephyrSavedDone)).show();
                break;
            case ID_COPY_PHOTO:
                copyPhoto(chat, account, group != null && group.findPrimaryMessageObject() != null ? group.findPrimaryMessageObject() : message);
                break;
            case ID_DETAILS:
                showDetails(chat, message);
                break;
            case ID_ASK_AI:
                chat.presentFragment(new ZephyrAssistantActivity(askAiText(message)));
                break;
        }
    }

    private static void forwardWithoutAuthor(ChatActivity chat, int account, ArrayList<MessageObject> list) {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
        args.putInt("messagesCount", list.size());
        args.putBoolean("canSelectTopics", true);
        DialogsActivity fragment = new DialogsActivity(args);
        fragment.setDelegate((f, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            if (dids == null || dids.isEmpty()) {
                return false;
            }
            for (int a = 0; a < dids.size(); a++) {
                final long did = dids.get(a).dialogId;
                if (message != null && message.length() > 0) {
                    SendMessagesHelper.getInstance(account).sendMessage(SendMessagesHelper.SendMessageParams.of(message.toString(), did, null, null, null, true, null, null, null, notify, scheduleDate, scheduleRepeatPeriod, null, false));
                }
                SendMessagesHelper.getInstance(account).sendMessage(list, did, true, false, notify, scheduleDate, 0);
            }
            f.finishFragment();
            BulletinFactory.of(chat).showForwardedBulletinWithTag(dids.get(0).dialogId, list.size());
            return true;
        });
        chat.presentFragment(fragment);
    }

    private static void copyPhoto(ChatActivity chat, int account, MessageObject message) {
        try {
            final File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
            if (file == null || !file.exists()) {
                BulletinFactory.of(chat).createSimpleBulletin(R.raw.error, LocaleController.getString(R.string.ZephyrPhotoNotLoaded)).show();
                return;
            }
            final Context context = chat.getParentActivity();
            final Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
            final ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext.getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(context.getContentResolver(), "image", uri));
            BulletinFactory.of(chat).createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.ZephyrPhotoCopied)).show();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void showDetails(ChatActivity chat, MessageObject message) {
        final TLRPC.Message m = message.messageOwner;
        final StringBuilder sb = new StringBuilder();
        line(sb, "ID", String.valueOf(m.id));
        line(sb, "Chat", String.valueOf(message.getDialogId()));
        line(sb, "From", String.valueOf(message.getFromChatId()));
        line(sb, "Date", formatDate(m.date));
        if (m.edit_date != 0) {
            line(sb, "Edited", formatDate(m.edit_date));
        }
        if (m.views > 0) {
            line(sb, "Views", String.valueOf(m.views));
        }
        if (m.forwards > 0) {
            line(sb, "Forwards", String.valueOf(m.forwards));
        }
        if (m.fwd_from != null) {
            line(sb, "Forwarded date", formatDate(m.fwd_from.date));
            if (m.fwd_from.from_id != null) {
                line(sb, "Forwarded from", String.valueOf(MessageObject.getPeerId(m.fwd_from.from_id)));
            }
            if (m.fwd_from.from_name != null) {
                line(sb, "Forwarded name", m.fwd_from.from_name);
            }
        }
        if (message.getReplyMsgId() != 0) {
            line(sb, "Reply to", String.valueOf(message.getReplyMsgId()));
        }
        if (m.grouped_id != 0) {
            line(sb, "Album", String.valueOf(m.grouped_id));
        }
        if (m.media != null) {
            line(sb, "Media", m.media.getClass().getSimpleName());
            final TLRPC.Document document = message.getDocument();
            if (document != null) {
                line(sb, "Document ID", String.valueOf(document.id));
                line(sb, "MIME", String.valueOf(document.mime_type));
                line(sb, "Size", String.valueOf(document.size));
                line(sb, "DC", String.valueOf(document.dc_id));
            } else if (m.media.photo != null) {
                line(sb, "Photo ID", String.valueOf(m.media.photo.id));
                line(sb, "DC", String.valueOf(m.media.photo.dc_id));
            }
        }
        final String text = sb.toString().trim();
        final AlertDialog.Builder builder = new AlertDialog.Builder(chat.getParentActivity());
        builder.setTitle(LocaleController.getString(R.string.ZephyrMessageDetails));
        builder.setMessage(text);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        builder.setNeutralButton(LocaleController.getString(R.string.ZephyrCopy), (d, w) -> AndroidUtilities.addToClipboard(text));
        chat.showDialog(builder.create());
    }

    private static void line(StringBuilder sb, String name, String value) {
        sb.append(name).append(": ").append(value).append('\n');
    }

    private static String formatDate(int date) {
        return java.text.DateFormat.getDateTimeInstance().format(new Date(date * 1000L));
    }
}
