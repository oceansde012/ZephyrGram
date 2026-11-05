package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * A settings row whose title and value wrap to as many lines as they need (the stock one-line cells cut
 * long plugin texts with "..."). Optional switch on the right; the value is shown under the title.
 */
public class ZephyrWrapCell extends FrameLayout {
    private final TextView titleView;
    private final TextView valueView;
    private final Switch checkBox;
    private boolean divider;

    public ZephyrWrapCell(Context context) {
        super(context);
        setMinimumHeight(AndroidUtilities.dp(50));
        setWillNotDraw(false);
        setBackground(Theme.createSelectorWithBackgroundDrawable(Theme.getColor(Theme.key_windowBackgroundWhite), Theme.getColor(Theme.key_listSelector)));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT, 0, 0, 0, 0));

        titleView = new TextView(context);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleView.setSingleLine(false);
        titleView.setMaxLines(Integer.MAX_VALUE);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        valueView = new TextView(context);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        valueView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        valueView.setSingleLine(false);
        valueView.setMaxLines(Integer.MAX_VALUE);
        valueView.setVisibility(GONE);
        column.addView(valueView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        checkBox = new Switch(context);
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        checkBox.setVisibility(GONE);
        addView(checkBox, LayoutHelper.createFrame(37, 20, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.CENTER_VERTICAL, 22, 0, 22, 0));
    }

    /**
     * @param value      shown under the title, empty = hidden
     * @param withSwitch show the switch (checked = its state)
     */
    public void setData(CharSequence title, CharSequence value, boolean withSwitch, boolean checked, boolean needDivider) {
        titleView.setText(title == null ? "" : title);
        boolean hasValue = !TextUtils.isEmpty(value);
        valueView.setText(hasValue ? value : "");
        valueView.setVisibility(hasValue ? VISIBLE : GONE);
        checkBox.setVisibility(withSwitch ? VISIBLE : GONE);
        if (withSwitch) {
            checkBox.setChecked(checked, false);
        }
        divider = needDivider;
        int side = AndroidUtilities.dp(21);
        int end = withSwitch ? AndroidUtilities.dp(80) : side;
        int vertical = AndroidUtilities.dp(12);
        View column = getChildAt(0);
        if (LocaleController.isRTL) {
            column.setPadding(end, vertical, side, vertical);
        } else {
            column.setPadding(side, vertical, end, vertical);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (divider && Theme.dividerPaint != null) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(21), getMeasuredHeight() - 1,
                    getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(21) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }
}
