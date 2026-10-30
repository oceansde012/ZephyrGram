package org.telegram.messenger.zephyr;

import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Ghost mode: suppresses outgoing signals at the single choke point of the network layer
 * (ConnectionsManager.sendRequestInternal), so every sender is covered, including those
 * that bypass MessagesController.
 */
public class ZephyrGhost {

    /**
     * @return true if the request must NOT be sent. The completion block (if any) has already
     * been invoked with a harmless result when the caller relies on it.
     */
    public static boolean intercept(TLObject object, RequestDelegate onComplete) {
        ZephyrConfig.load();
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
