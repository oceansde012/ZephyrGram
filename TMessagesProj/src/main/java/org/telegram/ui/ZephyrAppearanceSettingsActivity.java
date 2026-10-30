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

public class ZephyrAppearanceSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsAppearance);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addChoice(items, new Choice("titleText", R.string.ZephyrMainTitle,
                LocaleController.getString(R.string.ZephyrMainTitleAppName),
                LocaleController.getString(R.string.ZephyrMainTitleUsername),
                LocaleController.getString(R.string.ZephyrMainTitleName)));
        addSwitch(items, "centerTitle", R.string.ZephyrCenterTitle);
        addSwitch(items, "tabCounter", R.string.ZephyrTabCounter);
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRestartToApply)));
    }

}
