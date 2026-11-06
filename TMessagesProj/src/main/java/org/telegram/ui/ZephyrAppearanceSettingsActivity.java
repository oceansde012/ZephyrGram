/* 0.2.8 redesign: Appearance (absorbs the interface items of the former General screen). Keys unchanged. */
package org.telegram.ui;

import android.app.Activity;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrAppearanceSettingsActivity extends ZephyrBaseSettingsActivity {
    private static final int ID_SYSTEM_EMOJI = 9001;

    private ZephyrPreviews.TabStyles tabs;
    private ZephyrPreviews.Fab fab;
    private ZephyrPreviews.Avatars avatars;

    private void refreshChoice() {
        if (listView != null && listView.adapter != null) listView.adapter.update(false);
    }

    @Override
    protected void onChoiceChanged() {
        if (tabs != null) tabs.refresh();
        if (fab != null) fab.refresh();
    }

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsAppearance);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (tabs == null) { tabs = new ZephyrPreviews.TabStyles(getParentActivity()); tabs.onChange = () -> refreshChoice(); }
        if (fab == null) { fab = new ZephyrPreviews.Fab(getParentActivity()); fab.onChange = () -> refreshChoice(); }
        if (avatars == null) avatars = new ZephyrPreviews.Avatars(getParentActivity());
        addHeader(items, R.string.ZephyrRdFolderTabs);
        addPreview(items, tabs, 3 * 92 + 16);
        addChoice(items, new Choice("tabStyle", R.string.ZephyrRdTabStyle,
                LocaleController.getString(R.string.ZephyrTabStylePill),
                LocaleController.getString(R.string.ZephyrTabStyleRounded),
                LocaleController.getString(R.string.ZephyrTabStyleUnderline),
                LocaleController.getString(R.string.ZephyrTabStyleNone),
                LocaleController.getString(R.string.ZephyrTabStyleFilled)));
        addSwitch(items, "tabCounter", R.string.ZephyrRdUnreadCounter);

        addHeader(items, R.string.ZephyrRdNewChatButton);
        addPreview(items, fab, 96);
        addChoice(items, new Choice("fabShape", R.string.ZephyrFabShape,
                LocaleController.getString(R.string.ZephyrFabCircle),
                LocaleController.getString(R.string.ZephyrFabSquare)));

        addHeader(items, R.string.ZephyrRdAvatars);
        addPreview(items, avatars, 84);
        addSlider(items, "avatarCorners", R.string.ZephyrAvatarCorners, 0, 100, () -> avatars.refresh());

        addHeader(items, R.string.ZephyrRdMainScreen);
        addChoice(items, new Choice("titleText", R.string.ZephyrMainTitle,
                LocaleController.getString(R.string.ZephyrMainTitleAppName),
                LocaleController.getString(R.string.ZephyrMainTitleUsername),
                LocaleController.getString(R.string.ZephyrMainTitleName)));
        addSwitch(items, "centerTitle", R.string.ZephyrCenterTitle);

        addHeader(items, R.string.ZephyrRdInterface);
        addSwitch(items, "useSystemFonts", R.string.ZephyrRdSystemFont);
        items.add(UItem.asCheck(ID_SYSTEM_EMOJI, LocaleController.getString(R.string.ZephyrRdSystemEmoji)).setChecked(SharedConfig.useSystemEmoji));
        addSwitch(items, "newSwitchStyle", R.string.ZephyrRdNewSwitch);
        addInvertedSwitch(items, "disableDividers", R.string.ZephyrRdDividers, 0);
        addInvertedSwitch(items, "hideActionBarStatus", R.string.ZephyrRdStatusEmoji, 0);
        addInvertedSwitch(items, "disableNumberRounding", R.string.ZephyrRdExactNumbers, R.string.ZephyrRdExactNumbersInfo);
        addSwitch(items, "formatTimeWithSeconds", R.string.ZephyrRdSeconds);
        addChoice(items, new Choice("tabletMode", R.string.ZephyrTabletMode,
                LocaleController.getString(R.string.ZephyrTabletAuto),
                LocaleController.getString(R.string.ZephyrTabletOn),
                LocaleController.getString(R.string.ZephyrTabletOff)));
        addSwitch(items, "settingsCards", R.string.ZephyrSettingsCards);

        addHeader(items, R.string.ZephyrRdEffects);
        addChoice(items, new Choice("holidayMode", R.string.ZephyrRdHoliday,
                LocaleController.getString(R.string.ZephyrHolidayAuto),
                LocaleController.getString(R.string.ZephyrHolidayAlways),
                LocaleController.getString(R.string.ZephyrHolidayNever)));
        addSwitch(items, "forceSnow", R.string.ZephyrRdSnow);
        addSwitch(items, "forceBlur", R.string.ZephyrRdBlur);
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRestartToApply)));
    }

    @Override
    protected void onClick(UItem item, View view, int position, float x, float y) {
        super.onClick(item, view, position, x, y);
        if (item.object instanceof Inverted && "disableDividers".equals(((Inverted) item.object).key)) {
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
