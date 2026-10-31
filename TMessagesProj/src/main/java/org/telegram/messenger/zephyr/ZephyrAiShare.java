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

    public static final int FAIL_INVALID = 0;
    public static final int FAIL_NOT_FOUND = 1;
    public static final int FAIL_NOT_USER = 2;
    public static final int FAIL_NETWORK = 3;

    public interface ResolveListener {
        void onResolved(long userId, TLRPC.User user);

        void onFailed();

        /** More precise failure (one of FAIL_*); defaults to {@link #onFailed()}. */
        default void onFailed(int reason) {
            onFailed();
        }
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
        return ZephyrAiSetupParser.normalizeServerUrl(ZephyrConfig.getAiServerUrl());
    }

    public static String buildMessage(String token) {
        String url = serverUrl();
        String link = SCHEME_PREFIX + "url=" + Uri.encode(url) + "&token=" + Uri.encode(token);
        return LocaleController.formatString(R.string.ZephyrShareMessage, url, token, link);
    }

    /**
     * Finds server URL + token in arbitrary text (zephyrai://setup link, "Server:/Token:" lines or bare values). Returns null
     * unless both are present; use {@link #parseLoose} to accept a lone token or URL.
     */
    public static Setup parseSetup(CharSequence text) {
        ZephyrAiSetupParser.Result r = ZephyrAiSetupParser.parse(text);
        return r.complete() ? new Setup(r.url, r.token) : null;
    }

    /** Same as parseSetup but url and/or token may be empty. Never null. */
    public static ZephyrAiSetupParser.Result parseLoose(CharSequence text) {
        return ZephyrAiSetupParser.parse(text);
    }

    /** Extracts a Telegram username from "@name", "name", "t.me/name", "https://t.me/name?x" etc. Returns "" when invalid. */
    public static String cleanUsername(String input) {
        String name = input == null ? "" : input.trim();
        int q = name.indexOf('?');
        if (q >= 0) {
            name = name.substring(0, q);
        }
        while (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.startsWith("@")) {
            name = name.substring(1);
        }
        name = name.trim();
        return name.matches("[A-Za-z][A-Za-z0-9_]{3,31}") ? name : "";
    }

    /** Resolves "@name" / "name" / t.me link to a user (private chats only). */
    public static void resolveUsername(int account, String input, ResolveListener listener) {
        String name = cleanUsername(input);
        if (name.isEmpty()) {
            listener.onFailed(FAIL_INVALID);
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
                } else {
                    listener.onFailed(FAIL_NOT_USER);
                }
                return;
            }
            if (error != null && error.text != null && error.text.contains("USERNAME_NOT_OCCUPIED")) {
                listener.onFailed(FAIL_NOT_FOUND);
            } else if (error != null && error.text != null && error.text.contains("USERNAME_INVALID")) {
                listener.onFailed(FAIL_INVALID);
            } else {
                listener.onFailed(FAIL_NETWORK);
            }
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
