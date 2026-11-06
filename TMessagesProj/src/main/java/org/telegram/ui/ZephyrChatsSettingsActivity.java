/* 0.2.8 redesign: Chats. Keys are unchanged; negative keys are shown inverted. */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrChatsSettingsActivity extends ZephyrBaseSettingsActivity {

    private ZephyrPreviews.Bubbles bubbles;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsChats);
    }

    @Override
    protected void onChoiceChanged() { if (bubbles != null) bubbles.refresh(); }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addHeader(items, R.string.ZephyrRdDoubleTap);
        if (bubbles == null) bubbles = new ZephyrPreviews.Bubbles(getParentActivity());
        addPreview(items, bubbles, 150);
        final CharSequence[] doubleTapOptions = {
                LocaleController.getString(R.string.ZephyrDoubleTapOff),
                LocaleController.getString(R.string.ZephyrDoubleTapReaction),
                LocaleController.getString(R.string.ZephyrDoubleTapReply),
                LocaleController.getString(R.string.ZephyrDoubleTapCopy),
                LocaleController.getString(R.string.ZephyrDoubleTapForward),
                LocaleController.getString(R.string.ZephyrDoubleTapEdit),
                LocaleController.getString(R.string.ZephyrDoubleTapDelete)};
        addChoice(items, new Choice("doubleTapAction", R.string.ZephyrRdByIncoming, doubleTapOptions));
        addChoice(items, new Choice("doubleTapActionOutOwner", R.string.ZephyrRdByOutgoing, doubleTapOptions));

        addHeader(items, R.string.ZephyrRdMessageMenu);
        addGroup(items, new Group("messageMenu", R.string.ZephyrRdExtraItems,
                new String[]{"showForwardNoAuthor", "showSaveMessage", "showCopyPhoto", "showMessageDetails"},
                new int[]{R.string.ZephyrMenuForwardNoAuthor, R.string.ZephyrMenuSaveToSaved, R.string.ZephyrMenuCopyPhoto, R.string.ZephyrMenuDetails}));

        addHeader(items, R.string.ZephyrRdChatMenu);
        addGroup(items, new Group("chatMenu", R.string.ZephyrRdQuickItems,
                new String[]{"permissionsShortcut", "administratorsShortcut", "membersShortcut", "recentActionsShortcut"},
                new int[]{R.string.ZephyrShortcutPermissions, R.string.ZephyrShortcutAdmins, R.string.ZephyrShortcutMembers, R.string.ZephyrShortcutRecentActions}));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRdChatMenuInfo)));

        addHeader(items, R.string.ZephyrRdChannels);
        addChoice(items, new Choice("bottomButton", R.string.ZephyrRdBottomButton,
                LocaleController.getString(R.string.ZephyrBottomButtonMute),
                LocaleController.getString(R.string.ZephyrBottomButtonDiscuss)));
        addInvertedSwitch(items, "disableJumpToNextChannel", R.string.ZephyrRdNextChannel, R.string.ZephyrRdNextChannelInfo);

        addHeader(items, R.string.ZephyrRdInput);
        addSwitch(items, "hideKeyboardOnScroll", R.string.ZephyrRdHideKeyboard);
        addSwitch(items, "addCommaAfterMention", R.string.ZephyrRdCommaAfterMention);
        addInvertedSwitch(items, "hideCategories", R.string.ZephyrRdEmojiCategories, 0);
        addInvertedSwitch(items, "hideShareButton", R.string.ZephyrRdShareButton, 0);
        addInvertedSwitch(items, "hideViaBot", R.string.ZephyrRdViaBot, 0);
        addSwitch(items, "showActionTimestamps", R.string.ZephyrActionTimestamps);

        addHeader(items, R.string.ZephyrRdArchive);
        addSwitch(items, "archiveOnPull", R.string.ZephyrRdArchivePull);
        addInvertedSwitch(items, "disableUnarchiveSwipe", R.string.ZephyrRdUnarchiveSwipe, 0);
        items.add(UItem.asShadow(null));
    }
}
