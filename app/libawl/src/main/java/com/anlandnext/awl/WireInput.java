package com.anlandnext.awl;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen input helpers for the desktop: the two things a phone screen lacks
 * when a Linux desktop is running inside a window.
 *
 * Both feed the existing binder input channel (AwlClient.input → AWL_T_INPUT),
 * which was verified end to end before this was written: an AWL_IN_KEY with an
 * evdev code arrives at a Wayland client as wl_keyboard.key, and the pointer
 * events arrive as wl_pointer.motion/button/axis. Nothing native was needed —
 * the daemon already translates all of it.
 */
public final class WireInput {

    private WireInput() { }

    /* ---- Linux evdev codes (what the wire carries) ---- */
    public static final int KEY_ESC = 1, KEY_BACKSPACE = 14, KEY_TAB = 15,
            KEY_ENTER = 28, KEY_LEFTCTRL = 29, KEY_LEFTSHIFT = 42,
            KEY_LEFTALT = 56, KEY_UP = 103, KEY_LEFT = 105,
            KEY_RIGHT = 106, KEY_DOWN = 108, KEY_LEFTMETA = 125;
    public static final int BTN_LEFT = 0x110, BTN_RIGHT = 0x111, BTN_MIDDLE = 0x112;

    /* daemon event types (include/awl.h AWL_IN_*) */
    static final int PTR_ENTER = 1, PTR_LEAVE = 2, PTR_MOTION = 3,
            PTR_BUTTON = 4, PTR_AXIS = 5, PTR_REL = 6, KEY = 9;

    /** The window id is only known after the Activity binds it, while these
     *  views are built in onCreate — so they read it through this instead of
     *  capturing a value that is still -1. */
    public interface WinId { long get(); }

    /** The touch / touchpad switch the key bar offers.
     *
     *  Deliberately not a Runnable: the key has two states and draws which one
     *  is active, so the bar has to be able to ask. The window owns the mode
     *  (it holds the touchpad view and the preference) — the bar only reads
     *  {@link #isOn} and calls {@link #toggle}. */
    public interface TouchpadToggle {
        /** True while the touchpad (rather than direct touch) is active. */
        boolean isOn();
        void toggle();
    }

    /* ---- printable ASCII → evdev (US layout) ----
     * A desktop client often has no text_input object, and without one the IME
     * bridge has nowhere to deliver text. Keys always land, so printable ASCII
     * is typed this way; anything else (CJK) still goes through text-input.
     * Codes are Linux evdev (input-event-codes.h) — the daemon forwards them
     * to wl_keyboard.key and the client resolves them through its keymap. */
    private static final int KEY_SPACE = 57;
    private static final String UNSHIFTED =
            "1234567890-=`qwertyuiop[]\\asdfghjkl;'zxcvbnm,./ ";
    private static final int[] UNSHIFTED_CODE = {
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 41,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 43,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 57 };
    private static final String SHIFTED =
            "!@#$%^&*()_+~QWERTYUIOP{}|ASDFGHJKL:\"ZXCVBNM<>?";
    private static final int[] SHIFTED_CODE = {
            2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 41,
            16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 43,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53 };

    /** Whether {@link #typeChar} can type this character (US layout). */
    public static boolean typeable(char c) {
        return UNSHIFTED.indexOf(c) >= 0 || SHIFTED.indexOf(c) >= 0;
    }

    /** Type one printable ASCII character as a key tap. Returns false when the
     *  character is not on a US layout (caller falls back to text-input). */
    public static boolean typeChar(long winId, char c) {
        int idx = UNSHIFTED.indexOf(c);
        int code;
        boolean shift = false;
        if (idx >= 0 && idx < UNSHIFTED_CODE.length) {
            code = UNSHIFTED_CODE[idx];
        } else {
            idx = SHIFTED.indexOf(c);
            if (idx < 0 || idx >= SHIFTED_CODE.length) return false;
            code = SHIFTED_CODE[idx];
            shift = true;
        }
        if (shift) AwlClient.input(winId, KEY, KEY_LEFTSHIFT, 0, 0, 1, 0, 0);
        AwlClient.input(winId, KEY, code, 0, 0, 1, 0, 0);
        AwlClient.input(winId, KEY, code, 0, 0, 0, 0, 0);
        if (shift) AwlClient.input(winId, KEY, KEY_LEFTSHIFT, 0, 0, 0, 0, 0);
        return true;
    }

    /* ======================================================================
     * Modifier / shortcut bar
     * ==================================================================== */

