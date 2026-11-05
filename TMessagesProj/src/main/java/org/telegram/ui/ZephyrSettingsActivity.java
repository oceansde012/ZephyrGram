package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.messenger.zephyr.ZephyrUpdater;
import org.telegram.messenger.zephyr.plugins.PluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/**
 * ZephyrGram settings, top level: a short list of categories. Every row opens its own screen
 * (plugins, auto-reply, ghost mode, message history, app updates, the settings sections),
 * the subtitle shows its current state. All settings keep their ZephyrConfig keys.
 */
public class ZephyrSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_SHADOW = 3;
    private static final int TYPE_SETTINGS = 4;
    private static final int TYPE_TILES = 5;

    // 0.2.8 layout: tiles, then cards with a header above each.
    private static final int ROW_TILES = 0;
    private static final int ROW_TILES_INFO = 1;
    private static final int ROW_UI_HEADER = 2;
    private static final int ROW_APPEARANCE = 3;
    private static final int ROW_CHATS = 4;
    private static final int ROW_MEDIA = 5;
    private static final int ROW_PRIVACY_HEADER = 6;
    private static final int ROW_PRIVACY = 7;
    private static final int ROW_HISTORY = 8;
    private static final int ROW_FILTERS = 9;
    private static final int ROW_EXT_HEADER = 10;
    private static final int ROW_PLUGINS = 11;
    private static final int ROW_AUTOREPLY = 12;
    private static final int ROW_ASSISTANT = 13;
    private static final int ROW_SYSTEM_HEADER = 14;
    private static final int ROW_PUSH = 15;
    private static final int ROW_UPDATES = 16;
    private static final int ROW_DIAGNOSTICS = 17;
    private static final int ROW_GAP = 18;
    private static final int ROW_CREDITS = 19;
    private static final int ROW_RESET = 20;
    private static final int ROW_CREDITS_INFO = 21;
    private static final int ROW_COUNT = 22;

    private RecyclerListView listView;
    private ListAdapter adapter;

    @Override
    public View createView(Context context) {
        ZephyrConfig.load();
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.ZephyrSettings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout frameLayout = new FrameLayout(context);
        frameLayout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = frameLayout;

        listView = new RecyclerListView(context);
        ZephyrCards.enable(listView, true);
        actionBar.setAdaptiveBackground(listView);
        listView.setLayoutManager(new LinearLayoutManager(context));
        listView.setVerticalScrollBarEnabled(false);
        listView.setAdapter(adapter = new ListAdapter(context));
        listView.setOnItemClickListener((view, position) -> onRowClicked(position));
        frameLayout.addView(listView, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT
        ));
        return fragmentView;
    }

    private static boolean isLinkRow(int position) {
        return position == ROW_APPEARANCE || position == ROW_CHATS || position == ROW_MEDIA
                || position == ROW_PRIVACY || position == ROW_HISTORY || position == ROW_FILTERS
                || position == ROW_PLUGINS || position == ROW_AUTOREPLY || position == ROW_ASSISTANT
                || position == ROW_PUSH || position == ROW_UPDATES || position == ROW_DIAGNOSTICS
                || position == ROW_CREDITS || position == ROW_RESET;
    }

    private void onRowClicked(int position) {
        if (position == ROW_APPEARANCE) {
            presentFragment(new ZephyrAppearanceSettingsActivity());
        } else if (position == ROW_CHATS) {
            presentFragment(new ZephyrChatsSettingsActivity());
        } else if (position == ROW_MEDIA) {
            presentFragment(new ZephyrMediaSettingsActivity());
        } else if (position == ROW_PRIVACY) {
            presentFragment(new ZephyrPrivacySettingsActivity());
        } else if (position == ROW_HISTORY) {
            presentFragment(new ZephyrHistorySettingsActivity());
        } else if (position == ROW_FILTERS) {
            presentFragment(new ZephyrFiltersActivity());
        } else if (position == ROW_PLUGINS) {
            presentFragment(new ZephyrPluginsActivity());
        } else if (position == ROW_AUTOREPLY) {
            presentFragment(new ZephyrAutoReplyChatsActivity());
        } else if (position == ROW_ASSISTANT) {
            presentFragment(new ZephyrAssistantSettingsActivity());
        } else if (position == ROW_PUSH) {
            presentFragment(new ZephyrPushSettingsActivity());
        } else if (position == ROW_UPDATES) {
            presentFragment(new ZephyrUpdatesSettingsActivity());
        } else if (position == ROW_DIAGNOSTICS) {
            presentFragment(new ZephyrDiagnosticsActivity());
        } else if (position == ROW_RESET) {
            ZephyrBaseSettingsActivity.confirmReset(this, null);
        } else if (position == ROW_CREDITS) {
            ZephyrBaseSettingsActivity.showCredits(this);
        }
    }

    private static int linkTitle(int position) {
        switch (position) {
            case ROW_APPEARANCE: return R.string.ZephyrSettingsAppearance;
            case ROW_CHATS: return R.string.ZephyrSettingsChats;
            case ROW_MEDIA: return R.string.ZephyrNavMedia;
            case ROW_PRIVACY: return R.string.ZephyrNavPrivacy;
            case ROW_HISTORY: return R.string.ZephyrNavHistory;
            case ROW_FILTERS: return R.string.ZephyrMessageFilters;
            case ROW_PLUGINS: return R.string.ZephyrPlugins;
            case ROW_AUTOREPLY: return R.string.ZephyrArTitle;
            case ROW_ASSISTANT: return R.string.ZephyrAi;
            case ROW_PUSH: return R.string.ZephyrPushTitle;
            case ROW_UPDATES: return R.string.ZephyrNavUpdates;
            case ROW_DIAGNOSTICS: return R.string.ZephyrUxHeaderDiagnostics;
            case ROW_RESET: return R.string.ZephyrResetSettings;
            default: return R.string.ZephyrCredits;
        }
    }

    private static String linkSubtitle(int position) {
        switch (position) {
            case ROW_APPEARANCE: return LocaleController.getString(R.string.ZephyrNavAppearanceInfo);
            case ROW_CHATS: return LocaleController.getString(R.string.ZephyrNavChatsInfo);
            case ROW_MEDIA: return LocaleController.getString(R.string.ZephyrNavMediaInfo);
            case ROW_PRIVACY: {
                int idDc = ZephyrConfig.showIdAndDc;
                String id = LocaleController.getString(idDc == 2 ? R.string.ZephyrNavIdAndDc : idDc == 1 ? R.string.ZephyrNavIdOnly : R.string.ZephyrNavIdHidden);
                return LocaleController.formatString(R.string.ZephyrNavPrivacyGhostOn, LocaleController.getString(ghostOn() ? R.string.ZephyrNavGhostStateOn : R.string.ZephyrNavGhostStateOff), id);
            }
            case ROW_HISTORY: return LocaleController.formatString(R.string.ZephyrNavHistoryState, onOff(ZephyrConfig.saveDeleted), onOff(ZephyrConfig.saveEdited));
            case ROW_FILTERS: return LocaleController.formatString(R.string.ZephyrNavFiltersState, filtersCount());
            case ROW_PLUGINS: return pluginsSubtitle();
            case ROW_AUTOREPLY: return autoReplySubtitle();
            case ROW_ASSISTANT: return LocaleController.getString(R.string.ZephyrNavAssistantInfo);
            case ROW_PUSH: return onOff(org.telegram.messenger.zephyr.ZephyrPush.isServiceEnabled());
            case ROW_UPDATES: return LocaleController.formatString(R.string.ZephyrNavUpdatesState, ZephyrUpdater.currentVersion(), onOff(ZephyrConfig.autoUpdateCheck));
            case ROW_DIAGNOSTICS: return LocaleController.getString(org.telegram.messenger.BuildVars.LOGS_ENABLED ? R.string.ZephyrNavLogOn : R.string.ZephyrNavLogOff);
            default: return "";
        }
    }

    private static int linkIcon(int position) {
        switch (position) {
            case ROW_APPEARANCE: return R.drawable.msg_theme;
            case ROW_CHATS: return R.drawable.msg_discussion;
            case ROW_MEDIA: return R.drawable.msg_media;
            case ROW_PRIVACY: return R.drawable.msg_secret;
            case ROW_HISTORY: return R.drawable.msg_recent;
            case ROW_FILTERS: return R.drawable.msg_block;
            case ROW_PLUGINS: return R.drawable.msg_instant;
            case ROW_AUTOREPLY: return R.drawable.msg_reply_small;
            case ROW_ASSISTANT: return R.drawable.msg_bot;
            case ROW_PUSH: return R.drawable.msg_customize;
            case ROW_UPDATES: return R.drawable.msg_download;
            case ROW_DIAGNOSTICS: return R.drawable.msg_log;
            case ROW_RESET: return R.drawable.msg_reset;
            default: return R.drawable.msg_info;
        }
    }

    /** No divider under the last row of a card. */
    private static boolean linkDivider(int position) {
        return position != ROW_MEDIA && position != ROW_FILTERS && position != ROW_ASSISTANT
                && position != ROW_DIAGNOSTICS && position != ROW_RESET;
    }

    private static boolean ghostOn() {
        return ZephyrConfig.ghostNoRead || ZephyrConfig.ghostNoTyping || ZephyrConfig.ghostAlwaysOffline || ZephyrConfig.ghostNoStoryViews;
    }

    private static int filtersCount() {
        try {
            org.telegram.messenger.zephyr.ZephyrMessageFilters.load();
            return org.telegram.messenger.zephyr.ZephyrMessageFilters.getAll().size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private static String onOff(boolean value) {
        return LocaleController.getString(value ? R.string.ZephyrPushOn : R.string.ZephyrPushOff);
    }

    private static String ghostSubtitle() {
        int n = 0;
        if (ZephyrConfig.ghostNoRead) n++;
        if (ZephyrConfig.ghostNoTyping) n++;
        if (ZephyrConfig.ghostAlwaysOffline) n++;
        if (ZephyrConfig.ghostNoStoryViews) n++;
        return n == 0 ? LocaleController.getString(R.string.ZephyrNavGhostOff)
                : LocaleController.formatString(R.string.ZephyrNavGhostOn, n, 4);
    }

    private static String pluginsSubtitle() {
        try {
            final java.util.List<PluginManager.InstalledPlugin> plugins = PluginManager.INSTANCE.getInstalled();
            if (plugins == null || plugins.isEmpty()) {
                return LocaleController.getString(R.string.ZephyrUxPluginsInfo);
            }
            int on = 0;
            for (PluginManager.InstalledPlugin plugin : plugins) {
                if (plugin != null && plugin.enabled) on++;
            }
            return LocaleController.formatString(R.string.ZephyrNavPluginsState, plugins.size(), on);
        } catch (Throwable t) {
            return LocaleController.getString(R.string.ZephyrUxPluginsInfo);
        }
    }

    private static String autoReplySubtitle() {
        try {
            if (!org.telegram.messenger.zephyr.ZephyrAutoReplyStore.isInstalledAndEnabled()) {
                return LocaleController.getString(PluginManager.INSTANCE.getInstalled(
                        org.telegram.messenger.zephyr.ZephyrAutoReplyStore.PLUGIN_ID) == null
                        ? R.string.ZephyrArHubNoPlugin : R.string.ZephyrArHubPluginOff);
            }
            int n = org.telegram.messenger.zephyr.ZephyrAutoReplyStore.countActive();
            if (!org.telegram.messenger.zephyr.ZephyrAutoReplyStore.isMaster()) {
                return LocaleController.formatString(R.string.ZephyrArHubMasterOff, n);
            }
            return n == 0 ? LocaleController.getString(R.string.ZephyrArHubNone)
                    : LocaleController.formatString(R.string.ZephyrArHubCount, n);
        } catch (Throwable t) {
            return "";
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // A sub-screen may have changed a state shown in the subtitles.
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** Three tiles: tap toggles, long press opens the settings of that feature. */
    private final class TilesView extends android.widget.LinearLayout {
        private final android.widget.TextView[] states = new android.widget.TextView[3];
        private final android.widget.TextView[] titles = new android.widget.TextView[3];
        private final android.widget.ImageView[] icons = new android.widget.ImageView[3];
        private final View[] tiles = new View[3];

        TilesView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            int margin = org.telegram.messenger.AndroidUtilities.dp(ZephyrCards.MARGIN_DP);
            setPadding(margin, org.telegram.messenger.AndroidUtilities.dp(4), margin, 0);
            int[] titleRes = {R.string.ZephyrGhostMode, R.string.ZephyrDisableAdsTile, R.string.ZephyrLocalPremium};
            int[] iconRes = {R.drawable.zephyr_ghost, R.drawable.msg_block, R.drawable.msg_settings};
            for (int i = 0; i < 3; i++) {
                final int index = i;
                android.widget.LinearLayout tile = new android.widget.LinearLayout(context);
                tile.setOrientation(VERTICAL);
                tile.setGravity(android.view.Gravity.BOTTOM);
                int pad = org.telegram.messenger.AndroidUtilities.dp(14);
                tile.setPadding(pad, pad, pad, pad - 1);
                icons[i] = new android.widget.ImageView(context);
                icons[i].setImageResource(iconRes[i]);
                tile.addView(icons[i], LayoutHelper.createLinear(24, 24, 0, 0, 0, 0, 0));
                android.view.View spacer = new View(context);
                tile.addView(spacer, LayoutHelper.createLinear(1, 0, 1f));
                titles[i] = new android.widget.TextView(context);
                titles[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15);
                titles[i].setTypeface(org.telegram.messenger.AndroidUtilities.bold());
                titles[i].setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                titles[i].setText(LocaleController.getString(titleRes[i]));
                tile.addView(titles[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                states[i] = new android.widget.TextView(context);
                states[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
                tile.addView(states[i], LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
                tile.setOnClickListener(v -> toggle(index));
                tile.setOnLongClickListener(v -> { openSettings(index); return true; });
                tiles[i] = tile;
                addView(tile, LayoutHelper.createLinear(0, 116, 1f, 0, 0, i < 2 ? 8 : 0, 0));
            }
            refresh();
        }

        void refresh() {
            boolean[] on = {ghostOn(), ZephyrConfig.disableAds, ZephyrConfig.localPremium};
            String[] text = {
                    ghostOn() ? LocaleController.formatString(R.string.ZephyrNavGhostOn, ghostCount(), 4) : LocaleController.getString(R.string.ZephyrNavGhostOff),
                    LocaleController.getString(ZephyrConfig.disableAds ? R.string.ZephyrTileOnN : R.string.ZephyrTileOffN),
                    LocaleController.getString(ZephyrConfig.localPremium ? R.string.ZephyrTileOnM : R.string.ZephyrTileOffM)};
            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            int card = Theme.getColor(Theme.key_windowBackgroundWhite);
            int secondary = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
            for (int i = 0; i < 3; i++) {
                int bg = on[i] ? androidx.core.graphics.ColorUtils.compositeColors(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x33), card) : card;
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setColor(bg);
                shape.setCornerRadius(org.telegram.messenger.AndroidUtilities.dp(ZephyrCards.CARD_RADIUS_DP));
                tiles[i].setBackground(new android.graphics.drawable.RippleDrawable(
                        android.content.res.ColorStateList.valueOf(Theme.getColor(Theme.key_listSelector)), shape, null));
                icons[i].setColorFilter(new android.graphics.PorterDuffColorFilter(on[i] ? accent : secondary, android.graphics.PorterDuff.Mode.MULTIPLY));
                states[i].setText(text[i]);
                states[i].setTextColor(on[i] ? accent : secondary);
            }
        }

        private int ghostCount() {
            int n = 0;
            if (ZephyrConfig.ghostNoRead) n++;
            if (ZephyrConfig.ghostNoTyping) n++;
            if (ZephyrConfig.ghostAlwaysOffline) n++;
            if (ZephyrConfig.ghostNoStoryViews) n++;
            return n;
        }

        private void toggle(int index) {
            if (index == 0) {
                // The mode switch of the tile: everything on, or everything off (same keys as the Ghost screen).
                ZephyrConfig.setGhostAll(!ghostOn());
            } else if (index == 1) {
                ZephyrConfig.setDisableAds(!ZephyrConfig.disableAds);
            } else {
                ZephyrConfig.setLocalPremium(!ZephyrConfig.localPremium);
            }
            refresh();
            if (adapter != null) adapter.notifyDataSetChanged();
        }

        private void openSettings(int index) {
            if (index == 0) {
                presentFragment(new ZephyrPrivacySettingsActivity());
            } else {
                org.telegram.ui.Components.BulletinFactory.of(ZephyrSettingsActivity.this).createSimpleBulletin(R.raw.info,
                        LocaleController.getString(index == 1 ? R.string.ZephyrDisableAds : R.string.ZephyrUxLocalPremiumInfo)).show();
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;
        private TilesView tilesView;

        private ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public int getItemCount() {
            return ROW_COUNT;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return isLinkRow(holder.getAdapterPosition());
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == TYPE_TILES) {
                view = tilesView = new TilesView(context);
                view.setTag(RecyclerListView.TAG_NOT_SECTION);
            } else if (viewType == TYPE_SETTINGS) {
                TextDetailSettingsCell detailCell = new TextDetailSettingsCell(context);
                detailCell.setMultilineDetail(true);
                view = detailCell;
            } else {
                view = new TextInfoPrivacyCell(context);
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
            ));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            int type = holder.getItemViewType();
            if (type == TYPE_HEADER) {
                int title = position == ROW_UI_HEADER ? R.string.ZephyrNavHeaderInterface
                        : position == ROW_PRIVACY_HEADER ? R.string.ZephyrNavHeaderPrivacy
                        : position == ROW_EXT_HEADER ? R.string.ZephyrNavHeaderExtensions : R.string.ZephyrNavHeaderSystem;
                ((HeaderCell) holder.itemView).setText(LocaleController.getString(title));
            } else if (type == TYPE_TILES) {
                ((TilesView) holder.itemView).refresh();
            } else if (type == TYPE_SETTINGS) {
                TextDetailSettingsCell cell = (TextDetailSettingsCell) holder.itemView;
                cell.setTextAndValueAndIcon(
                        LocaleController.getString(linkTitle(position)),
                        linkSubtitle(position),
                        linkIcon(position),
                        linkDivider(position));
                cell.setTitleColor(Theme.getColor(position == ROW_RESET ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlackText));
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(
                        position == ROW_TILES_INFO ? R.string.ZephyrNavTilesInfo : R.string.ZephyrCreditsText));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_TILES) {
                return TYPE_TILES;
            }
            if (position == ROW_UI_HEADER || position == ROW_PRIVACY_HEADER || position == ROW_EXT_HEADER || position == ROW_SYSTEM_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_CREDITS_INFO || position == ROW_TILES_INFO) {
                return TYPE_INFO;
            }
            if (position == ROW_GAP) {
                return TYPE_SHADOW;
            }
            return TYPE_SETTINGS;
        }
    }
}
