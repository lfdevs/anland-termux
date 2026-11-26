#!/usr/bin/env bash
# Void Linux PRoot launcher for KDE Plasma 6 on Anland Termux.
# Unlike the generic container script, this uses the standard APK's Termux
# $TMPDIR socket directly, so the PRoot does not need --shared-tmp.

set -u

TERMUX_PREFIX=/data/data/com.termux/files/usr
ANLAND_DIR=${ANLAND_DIR:-$TERMUX_PREFIX/tmp/anland}
ANLAND_SOCKET=${ANLAND_SOCKET:-$ANLAND_DIR/display_daemon.sock}
ANLAND_BIN=${ANLAND_BIN:-$(command -v anland 2>/dev/null || true)}
[[ -n $ANLAND_BIN ]] || ANLAND_BIN=$TERMUX_PREFIX/bin/anland

RED='\033[31m'
GREEN='\033[32m'
NC='\033[0m'

log_error() {
    printf '%b\n' "${RED}$*${NC}" >&2
}

log_info() {
    printf '%b\n' "${GREEN}$*${NC}"
}

daemon_uses_socket() {
    local proc_dir=$1 socket=$2 comm arg prev tmpdir=$TERMUX_PREFIX/tmp

    [[ -r $proc_dir/comm && -r $proc_dir/cmdline ]] || return 1
    read -r comm < "$proc_dir/comm" || return 1
    [[ $comm == anland ]] || return 1

    prev=
    while IFS= read -r -d '' arg; do
        if [[ $prev == --socket && $arg == "$socket" ]] || [[ $arg == "$socket" ]]; then
            return 0
        fi
        prev=$arg
    done < "$proc_dir/cmdline"

    if [[ -r $proc_dir/environ ]]; then
        while IFS= read -r -d '' arg; do
            if [[ $arg == TMPDIR=* && -n ${arg#*=} ]]; then
                tmpdir=${arg#*=}
                break
            fi
        done < "$proc_dir/environ"
    fi
    [[ $socket == "$tmpdir/anland/display_daemon.sock" ]]
}

wait_for_socket() {
    local socket=$1 attempts=50
    while [[ ! -S $socket && $attempts -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done
    [[ -S $socket ]]
}

prepare_anland_daemon() {
    local proc_dir pid attempts=50

    if [[ ! -x $ANLAND_BIN ]]; then
        log_error "The anland command is missing: $ANLAND_BIN"
        return 1
    fi

    mkdir -p "$ANLAND_DIR" || return 1
    chmod -R 777 "$ANLAND_DIR" || true

    for proc_dir in /proc/[0-9]*; do
        if daemon_uses_socket "$proc_dir" "$ANLAND_SOCKET"; then
            pid=${proc_dir##*/}
            if [[ -S $ANLAND_SOCKET ]]; then
                log_info "Reusing the existing Anland daemon at $ANLAND_SOCKET."
                return 0
            fi
            kill "$pid" > /dev/null 2>&1 || true
        fi
    done

    while [[ -S $ANLAND_SOCKET ]] && (( attempts > 0 )); do
        sleep 0.1
        attempts=$((attempts - 1))
    done

    rm -f -- "$ANLAND_SOCKET" || return 1
    setsid env TMPDIR="$TERMUX_PREFIX/tmp" "$ANLAND_BIN" \
        --socket "$ANLAND_SOCKET" \
        </dev/null >"$ANLAND_DIR/anland.log" 2>&1 &

    if ! wait_for_socket "$ANLAND_SOCKET"; then
        log_error "Anland daemon failed; see $ANLAND_DIR/anland.log"
        return 1
    fi
}

stop_plasma() {
    pkill -x plasmashell > /dev/null 2>&1 || true
    pkill -x kwin_wayland > /dev/null 2>&1 || true
    pkill -f '^startplasma-wayland$' > /dev/null 2>&1 || true
    sleep 1
}

runtime_dir=/run/user/$(id -u)
mkdir -p "$runtime_dir" /tmp/.X11-unix
chmod 700 "$runtime_dir" 2>/dev/null || true
chmod 1777 /tmp/.X11-unix 2>/dev/null || true

prepare_anland_daemon || exit 1
stop_plasma

unset DISPLAY WAYLAND_DISPLAY SSH_CONNECTION

export QT_QPA_PLATFORM=wayland
export XDG_CURRENT_DESKTOP=KDE
export XDG_SESSION_DESKTOP=KDE
export XDG_SESSION_TYPE=wayland
export XDG_RUNTIME_DIR="$runtime_dir"

export ANLAND=1
export ANLAND_SOCKET="$ANLAND_SOCKET"
export ANLAND_SKIP_IMPLICIT_SYNC_WAIT=1
export ANLAND_NO_DRM_DEVICE=1
export ANLAND_PIPEWIRE_UNRESTRICTED=1
export EGL_PLATFORM=surfaceless

# Native Void/Qualcomm GPU stack: KGSL surfaceless EGL plus Freedreno/Turnip.
export MESA_LOADER_DRIVER_OVERRIDE=kgsl
export TURNIP_KMD=kgsl
export GALLIUM_DRIVER=freedreno
export FD_FORCE_KGSL=1
export XWAYLAND_FORCE_KGSL_SURFACELESS=1
export LIBGL_DRIVERS_PATH=/usr/lib/dri
export VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json

rm -f "$runtime_dir"/wayland-* > /dev/null 2>&1 || true

am start --user 0 -n com.anland.termux/.MainActivity > /dev/null 2>&1 || true

log_info "Starting Plasma 6 on Anland. Switch to the Anland Termux app."
dbus-run-session -- startplasma-wayland
