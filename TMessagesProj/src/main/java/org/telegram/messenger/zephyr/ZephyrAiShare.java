package org.telegram.messenger.zephyr;

import android.net.Uri;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.messenger.zephyr.plugins.PluginHooks;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

/**
 * Core-only helpers for "Share access": builds/parses the access message, resolves @usernames and sends the message
 * from the owner's account. The admin secret never leaves ZephyrAiClient; plugin hooks are skipped for the outgoing
 * message so plugins never see the token.
 */
public class ZephyrAiShare {

    private static final String SCHEME_PREFIX = "zephyrai://setup?";

    public interface ResolveListener {
        void onResolved(long userId, TLRPC.User user);

        void onFailed();
    }

    public static class Setup {
        public final String url;
        public final String token;

        Setup(String url, String token) {
            this.url = url;
            this.token = token;
        }
    }

    /** Server URL in the form stored by the owner, trimmed. */
    private static String serverUrl() {
        String u = ZephyrConfig.getAiServerUrl().trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u;
    }

    public static String buildMessage(String token) {
        String url = serverUrl();
        String link = SCHEME_PREFIX + "url=" + Uri.encode(url) + "&token=" + Uri.encode(token);
        return LocaleController.formatString(R.string.ZephyrShareMessage, url, token, link);
    }

    /** Finds a zephyrai://setup?url=...&token=... line in arbitrary text. Returns null when absent or incomplete. */
    public static Setup parseSetup(CharSequence text) {
        if (text == null) {
            return null;
        }
        String s = text.toString();
        int i = s.indexOf(SCHEME_PREFIX);
        if (i < 0) {
            return null;
        }
        int end = i;
        while (end < s.length() && !Character.isWhitespace(s.charAt(end))) {
            end++;
        }
        try {
            Uri uri = Uri.parse(s.substring(i, end));
            String url = uri.getQueryParameter("url");
            String token = uri.getQueryParameter("token");
            if (url == null || token == null || url.trim().isEmpty() || token.trim().isEmpty()) {
                return null;
            }
            return new Setup(url.trim(), token.trim());
        } catch (Throwable e) {
            // Never log the link itself: it carries the access token.
            FileLog.e("ZephyrAiShare: setup link parse failed " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Resolves "@name" / "name" to a user (private chats only). */
    public static void resolveUsername(int account, String input, ResolveListener listener) {
        String name = input == null ? "" : input.trim();
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isEmpty()) {
            listener.onFailed();
            return;
        }
        TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = name;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (error == null && response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                MessagesController.getInstance(account).putUsers(res.users, false);
                MessagesController.getInstance(account).putChats(res.chats, false);
                if (res.peer instanceof TLRPC.TL_peerUser) {
                    long id = res.peer.user_id;
                    listener.onResolved(id, MessagesController.getInstance(account).getUser(id));
                    return;
                }
            }
            listener.onFailed();
        }));
    }

    /**
     * Sends text to a private chat. onFail runs (main thread) if sending is rejected immediately or the send reports an
     * error shortly afterwards, so the caller can fall back to showing the token once.
     */
    public static void send(int account, long userId, String text, Runnable onFail) {
        final NotificationCenter nc = NotificationCenter.getInstance(account);
        final NotificationCenter.NotificationCenterDelegate[] holder = new NotificationCenter.NotificationCenterDelegate[1];
        final boolean[] finished = new boolean[1];
        holder[0] = (id, acc, args) -> {
            if (id == NotificationCenter.messageSendError && !finished[0]) {
                finished[0] = true;
                nc.removeObserver(holder[0], NotificationCenter.messageSendError);
                FileLog.w("ZephyrAiShare: messageSendError received");
                onFail.run();
            }
        };
        try {
            nc.addObserver(holder[0], NotificationCenter.messageSendError);
            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                    text, userId, null, null, null, false, null, null, null, true, 0, 0, null, false);
            PluginHooks.skipHooks(params);
            SendMessagesHelper.getInstance(account).sendMessage(params);
        } catch (Throwable e) {
            FileLog.e("ZephyrAiShare: send failed " + e.getClass().getSimpleName());
            finished[0] = true;
            nc.removeObserver(holder[0], NotificationCenter.messageSendError);
            onFail.run();
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (!finished[0]) {
                finished[0] = true;
                nc.removeObserver(holder[0], NotificationCenter.messageSendError);
            }
        }, 20000);
    }
}
