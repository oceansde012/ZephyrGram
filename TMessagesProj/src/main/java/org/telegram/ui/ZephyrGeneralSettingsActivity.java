/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import android.app.Activity;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrGeneralSettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_SYSTEM_EMOJI = 9001;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsGeneral);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addHeader(items, R.string.ZephyrUxHeaderDisplay);
        addSwitch(items, "disableNumberRounding", R.string.ZephyrNumberRoundingOff, R.string.ZephyrUxNumberRoundingInfo);
        addSwitch(items, "formatTimeWithSeconds", R.string.ZephyrTimeWithSeconds);
        addSwitch(items, "hidePhoneNumber", R.string.ZephyrHidePhoneNumber, R.string.ZephyrUxHidePhoneInfo);
        items.add(UItem.asShadow(null));

        // Batch 4: profile / archive / system / look (exteraGram-derived, GPLv2)
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrProfileHeader)));
        addChoice(items, new Choice("showIdAndDc", R.string.ZephyrShowIdAndDc,
                LocaleController.getString(R.string.ZephyrShowIdHide),
                LocaleController.getString(R.string.ZephyrShowIdOnly),
                LocaleController.getString(R.string.ZephyrShowIdAndDcBoth)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrArchiveHeader)));
        addSwitch(items, "archiveOnPull", R.string.ZephyrArchiveOnPull, R.string.ZephyrUxArchivePullInfo);
        addSwitch(items, "disableUnarchiveSwipe", R.string.ZephyrDisableUnarchiveSwipe, R.string.ZephyrUxUnarchiveSwipeInfo);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrLookHeader)));
        addSwitch(items, "useSystemFonts", R.string.ZephyrUseSystemFonts);
        addSwitch(items, "newSwitchStyle", R.string.ZephyrNewSwitchStyle);
        addSwitch(items, "disableDividers", R.string.ZephyrDisableDividers);
        addSwitch(items, "hideActionBarStatus", R.string.ZephyrHideActionBarStatus);
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrSystemHeader)));
        addChoice(items, new Choice("tabletMode", R.string.ZephyrTabletMode,
                LocaleController.getString(R.string.ZephyrTabletAuto),
                LocaleController.getString(R.string.ZephyrTabletOn),
                LocaleController.getString(R.string.ZephyrTabletOff)));
        items.add(UItem.asCheck(ID_SYSTEM_EMOJI, LocaleController.getString(R.string.ZephyrUseSystemEmoji)).setChecked(SharedConfig.useSystemEmoji));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRestartRequired)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        super.onClick(item, view, position, x, y);
        if ("disableDividers".equals(item.object)) {
            Theme.applyCommonTheme();
        }
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        if (item.id == ID_SYSTEM_EMOJI) {
            final boolean value = !SharedConfig.useSystemEmoji;
            SharedConfig.useSystemEmoji = value;
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).edit().putBoolean("useSystemEmoji", value).apply();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        }
    }

}