    /**
     * Two rows of keys a phone keyboard cannot send, plus the window controls
     * (the IME toggle, the touch/touchpad switch and the settings screen).
     *
     * Two rows rather than one: at 14 keys a single row gave each key 24.7 dp
     * while the labels are 13 sp — "Shift" needs about 30 dp and ran into
     * "Super" before the Pad key was even added. Two rows give every key about
     * 48 dp, which is also the size a finger wants.
     *
     * Modifiers are LATCHED: tapping Ctrl sends a key-down and keeps it down
     * until tapped again, so the compositor tracks the modifier state itself
     * exactly as it does for a real keyboard. Press-and-release per tap would
     * not latch anything.
     */
    public static final class KeyBar extends View {
        private final WinId winId;
        private final Handler ui = new Handler(Looper.getMainLooper());

        private static final class Key {
            final String label; final int code;
            final boolean modifier;
            /** Special keys (the keyboard toggle, the settings screen) run this
             *  instead of sending a keycode — they are window controls, not
             *  keystrokes. */
            final Runnable action;
            /** Which row it sits in. Rows are laid out independently, so they
             *  need not hold the same number of keys. */
            final int row;
            boolean on;
            RectF rect = new RectF();
            Key(String label, int code, boolean modifier, int row) {
                this(label, code, modifier, null, row);
            }
            Key(String label, Runnable action, int row) {
                this(label, 0, false, action, row);
            }
            Key(String label, int code, boolean modifier, Runnable action, int row) {
                this.label = label; this.code = code; this.modifier = modifier;
                this.action = action; this.row = row;
            }
        }

        /** Row 0: the mode switches and the modifiers. Row 1: the keys. */
        private static final int ROWS = 2;
        private static final float ROW_H = 32f;    /* dp, one row of keys */
        private static final float ROW_PAD = 3f;   /* dp, around and between rows */

        private final List<Key> keys = new ArrayList<>();
        /** The touch/touchpad switch, or null when the window does not offer one. */
        private final TouchpadToggle pad;
        private Key padKey;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int downIndex = -1;

        /** Height in pixels of a key bar. The window reserves exactly this many
         *  pixels out of the desktop surface, so the reservation and the drawn
         *  bar have to come from one place — they drifted apart the moment the
         *  bar changed height, because the reservation was a hardcoded 38 dp. */
        public static int heightPx(Context c) {
            float d = c.getResources().getDisplayMetrics().density;
            return Math.round((ROW_H * ROWS + ROW_PAD * (ROWS + 1)) * d);
        }

