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

    // Most used first: plugins, auto-reply, ghost mode, message history; then updates and the settings sections.
    private static final int ROW_PLUGINS = 0;
    private static final int ROW_AUTOREPLY = 1;
    private static final int ROW_GHOST = 2;
    private static final int ROW_HISTORY = 3;
    private static final int ROW_UPDATES = 4;
    private static final int ROW_TOP_SHADOW = 5;
    private static final int ROW_MORE_HEADER = 6;
    private static final int ROW_GENERAL = 7;
    private static final int ROW_APPEARANCE = 8;
    private static final int ROW_CHATS = 9;
    private static final int ROW_OTHER_SCREEN = 10;
    private static final int ROW_MORE_SHADOW = 11;
    private static final int ROW_RESET = 12;
    private static final int ROW_CREDITS = 13;
    private static final int ROW_CREDITS_INFO = 14;
    private static final int ROW_COUNT = 15;

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
        listView.setSections();
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
        return position == ROW_PLUGINS || position == ROW_AUTOREPLY || position == ROW_GHOST || position == ROW_HISTORY
                || position == ROW_UPDATES || position == ROW_GENERAL || position == ROW_APPEARANCE || position == ROW_CHATS
                || position == ROW_OTHER_SCREEN || position == ROW_RESET || position == ROW_CREDITS;
    }

    private void onRowClicked(int position) {
        if (position == ROW_PLUGINS) {
            presentFragment(new ZephyrPluginsActivity());
        } else if (position == ROW_AUTOREPLY) {
            presentFragment(new ZephyrAutoReplyChatsActivity());
        } else if (position == ROW_GHOST) {
            presentFragment(new ZephyrGhostSettingsActivity());
        } else if (position == ROW_HISTORY) {
            presentFragment(new ZephyrHistorySettingsActivity());
        } else if (position == ROW_UPDATES) {
            presentFragment(new ZephyrUpdatesSettingsActivity());
        } else if (position == ROW_GENERAL) {
            presentFragment(new ZephyrGeneralSettingsActivity());
        } else if (position == ROW_APPEARANCE) {
            presentFragment(new ZephyrAppearanceSettingsActivity());
        } else if (position == ROW_CHATS) {
            presentFragment(new ZephyrChatsSettingsActivity());
        } else if (position == ROW_OTHER_SCREEN) {
            presentFragment(new ZephyrOtherSettingsActivity());
        } else if (position == ROW_RESET) {
            ZephyrBaseSettingsActivity.confirmReset(this, null);
        } else if (position == ROW_CREDITS) {
            ZephyrBaseSettingsActivity.showCredits(this);
        }
    }

    private static int linkTitle(int position) {
        if (position == ROW_PLUGINS) {
            return R.string.ZephyrPlugins;
        } else if (position == ROW_AUTOREPLY) {
            return R.string.ZephyrArTitle;
        } else if (position == ROW_GHOST) {
            return R.string.ZephyrGhostMode;
        } else if (position == ROW_HISTORY) {
            return R.string.ZephyrNavHistory;
        } else if (position == ROW_UPDATES) {
            return R.string.ZephyrNavUpdates;
        } else if (position == ROW_GENERAL) {
            return R.string.ZephyrSettingsGeneral;
        } else if (position == ROW_APPEARANCE) {
            return R.string.ZephyrSettingsAppearance;
        } else if (position == ROW_CHATS) {
            return R.string.ZephyrSettingsChats;
        } else if (position == ROW_OTHER_SCREEN) {
            return R.string.ZephyrSettingsOther;
        } else if (position == ROW_RESET) {
            return R.string.ZephyrResetSettings;
        }
        return R.string.ZephyrCredits;
    }

    private static String linkSubtitle(int position) {
        if (position == ROW_PLUGINS) {
            return pluginsSubtitle();
        } else if (position == ROW_AUTOREPLY) {
            return autoReplySubtitle();
        } else if (position == ROW_GHOST) {
            return ghostSubtitle();
        } else if (position == ROW_HISTORY) {
            return LocaleController.formatString(R.string.ZephyrNavHistoryState, onOff(ZephyrConfig.saveDeleted), onOff(ZephyrConfig.saveEdited));
        } else if (position == ROW_UPDATES) {
            return LocaleController.formatString(R.string.ZephyrNavUpdatesState, ZephyrUpdater.currentVersion(), onOff(ZephyrConfig.autoUpdateCheck));
        } else if (position == ROW_GENERAL) {
            return LocaleController.getString(R.string.ZephyrUxGeneralInfo);
        } else if (position == ROW_APPEARANCE) {
            return LocaleController.getString(R.string.ZephyrUxAppearanceInfo);
        } else if (position == ROW_CHATS) {
            return LocaleController.getString(R.string.ZephyrUxChatsInfo);
        } else if (position == ROW_OTHER_SCREEN) {
            return LocaleController.getString(R.string.ZephyrNavAdvancedInfo);
        } else if (position == ROW_RESET) {
            return LocaleController.getString(R.string.ZephyrUxResetInfo);
        }
        return "";
    }

    private static int linkIcon(int position) {
        if (position == ROW_PLUGINS) {
            return R.drawable.msg_instant;
        } else if (position == ROW_AUTOREPLY) {
            return R.drawable.msg_reply_small;
        } else if (position == ROW_GHOST) {
            return R.drawable.zephyr_ghost;
        } else if (position == ROW_HISTORY) {
            return R.drawable.msg_recent;
        } else if (position == ROW_UPDATES) {
            return R.drawable.msg_download;
        } else if (position == ROW_GENERAL) {
            return R.drawable.msg_settings;
        } else if (position == ROW_APPEARANCE) {
            return R.drawable.msg_theme;
        } else if (position == ROW_CHATS) {
            return R.drawable.msg_discussion;
        } else if (position == ROW_OTHER_SCREEN) {
            return R.drawable.msg_customize;
        } else if (position == ROW_RESET) {
            return R.drawable.msg_reset;
        }
        return R.drawable.msg_info;
    }

    /** No divider under the last row of a block. */
    private static boolean linkDivider(int position) {
        return position != ROW_UPDATES && position != ROW_OTHER_SCREEN && position != ROW_CREDITS;
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

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final Context context;

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
                ((HeaderCell) holder.itemView).setText(LocaleController.getString(R.string.ZephyrSettingsMore));
            } else if (type == TYPE_SETTINGS) {
                ((TextDetailSettingsCell) holder.itemView).setTextAndValueAndIcon(
                        LocaleController.getString(linkTitle(position)),
                        linkSubtitle(position),
                        linkIcon(position),
                        linkDivider(position));
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(LocaleController.getString(R.string.ZephyrCreditsText));
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_MORE_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_CREDITS_INFO) {
                return TYPE_INFO;
            }
            if (position == ROW_TOP_SHADOW || position == ROW_MORE_SHADOW) {
                return TYPE_SHADOW;
            }
            return TYPE_SETTINGS;
        }
    }
}
