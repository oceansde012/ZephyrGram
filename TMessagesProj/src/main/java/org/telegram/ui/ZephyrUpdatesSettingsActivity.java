package org.telegram.ui;

import android.view.View;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.messenger.zephyr.ZephyrUpdater;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/** App updates: automatic check and "check now" (moved here from the ZephyrGram screen; same ZephyrConfig key). */
public class ZephyrUpdatesSettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_AUTO_UPDATE = 1;
    private static final int ID_CHECK_NOW = 2;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrNavUpdates);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUpdates)));
        items.add(UItem.asCheck(ID_AUTO_UPDATE, LocaleController.getString(R.string.ZephyrAutoUpdateCheck)).setChecked(ZephyrConfig.autoUpdateCheck));
        items.add(UItem.asButton(ID_CHECK_NOW, R.drawable.msg_download, LocaleController.getString(R.string.ZephyrCheckForUpdates),
                LocaleController.formatString(R.string.ZephyrUxCurrentVersion, ZephyrUpdater.currentVersion())));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrUpdatesInfo)));
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        if (item.id == ID_AUTO_UPDATE) {
            final boolean value = !ZephyrConfig.autoUpdateCheck;
            ZephyrConfig.setAutoUpdateCheck(value);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        } else if (item.id == ID_CHECK_NOW) {
            ZephyrUpdater.checkManually(this);
        }
    }
}
