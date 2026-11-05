/* 0.2.8 redesign: Media (new screen). Items come from the former Chats and Appearance screens, keys unchanged. */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrMediaSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrNavMedia);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addHeader(items, R.string.ZephyrRdStickers);
        addSlider(items, "stickerSize", R.string.ZephyrStickerSize, 50, 150);
        addInvertedSwitch(items, "hideStickerTime", R.string.ZephyrRdStickerTime, 0);
        addSwitch(items, "unlimitedRecentStickers", R.string.ZephyrRdUnlimitedRecent);

        addHeader(items, R.string.ZephyrRdPhotos);
        addChoice(items, new Choice("sendPhotosQuality", R.string.ZephyrRdSendQuality, "800", "1280", "2560"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRdSendQualityInfo)));

        addHeader(items, R.string.ZephyrRdVideoMessages);
        addChoice(items, new Choice("videoMessagesCamera", R.string.ZephyrRdCamera,
                LocaleController.getString(R.string.ZephyrCameraDefault),
                LocaleController.getString(R.string.ZephyrCameraFront),
                LocaleController.getString(R.string.ZephyrCameraRear),
                LocaleController.getString(R.string.ZephyrCameraLast)));
        addSwitch(items, "staticZoom", R.string.ZephyrRdStaticZoom);

        addHeader(items, R.string.ZephyrRdPlayer);
        addChoice(items, new Choice("doubleTapSeekDuration", R.string.ZephyrRdSeekStep,
                LocaleController.formatString(R.string.ZephyrSecondsShort, 5),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 10),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 15),
                LocaleController.formatString(R.string.ZephyrSecondsShort, 30)));
        addSwitch(items, "pauseOnMinimize", R.string.ZephyrRdPauseMinimize);

        addHeader(items, R.string.ZephyrRdAttachments);
        addInvertedSwitch(items, "hideCameraTile", R.string.ZephyrRdCameraTile, 0);
        items.add(UItem.asShadow(null));
    }
}
