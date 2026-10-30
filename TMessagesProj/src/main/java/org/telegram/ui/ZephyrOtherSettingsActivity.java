/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.messenger.zephyr.ZephyrSavedMessages;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrOtherSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsOther);
    }

    private long[] historyStats;

    @Override
    public android.view.View createView(android.content.Context context) {
        android.view.View view = super.createView(context);
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
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrHistoryStorageHeader)));
        items.add(UItem.asCheck(ID_SAVE_DELETED, LocaleController.getString(R.string.ZephyrSaveDeleted)).setChecked(ZephyrConfig.saveDeleted));
        items.add(UItem.asCheck(ID_SAVE_EDITED, LocaleController.getString(R.string.ZephyrSaveEdited)).setChecked(ZephyrConfig.saveEdited));
        items.add(UItem.asButton(ID_HISTORY_STATS, LocaleController.getString(R.string.ZephyrHistoryClear), historyStats == null
                ? LocaleController.getString(R.string.ZephyrHistoryLoading)
                : LocaleController.formatString(R.string.ZephyrHistoryStats, historyStats[0], historyStats[1], AndroidUtilities.formatFileSize(historyStats[2])).replace('\n', ' ')));
        items.add(UItem.asShadow(null));
        items.add(UItem.asButton(ID_FILTERS, LocaleController.getString(R.string.ZephyrMessageFilters)));
        items.add(UItem.asCheck(ID_LOGS, LocaleController.getString(R.string.ZephyrEnableLogs)).setChecked(org.telegram.messenger.BuildVars.LOGS_ENABLED));
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            items.add(UItem.asButton(ID_SHARE_LOGS, LocaleController.getString(R.string.ZephyrShareLogs)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrLogsInfo)));
        items.add(UItem.asButton(ID_PUSH, LocaleController.getString(R.string.ZephyrPushTitle), org.telegram.messenger.zephyr.ZephyrPush.isServiceEnabled() ? LocaleController.getString(R.string.ZephyrPushOn) : LocaleController.getString(R.string.ZephyrPushOff)));
        items.add(UItem.asButton(ID_RESET, LocaleController.getString(R.string.ZephyrResetSettings)));
        items.add(UItem.asButton(ID_CREDITS, LocaleController.getString(R.string.ZephyrCredits)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrCreditsText)));
    }

    private static final int ID_FILTERS = 6;
    private static final int ID_LOGS = 9101;
    private static final int ID_RESET = 1;
    private static final int ID_CREDITS = 2;
    private static final int ID_SAVE_DELETED = 3;
    private static final int ID_SAVE_EDITED = 4;
    private static final int ID_HISTORY_STATS = 5;
    private static final int ID_PUSH = 9102;
    private static final int ID_SHARE_LOGS = 9103;

    @Override
    protected void onCustomClick(UItem item, android.view.View view) {
        if (item.id == ID_FILTERS) {
            presentFragment(new ZephyrFiltersActivity());
        } else if (item.id == ID_LOGS) {
            // exteraGram-derived logging toggle (GPLv2)
            org.telegram.messenger.BuildVars.LOGS_ENABLED = !org.telegram.messenger.BuildVars.LOGS_ENABLED;
            org.telegram.messenger.ApplicationLoader.applicationContext.getSharedPreferences("systemConfig", android.content.Context.MODE_PRIVATE)
                    .edit().putBoolean("logsEnabled", org.telegram.messenger.BuildVars.LOGS_ENABLED).commit();
            if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(org.telegram.messenger.BuildVars.LOGS_ENABLED);
            }
            item.checked = org.telegram.messenger.BuildVars.LOGS_ENABLED;
            if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
                org.telegram.messenger.FileLog.d("ZephyrGram logging enabled by user, version=" + org.telegram.messenger.BuildVars.BUILD_VERSION_STRING + " zephyr=" + org.telegram.messenger.BuildConfig.ZEPHYR_VERSION);
            }
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
        } else if (item.id == ID_SHARE_LOGS) {
            org.telegram.ui.ProfileActivity.sendLogs(getParentActivity(), false);        } else if (item.id == ID_PUSH) {
            presentFragment(new ZephyrPushSettingsActivity());
        } else if (item.id == ID_RESET) {
            confirmReset(this, null);
        } else if (item.id == ID_CREDITS) {
            showCredits(this);
        } else if (item.id == ID_SAVE_DELETED || item.id == ID_SAVE_EDITED) {
            boolean value;
            if (item.id == ID_SAVE_DELETED) {
                value = !ZephyrConfig.saveDeleted;
                ZephyrConfig.setSaveDeleted(value);
            } else {
                value = !ZephyrConfig.saveEdited;
                ZephyrConfig.setSaveEdited(value);
            }
            if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(value);
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
