#!/bin/bash
# anland-launch — the two launch modes, in one place.
#
#   anland-launch desktop [stop]      whole KDE Plasma desktop in ONE Android window
#   anland-launch app <command...>    a single app, its own Android window
#   anland-launch status              what is running
#
# Why two modes exist: anland's model is "every Wayland toplevel becomes one
# Android window". A whole desktop is therefore one nested compositor (KWin)
# whose own toplevel carries every app inside it, while an app started against
# anland directly gets a window of its own. Both can run at once — the mode is
# chosen per client by which socket it connects to:
#
#   desktop mode : KWin connects to anland; the apps inside it speak to KWin
#   app mode     : the app connects to anland directly
#
# Established by testing during bring-up:
#   * use startplasma-wayland, not a hand-rolled "kwin + plasmashell":
#     plasmashell was SIGKILLed within seconds that way while KWin survived
#     (no OOM involved — oom_score_adj was -1000).
#   * startplasmacompositor aborts with "Could not start D-Bus" unless
#     DBUS_SESSION_BUS_ADDRESS is set; ~/.anlandx-env provides it.
#   * MESA_LOADER_DRIVER_OVERRIDE must be msm — the anland session exports
#     kgsl, and kgsl breaks GBM/EGL here (EGL then reports no DRM node, which
#     KWin rejects).
#   * KWIN_DISABLE_VULKAN=1: Turnip lacks VK_EXT_physical_device_drm, which
#     KWin requires to map a Vulkan device to a DRM node.
#   * KWin needs the wayland protocols waylandbridge implements on top of core
#     wayland (wl_compositor >= v4, zwp_linux_dmabuf_v1 >= v4 with feedback,
#     wp_single_pixel_buffer_manager_v1, wp_presentation, wl_seat, wp_viewporter,
#     zwp_pointer_constraints_v1) — see the daemon's protocol section.
set -u

ANLAND_SOCK=wayland-anland       # anland's socket
KWIN_SOCK=wayland-0              # the guest compositor's own socket

session_env() {
    if [ -r "$HOME/.anlandx-env" ]; then
        set -a
        # shellcheck disable=SC1090
        . "$HOME/.anlandx-env"
        set +a
    fi
    export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
    unset GALLIUM_DRIVER FD_FORCE_KGSL DISPLAY
    export MESA_LOADER_DRIVER_OVERRIDE=msm
    if [ "${1:-}" = desktop ]; then
        export KWIN_DISABLE_VULKAN=1
    else
        unset KWIN_DISABLE_VULKAN
    fi
    export QT_QPA_PLATFORM=wayland
    export QT_FORCE_STDERR_LOGGING=1
}

require_session() {
    [ -n "${DBUS_SESSION_BUS_ADDRESS:-}" ] || {
        echo "no session bus: is anland-session running? (see ~/.anlandx-env)" >&2
        exit 1
    }
    [ -S "$XDG_RUNTIME_DIR/$ANLAND_SOCK" ] || {
        echo "no anland socket at $XDG_RUNTIME_DIR/$ANLAND_SOCK" >&2
        exit 1
    }
}

# Match on exact process names. KWin's command line contains "plasmashell", so
# a pattern match like `pkill -f plasmashell` also kills the compositor (that
# mistake cost a session during bring-up).
desktop_stop() {
    for p in $(pgrep -x plasmashell 2>/dev/null) \
             $(pgrep -x kwin_wayland 2>/dev/null) \
             $(pgrep -x kwin_wayland_wrapper 2>/dev/null) \
             $(pgrep -x startplasma-wayland 2>/dev/null) \
             $(pgrep -x plasma_waitforname 2>/dev/null); do
        kill "$p" 2>/dev/null
    done
    sleep 2
    for p in $(pgrep -x plasmashell 2>/dev/null) \
             $(pgrep -x kwin_wayland 2>/dev/null) \
             $(pgrep -x startplasma-wayland 2>/dev/null); do
        kill -9 "$p" 2>/dev/null
    done
}

desktop_start() {
    session_env desktop
    require_session
    desktop_stop
    echo "==> desktop mode: startplasma-wayland, nested in anland ($ANLAND_SOCK)"
    export WAYLAND_DISPLAY="$ANLAND_SOCK"
    exec startplasma-wayland
}

app_start() {
    session_env app
    require_session
    export WAYLAND_DISPLAY="$ANLAND_SOCK"
    echo "==> app mode: $* → $ANLAND_SOCK (its own Android window)"
    exec "$@"
}

count() { c=$(pgrep -c -x "$1" 2>/dev/null); echo "${c:-0}"; }

case "${1:-}" in
    desktop)
        shift
        if [ "${1:-}" = stop ]; then
            session_env desktop
            desktop_stop
            echo "desktop stopped"
        else
            desktop_start
        fi
        ;;
    app)
        shift
        [ $# -gt 0 ] || { echo "usage: anland-launch app <command...>" >&2; exit 1; }
        app_start "$@"
        ;;
    status)
        session_env app
        echo "desktop: KWin $(count kwin_wayland), plasmashell $(count plasmashell)"
        echo "sockets:"
        ls -l "$XDG_RUNTIME_DIR/$ANLAND_SOCK" 2>/dev/null | sed 's/^/  anland  /'
        ls -l "$XDG_RUNTIME_DIR/$KWIN_SOCK" 2>/dev/null | sed 's/^/  desktop /'
        # the daemon itself runs on the host, outside this container
        echo "note: the anland daemon runs on Android, not in this container"
        ;;
    *)
        cat <<'EOF'
usage:
  anland-launch desktop          start the whole Plasma desktop (one Android window)
  anland-launch desktop stop     stop it
  anland-launch app <command>    run one app in its own Android window
  anland-launch status           show what is running
EOF
        exit 1
        ;;
esac
