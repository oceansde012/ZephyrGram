package org.telegram.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.zephyr.ZephyrConfig;
import org.telegram.ui.ActionBar.Theme;

/** Live previews for the 0.2.8 settings screens. All colors come from Theme, settings from ZephyrConfig. */
public final class ZephyrPreviews {
    private ZephyrPreviews() {}

    private static int dp(float v) { return AndroidUtilities.dp(v); }

    /** Pure geometry helpers, kept apart from drawing so they can be tested. */
    public static float stickerSidePx(int percent) { return dp(120) * Math.max(50, Math.min(150, percent)) / 100f; }
    public static float avatarRadiusPx(float sidePx, int percent) { return sidePx / 2f * Math.max(0, Math.min(100, percent)) / 100f; }

    private abstract static class Base extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        Base(Context c) {
            super(c);
            paint.setTextAlign(Paint.Align.LEFT);
        }
        void text(Canvas c, String s, float x, float y, int color, float sp, boolean bold) {
            paint.setColor(color);
            paint.setTextSize(dp(sp));
            paint.setTypeface(bold ? AndroidUtilities.bold() : Typeface.DEFAULT);
            c.drawText(s, x, y, paint);
        }
        void round(Canvas c, float l, float t, float r, float b, float rad, int color) {
            paint.setColor(color);
            rect.set(l, t, r, b);
            c.drawRoundRect(rect, rad, rad, paint);
        }
        @Override protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(heightDp()));
        }
        abstract int heightDp();
        public void refresh() { invalidate(); }
    }

    /** Two chat bubbles with the double-tap action shown under each. */
    public static final class Bubbles extends Base {
        public Bubbles(Context c) { super(c); }
        @Override int heightDp() { return 150; }
        private String action(int v) {
            int[] res = {R.string.ZephyrDoubleTapOff, R.string.ZephyrDoubleTapReaction, R.string.ZephyrDoubleTapReply,
                    R.string.ZephyrDoubleTapCopy, R.string.ZephyrDoubleTapForward, R.string.ZephyrDoubleTapEdit, R.string.ZephyrDoubleTapDelete};
            return "↷ " + LocaleController.getString(res[Math.max(0, Math.min(res.length - 1, v))]);
        }
        @Override protected void onDraw(Canvas c) {
            int w = getWidth();
            int pad = dp(28);
            float bw = w * 0.62f;
            round(c, pad, dp(12), pad + bw, dp(52), dp(16), Theme.getColor(Theme.key_chat_inBubble));
            text(c, LocaleController.getString(R.string.ZephyrRdPrevHello), pad + dp(12), dp(36), Theme.getColor(Theme.key_chat_messageTextIn), 15, false);
            text(c, action(ZephyrConfig.getInt("doubleTapAction")), pad, dp(68), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 12, false);
            round(c, w - pad - bw, dp(80), w - pad, dp(120), dp(16), Theme.getColor(Theme.key_chat_outBubble));
            text(c, LocaleController.getString(R.string.ZephyrRdPrevHowAreYou), w - pad - bw + dp(12), dp(104), Theme.getColor(Theme.key_chat_messageTextOut), 15, false);
            text(c, action(ZephyrConfig.getInt("doubleTapActionOutOwner")), w - pad - bw, dp(136), Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), 12, false);
        }
    }

    /** A sticker stand-in: size follows the slider, the time chip follows hideStickerTime. */
    public static final class Sticker extends Base {
        public Sticker(Context c) { super(c); }
        @Override int heightDp() { return 190; }
        @Override protected void onDraw(Canvas c) {
            float side = stickerSidePx(ZephyrConfig.getInt("stickerSize"));
            float cx = getWidth() / 2f, bottom = getHeight() - dp(14);
            float l = cx - side / 2f, t = bottom - side;
            round(c, l, t, l + side, bottom, side * 0.2f, Theme.getColor(Theme.key_chat_inBubble));
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(side * 0.55f);
            paint.setTypeface(Typeface.DEFAULT);
            c.drawText("😊", cx, t + side * 0.66f, paint);
            paint.setTextAlign(Paint.Align.LEFT);
            if (!ZephyrConfig.getBool("hideStickerTime")) {
                float cw = dp(40), ch = dp(18);
                round(c, l + side - cw - dp(6), bottom - ch - dp(6), l + side - dp(6), bottom - dp(6), dp(9), 0x66000000);
                text(c, "12:30", l + side - cw - dp(6) + dp(6), bottom - dp(11), 0xFFFFFFFF, 11, false);
            }
        }
    }

    /** Five tab styles in two columns; tapping a card selects the style. */
    public static final class TabStyles extends Base {
        public static final int CELL_DP = 56, GAP_DP = 12, ROWS = 3;
        public static final int HEIGHT_DP = ROWS * CELL_DP + (ROWS - 1) * GAP_DP + 8;
        private final String[] labels = {LocaleController.getString(R.string.ZephyrRdPrevTabAll), LocaleController.getString(R.string.ZephyrRdPrevTabPersonal), LocaleController.getString(R.string.ZephyrRdPrevTabChannels)};
        public Runnable onChange;
        public TabStyles(Context c) { super(c); }
        @Override int heightDp() { return HEIGHT_DP; }
        private RectF cell(int i) {
            float w = (getWidth() - dp(GAP_DP) * 3) / 2f;
            float h = dp(CELL_DP);
            int col = i % 2, row = i / 2;
            float l = dp(GAP_DP) + col * (w + dp(GAP_DP)), t = dp(4) + row * (h + dp(GAP_DP));
            return new RectF(l, t, l + w, t + h);
        }
        /** Label sizes that fit: starts at 12sp and goes down to 10sp; returns how many labels (3 or 2) and the size in sp. */
        private float fit(float room, int[] count) {
            for (float sp = 12; sp >= 10; sp -= 1) {
                paint.setTextSize(dp(sp));
                float need = 0;
                for (int k = 0; k < 3; k++) need += paint.measureText(labels[k]) + dp(14);
                if (need <= room) { count[0] = 3; return sp; }
            }
            paint.setTextSize(dp(10));
            count[0] = 2;
            return 10;
        }
        @Override protected void onDraw(Canvas c) {
            int selected = ZephyrConfig.getInt("tabStyle");
            int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
            int tint = accent & 0x00FFFFFF | 0x33000000;
            int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2);
            int card = Theme.getColor(Theme.key_windowBackgroundWhite);
            boolean badge = ZephyrConfig.getBool("tabCounter");
            for (int i = 0; i < 5; i++) {
                RectF r = cell(i);
                round(c, r.left, r.top, r.right, r.bottom, dp(14), card);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(i == selected ? 2 : 1));
                paint.setColor(i == selected ? accent : Theme.getColor(Theme.key_divider));
                c.drawRoundRect(r, dp(14), dp(14), paint);
                paint.setStyle(Paint.Style.FILL);
                float pad = dp(12);
                float room = r.width() - 2 * pad - (badge ? dp(30) : 0);
                int[] count = new int[1];
                float sp = fit(room, count);
                float x = r.left + pad, y = r.centerY() + dp(5);
                for (int k = 0; k < count[0]; k++) {
                    paint.setTextSize(dp(sp));
                    paint.setTypeface(AndroidUtilities.bold());
                    float tw = paint.measureText(labels[k]);
                    boolean active = k == 0;
                    if (active) {
                        if (i == 0) round(c, x - dp(6), y - dp(16), x + tw + dp(6), y + dp(6), dp(11), tint);
                        if (i == 1) round(c, x - dp(6), y - dp(16), x + tw + dp(6), y + dp(6), dp(5), tint);
                        if (i == 4) round(c, x - dp(6), y - dp(16), x + tw + dp(6), y + dp(6), dp(3), accent);
                        if (i == 2) round(c, x, y + dp(8), x + tw, y + dp(10), 0, accent);
                    }
                    text(c, labels[k], x, y, i == 4 && active ? 0xFFFFFFFF : active ? accent : gray, sp, active);
                    x += tw + dp(14);
                }
                if (badge) {
                    float bx = Math.min(x, r.right - pad - dp(22));
                    round(c, bx, y - dp(15), bx + dp(22), y + dp(3), dp(9), accent);
                    text(c, "12", bx + dp(4), y - dp(2), 0xFFFFFFFF, 11, true);
                }
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                for (int i = 0; i < 5; i++) {
                    if (cell(i).contains(e.getX(), e.getY())) {
                        ZephyrConfig.setInt("tabStyle", i);
                        invalidate();
                        if (onChange != null) onChange.run();
                        return true;
                    }
                }
            }
            return e.getAction() == MotionEvent.ACTION_DOWN;
        }
    }

    /** Two floating-button shapes; tapping selects. */
    public static final class Fab extends Base {
        public Runnable onChange;
        public Fab(Context c) { super(c); }
        @Override int heightDp() { return 96; }
        private RectF cell(int i) {
            float w = (getWidth() - dp(12) * 3) / 2f;
            return new RectF(dp(12) + i * (w + dp(12)), dp(4), dp(12) + i * (w + dp(12)) + w, dp(88));
        }
        @Override protected void onDraw(Canvas c) {
            int selected = ZephyrConfig.getInt("fabShape");
            int accent = Theme.getColor(Theme.key_chats_actionBackground);
            for (int i = 0; i < 2; i++) {
                RectF r = cell(i);
                round(c, r.left, r.top, r.right, r.bottom, dp(14), Theme.getColor(Theme.key_windowBackgroundWhite));
                if (i == selected) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
                    c.drawRoundRect(r, dp(14), dp(14), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
                float cx = r.centerX(), cy = r.centerY(), s = dp(28);
                round(c, cx - s, cy - s, cx + s, cy + s, i == 0 ? s : dp(16), accent);
                text(c, "+", cx - dp(7), cy + dp(9), Theme.getColor(Theme.key_chats_actionIcon), 26, false);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                for (int i = 0; i < 2; i++) {
                    if (cell(i).contains(e.getX(), e.getY())) {
                        ZephyrConfig.setInt("fabShape", i);
                        invalidate();
                        if (onChange != null) onChange.run();
                        return true;
                    }
                }
            }
            return e.getAction() == MotionEvent.ACTION_DOWN;
        }
    }

    /** Four avatars; corner radius follows the avatarCorners slider. */
    public static final class Avatars extends Base {
        public Avatars(Context c) { super(c); }
        @Override int heightDp() { return 84; }
        @Override protected void onDraw(Canvas c) {
            int[] keys = {Theme.key_avatar_backgroundRed, Theme.key_avatar_backgroundOrange, Theme.key_avatar_backgroundGreen, Theme.key_avatar_backgroundBlue};
            String[] letters = {"А", "Б", "В", "Г"};
            float side = dp(52), gap = (getWidth() - 4 * side) / 5f;
            float r = avatarRadiusPx(side, ZephyrConfig.getInt("avatarCorners"));
            for (int i = 0; i < 4; i++) {
                float l = gap + i * (side + gap), t = dp(16);
                round(c, l, t, l + side, t + side, r, Theme.getColor(keys[i]));
                paint.setTextSize(dp(20));
                paint.setTypeface(AndroidUtilities.bold());
                text(c, letters[i], l + (side - paint.measureText(letters[i])) / 2f, t + side / 2f + dp(7), 0xFFFFFFFF, 20, true);
            }
        }
    }
}
