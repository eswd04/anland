package com.anlandnext;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Shared helpers for the two code-built layouts.
 *
 * The APK deliberately has no dependencies (framework widgets only, no
 * Material), so both activities assemble their views in Java. These helpers
 * keep spacing, hierarchy and the theme accent identical between them.
 *
 * Sizes are dp/sp throughout. The layout code used to hand raw pixel
 * constants to setPadding/setMinimumHeight, so the same number meant a
 * different amount of space on every density and the page read as one flat
 * run of equally weighted text.
 */
final class Ui {

    private Ui() { }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Framework theme colour with fallback — a plain theme may not define the
     *  attribute (or may expose it as a reference rather than a literal). */
    static int themeColor(Context c, int attr, int fallback) {
        TypedValue tv = new TypedValue();
        try {
            if (c.getTheme().resolveAttribute(attr, tv, true)) {
                if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                        && tv.type <= TypedValue.TYPE_LAST_COLOR_INT)
                    return tv.data;
                if (tv.resourceId != 0)
                    return c.getResources().getColor(tv.resourceId, c.getTheme());
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    static int accent(Context c) {
        return themeColor(c, android.R.attr.colorAccent,
                themeColor(c, android.R.attr.textColorPrimary, 0xFF888888));
    }

    /** Section header: the settings page previously had no structure at all —
     *  nine unrelated settings separated by blank Space. */
    static TextView section(Context c, int res) {
        TextView tv = new TextView(c);
        tv.setText(res);
        tv.setTextSize(12);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(accent(c));
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.09f);
        tv.setPadding(0, dp(c, 26), 0, dp(c, 6));
        return tv;
    }

    /** Explanatory line: smaller and dimmed via alpha so it stays legible on
     *  both light and dark themes instead of sharing the control's weight. */
    static TextView tip(Context c, int res) {
        TextView tv = new TextView(c);
        tv.setText(res);
        tv.setTextSize(13);
        tv.setAlpha(0.68f);
        tv.setLineSpacing(dp(c, 2), 1f);
        tv.setPadding(0, 0, 0, dp(c, 4));
        return tv;
    }

    /** Standalone note (warning / unsupported-option hint). */
    static TextView note(Context c, int res) {
        TextView tv = tip(c, res);
        tv.setTextColor(0xFFC8791B);
        tv.setAlpha(1f);
        return tv;
    }

    /** The prominent numeric readout (zoom %, initial size). */
    static TextView value(Context c, String text) {
        TextView tv = new TextView(c);
        tv.setText(text);
        tv.setTextSize(22);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(c, 2), 0, dp(c, 4));
        return tv;
    }

    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(0xFF888888);
        v.setAlpha(0.22f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        lp.topMargin = dp(c, 10);
        v.setLayoutParams(lp);
        return v;
    }

    static Space gap(Context c, float dpValue) {
        Space s = new Space(c);
        s.setMinimumHeight(dp(c, dpValue));
        return s;
    }

    /** Horizontal spacer: same idea, but a Space with only a minimum
     *  height collapses to width 0 inside a horizontal row. */
    static Space hgap(Context c, float dpValue) {
        Space s = new Space(c);
        s.setMinimumWidth(dp(c, dpValue));
        return s;
    }

    /** Switch row: label and control on one line with a comfortable touch
     *  target (the bare Switch is only as tall as its thumb). */
    static LinearLayout switchRow(Context c, Switch sw) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(c, 48));
        sw.setTextSize(16);
        row.addView(sw, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    static RadioButton radio(Context c, int id, int textRes) {
        RadioButton rb = new RadioButton(c);
        rb.setId(id);
        rb.setText(textRes);
        rb.setTextSize(15);
        rb.setMinimumHeight(dp(c, 44));
        return rb;
    }

    /** Compact preset chip. The default Button is all-caps, heavily padded and
     *  oversized for a four- or five-across grid. */
    static Button preset(Context c, String text) {
        Button b = new Button(c);
        b.setText(text);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinimumHeight(dp(c, 42));
        b.setPadding(0, 0, 0, 0);
        return b;
    }

    static Button action(Context c, int textRes) {
        Button b = new Button(c);
        b.setText(textRes);
        b.setTextSize(15);
        b.setAllCaps(false);
        b.setMinimumHeight(dp(c, 46));
        return b;
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static LinearLayout.LayoutParams weightedGap(Context c) {
        LinearLayout.LayoutParams lp = weighted();
        int m = dp(c, 3);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    /** Green / amber state colours that stay readable on both themes
     *  (the old fixed 0,128,0 turned into a dark smudge on dark). */
    static int stateAttached() { return 0xFF2EA043; }
    static int stateDetached() { return 0xFFC8791B; }
}
