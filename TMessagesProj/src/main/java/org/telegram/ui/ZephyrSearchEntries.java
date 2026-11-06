package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;

/** ZephyrGram screens and settings for the stock settings search. Guids 7000+ never clash with Telegram's (<1000). */
public final class ZephyrSearchEntries {
    private ZephyrSearchEntries() {}

    private static void add(ArrayList<ProfileActivity.SearchAdapter.SearchResult> out, int guid, int titleRes, int row, BaseFragment f, java.util.function.Supplier<BaseFragment> screen) {
        final String root = getString(R.string.ZephyrSettings);
        final String title = getString(titleRes);
        out.add(new ProfileActivity.SearchAdapter.SearchResult(guid, title, row == 0 ? null : getString(row), root, R.drawable.msg_settings, () -> f.presentFragment(screen.get())));
    }

    public static ProfileActivity.SearchAdapter.SearchResult[] create(final BaseFragment f) {
        ArrayList<ProfileActivity.SearchAdapter.SearchResult> r = new ArrayList<>();
        add(r, 7000, R.string.ZephyrSettings, 0, f, ZephyrSettingsActivity::new);
        add(r, 7001, R.string.ZephyrSettingsAppearance, 0, f, ZephyrAppearanceSettingsActivity::new);
        add(r, 7002, R.string.ZephyrSettingsChats, 0, f, ZephyrChatsSettingsActivity::new);
        add(r, 7003, R.string.ZephyrNavMedia, 0, f, ZephyrMediaSettingsActivity::new);
        add(r, 7004, R.string.ZephyrNavPrivacy, 0, f, ZephyrPrivacySettingsActivity::new);
        add(r, 7005, R.string.ZephyrGhostMode, 0, f, ZephyrPrivacySettingsActivity::new);
        add(r, 7006, R.string.ZephyrNavHistory, 0, f, ZephyrHistorySettingsActivity::new);
        add(r, 7007, R.string.ZephyrMessageFilters, 0, f, ZephyrFiltersActivity::new);
        add(r, 7008, R.string.ZephyrPlugins, 0, f, ZephyrPluginsActivity::new);
        add(r, 7009, R.string.ZephyrArTitle, 0, f, ZephyrAutoReplyChatsActivity::new);
        add(r, 7010, R.string.ZephyrAi, 0, f, ZephyrAssistantSettingsActivity::new);
        add(r, 7011, R.string.ZephyrPushTitle, 0, f, ZephyrPushSettingsActivity::new);
        add(r, 7012, R.string.ZephyrNavUpdates, 0, f, ZephyrUpdatesSettingsActivity::new);
        add(r, 7013, R.string.ZephyrUxHeaderDiagnostics, 0, f, ZephyrDiagnosticsActivity::new);
        add(r, 7014, R.string.ZephyrRdDoubleTap, R.string.ZephyrSettingsChats, f, ZephyrChatsSettingsActivity::new);
        add(r, 7015, R.string.ZephyrStickerSize, R.string.ZephyrNavMedia, f, ZephyrMediaSettingsActivity::new);
        add(r, 7016, R.string.ZephyrRdTabStyle, R.string.ZephyrSettingsAppearance, f, ZephyrAppearanceSettingsActivity::new);
        add(r, 7017, R.string.ZephyrSaveDeleted, R.string.ZephyrNavPrivacy, f, ZephyrPrivacySettingsActivity::new);
        add(r, 7018, R.string.ZephyrSaveEdited, R.string.ZephyrNavPrivacy, f, ZephyrPrivacySettingsActivity::new);
        add(r, 7019, R.string.ZephyrRdViaBot, R.string.ZephyrSettingsChats, f, ZephyrChatsSettingsActivity::new);
        return r.toArray(new ProfileActivity.SearchAdapter.SearchResult[0]);
    }
}
