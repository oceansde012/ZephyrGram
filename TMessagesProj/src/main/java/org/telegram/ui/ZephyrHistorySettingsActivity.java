package org.telegram.ui;

import android.content.Context;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.messenger.zephyr.ZephyrSavedMessages;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * Message history: keeping deleted / edited messages and the local storage of that history
 * (moved here from the top of the ZephyrGram screen and from "Advanced"; same ZephyrConfig keys).
 */
public class ZephyrHistorySettingsActivity extends ZephyrBaseSettingsActivity {

    private static final int ID_SAVE_DELETED = 1;
    private static final int ID_SAVE_EDITED = 2;
    private static final int ID_HISTORY_STATS = 3;

    private long[] historyStats;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrNavHistory);
    }

    @Override
    public View createView(Context context) {
        View view = super.createView(context);
        loadHistoryStats();
        return view;
    }

    private void loadHistoryStats() {
        Utilities.globalQueue.postRunnable(() -> {
            final long[] stats = ZephyrSavedMessages.getInstance().getStats();
            AndroidUtilities.runOnUIThread(() -> {
                historyStats = stats;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUxHistoryHeader)));
        items.add(UItem.asCheck(ID_SAVE_DELETED, LocaleController.getString(R.string.ZephyrSaveDeleted)).setChecked(ZephyrConfig.saveDeleted));
        items.get(items.size() - 1).subtext = LocaleController.getString(R.string.ZephyrUxSaveDeletedInfo);
        items.add(UItem.asCheck(ID_SAVE_EDITED, LocaleController.getString(R.string.ZephyrSaveEdited)).setChecked(ZephyrConfig.saveEdited));
        items.get(items.size() - 1).subtext = LocaleController.getString(R.string.ZephyrUxSaveEditedInfo);
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrHistoryStorageHeader)));
        items.add(UItem.asButton(ID_HISTORY_STATS, LocaleController.getString(R.string.ZephyrHistoryClear), historyStats == null
                ? LocaleController.getString(R.string.ZephyrHistoryLoading)
                : LocaleController.formatString(R.string.ZephyrHistoryStats, historyStats[0], historyStats[1], AndroidUtilities.formatFileSize(historyStats[2])).replace('\n', ' ')));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrNavHistoryInfo)));
    }

    @Override
    protected void onCustomClick(UItem item, View view) {
        if (item.id == ID_SAVE_DELETED || item.id == ID_SAVE_EDITED) {
            final boolean value;
            if (item.id == ID_SAVE_DELETED) {
                value = !ZephyrConfig.saveDeleted;
                ZephyrConfig.setSaveDeleted(value);
            } else {
                value = !ZephyrConfig.saveEdited;
                ZephyrConfig.setSaveEdited(value);
            }
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        } else if (item.id == ID_HISTORY_STATS) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.ZephyrHistoryClear));
            builder.setMessage(LocaleController.getString(R.string.ZephyrHistoryClearConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> Utilities.globalQueue.postRunnable(() -> {
                ZephyrSavedMessages.getInstance().clearAll();
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrHistoryCleared)).show();
                    loadHistoryStats();
                });
            }));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        }
    }
}
