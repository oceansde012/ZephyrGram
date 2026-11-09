package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.view.View;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * 0.2.8 settings cards for any list built on RecyclerListView, without rewriting the screen. It reuses the
 * list's own section mechanism (RecyclerListView.setSections: rounded white section under consecutive rows,
 * side margins, rounded ripple) with two changes: a header stays OUTSIDE the card, above it, as small grey text,
 * and the card geometry comes from the constants below (one place). Colours come from the theme only
 * (windowBackgroundGray behind, windowBackgroundWhite for the card). A note (TextInfoPrivacyCell) lies under
 * the card, a ShadowSectionCell is a transparent gap. The cells must not paint their own background, so an
 * adapter of a card screen has to be created with setApplyBackground(false) (done here for UniversalRecyclerView).
 * ZephyrConfig.settingsCards = false gives the stock look back (Telegram screens get the stock sections).
 */
public final class ZephyrCards {
    private ZephyrCards() { }

    /** Side margin of a card (dp). */
    public static final int MARGIN_DP = 12;
    /** Corner radius of a card (dp). */
    public static final int CARD_RADIUS_DP = 18;
    /** Corner radius of elements nested in a card: expanded groups, chips (dp). */
    public static final int INNER_RADIUS_DP = 12;
    /** Gap between two cards (dp): a ShadowSectionCell (12dp) plus the card's own bottom padding. */
    public static final int SPACING_DP = 16;

    /**
     * Turns the list into cards (call once after the list is created, before the first fill).
     * @param stockFallback when the flag is off: true = stock Telegram sections (screens that had them before),
     *                      false = leave the list as it was (our own screens had none).
     */
    public static void enable(RecyclerListView list, boolean stockFallback) {
        if (list == null) return;
        if (!ZephyrConfig.settingsCards) {
            if (stockFallback) list.setSections();
            return;
        }
        if (list instanceof UniversalRecyclerView) {
            UniversalRecyclerView universal = (UniversalRecyclerView) list;
            if (universal.adapter != null) universal.adapter.setApplyBackground(false);
            list.setSections(
                    view -> {
                        if (view.getParent() != list) return false;
                        RecyclerView.ViewHolder holder = list.getChildViewHolder(view);
                        return holder != null && isCardType(holder.getItemViewType());
                    },
                    ZephyrCards::isCardType,
                    dp(MARGIN_DP), dp(CARD_RADIUS_DP), false);
        } else {
            list.setSections(
                    view -> !(view instanceof HeaderCell || view instanceof TextInfoPrivacyCell || view instanceof ShadowSectionCell
                            || view instanceof org.telegram.ui.Cells.GraySectionCell || view instanceof org.telegram.ui.Cells.CollapseTextCell)
                            && !java.util.Objects.equals(view.getTag(), RecyclerListView.TAG_NOT_SECTION),
                    dp(MARGIN_DP), dp(CARD_RADIUS_DP), false);
        }
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override public void onChildViewAttachedToWindow(View view) { styleHeader(view); }
            @Override public void onChildViewDetachedFromWindow(View view) { }
        });
    }

    /** View types that sit inside a card: everything except shadows/notes and headers. */
    static boolean isCardType(int viewType) {
        return !UniversalAdapter.isShadow(viewType)
                && viewType != UniversalAdapter.VIEW_TYPE_HEADER
                && viewType != UniversalAdapter.VIEW_TYPE_ANIMATED_HEADER
                && viewType != UniversalAdapter.VIEW_TYPE_BLACK_HEADER;
    }

    /** Header above a card: small grey regular text, aligned with the text inside the card. */
    private static void styleHeader(View view) {
        if (!(view instanceof HeaderCell)) return;
        TextView text = ((HeaderCell) view).getTextView();
        if (text == null) return;
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13);
        text.setTypeface(android.graphics.Typeface.DEFAULT);
        text.setPadding(dp(MARGIN_DP), 0, dp(MARGIN_DP), 0);
    }
}
