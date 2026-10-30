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
        // Batch 2 variations (exteraGram-derived, GPLv2)
        addSlider(items, "avatarCorners", R.string.ZephyrAvatarCorners, 0, 100);
        addChoice(items, new Choice("tabStyle", R.string.ZephyrTabStyle,
                LocaleController.getString(R.string.ZephyrTabStylePill),
                LocaleController.getString(R.string.ZephyrTabStyleRounded),
                LocaleController.getString(R.string.ZephyrTabStyleUnderline),
                LocaleController.getString(R.string.ZephyrTabStyleNone),
                LocaleController.getString(R.string.ZephyrTabStyleFilled)));
        addChoice(items, new Choice("fabShape", R.string.ZephyrFabShape,
                LocaleController.getString(R.string.ZephyrFabCircle),
                LocaleController.getString(R.string.ZephyrFabSquare)));
        addChoice(items, new Choice("holidayMode", R.string.ZephyrHolidayMode,
                LocaleController.getString(R.string.ZephyrHolidayAuto),
                LocaleController.getString(R.string.ZephyrHolidayAlways),
                LocaleController.getString(R.string.ZephyrHolidayNever)));
        addSwitch(items, "forceSnow", R.string.ZephyrForceSnow);
        addSwitch(items, "forceBlur", R.string.ZephyrForceBlur);
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRestartToApply)));
    }

}
