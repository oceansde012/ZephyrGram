/* 0.2.8 redesign: Privacy (new screen): ghost mode, profile, message history, filters. Keys unchanged. */
package org.telegram.ui;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.messenger.zephyr.ZephyrSavedMessages;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

public class ZephyrPrivacySettingsActivity extends ZephyrBaseSettingsActivity {
    private static final int ID_CLEAR = 9301;
    private static final int ID_FILTERS = 9302;
    private static final int ID_HISTORY_SCREEN = 9303;

    private long[] historyStats;

    @Override
    protected CharSequence getTitle() {
        return LocaleController.getString(R.string.ZephyrNavPrivacy);
    }

    @Override
    public android.view.View createView(android.content.Context context) {
        android.view.View view = super.createView(context);
        loadStats();
        return view;
    }

    private void loadStats() {
        Utilities.globalQueue.postRunnable(() -> {
            final long[] stats = ZephyrSavedMessages.getInstance().getStats();
            AndroidUtilities.runOnUIThread(() -> {
                historyStats = stats;
                if (listView != null && listView.adapter != null) listView.adapter.update(false);
            });
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        addHeader(items, R.string.ZephyrRdGhostGroup);
        addGroup(items, new Group("ghost", R.string.ZephyrRdGhostGroup,
                new String[]{"ghostNoRead", "ghostNoTyping", "ghostAlwaysOffline", "ghostNoStoryViews"},
                new int[]{R.string.ZephyrGhostNoRead, R.string.ZephyrGhostNoTyping, R.string.ZephyrGhostAlwaysOffline, R.string.ZephyrGhostNoStoryViews}));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ZephyrRdGhostInfo)));

        addHeader(items, R.string.ZephyrRdProfile);
        addChoice(items, new Choice("showIdAndDc", R.string.ZephyrShowIdAndDc,
                LocaleController.getString(R.string.ZephyrShowIdHide),
                LocaleController.getString(R.string.ZephyrShowIdOnly),
                LocaleController.getString(R.string.ZephyrShowIdAndDcBoth)));
        addSwitch(items, "hidePhoneNumber", R.string.ZephyrHidePhoneNumber);

        addHeader(items, R.string.ZephyrRdMessageHistory);
        addSwitchWithInfo(items, "saveDeleted", R.string.ZephyrSaveDeleted, historyStats == null ? null : LocaleController.formatString(R.string.ZephyrRdSavedCount, historyStats[0]));
        addSwitchWithInfo(items, "saveEdited", R.string.ZephyrSaveEdited, historyStats == null ? null : LocaleController.formatString(R.string.ZephyrRdSavedCount, historyStats[1]));
        items.add(UItem.asButton(ID_CLEAR, LocaleController.getString(R.string.ZephyrRdClearSaved)));
        items.add(UItem.asButton(ID_HISTORY_SCREEN, LocaleController.getString(R.string.ZephyrNavHistory)));

        addHeader(items, R.string.ZephyrRdFilters);
        items.add(UItem.asButton(ID_FILTERS, LocaleController.getString(R.string.ZephyrMessageFilters)));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onCustomClick(UItem item, android.view.View view) {
        if (item.id == ID_FILTERS) {
            presentFragment(new ZephyrFiltersActivity());
        } else if (item.id == ID_HISTORY_SCREEN) {
            presentFragment(new ZephyrHistorySettingsActivity());
        } else if (item.id == ID_CLEAR) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle(LocaleController.getString(R.string.ZephyrHistoryClear));
            builder.setMessage(LocaleController.getString(R.string.ZephyrHistoryClearConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.Delete), (d, w) -> Utilities.globalQueue.postRunnable(() -> {
                ZephyrSavedMessages.getInstance().clearAll();
                AndroidUtilities.runOnUIThread(() -> {
                    BulletinFactory.of(this).createSimpleBulletin(R.raw.done, LocaleController.getString(R.string.ZephyrHistoryCleared)).show();
                    loadStats();
                });
            }));
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            showDialog(builder.create());
        }
    }
}
