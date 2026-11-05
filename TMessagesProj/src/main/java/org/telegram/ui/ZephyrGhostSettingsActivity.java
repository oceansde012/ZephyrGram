package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** Ghost mode switches (moved here from the top of the ZephyrGram settings screen; same ZephyrConfig keys). */
public class ZephyrGhostSettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_NO_READ = 1;
    private static final int ID_NO_TYPING = 2;
    private static final int ID_ALWAYS_OFFLINE = 3;
    private static final int ID_NO_STORY_VIEWS = 4;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrGhostMode);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrGhostMode)));
        items.add(UItem.asCheck(ID_NO_READ, LocaleController.getString(R.string.ZephyrGhostNoRead)).setChecked(ZephyrConfig.ghostNoRead));
        items.add(UItem.asCheck(ID_NO_TYPING, LocaleController.getString(R.string.ZephyrGhostNoTyping)).setChecked(ZephyrConfig.ghostNoTyping));
        items.add(UItem.asCheck(ID_ALWAYS_OFFLINE, LocaleController.getString(R.string.ZephyrGhostAlwaysOffline)).setChecked(ZephyrConfig.ghostAlwaysOffline));
        items.add(UItem.asCheck(ID_NO_STORY_VIEWS, LocaleController.getString(R.string.ZephyrGhostNoStoryViews)).setChecked(ZephyrConfig.ghostNoStoryViews));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrGhostModeInfo)));
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        final boolean value;
        if (item.id == ID_NO_READ) {
            value = !ZephyrConfig.ghostNoRead;
            ZephyrConfig.setGhostNoRead(value);
        } else if (item.id == ID_NO_TYPING) {
            value = !ZephyrConfig.ghostNoTyping;
            ZephyrConfig.setGhostNoTyping(value);
        } else if (item.id == ID_ALWAYS_OFFLINE) {
            value = !ZephyrConfig.ghostAlwaysOffline;
            ZephyrConfig.setGhostAlwaysOffline(value);
        } else if (item.id == ID_NO_STORY_VIEWS) {
            value = !ZephyrConfig.ghostNoStoryViews;
            ZephyrConfig.setGhostNoStoryViews(value);
        } else {
            return;
        }
        if (view instanceof TextCheckCell) {
            ((TextCheckCell) view).setChecked(value);
        }
        item.checked = value;
    }
}
