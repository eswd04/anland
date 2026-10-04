package com.anlandnext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.anlandnext.awl.Awl;

import java.util.List;
import java.util.Locale;

/**
 * Window list (launcher entry), implemented on the libawl client library —
 * the same API third-party consumer apps use (Awl.getWindows /
 * registerCallback / attachWindow / closeWindow). The daemon scopes by
 * caller identity: this APK is package-authenticated, so it manages every
 * window; a consumer app only ever sees its own uid's.
 * Refreshed from daemon window events (create/destroy/attach/detach) while
 * resumed, plus a snapshot pull on resume (events are live-edge only).
 * Tap → attachWindow (bring-to-front / re-attach). Long-press → dropdown
 * menu (the long-press itself does nothing, guarding against accidental
 * triggers):
 *   Close — the only close entry (daemon T_CLOSE → the client exits
 *   cleanly);
 *   Window info — id/title/attach state.
 */
public class MainActivity extends Activity {
    private static final int MENU_CLOSE = 1;
    private static final int MENU_INFO = 2;

    private LinearLayout list;
    private TextView status;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = this::refresh;

    /** daemon window events → one coalesced snapshot pull (a re-attach bursts
     *  detach+attach; a window going away bursts destroy+detach) */
    private final Awl.Callback events = new Awl.Callback() {
        @Override public void onWindowCreated(long id, String title) { scheduleRefresh(); }
        @Override public void onWindowDestroyed(long id) { scheduleRefresh(); }
        @Override public void onWindowAttached(long id) { scheduleRefresh(); }
        @Override public void onWindowDetached(long id) { scheduleRefresh(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 16), p, Ui.dp(this, 10));

        status = new TextView(this);
        status.setTextSize(14);
        status.setAlpha(0.75f);
        status.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(status);

        android.widget.Button settings = Ui.action(this, R.string.settings_title);
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, WlSettingsActivity.class)));
        root.addView(settings);

        root.addView(Ui.gap(this, 14));

        ScrollView sc = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sc.addView(list);
        root.addView(sc, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Awl.registerCallback(events);   /* live list updates while resumed (daemon disconnects a paused subscriber) */
        refresh();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        Awl.unregisterCallback(events);   /* last callback out → subscription dropped */
        super.onPause();
    }

    /** event-burst coalescing: one snapshot pull per burst */
    private void scheduleRefresh() {
        handler.removeCallbacks(refresher);
        handler.postDelayed(refresher, 60);
    }

    private void refresh() {
        list.removeAllViews();
        List<Awl.WlWindow> wins = Awl.getWindows();
        if (wins == null) {
            status.setText(R.string.status_daemon_unreachable);
            status.setTextColor(Ui.stateDetached());
            return;
        }
        Awl.ensureSubscribed();   /* daemon restarted under us → re-subscribe the event stream */
        status.setTextColor(Ui.themeColor(this, android.R.attr.textColorPrimary, 0xFF888888));
        status.setText(getString(R.string.status_window_count, wins.size()));
        for (int i = 0; i < wins.size(); i++) {
            if (i > 0) list.addView(Ui.divider(this));
            list.addView(row(wins.get(i)));
        }
        if (wins.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.status_empty);
            empty.setGravity(Gravity.CENTER);
            empty.setTextSize(14);
            empty.setAlpha(0.6f);
            empty.setLineSpacing(Ui.dp(this, 3), 1f);
            empty.setPadding(0, Ui.dp(this, 72), 0, 0);
            list.addView(empty);
        }
    }

    private View row(Awl.WlWindow w) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int px = Ui.dp(this, 6);
        r.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 14));
        r.setMinimumHeight(Ui.dp(this, 56));

        TextView t = new TextView(this);
        t.setText(w.title == null || w.title.isEmpty()
                ? getString(R.string.window_fallback_title, w.id) : w.title);
        t.setTextSize(16);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(px, 0, px, 0);
        r.addView(t, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (w.attached) t.setTextColor(Ui.themeColor(this, android.R.attr.textColorPrimary, 0xFF888888));
        else t.setAlpha(0.62f);   /* unattached windows read as secondary */

        TextView st = new TextView(this);
        st.setText(w.attached ? R.string.window_state_attached : R.string.window_state_detached);
        st.setTextSize(13);
        st.setTextColor(w.attached ? Ui.stateAttached() : Ui.stateDetached());
        r.addView(st);

        r.setOnClickListener(v -> {
            /* libawl hosting: AwlWindowActivity (merged from the library) —
             * the daemon evicts any previous holder of this window */
            Awl.attachWindow(MainActivity.this, w.id, w.title);
        });
        r.setOnLongClickListener(v -> {
            showRowMenu(v, w);
            return true;
        });
        return r;
    }

    /** Row long-press dropdown: Close (the only close entry) / Window info */
    private void showRowMenu(View anchor, Awl.WlWindow w) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(Menu.NONE, MENU_CLOSE, Menu.NONE, R.string.menu_close);
        menu.getMenu().add(Menu.NONE, MENU_INFO, Menu.NONE, R.string.menu_window_info);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == MENU_CLOSE) {
                Awl.closeWindow(w.id);   /* daemon decides: the client exits cleanly */
                scheduleRefresh();
            } else if (item.getItemId() == MENU_INFO) {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.menu_window_info)
                        .setMessage(String.format(Locale.US,
                                getString(R.string.window_info_format),
                                w.id,
                                w.title == null || w.title.isEmpty()
                                        ? getString(R.string.title_none) : w.title,
                                w.attached ? getString(R.string.state_attached_foreground)
                                           : getString(R.string.state_detached_kept)))
                        .setPositiveButton(R.string.dialog_ok, null)
                        .show();
            }
            return true;
        });
        menu.show();
    }
}
