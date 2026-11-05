/*
 * Diagnostics screen (0.2.8): the logging block of the former "Other" screen, nothing removed.
 */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrDiagnosticsActivity extends ZephyrBaseSettingsActivity {
    private static final int ID_LOGS = 9101;
    private static final int ID_SHARE_LOGS = 9103;
    private static final int ID_FREEZE_LOG = 9104;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrUxHeaderDiagnostics);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUxHeaderDiagnostics)));
        items.add(UItem.asCheck(ID_LOGS, LocaleController.getString(R.string.ZephyrEnableLogs)).setChecked(org.telegram.messenger.BuildVars.LOGS_ENABLED));
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            items.add(UItem.asButton(ID_SHARE_LOGS, LocaleController.getString(R.string.ZephyrShareLogs)));
        }
        // Always available: the freeze journal is written even with logging off.
        items.add(UItem.asButton(ID_FREEZE_LOG, LocaleController.getString(R.string.ZephyrCopyFreezeLog)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrLogsInfo)));
    }

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
            org.telegram.ui.ProfileActivity.sendLogs(getParentActivity(), false);
        } else if (item.id == ID_FREEZE_LOG) {
            String journal = org.telegram.messenger.zephyr.plugins.MainThreadWatchdog.readJournal();
            if (journal.trim().isEmpty()) {
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.info, LocaleController.getString(R.string.ZephyrFreezeLogEmpty)).show();
            } else {
                org.telegram.messenger.AndroidUtilities.addToClipboard(journal);
                org.telegram.ui.Components.BulletinFactory.of(this).createSimpleBulletin(R.raw.copy, LocaleController.getString(R.string.ZephyrFreezeLogCopied)).show();
            }
                }
    }
}
