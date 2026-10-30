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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** ZephyrGram settings: ghost mode, saved messages, local overrides and updates. */
public class ZephyrSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_SHADOW = 3;
    private static final int TYPE_SETTINGS = 4;

    private static final int ROW_GHOST_HEADER = 0;
    private static final int ROW_GHOST_NO_READ = 1;
    private static final int ROW_GHOST_NO_TYPING = 2;
    private static final int ROW_GHOST_ALWAYS_OFFLINE = 3;
    private static final int ROW_GHOST_NO_STORY_VIEWS = 4;
    private static final int ROW_GHOST_INFO = 5;
    private static final int ROW_MESSAGES_HEADER = 6;
    private static final int ROW_SAVE_DELETED = 7;
    private static final int ROW_SAVE_EDITED = 8;
    private static final int ROW_MESSAGES_SHADOW = 9;
    private static final int ROW_OTHER_HEADER = 10;
    private static final int ROW_LOCAL_PREMIUM = 11;
    private static final int ROW_DISABLE_ADS = 12;
    private static final int ROW_OTHER_SHADOW = 13;
    private static final int ROW_UPDATES_HEADER = 14;
    private static final int ROW_AUTO_UPDATE = 15;
    private static final int ROW_CHECK_UPDATE = 16;
    private static final int ROW_UPDATES_INFO = 17;
    private static final int ROW_MORE_HEADER = 18;
    private static final int ROW_GENERAL = 19;
    private static final int ROW_APPEARANCE = 20;
    private static final int ROW_CHATS = 21;
    private static final int ROW_OTHER_SCREEN = 22;
    private static final int ROW_MORE_SHADOW = 23;
    private static final int ROW_RESET = 24;
    private static final int ROW_CREDITS = 25;
    private static final int ROW_CREDITS_INFO = 26;
    private static final int ROW_PLUGINS = 27;
    private static final int ROW_COUNT = 28;

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

    private static boolean isChecked(int position) {
        switch (position) {
            case ROW_GHOST_NO_READ:
                return ZephyrConfig.ghostNoRead;
            case ROW_GHOST_NO_TYPING:
                return ZephyrConfig.ghostNoTyping;
            case ROW_GHOST_ALWAYS_OFFLINE:
                return ZephyrConfig.ghostAlwaysOffline;
            case ROW_GHOST_NO_STORY_VIEWS:
                return ZephyrConfig.ghostNoStoryViews;
            case ROW_SAVE_DELETED:
                return ZephyrConfig.saveDeleted;
            case ROW_SAVE_EDITED:
                return ZephyrConfig.saveEdited;
            case ROW_LOCAL_PREMIUM:
                return ZephyrConfig.localPremium;
            case ROW_DISABLE_ADS:
                return ZephyrConfig.disableAds;
            case ROW_AUTO_UPDATE:
                return ZephyrConfig.autoUpdateCheck;
            default:
                return false;
        }
    }

    private static boolean isSwitchRow(int position) {
        return position == ROW_GHOST_NO_READ
                || position == ROW_GHOST_NO_TYPING
                || position == ROW_GHOST_ALWAYS_OFFLINE
                || position == ROW_GHOST_NO_STORY_VIEWS
                || position == ROW_SAVE_DELETED
                || position == ROW_SAVE_EDITED
                || position == ROW_LOCAL_PREMIUM
                || position == ROW_DISABLE_ADS
                || position == ROW_AUTO_UPDATE;
    }

    private static boolean isLinkRow(int position) {
        return position == ROW_PLUGINS || position == ROW_CHECK_UPDATE || position == ROW_GENERAL || position == ROW_APPEARANCE
                || position == ROW_CHATS || position == ROW_OTHER_SCREEN || position == ROW_RESET
                || position == ROW_CREDITS;
    }

    private void onRowClicked(int position) {
        if (position == ROW_PLUGINS) {
            presentFragment(new ZephyrPluginsActivity());
            return;
        }
        if (position == ROW_CHECK_UPDATE) {
            ZephyrUpdater.checkManually(this);
            return;
        }
        if (position == ROW_GENERAL) {
            presentFragment(new ZephyrGeneralSettingsActivity());
            return;
        } else if (position == ROW_APPEARANCE) {
            presentFragment(new ZephyrAppearanceSettingsActivity());
            return;
        } else if (position == ROW_CHATS) {
            presentFragment(new ZephyrChatsSettingsActivity());
            return;
        } else if (position == ROW_OTHER_SCREEN) {
            presentFragment(new ZephyrOtherSettingsActivity());
            return;
        } else if (position == ROW_RESET) {
            ZephyrBaseSettingsActivity.confirmReset(this, null);
            return;
        } else if (position == ROW_CREDITS) {
            ZephyrBaseSettingsActivity.showCredits(this);
            return;
        }
        if (!isSwitchRow(position)) {
            return;
        }
        boolean value = !isChecked(position);
        switch (position) {
            case ROW_GHOST_NO_READ:
                ZephyrConfig.setGhostNoRead(value);
                break;
            case ROW_GHOST_NO_TYPING:
                ZephyrConfig.setGhostNoTyping(value);
                break;
            case ROW_GHOST_ALWAYS_OFFLINE:
                ZephyrConfig.setGhostAlwaysOffline(value);
                break;
            case ROW_GHOST_NO_STORY_VIEWS:
                ZephyrConfig.setGhostNoStoryViews(value);
                break;
            case ROW_SAVE_DELETED:
                ZephyrConfig.setSaveDeleted(value);
                break;
            case ROW_SAVE_EDITED:
                ZephyrConfig.setSaveEdited(value);
                break;
            case ROW_LOCAL_PREMIUM:
                ZephyrConfig.setLocalPremium(value);
                break;
            case ROW_DISABLE_ADS:
                ZephyrConfig.setDisableAds(value);
                break;
            case ROW_AUTO_UPDATE:
                ZephyrConfig.setAutoUpdateCheck(value);
                break;
            default:
                break;
        }
        adapter.notifyItemChanged(position);
    }

    private static String switchTitle(int position) {
        switch (position) {
            case ROW_GHOST_NO_READ:
                return LocaleController.getString(R.string.ZephyrGhostNoRead);
            case ROW_GHOST_NO_TYPING:
                return LocaleController.getString(R.string.ZephyrGhostNoTyping);
            case ROW_GHOST_ALWAYS_OFFLINE:
                return LocaleController.getString(R.string.ZephyrGhostAlwaysOffline);
            case ROW_GHOST_NO_STORY_VIEWS:
                return LocaleController.getString(R.string.ZephyrGhostNoStoryViews);
            case ROW_SAVE_DELETED:
                return LocaleController.getString(R.string.ZephyrSaveDeleted);
            case ROW_SAVE_EDITED:
                return LocaleController.getString(R.string.ZephyrSaveEdited);
            case ROW_LOCAL_PREMIUM:
                return LocaleController.getString(R.string.ZephyrLocalPremium);
            case ROW_DISABLE_ADS:
                return LocaleController.getString(R.string.ZephyrDisableAds);
            case ROW_AUTO_UPDATE:
                return LocaleController.getString(R.string.ZephyrAutoUpdateCheck);
            default:
                return "";
        }
    }

    private static String headerTitle(int position) {
        if (position == ROW_GHOST_HEADER) {
            return LocaleController.getString(R.string.ZephyrGhostMode);
        }
        if (position == ROW_MESSAGES_HEADER) {
            return LocaleController.getString(R.string.ZephyrMessages);
        }
        if (position == ROW_UPDATES_HEADER) {
            return LocaleController.getString(R.string.ZephyrUpdates);
        }
        if (position == ROW_MORE_HEADER) {
            return LocaleController.getString(R.string.ZephyrSettingsMore);
        }
        return LocaleController.getString(R.string.ZephyrOther);
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
            int position = holder.getAdapterPosition();
            return isSwitchRow(position) || isLinkRow(position);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view;
            if (viewType == TYPE_HEADER) {
                view = new HeaderCell(context);
            } else if (viewType == TYPE_CHECK) {
                view = new TextCheckCell(context);
            } else if (viewType == TYPE_SHADOW) {
                view = new ShadowSectionCell(context);
            } else if (viewType == TYPE_SETTINGS) {
                view = new TextSettingsCell(context);
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
                ((HeaderCell) holder.itemView).setText(headerTitle(position));
            } else if (type == TYPE_CHECK) {
                boolean divider = position != ROW_GHOST_NO_STORY_VIEWS
                        && position != ROW_SAVE_EDITED
                        && position != ROW_DISABLE_ADS;
                ((TextCheckCell) holder.itemView).setTextAndCheck(
                        switchTitle(position),
                        isChecked(position),
                        divider
                );
            } else if (type == TYPE_SETTINGS) {
                TextSettingsCell settingsCell = (TextSettingsCell) holder.itemView;
                if (position == ROW_CHECK_UPDATE) {
                    settingsCell.setTextAndValue(
                            LocaleController.getString(R.string.ZephyrCheckForUpdates),
                            ZephyrUpdater.currentVersion(),
                            false
                    );
                } else {
                    int titleRes;
                    if (position == ROW_PLUGINS) {
                        titleRes = R.string.ZephyrPlugins;
                    } else if (position == ROW_GENERAL) {
                        titleRes = R.string.ZephyrSettingsGeneral;
                    } else if (position == ROW_APPEARANCE) {
                        titleRes = R.string.ZephyrSettingsAppearance;
                    } else if (position == ROW_CHATS) {
                        titleRes = R.string.ZephyrSettingsChats;
                    } else if (position == ROW_OTHER_SCREEN) {
                        titleRes = R.string.ZephyrSettingsOther;
                    } else if (position == ROW_RESET) {
                        titleRes = R.string.ZephyrResetSettings;
                    } else {
                        titleRes = R.string.ZephyrCredits;
                    }
                    settingsCell.setText(LocaleController.getString(titleRes), position != ROW_OTHER_SCREEN && position != ROW_RESET && position != ROW_CREDITS);
                }
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        position == ROW_UPDATES_INFO
                                ? LocaleController.getString(R.string.ZephyrUpdatesInfo)
                                : position == ROW_CREDITS_INFO
                                ? LocaleController.getString(R.string.ZephyrCreditsText)
                                : LocaleController.getString(R.string.ZephyrGhostModeInfo)
                );
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_GHOST_HEADER
                    || position == ROW_MESSAGES_HEADER
                    || position == ROW_OTHER_HEADER
                    || position == ROW_UPDATES_HEADER
                    || position == ROW_MORE_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_GHOST_INFO || position == ROW_UPDATES_INFO || position == ROW_CREDITS_INFO) {
                return TYPE_INFO;
            }
            if (position == ROW_MESSAGES_SHADOW || position == ROW_OTHER_SHADOW || position == ROW_MORE_SHADOW) {
                return TYPE_SHADOW;
            }
            if (isLinkRow(position)) {
                return TYPE_SETTINGS;
            }
            return TYPE_CHECK;
        }
    }
}
