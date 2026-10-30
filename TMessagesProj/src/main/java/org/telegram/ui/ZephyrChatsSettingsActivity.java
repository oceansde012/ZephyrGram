/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrChatsSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsChats);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addHeader(items, R.string.ZephyrUxHeaderBehavior);
        addSwitch(items, "disableJumpToNextChannel", R.string.ZephyrDisableJumpToNextChannel, R.string.ZephyrUxJumpChannelInfo);
        addSwitch(items, "hideKeyboardOnScroll", R.string.ZephyrHideKeyboardOnScroll);
        addSwitch(items, "hideShareButton", R.string.ZephyrHideShareButton, R.string.ZephyrUxShareButtonInfo);
        addSwitch(items, "hideStickerTime", R.string.ZephyrHideStickerTime);
        addSwitch(items, "unlimitedRecentStickers", R.string.ZephyrUnlimitedRecentStickers);
        addSwitch(items, "pauseOnMinimize", R.string.ZephyrPauseOnMinimize);
        addSlider(items, "stickerSize", R.string.ZephyrStickerSize, 50, 150);
        final CharSequence[] doubleTapOptions = {
                LocaleController.getString(R.string.ZephyrDoubleTapOff),
                LocaleController.getString(R.string.ZephyrDoubleTapReaction),
                LocaleController.getString(R.string.ZephyrDoubleTapReply),
                LocaleController.getString(R.string.ZephyrDoubleTapCopy),
                LocaleController.getString(R.string.ZephyrDoubleTapForward),
                LocaleController.getString(R.string.ZephyrDoubleTapEdit),
                LocaleController.getString(R.string.ZephyrDoubleTapDelete)};
        addChoice(items, new Choice("doubleTapAction", R.string.ZephyrDoubleTapIncoming, doubleTapOptions));
        addChoice(items, new Choice("doubleTapActionOutOwner", R.string.ZephyrDoubleTapOutgoing, doubleTapOptions));
        addChoice(items, new Choice("sendPhotosQuality", R.string.ZephyrPhotoQuality, "800", "1280", "2560"));
        addChoice(items, new Choice("doubleTapSeekDuration", R.string.ZephyrRewindStep,
                LocaleController.formatString(R.string.ZephyrSecondsShort, 5),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 10),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 15),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 30)));
        // Batch 3: message menu, admin shortcuts, channel button, service messages, round video camera.
        items.add(UItem.asShadow(null));
        addHeader(items, R.string.ZephyrUxHeaderMessageMenu);
        addSwitch(items, "showForwardNoAuthor", R.string.ZephyrMenuForwardNoAuthor);
        addSwitch(items, "showSaveMessage", R.string.ZephyrMenuSaveToSaved);
        addSwitch(items, "showCopyPhoto", R.string.ZephyrMenuCopyPhoto);
        addSwitch(items, "showMessageDetails", R.string.ZephyrMenuDetails);
        items.add(UItem.asShadow(null));
        addHeader(items, R.string.ZephyrUxHeaderChatMenu);
        addSwitch(items, "permissionsShortcut", R.string.ZephyrShortcutPermissions);
        addSwitch(items, "administratorsShortcut", R.string.ZephyrShortcutAdmins);
        addSwitch(items, "membersShortcut", R.string.ZephyrShortcutMembers);
        addSwitch(items, "recentActionsShortcut", R.string.ZephyrShortcutRecentActions);
        items.add(UItem.asShadow(null));
        addHeader(items, R.string.ZephyrUxHeaderChannels);
        addChoice(items, new Choice("bottomButton", R.string.ZephyrBottomButton,
                LocaleController.getString(R.string.ZephyrBottomButtonMute),
                LocaleController.getString(R.string.ZephyrBottomButtonDiscuss)));
        addSwitch(items, "showActionTimestamps", R.string.ZephyrActionTimestamps);
        addSwitch(items, "addCommaAfterMention", R.string.ZephyrCommaAfterMention);
        addSwitch(items, "hideCategories", R.string.ZephyrHideCategories);
        addSwitch(items, "hideCameraTile", R.string.ZephyrHideCameraTile);
        items.add(UItem.asShadow(null));
        addHeader(items, R.string.ZephyrUxHeaderRoundVideo);
        addChoice(items, new Choice("videoMessagesCamera", R.string.ZephyrVideoMessagesCamera,
                LocaleController.getString(R.string.ZephyrCameraDefault),
                LocaleController.getString(R.string.ZephyrCameraFront),
                LocaleController.getString(R.string.ZephyrCameraRear),
                LocaleController.getString(R.string.ZephyrCameraLast)));
        addSwitch(items, "staticZoom", R.string.ZephyrStaticZoom);
        items.add(UItem.asShadow(null));
    }

}
