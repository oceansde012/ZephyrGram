package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.InsetDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;

/**
 * One row of a settings card (plugin settings, ZephyrGram 0.2.8 design). The cell paints its part of a rounded
 * card: POSITION_* says whether it is the top, the middle, the bottom or the only row of the group. Layout:
 * [icon] [title / second line] [value, grey, right] [| switch] or a chevron for a sub-page. Title and second line
 * wrap to as many lines as they need (the stock cells cut long plugin texts with "...").
 */
public class ZephyrWrapCell extends FrameLayout {
    public static final int POSITION_SINGLE = 0, POSITION_FIRST = 1, POSITION_MIDDLE = 2, POSITION_LAST = 3;

    private static final int CARD_MARGIN_DP = org.telegram.ui.ZephyrCards.MARGIN_DP, CARD_RADIUS_DP = org.telegram.ui.ZephyrCards.CARD_RADIUS_DP, TEXT_START_DP = 16;
    private static final int ICON_SIZE_DP = 24, ICON_GAP_DP = 16, INDENT_DP = 20;

    private final LinearLayout row;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView subtitleView;
    private final TextView valueView;
    private final View switchDivider;
    private final Switch checkBox;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cardPath = new Path();
    private final RectF cardRect = new RectF();
    private final float[] radii = new float[8];
    private boolean divider;
    private boolean hasIcon;
    private int indentDp;
    private int position = POSITION_SINGLE;
    private float lastTouchX;
    private boolean switchZone;

    /** True = the cell paints its own card (plugin screens); false = a flat row inside a ZephyrCards section. */
    private final boolean standalone;
    private final org.telegram.ui.Components.CheckBox2 checkMark;

    public ZephyrWrapCell(Context context) {
        this(context, true);
    }

