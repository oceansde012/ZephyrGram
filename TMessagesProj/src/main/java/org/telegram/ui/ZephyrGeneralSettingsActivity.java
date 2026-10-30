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

public class ZephyrGeneralSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsGeneral);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addSwitch(items, "disableNumberRounding", R.string.ZephyrNumberRoundingOff);
        addSwitch(items, "formatTimeWithSeconds", R.string.ZephyrTimeWithSeconds);
        addSwitch(items, "hidePhoneNumber", R.string.ZephyrHidePhoneNumber);
        items.add(UItem.asShadow(null));
    }

}
