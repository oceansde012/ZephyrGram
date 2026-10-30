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
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

/** ZephyrGram settings: ghost mode, saved messages and local overrides. */
public class ZephyrSettingsActivity extends BaseFragment {
    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHECK = 1;
    private static final int TYPE_INFO = 2;
    private static final int TYPE_SHADOW = 3;

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
    private static final int ROW_COUNT = 13;

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
                || position == ROW_DISABLE_ADS;
    }

    private void onRowClicked(int position) {
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
            return isSwitchRow(holder.getAdapterPosition());
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
            } else if (type == TYPE_INFO) {
                ((TextInfoPrivacyCell) holder.itemView).setText(
                        LocaleController.getString(R.string.ZephyrGhostModeInfo)
                );
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == ROW_GHOST_HEADER
                    || position == ROW_MESSAGES_HEADER
                    || position == ROW_OTHER_HEADER) {
                return TYPE_HEADER;
            }
            if (position == ROW_GHOST_INFO) {
                return TYPE_INFO;
            }
            if (position == ROW_MESSAGES_SHADOW) {
                return TYPE_SHADOW;
            }
            return TYPE_CHECK;
        }
    }
}
