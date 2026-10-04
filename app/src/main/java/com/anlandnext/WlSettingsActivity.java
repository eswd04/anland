package com.anlandnext;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.function.IntConsumer;

/**
 * Window / display settings.
 *
 * Two stores: the IME mode lives in SharedPreferences (APK-local, read by the
 * window Activity), everything else is a daemon config key — the daemon is the
 * single numeric source of truth, read and written over binder (#31), and the
 * value is persisted to its own config.json. A key the running daemon does not
 * know (older module build) reads back as -1: the switch is then shown
 * disabled instead of pretending to own a value.
 *
 * Layout is built in code; see Ui for the dp/sp and accent helpers. Sections
 * are grouped and separated so the page is scannable.
 */
public class WlSettingsActivity extends Activity {

    /* RadioButton ids — unique per activity: the IME group uses 3/4, the
     * scaling group 5/6/7. */
    private static final int ID_IME_INSET = 3;
    private static final int ID_IME_OVERLAY = 4;
    private static final int ID_SCALE_STRETCH = 5;
    private static final int ID_SCALE_FIT = 6;
    private static final int ID_SCALE_CENTER = 7;

    /** Shared by the zoom and initial-size debounces. */
    private final Handler h = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.settings_title);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 40));

        if (!WlBinder.available()) {
            TextView warn = Ui.note(this, R.string.status_daemon_unreachable);
            warn.setPadding(0, 0, 0, Ui.dp(this, 8));
            root.addView(warn);
        }

        buildDisplay(root);
        root.addView(Ui.divider(this));

        buildWindows(root);
        root.addView(Ui.divider(this));

        buildRenderer(root);
        root.addView(Ui.divider(this));

        buildInput(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ------------------------------------------------------------------ display

    private void buildDisplay(LinearLayout root) {
        root.addView(Ui.section(this, R.string.section_display));

        root.addView(Ui.tip(this, R.string.zoom_tip));

        final TextView zoomVal = Ui.value(this, "");
        root.addView(zoomVal);

        final int[] cur = {100};
        int got = WlBinder.configGet("zoom");
        cur[0] = (got < 50 || got > 300) ? 100 : got;   /* daemon down / unknown → 100 */

        final SeekBar seek = new SeekBar(this);
        seek.setMax(250);          /* progress = pct - 50 → any value in 50..300 */
        seek.setProgress(cur[0] - 50);
        root.addView(seek);

        /* 200ms drag debounce + immediate on release/preset — applies
         * dynamically without bombarding per tick (each set = a
         * preferred_scale broadcast + re-configure of every window) */
        final Runnable[] pending = new Runnable[1];
        final Runnable apply = () -> WlBinder.configSet("zoom", cur[0]);
        final IntConsumer showZoom =
                pct -> zoomVal.setText(pct + "%  =  " + (pct / 100.0) + "×");

        final IntConsumer setZoom = pct -> {
            cur[0] = pct;
            seek.setProgress(pct - 50);
            showZoom.accept(pct);
            if (pending[0] != null) h.removeCallbacks(pending[0]);
            h.post(apply);   /* preset: immediate */
        };
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int prog, boolean fromUser) {
                if (!fromUser) return;
                cur[0] = prog + 50;
                showZoom.accept(cur[0]);
                if (pending[0] != null) h.removeCallbacks(pending[0]);
                int pct = cur[0];
                pending[0] = () -> WlBinder.configSet("zoom", pct);
                h.postDelayed(pending[0], 200);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) {
                if (pending[0] != null) h.removeCallbacks(pending[0]);
                pending[0] = null;
                h.post(apply);   /* release: immediate */
            }
        });
        showZoom.accept(cur[0]);

        LinearLayout presets = new LinearLayout(this);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        int[] ratios = {100, 150, 175, 200, 250};
        for (int r : ratios) {
            Button btn = Ui.preset(this, r + "%");
            btn.setOnClickListener(v -> setZoom.accept(r));
            presets.addView(btn, Ui.weightedGap(this));
        }
        root.addView(presets);

        /* ---- scaling mode under resize (daemon config scale_mode; #34) ---- */
        root.addView(Ui.gap(this, 10));
        root.addView(Ui.tip(this, R.string.scale_mode_tip));

        RadioGroup scaleRg = new RadioGroup(this);
        scaleRg.addView(Ui.radio(this, ID_SCALE_STRETCH, R.string.scale_mode_stretch));
        scaleRg.addView(Ui.radio(this, ID_SCALE_FIT, R.string.scale_mode_fit));
        scaleRg.addView(Ui.radio(this, ID_SCALE_CENTER, R.string.scale_mode_center));
        int gotMode = WlBinder.configGet("scale_mode");
        if (gotMode < 0 || gotMode > 2) gotMode = 0;   /* daemon down / unknown → stretch */
        (gotMode == 2 ? (RadioButton) scaleRg.getChildAt(2)
                : gotMode == 1 ? (RadioButton) scaleRg.getChildAt(1)
                : (RadioButton) scaleRg.getChildAt(0)).setChecked(true);
        /* listener AFTER the initial setChecked: opening the page must not
         * fire a write back to the daemon */
        scaleRg.setOnCheckedChangeListener((g, checkedId) ->
                WlBinder.configSet("scale_mode",
                        checkedId == ID_SCALE_CENTER ? 2 : checkedId == ID_SCALE_FIT ? 1 : 0));
        root.addView(scaleRg);

        /* ---- XWayland scaling (X11 clients receive the zoom-adjusted X size
         *      on resize, then the existing stretch path scales them) ---- */
        root.addView(Ui.gap(this, 10));
        root.addView(Ui.tip(this, R.string.xwayland_scale_tip));
        Switch xwaylandScaleSw = new Switch(this);
        bindSwitch(xwaylandScaleSw, R.string.xwayland_scale, "xwayland_scale", true);
        root.addView(Ui.switchRow(this, xwaylandScaleSw));
    }

    // ------------------------------------------------------------------ windows

    private void buildWindows(LinearLayout root) {
        root.addView(Ui.section(this, R.string.section_windows));

        /* auto_attach: off = the window waits for a binder SURFACE from its
         * app (the third-party path); effective for windows created from now
         * on. */
        root.addView(Ui.tip(this, R.string.auto_attach_tip));
        Switch autoSw = new Switch(this);
        bindSwitch(autoSw, R.string.auto_attach, "auto_attach", false);
        root.addView(Ui.switchRow(this, autoSw));

        /* ---- initial window size (first-frame configure placeholder; #33,
         *      new windows only) ---- */
        root.addView(Ui.gap(this, 12));
        root.addView(Ui.tip(this, R.string.init_size_tip));

        final int[] sz = {800, 600};
        int gw = WlBinder.configGet("init_w");
        int gh = WlBinder.configGet("init_h");
        sz[0] = (gw >= 100 && gw <= 7680) ? gw : 800;   /* daemon down → defaults */
        sz[1] = (gh >= 100 && gh <= 4320) ? gh : 600;

        final TextView sizeVal = Ui.value(this, "");
        root.addView(sizeVal);

        /* pickers bounded to the daemon's accepted domain (a set outside it
         * is rejected — keep the UI from producing one); the horizontal
         * padding keeps the wheel away from the labels */
        final NumberPicker wp = new NumberPicker(this);
        final NumberPicker hp = new NumberPicker(this);

        LinearLayout pickers = new LinearLayout(this);
        pickers.setOrientation(LinearLayout.HORIZONTAL);
        pickers.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView wl = new TextView(this);
        wl.setText(R.string.init_size_w);
        wl.setTextSize(14);
        wl.setAlpha(0.8f);
        TextView hl = new TextView(this);
        hl.setText(R.string.init_size_h);
        hl.setTextSize(14);
        hl.setAlpha(0.8f);

        pickers.addView(wl);
        pickers.addView(wp, Ui.weighted());
        pickers.addView(Ui.hgap(this, 12));
        pickers.addView(hl);
        pickers.addView(hp, Ui.weighted());
        root.addView(pickers);

        /* debounce like zoom (a scroll fires many changes); presets apply
         * immediately */
        final Runnable[] pendSz = new Runnable[1];
        final Runnable applySz = () -> {
            WlBinder.configSet("init_w", sz[0]);
            WlBinder.configSet("init_h", sz[1]);
        };
        final Runnable showSz = () -> sizeVal.setText(sz[0] + " × " + sz[1]);
        final Runnable changedSz = () -> {
            showSz.run();
            if (pendSz[0] != null) h.removeCallbacks(pendSz[0]);
            int w = sz[0], ht = sz[1];
            pendSz[0] = () -> {
                WlBinder.configSet("init_w", w);
                WlBinder.configSet("init_h", ht);
            };
            h.postDelayed(pendSz[0], 300);
        };
        NumberPicker.OnValueChangeListener ncl = (picker, oldV, newV) -> {
            if (picker == wp) sz[0] = newV; else sz[1] = newV;
            changedSz.run();
        };
        wp.setMinValue(100);
        wp.setMaxValue(7680);
        wp.setWrapSelectorWheel(false);
        wp.setValue(sz[0]);
        wp.setOnValueChangedListener(ncl);
        hp.setMinValue(100);
        hp.setMaxValue(4320);
        hp.setWrapSelectorWheel(false);
        hp.setValue(sz[1]);
        hp.setOnValueChangedListener(ncl);

        LinearLayout sizes = new LinearLayout(this);
        sizes.setOrientation(LinearLayout.HORIZONTAL);
        int[][] sizePresets = {{800, 600}, {1024, 768}, {1280, 720}, {1920, 1080}};
        for (int[] s : sizePresets) {
            Button btn = Ui.preset(this, s[0] + "×" + s[1]);
            btn.setOnClickListener(v -> {
                if (pendSz[0] != null) h.removeCallbacks(pendSz[0]);
                pendSz[0] = null;
                sz[0] = s[0];
                sz[1] = s[1];
                wp.setValue(s[0]);
                hp.setValue(s[1]);
                showSz.run();
                h.post(applySz);
            });
            sizes.addView(btn, Ui.weightedGap(this));
        }
        root.addView(sizes);
        showSz.run();
    }

    // ----------------------------------------------------------------- renderer

    private void buildRenderer(LinearLayout root) {
        root.addView(Ui.section(this, R.string.section_renderer));

        /* sc_enabled: on = wayland layers become sibling ASurfaceControls
         * composed by SurfaceFlinger/HWC, dma-buf scanout when allowed; off =
         * the per-window GL renderer fallback. Windows already attached keep
         * their backend until they re-attach. */
        root.addView(Ui.tip(this, R.string.sc_backend_tip));
        Switch scSw = new Switch(this);
        bindSwitch(scSw, R.string.sc_backend, "sc_enabled", true);
        root.addView(Ui.switchRow(this, scSw));

        /* next_serial: on = every xdg_surface.configure carries a freshly
         * allocated wayland serial. Clients that validate it silently drop a
         * serial-0 configure — which is what a daemon that has not handled any
         * input yet used to send, leaving the first app with no window. */
        root.addView(Ui.gap(this, 12));
        root.addView(Ui.tip(this, R.string.next_serial_tip));
        Switch nsSw = new Switch(this);
        boolean nsKnown = bindSwitch(nsSw, R.string.next_serial, "next_serial", true);
        root.addView(Ui.switchRow(this, nsSw));
        if (!nsKnown) {
            TextView un = Ui.note(this, R.string.config_unsupported);
            un.setPadding(0, Ui.dp(this, 4), 0, 0);
            root.addView(un);
        }
    }

    // -------------------------------------------------------------------- input

    private void buildInput(LinearLayout root) {
        root.addView(Ui.section(this, R.string.section_input));
        root.addView(Ui.tip(this, R.string.ime_mode_tip));

        RadioGroup imeRg = new RadioGroup(this);
        imeRg.addView(Ui.radio(this, ID_IME_INSET, R.string.ime_mode_inset));
        imeRg.addView(Ui.radio(this, ID_IME_OVERLAY, R.string.ime_mode_overlay));

        int imeMode = getSharedPreferences("awl", MODE_PRIVATE).getInt("ime_mode", 0);
        ((RadioButton) imeRg.getChildAt(imeMode != 0 ? 1 : 0)).setChecked(true);
        imeRg.setOnCheckedChangeListener((g, checkedId) ->
                getSharedPreferences("awl", MODE_PRIVATE).edit()
                        .putInt("ime_mode", checkedId == ID_IME_OVERLAY ? 1 : 0)
                        .apply());
        root.addView(imeRg);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Wires one daemon config key to a switch: initial state plus a write on
     * user change. The listener is registered AFTER the initial setChecked, so
     * merely opening the page never writes back to the daemon.
     *
     * @param onWhenUnknown the daemon default, used when the key reads back as
     *                      -1 (daemon down, or a module build that predates the
     *                      key)
     * @return false when the daemon does not know the key — the switch is left
     *         disabled and the caller labels why
     */
    private boolean bindSwitch(Switch sw, int labelRes, String key, boolean onWhenUnknown) {
        sw.setText(labelRes);
        int got = WlBinder.configGet(key);
        sw.setChecked(got == -1 ? onWhenUnknown : got != 0);
        if (got == -1) {
            sw.setEnabled(false);
            return false;
        }
        sw.setOnCheckedChangeListener((b, on) -> WlBinder.configSet(key, on ? 1 : 0));
        return true;
    }
}
