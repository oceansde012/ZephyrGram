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
        addSwitch(items, "disableJumpToNextChannel", R.string.ZephyrDisableJumpToNextChannel);
        addSwitch(items, "hideKeyboardOnScroll", R.string.ZephyrHideKeyboardOnScroll);
        addSwitch(items, "hideShareButton", R.string.ZephyrHideShareButton);
        addSwitch(items, "hideStickerTime", R.string.ZephyrHideStickerTime);
        addSwitch(items, "unlimitedRecentStickers", R.string.ZephyrUnlimitedRecentStickers);
        addSwitch(items, "pauseOnMinimize", R.string.ZephyrPauseOnMinimize);
        addChoice(items, new Choice("sendPhotosQuality", R.string.ZephyrPhotoQuality, "800", "1280", "2560"));
        addChoice(items, new Choice("doubleTapSeekDuration", R.string.ZephyrRewindStep,
                LocaleController.formatString(R.string.ZephyrSecondsShort, 5),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 10),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 15),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 30)));
        items.add(UItem.asShadow(null));
    }

}
