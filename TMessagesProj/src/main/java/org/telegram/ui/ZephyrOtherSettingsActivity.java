/*
 * Settings screens ported from exteraGram (exteraSquad), GPLv2.
 * Modified for ZephyrGram. Original: github.com/exteraSquad/exteraGram
 */
package org.telegram.ui;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrOtherSettingsActivity extends ZephyrBaseSettingsActivity {

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrSettingsOther);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        // Local Premium and ads (moved here from the top of the ZephyrGram screen; same ZephyrConfig keys).
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUxLocalHeader)));
        items.add(UItem.asCheck(ID_LOCAL_PREMIUM, LocaleController.getString(R.string.ZephyrLocalPremium)).setChecked(ZephyrConfig.localPremium));
        items.get(items.size() - 1).subtext = LocaleController.getString(R.string.ZephyrUxLocalPremiumInfo);
        items.add(UItem.asCheck(ID_DISABLE_ADS, LocaleController.getString(R.string.ZephyrDisableAds)).setChecked(ZephyrConfig.disableAds));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrLocalPremiumInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrFiltersHeader)));
        items.add(UItem.asButton(ID_FILTERS, R.drawable.msg_block, LocaleController.getString(R.string.ZephyrMessageFilters),
                LocaleController.getString(org.telegram.messenger.zephyr.ZephyrMessageFilters.isEnabled() ? R.string.ZephyrPushOn : R.string.ZephyrPushOff)));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUxHeaderDiagnostics)));
        items.add(UItem.asCheck(ID_LOGS, LocaleController.getString(R.string.ZephyrEnableLogs)).setChecked(org.telegram.messenger.BuildVars.LOGS_ENABLED));
        if (org.telegram.messenger.BuildVars.LOGS_ENABLED) {
            items.add(UItem.asButton(ID_SHARE_LOGS, LocaleController.getString(R.string.ZephyrShareLogs)));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrLogsInfo)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.ZephyrUxHeaderBackground)));
        items.add(UItem.asButton(ID_PUSH, LocaleController.getString(R.string.ZephyrPushTitle), org.telegram.messenger.zephyr.ZephyrPush.isServiceEnabled() ? LocaleController.getString(R.string.ZephyrPushOn) : LocaleController.getString(R.string.ZephyrPushOff)));
        items.add(UItem.asButton(ID_RESET, LocaleController.getString(R.string.ZephyrResetSettings)));
        items.add(UItem.asButton(ID_CREDITS, LocaleController.getString(R.string.ZephyrCredits)));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrCreditsText)));
    }

    private static final int ID_FILTERS = 6;
    private static final int ID_LOGS = 9101;
    private static final int ID_RESET = 1;
    private static final int ID_CREDITS = 2;
    private static final int ID_LOCAL_PREMIUM = 3;
    private static final int ID_DISABLE_ADS = 4;
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
        } else if (item.id == ID_LOCAL_PREMIUM || item.id == ID_DISABLE_ADS) {
            final boolean value;
            if (item.id == ID_LOCAL_PREMIUM) {
                value = !ZephyrConfig.localPremium;
                ZephyrConfig.setLocalPremium(value);
            } else {
                value = !ZephyrConfig.disableAds;
                ZephyrConfig.setDisableAds(value);
            }
            if (view instanceof org.telegram.ui.Cells.TextCheckCell) {
                ((org.telegram.ui.Cells.TextCheckCell) view).setChecked(value);
            }
            item.checked = value;
        }
    }
}