        /** @param imeToggle run by the keyboard key — summons/dismisses the
         *  Android IME (the bridge that feeds the text-input protocol). May be
         *  null when the summon ability is unavailable.
         *  @param pad the touch/touchpad switch, or null for no such key. Its
         *  key shows the current mode through the usual on/off highlight.
         *  @param settings opens the host app's settings screen, or null. */
        public KeyBar(Context c, WinId winId, Runnable imeToggle, TouchpadToggle pad,
                      Runnable settings) {
            super(c);
            this.winId = winId;
            this.pad = pad;
            /* Row 0: window controls first, then the modifiers — the keys a
             * shortcut needs, and the widest labels, which is what the two-row
             * layout is for. Row 1: everything that is a plain keystroke. */
            if (imeToggle != null)
                keys.add(new Key("⌨", imeToggle, 0));
            if (pad != null) {
                /* a window control, not a keystroke: nothing is sent until the
                 * pointer events the pad produces are */
                padKey = new Key("Pad", () -> { pad.toggle(); syncPadKey(); }, 0);
                padKey.on = pad.isOn();
                keys.add(padKey);
            }
            if (settings != null)
                keys.add(new Key("⚙", settings, 0));
            keys.add(new Key("Ctrl", KEY_LEFTCTRL, true, 0));
            keys.add(new Key("Alt", KEY_LEFTALT, true, 0));
            keys.add(new Key("Shift", KEY_LEFTSHIFT, true, 0));
            keys.add(new Key("Super", KEY_LEFTMETA, true, 0));
            keys.add(new Key("Esc", KEY_ESC, false, 1));
            keys.add(new Key("Tab", KEY_TAB, false, 1));
            keys.add(new Key("←", KEY_LEFT, false, 1));
            keys.add(new Key("↑", KEY_UP, false, 1));
            keys.add(new Key("↓", KEY_DOWN, false, 1));
            keys.add(new Key("→", KEY_RIGHT, false, 1));
            keys.add(new Key("Enter", KEY_ENTER, false, 1));
            keys.add(new Key("⌫", KEY_BACKSPACE, false, 1));
            bg.setColor(0xE0202020);
            fg.setColor(Color.WHITE);
            fg.setTextAlign(Paint.Align.CENTER);
            fg.setTextSize(dp(13));
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        /** Release every latched modifier (window losing focus would otherwise
         *  leave the compositor believing Ctrl is still held). */
        public void releaseAll() {
            for (Key k : keys) {
                if (k.modifier && k.on) {
                    k.on = false;
                    AwlClient.input(winId.get(), KEY, k.code, 0, 0, 0, 0, 0);
                }
            }
            postInvalidateOnAnimation();
        }

        /** Re-read the mode and redraw the key. The switch can also be flipped
         *  elsewhere (the settings page), and the bar has to show that. */
        public void syncPadKey() {
            if (padKey != null && pad != null) {
                padKey.on = pad.isOn();
                postInvalidateOnAnimation();
            }
        }

        @Override protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), wSpec),
                                 heightPx(getContext()));
        }

        @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
            float pad = dp(ROW_PAD), gap = dp(ROW_PAD);
            float rowH = (h - pad * (ROWS + 1)) / ROWS;
            for (int r = 0; r < ROWS; r++) {
                int n = 0;
                for (Key k : keys) if (k.row == r) n++;
                if (n == 0) continue;
                /* per row: a row with fewer keys gets wider ones */
                float kw = (w - pad * 2 - gap * (n - 1)) / n;
                int i = 0;
                for (Key k : keys) {
                    if (k.row != r) continue;
                    float x = pad + i * (kw + gap);
                    float y = pad + r * (rowH + pad);
                    k.rect.set(x, y, x + kw, y + rowH);
                    i++;
                }
            }
        }

        @Override protected void onDraw(Canvas cv) {
            for (int i = 0; i < keys.size(); i++) {
                Key k = keys.get(i);
                bg.setColor(k.on ? 0xE0406080 : (i == downIndex ? 0xE0505050 : 0xE0202020));
                cv.drawRoundRect(k.rect, dp(5), dp(5), bg);
                cv.drawText(k.label, k.rect.centerX(),
                        k.rect.centerY() - (fg.descent() + fg.ascent()) / 2f, fg);
            }
        }

        private int hit(float x, float y) {
            for (int i = 0; i < keys.size(); i++)
                if (keys.get(i).rect.contains(x, y)) return i;
            return -1;
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downIndex = hit(ev.getX(), ev.getY());
                if (downIndex < 0) return false;
                Key k = keys.get(downIndex);
                if (k.action != null) {
                    k.action.run();   /* window control, not a keystroke */
                    invalidate();
                    return true;
                }
                if (k.modifier) {
                    k.on = !k.on;   /* latch: keep the key held until tapped again */
                    AwlClient.input(winId.get(), KEY, k.code, 0, 0, k.on ? 1 : 0, 0, 0);
                } else {
                    AwlClient.input(winId.get(), KEY, k.code, 0, 0, 1, 0, 0);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                if (downIndex >= 0) {
                    Key k = keys.get(downIndex);
                    if (!k.modifier && k.action == null)
                        AwlClient.input(winId.get(), KEY, k.code, 0, 0, 0, 0, 0);
                    downIndex = -1;
                    invalidate();
                }
                return true;
            }
            }
            return true;
        }
    }

    /* ======================================================================
     * On-screen touchpad
     * ==================================================================== */

    /**
     * Screen area used as a laptop touchpad: the finger drives the pointer.
     *
     *   one finger drag     → pointer motion, by the finger's delta (relative:
     *                         the pointer does not jump to the finger)
     *   one finger tap      → left click
     *   long press + drag   → drag with the button held
     *   two fingers, tap    → right click
     *   two fingers, drag   → scroll
     *
     * Motion is absolute rather than relative because the pad covers the screen,
     * so a finger position maps directly to a cursor position — that is what the
     * daemon's pointer protocol expects (x, y in view coordinates) and it needs
     * no cursor bookkeeping here.
     */
    public static final class TouchpadView extends View {
        private final WinId winId;

        private boolean inWindow;
        private boolean dragging;      /* long-press drag: keep BTN_LEFT down */
        private float downX, downY;
        private float lastX, lastY;    /* previous finger position, for the delta */

        /* The cursor lives here, not under the finger: a touchpad moves the
         * pointer by the finger's travel, so the pointer can be anywhere on the
         * desktop regardless of where the finger is (and the finger can be
         * lifted and re-placed to continue). */
        private float curX, curY;
        private boolean haveCursor;
        private long downTime;
        private float lastScrollY;
        private boolean twoFinger;
        private boolean pressMoved;    /* finger left the slop before the long press fired */
        private int slop;

        public TouchpadView(Context c, WinId winId) {
            super(c);
            this.winId = winId;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        /** Tell the compositor the pointer left: without it the client keeps a
         *  stale pointer focus (and the protocol requires enter before motion). */
        public void detachPointer() {
            if (inWindow) {
                inWindow = false;
                AwlClient.input(winId.get(), PTR_LEAVE, 0, 0, 0, 0, 0, 0);
            }
            if (dragging) {
                dragging = false;
                AwlClient.input(winId.get(), PTR_BUTTON, BTN_LEFT, 0, 0, 0, 0, 0);
            }
        }

        /** Advance the pointer by a finger delta. PTR_MOTION carries the new
         *  absolute position (what every client follows); PTR_REL rides along so
         *  clients on zwp_relative_pointer_v1 get true relative motion. */
        private void moveBy(float dx, float dy) {
            if (!haveCursor) {
                curX = getWidth() / 2f;
                curY = getHeight() / 2f;
                haveCursor = true;
            }
            curX = Math.max(0f, Math.min(getWidth() - 1f, curX + dx));
            curY = Math.max(0f, Math.min(getHeight() - 1f, curY + dy));
            if (!inWindow) {
                inWindow = true;
                AwlClient.input(winId.get(), PTR_ENTER, 0, curX, curY, 0, 0, 0);
            }
            if (dx != 0f || dy != 0f)
                AwlClient.input(winId.get(), PTR_REL, 0, dx, dy, 0, 0, 0);
            AwlClient.input(winId.get(), PTR_MOTION, 0, curX, curY, 0, 0, 0);
        }

        /* Draws nothing on purpose: this view spans the whole desktop, so
         * anything it paints overlays the desktop — a tint read as a grey veil,
         * a border as a white frame. It is an input surface only. */

        @Override public boolean onTouchEvent(MotionEvent ev) {
            final int n = ev.getPointerCount();
            switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = lastX = ev.getX();
                downY = lastY = ev.getY();
                downTime = ev.getEventTime();
                twoFinger = false;
                dragging = false;
                pressMoved = false;
                lastScrollY = ev.getY();
                /* No motion yet: the pointer must not jump to the finger. The
                 * very first touch seeds the cursor so a tap clicks in place. */
                if (!haveCursor) { curX = ev.getX(); curY = ev.getY(); haveCursor = true; }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                twoFinger = true;
                lastScrollY = averageY(ev);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (twoFinger && n >= 2) {
                    /* two fingers travelling together → scroll wheel notches
                     * (positive y = wheel down, matching a real touchpad) */
                    float y = averageY(ev);
                    float d = y - lastScrollY;
                    if (Math.abs(d) >= slop) {
                        lastScrollY = y;
                        AwlClient.input(winId.get(), PTR_AXIS, 0, 0, 0, d > 0 ? 1f : -1f, 0, 0);
                    }
                } else if (n == 1) {
                    /* Drag = press and HOLD STILL, then move. The check must not
                     * be "has enough time passed": a slow cursor move also takes
                     * longer than the timeout and would start a drag (reported:
                     * moving the pointer turned into the left button being held).
                     * Once the finger has travelled past the slop this gesture is
                     * a move, so pressMoved latches and no drag can start. */
                    boolean moved = Math.abs(ev.getX() - downX) > slop
                            || Math.abs(ev.getY() - downY) > slop;
                    if (moved) pressMoved = true;
                    if (!dragging && !twoFinger && !pressMoved
                            && ev.getEventTime() - downTime > ViewConfiguration.getLongPressTimeout()) {
                        dragging = true;   /* held still, then moved = drag */
                        AwlClient.input(winId.get(), PTR_BUTTON, BTN_LEFT, curX, curY, 1, 0, 0);
                    }
                    moveBy(ev.getX() - lastX, ev.getY() - lastY);
                    lastX = ev.getX();
                    lastY = ev.getY();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                return true;

            case MotionEvent.ACTION_UP: {
                long dur = ev.getEventTime() - downTime;
                float dx = Math.abs(ev.getX() - downX), dy = Math.abs(ev.getY() - downY);
                /* clicks land where the POINTER is, not where the finger is */
                if (dragging) {
                    dragging = false;
                    AwlClient.input(winId.get(), PTR_BUTTON, BTN_LEFT, curX, curY, 0, 0, 0);
                } else if (dur < ViewConfiguration.getTapTimeout() + 100
                        && dx < slop && dy < slop) {
                    int btn = twoFinger ? BTN_RIGHT : BTN_LEFT;   /* quick tap = click */
                    AwlClient.input(winId.get(), PTR_BUTTON, btn, curX, curY, 1, 0, 0);
                    AwlClient.input(winId.get(), PTR_BUTTON, btn, curX, curY, 0, 0, 0);
                }
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                detachPointer();
                return true;
            }
            return true;
        }

        private static float averageY(MotionEvent ev) {
            float s = 0;
            for (int i = 0; i < ev.getPointerCount(); i++) s += ev.getY(i);
            return s / ev.getPointerCount();
        }
    }
}
