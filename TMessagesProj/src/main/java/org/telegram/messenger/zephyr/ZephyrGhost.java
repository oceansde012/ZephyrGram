package org.telegram.messenger.zephyr;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * Ghost mode: suppresses outgoing signals at the single choke point of the network layer
 * (ConnectionsManager.sendRequestInternal), so every sender is covered, including those
 * that bypass MessagesController.
 */
public class ZephyrGhost {

    /**
     * One-off "view stories invisibly" session started from the chat list while ghost mode is off.
     * Suppresses only story view requests; cleared when the story viewer closes.
     */
    public static volatile boolean storyPeekActive = false;

    /** Read requests the user asked for by hand ("Mark as read" in the chat menu); they pass even with ghostNoRead on. */
    private static final Set<TLObject> allowedReads = Collections.synchronizedSet(Collections.newSetFromMap(new IdentityHashMap<TLObject, Boolean>()));

    /**
     * Sends one read receipt for the dialog up to maxId although "don't send read receipts" is on.
     * Secret chats are not supported (their receipts need the message date, not an id).
     *
     * @return false when nothing was sent
     */
    public static boolean sendReadReceipt(int account, long dialogId, int maxId) {
        if (maxId <= 0 || dialogId == 0 || DialogObject.isEncryptedDialog(dialogId)) {
            return false;
        }
        try {
            final MessagesController controller = MessagesController.getInstance(account);
            final TLRPC.InputPeer peer = controller.getInputPeer(dialogId);
            if (peer == null || peer instanceof TLRPC.TL_inputPeerEmpty) {
                return false;
            }
            final TLObject req;
            if (peer instanceof TLRPC.TL_inputPeerChannel) {
                TLRPC.TL_channels_readHistory request = new TLRPC.TL_channels_readHistory();
                request.channel = controller.getInputChannel(-dialogId);
                request.max_id = maxId;
                req = request;
            } else {
                TLRPC.TL_messages_readHistory request = new TLRPC.TL_messages_readHistory();
                request.peer = peer;
                request.max_id = maxId;
                req = request;
            }
            allowedReads.add(req);
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
                allowedReads.remove(req);
                if (error == null && response instanceof TLRPC.TL_messages_affectedMessages) {
                    TLRPC.TL_messages_affectedMessages res = (TLRPC.TL_messages_affectedMessages) response;
                    controller.processNewDifferenceParams(-1, res.pts, -1, res.pts_count);
                }
            });
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /**
     * @return true if the request must NOT be sent. The completion block (if any) has already
     * been invoked with a harmless result when the caller relies on it.
     */
    public static boolean intercept(TLObject object, RequestDelegate onComplete) {
        ZephyrConfig.load();
        if (storyPeekActive && isStoryViewRequest(object)) {
            return true;
        }
        if (!ZephyrConfig.isGhostEnabled()) {
            return false;
        }

        if (ZephyrConfig.ghostNoTyping && object instanceof TLRPC.TL_messages_setTyping) {
            // MessagesController.sendTyping keeps per-dialog state until the callback fires,
            // so answer with a plain "true" instead of dropping silently.
            if (onComplete != null) {
                onComplete.run(new TLRPC.TL_boolTrue(), null);
            }
            return true;
        }

        if (ZephyrConfig.ghostAlwaysOffline && object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
            return false;
        }

        if (ZephyrConfig.ghostNoRead && isReadRequest(object)) {
            if (allowedReads.contains(object)) {
                return false; // asked for by the user, see sendReadReceipt
            }
            // Callbacks of these requests only feed pts bookkeeping from the server answer;
            // a fabricated answer would make the client fetch difference, so just drop.
            return true;
        }

        if (ZephyrConfig.ghostNoStoryViews && isStoryViewRequest(object)) {
            return true;
        }

        return false;
    }

    private static boolean isReadRequest(TLObject object) {
        return object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_channels_readMessageContents
                || object instanceof TLRPC.TL_messages_readEncryptedHistory
                || object instanceof TLRPC.TL_messages_readDiscussion
                || object instanceof TLRPC.TL_messages_readSavedHistory;
    }

    private static boolean isStoryViewRequest(TLObject object) {
        return object instanceof TL_stories.TL_stories_readStories
                || object instanceof TL_stories.TL_stories_incrementStoryViews;
    }
}