    public ZephyrWrapCell(Context context, boolean standalone) {
        super(context);
        this.standalone = standalone;
        setMinimumHeight(AndroidUtilities.dp(52));
        setWillNotDraw(false);
        if (standalone) setPadding(AndroidUtilities.dp(CARD_MARGIN_DP), 0, AndroidUtilities.dp(CARD_MARGIN_DP), 0);

        row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER);
        iconView.setVisibility(GONE);
        row.addView(iconView, LayoutHelper.createLinear(ICON_SIZE_DP, ICON_SIZE_DP, Gravity.CENTER_VERTICAL, 0, 0, ICON_GAP_DP, 0));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 11, 8, 11));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        titleView.setSingleLine(false);
        titleView.setMaxLines(Integer.MAX_VALUE);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        subtitleView.setSingleLine(false);
        subtitleView.setMaxLines(Integer.MAX_VALUE);
        subtitleView.setVisibility(GONE);
        column.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        valueView.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        valueView.setMaxLines(2);
        valueView.setEllipsize(TextUtils.TruncateAt.END);
        valueView.setMaxWidth(AndroidUtilities.dp(170));
        valueView.setVisibility(GONE);
        row.addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));

        switchDivider = new View(context);
        switchDivider.setVisibility(GONE);
        row.addView(switchDivider, LayoutHelper.createLinear(1, 28, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));

        checkBox = new Switch(context);
        checkBox.setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
        checkBox.setVisibility(GONE);
        row.addView(checkBox, LayoutHelper.createLinear(52, 32, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        checkMark = new org.telegram.ui.Components.CheckBox2(context, 22);
        checkMark.setColor(Theme.key_radioBackgroundChecked, Theme.key_checkboxDisabled, Theme.key_checkboxCheck);
        checkMark.setDrawUnchecked(true);
        checkMark.setVisibility(GONE);
        row.addView(checkMark, LayoutHelper.createLinear(22, 22, Gravity.CENTER_VERTICAL, 0, 0, 0, 0));
        applyColors();
    }

    /** Shows a check mark at the right instead of the switch (rows nested in an expandable group). */
    public void setCheckMode(boolean show, boolean checked) {
        checkMark.setVisibility(show ? VISIBLE : GONE);
        if (show) {
            checkBox.setVisibility(GONE);
            switchDivider.setVisibility(GONE);
            checkMark.setChecked(checked, false);
        }
    }

    private void applyColors() {
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        switchDivider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), android.graphics.PorterDuff.Mode.MULTIPLY));
        cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
    }

    /**
     * @param title      first line
     * @param subtitle   second, smaller line (empty = none)
     * @param value      grey text on the right (empty = none); for a Page row it is followed by a chevron
     * @param iconRes    drawable id or 0
     * @param withSwitch show the switch (checked = its state)
     * @param separated  a thin vertical line before the switch (rows that expand into children)
     * @param indent     1 for nested children (extra left indent)
     * @param position   POSITION_*; decides the rounded corners and the divider
     */
    public void bind(CharSequence title, CharSequence subtitle, CharSequence value, int iconRes, boolean withSwitch, boolean checked,
                     boolean separated, boolean chevron, int indent, int position) {
        applyColors();
        titleView.setText(title == null ? "" : title);
        boolean hasSub = !TextUtils.isEmpty(subtitle);
        subtitleView.setText(hasSub ? subtitle : "");
        subtitleView.setVisibility(hasSub ? VISIBLE : GONE);
        CharSequence shown = value;
        if (chevron) {
            SpannableStringBuilder b = new SpannableStringBuilder(TextUtils.isEmpty(value) ? "" : value + "  ");
            int from = b.length();
            b.append("›");
            b.setSpan(new ForegroundColorSpan(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2)), from, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            shown = b;
        }
        boolean hasValue = !TextUtils.isEmpty(shown);
        valueView.setText(hasValue ? shown : "");
        valueView.setVisibility(hasValue ? VISIBLE : GONE);
        if (iconRes != 0) {
            iconView.setImageResource(iconRes);
            iconView.setVisibility(VISIBLE);
        } else {
            iconView.setImageDrawable(null);
            iconView.setVisibility(GONE);
        }
        hasIcon = iconRes != 0;
        checkBox.setVisibility(withSwitch ? VISIBLE : GONE);
        switchDivider.setVisibility(withSwitch && separated ? VISIBLE : GONE);
        if (withSwitch) checkBox.setChecked(checked, false);
        indentDp = indent > 0 ? INDENT_DP : 0;
        int start = AndroidUtilities.dp(TEXT_START_DP + indentDp);
        int end = AndroidUtilities.dp(16);
        row.setPadding(LocaleController.isRTL ? end : start, 0, LocaleController.isRTL ? start : end, 0);
        setPosition(position);
    }

    public void setPosition(int position) {
        this.position = position;
        divider = position == POSITION_FIRST || position == POSITION_MIDDLE;
        int top = position == POSITION_FIRST || position == POSITION_SINGLE ? AndroidUtilities.dp(CARD_RADIUS_DP) : 0;
        int bottom = position == POSITION_LAST || position == POSITION_SINGLE ? AndroidUtilities.dp(CARD_RADIUS_DP) : 0;
        radii[0] = radii[1] = radii[2] = radii[3] = top;
        radii[4] = radii[5] = radii[6] = radii[7] = bottom;
        // The ripple follows the card's rounded corners and stays inside its side margins.
        int margin = standalone ? AndroidUtilities.dp(CARD_MARGIN_DP) : 0;
        if (standalone) setForeground(new InsetDrawable(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_listSelector), top, bottom), margin, 0, margin, 0));
        invalidate();
    }

    /** True if the last touch was on the switch part of the row (the switch and its separator). */
    public boolean wasSwitchTouched() {
        return switchZone;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN || ev.getActionMasked() == MotionEvent.ACTION_UP) {
            lastTouchX = ev.getX();
            int zone = AndroidUtilities.dp((standalone ? CARD_MARGIN_DP : 0) + 16 + 52 + 12 + 12);
            switchZone = checkBox.getVisibility() == VISIBLE
                    && (LocaleController.isRTL ? lastTouchX < zone : lastTouchX > getWidth() - zone);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int margin = standalone ? AndroidUtilities.dp(CARD_MARGIN_DP) : 0;
        if (standalone) {
            cardRect.set(margin, 0, getMeasuredWidth() - margin, getMeasuredHeight());
            cardPath.reset();
            cardPath.addRoundRect(cardRect, radii, Path.Direction.CW);
            canvas.drawPath(cardPath, cardPaint);
        }
        super.onDraw(canvas);
        if (divider && Theme.dividerPaint != null) {
            int inset = margin + AndroidUtilities.dp(TEXT_START_DP + indentDp + (hasIcon ? ICON_SIZE_DP + ICON_GAP_DP : 0));
            float y = getMeasuredHeight() - 1;
            if (LocaleController.isRTL) {
                canvas.drawLine(margin, y, getMeasuredWidth() - inset, y, Theme.dividerPaint);
            } else {
                canvas.drawLine(inset, y, getMeasuredWidth() - margin, y, Theme.dividerPaint);
            }
        }
    }

    /** Header above a card (small grey text) or a note under it. A thin view with no card of its own. */
    public static class TextBlock extends FrameLayout {
        public static final int HEADER = 0, NOTE = 1, SPACE = 2;
        private final TextView textView;
        private int mode = SPACE;

        public TextBlock(Context context) {
            super(context);
            textView = new TextView(context);
            textView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        public void bind(int mode, CharSequence text) {
            this.mode = mode;
            boolean empty = TextUtils.isEmpty(text);
            textView.setVisibility(empty ? GONE : VISIBLE);
            textView.setText(empty ? "" : text);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            int side = AndroidUtilities.dp(CARD_MARGIN_DP + 16);
            if (mode == HEADER) {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                textView.setPadding(side, AndroidUtilities.dp(18), side, AndroidUtilities.dp(7));
            } else {
                textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                textView.setPadding(side, AndroidUtilities.dp(7), side, AndroidUtilities.dp(7));
            }
            setMinimumHeight(AndroidUtilities.dp(mode == SPACE ? 12 : 0));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
    }
}
