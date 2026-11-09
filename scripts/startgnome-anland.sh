#!/usr/bin/env bash

set -euo pipefail

RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
NC='\033[0m'

GNOME_WAYLAND_DISPLAY=${GNOME_WAYLAND_DISPLAY:-wayland-anland}
ANLAND_GNOME_DEBUG=${ANLAND_GNOME_DEBUG:-0}
ANLAND_GNOME_XWAYLAND=${ANLAND_GNOME_XWAYLAND:-1}
ANLAND_AUDIO_DEBUG=${ANLAND_AUDIO_DEBUG:-0}
ANLAND_HAVE_KGSL=0
ANLAND_PRIVATE_SYSTEM_BUS_PID=
ANLAND_PRIVATE_SYSTEM_BUS_SOCKET=
ANLAND_GNOME_SESSION_PID=
ANLAND_GNOME_XSETTINGS_PID=
ANLAND_GNOME_XSETTINGS_SERVICE=
ANLAND_GNOME_XSETTINGS_DATA_DIR=${ANLAND_GNOME_XSETTINGS_DATA_DIR:-}
SCRIPT_PATH=$(readlink -f -- "${BASH_SOURCE[0]}")

if [[ -r /dev/kgsl-3d0 ]]; then
    ANLAND_HAVE_KGSL=1
fi

show_usage() {
    printf '%s\n' \
        'Usage: startgnome-anland.sh' \
        '' \
        'Environment overrides:' \
        '  ANLAND_SOCKET              Display daemon socket path' \
        '  GNOME_WAYLAND_DISPLAY      Wayland socket name (default: wayland-anland)' \
        '  ANLAND_GNOME_XWAYLAND=0    Disable Xwayland' \
        '  ANLAND_GNOME_DEBUG=1       Print GNOME Shell and session logs to the terminal' \
        '  ANLAND_AUDIO_DEBUG=1       Enable verbose PipeWire/WirePlumber logs' \
        '  ANLAND_LOG_DIR             Directory for session and audio logs'
}

print_error() {
    printf '%b\n' "${RED}$*${NC}" >&2
}

print_warning() {
    printf '%b\n' "${YELLOW}$*${NC}" >&2
}

validate_boolean() {
    local name=$1
    local value=$2

    if [[ $value != 0 && $value != 1 ]]; then
        print_error "$name must be 0 or 1: $value"
        return 1
    fi
}

run_as_root() {
    if [[ $EUID -eq 0 ]]; then
        "$@"
    elif command -v sudo > /dev/null 2>&1; then
        sudo "$@"
    else
        print_error "This operation needs root privileges, but sudo is unavailable: $*"
        return 1
    fi
}

require_command() {
    local command_name=$1
    local package_hint=${2:-$1}

    if ! command -v "$command_name" > /dev/null 2>&1; then
        print_error "Missing command: ${command_name}. Install ${package_hint} first."
        return 1
    fi
}

wait_for_socket() {
    local socket_path=$1
    local attempts=${2:-50}

    while [[ ! -S $socket_path && $attempts -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done

    [[ -S $socket_path ]]
}

wait_for_wireplumber() {
    local attempts=50
    local status

    if ! command -v wpctl > /dev/null 2>&1; then
        return 0
    fi

    while [[ $attempts -gt 0 ]]; do
        status=$(wpctl status --name 2> /dev/null || true)
        if [[ $status == *WirePlumber* ]]; then
            return 0
        fi

        sleep 0.1
        attempts=$((attempts - 1))
    done

    return 1
}

process_matches_anland_socket() {
    local proc_dir=$1
    local socket_path=$2
    local process_comm entry
    local -a process_args=()

    [[ -r $proc_dir/comm && -r $proc_dir/cmdline ]] || return 1
    read -r process_comm < "$proc_dir/comm" || return 1
    [[ $process_comm == anland ]] || return 1

    while IFS= read -r -d '' entry; do
        process_args+=("$entry")
    done < "$proc_dir/cmdline"

    if [[ ${#process_args[@]} -eq 1 ]]; then
        [[ -n ${TMPDIR:-} && $socket_path == "$TMPDIR/anland/display_daemon.sock" ]]
    elif [[ ${process_args[1]:-} == --socket ]]; then
        [[ ${process_args[2]:-} == "$socket_path" ]]
    else
        [[ ${process_args[1]:-} == "$socket_path" ]]
    fi
}

anland_daemon_uses_socket() {
    local socket_path=$1
    local proc_dir

    for proc_dir in /proc/[0-9]*; do
        process_matches_anland_socket "$proc_dir" "$socket_path" && return 0
    done

    return 1
}

stop_anland_daemon() {
    local socket_path=$1
    local proc_dir
    local attempts=50

    for proc_dir in /proc/[0-9]*; do
        if process_matches_anland_socket "$proc_dir" "$socket_path"; then
            kill "${proc_dir##*/}" > /dev/null 2>&1 || true
        fi
    done

    while anland_daemon_uses_socket "$socket_path" && [[ $attempts -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done

    if anland_daemon_uses_socket "$socket_path"; then
        print_error "The existing Anland daemon did not stop: $socket_path"
        return 1
    fi
}

set_common_environment() {
    local prefix

    unset DISPLAY
    unset ANLAND ANLAND_NO_DRM_DEVICE ANLAND_DRM_DEVICE EGL_PLATFORM
    unset MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER
    unset FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS

    export QT_QPA_PLATFORM=wayland
    export XDG_CURRENT_DESKTOP=GNOME
    export XDG_SESSION_DESKTOP=gnome
    export XDG_SESSION_TYPE=wayland
    export GNOME_SHELL_SESSION_MODE=gnome
    export WAYLAND_DISPLAY="$GNOME_WAYLAND_DISPLAY"

    prefix=${PREFIX:-}
    prefix=${prefix%/}
    export XDG_DATA_DIRS="${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
    export XDG_CONFIG_DIRS="${XDG_CONFIG_DIRS:-/etc/xdg}"
    if [[ -n $prefix ]]; then
        export XDG_DATA_DIRS="$prefix/share:$XDG_DATA_DIRS"
        export XDG_CONFIG_DIRS="$prefix/etc/xdg:$XDG_CONFIG_DIRS"
    fi

    if [[ -n ${TERMUX_VERSION:-} ]] && xwayland_is_enabled; then
        # dbus-daemon snapshots XDG_DATA_DIRS when dbus-run-session starts.
        # Put the temporary service in a directory it will search later.
        ANLAND_GNOME_XSETTINGS_DATA_DIR="$XDG_RUNTIME_DIR/anland-gnome-xsettings/share"
        export ANLAND_GNOME_XSETTINGS_DATA_DIR
        export XDG_DATA_DIRS="$ANLAND_GNOME_XSETTINGS_DATA_DIR:$XDG_DATA_DIRS"
    fi
}

prepare_ptyxis_systemd_run_shim() {
    local shim_dir="$XDG_RUNTIME_DIR/anland-ptyxis"
    local systemd_run

    # Ptyxis only probes systemd-run's version before using --user --scope.
    # A Chroot can provide that binary without a running user manager.
    [[ -S $XDG_RUNTIME_DIR/systemd/private ]] && return 0

    systemd_run=$(command -v systemd-run || true)
    [[ -n $systemd_run && -x $systemd_run ]] || return 0

    install -d -m 0700 "$shim_dir"
    # shellcheck disable=SC2016
    printf '%s\n' \
        '#!/bin/sh' \
        'if [ "${1:-}" = "--version" ] &&' \
        '    [ "$(cat "/proc/$PPID/comm" 2>/dev/null || true)" = "ptyxis-agent" ]; then' \
        '    exit 1' \
        'fi' \
        "exec \"$systemd_run\" \"\$@\"" \
        > "$shim_dir/systemd-run"
    chmod 0700 "$shim_dir/systemd-run"
    export PATH="$shim_dir:$PATH"
}

enable_kgsl() {
    export MESA_LOADER_DRIVER_OVERRIDE=kgsl
    export TURNIP_KMD=kgsl
    export GALLIUM_DRIVER=freedreno
    export FD_FORCE_KGSL=1
    export XWAYLAND_FORCE_KGSL_SURFACELESS=1
}

process_matches_pipewire_runtime() {
    local proc_dir=$1
    local process_name=$2
    local process_comm entry
    local process_pipewire_runtime process_xdg_runtime

    [[ -r $proc_dir/comm && -r $proc_dir/environ ]] || return 1
    read -r process_comm < "$proc_dir/comm" || return 1
    [[ $process_comm == "$process_name" ]] || return 1

    while IFS= read -r -d '' entry; do
        case $entry in
            PIPEWIRE_RUNTIME_DIR=*) process_pipewire_runtime=${entry#*=} ;;
            XDG_RUNTIME_DIR=*) process_xdg_runtime=${entry#*=} ;;
        esac
    done < "$proc_dir/environ"

    [[ ${process_pipewire_runtime:-${process_xdg_runtime:-}} == "$XDG_RUNTIME_DIR" ]]
}

process_uses_pipewire_runtime() {
    local process_name=$1
    local proc_dir

    for proc_dir in /proc/[0-9]*; do
        process_matches_pipewire_runtime "$proc_dir" "$process_name" && return 0
    done

    return 1
}

stop_audio_services() {
    local process_name proc_dir
    local attempts=20

    for process_name in pipewire-pulse wireplumber pipewire; do
        for proc_dir in /proc/[0-9]*; do
            if process_matches_pipewire_runtime "$proc_dir" "$process_name"; then
                kill "${proc_dir##*/}" > /dev/null 2>&1 || true
            fi
        done
    done

    while [[ $attempts -gt 0 ]]; do
        if ! process_uses_pipewire_runtime pipewire-pulse &&
            ! process_uses_pipewire_runtime wireplumber &&
            ! process_uses_pipewire_runtime pipewire; then
            break
        fi
        sleep 0.1
        attempts=$((attempts - 1))
    done

    rm -f -- \
        "$XDG_RUNTIME_DIR/pipewire-0" \
        "$XDG_RUNTIME_DIR/pipewire-0.lock" \
        "$XDG_RUNTIME_DIR/anland-pulse/native"
}

write_unrestricted_pipewire_config() {
    local config_home=$1

    mkdir -p \
        "$config_home/pipewire/pipewire.conf.d" \
        "$config_home/wireplumber/wireplumber.conf.d"

    printf '%s\n' \
        'module.access.args = {' \
        '    access.socket = {' \
        '        pipewire-0 = "unrestricted"' \
        '        pipewire-0-manager = "unrestricted"' \
        '    }' \
        '}' \
        > "$config_home/pipewire/pipewire.conf.d/99-anland-access.conf"

    printf '%s\n' \
        'access.rules = [' \
        '    {' \
        '        matches = [ { access = "flatpak" } ]' \
        '        actions = {' \
        '            update-props = {' \
        '                access = "unrestricted"' \
        '                default_permissions = "all"' \
        '            }' \
        '        }' \
        '    }' \
        ']' \
        > "$config_home/wireplumber/wireplumber.conf.d/99-anland-access.conf"
}

start_audio_services() {
    local audio_log_dir=${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}
    local pipewire_config_home="$XDG_RUNTIME_DIR/anland-pipewire-config"
    local command_name
    local -a pipewire_server_env=(env)
    local -a pipewire_client_env=(env)
    local -a wireplumber_env=(env)

    for command_name in pipewire wireplumber; do
        if ! command -v "$command_name" > /dev/null 2>&1; then
            print_warning "PipeWire services disabled: missing ${command_name}. Install pipewire (Termux) or pipewire-audio (Debian/Ubuntu)."
            return 0
        fi
    done

    if [[ ${ANLAND_AUDIO_DEBUG:-0} -eq 1 ]]; then
        pipewire_server_env+=("PIPEWIRE_DEBUG=I,mod.protocol-native:T,conn.*:T")
        pipewire_client_env+=("PIPEWIRE_DEBUG=I,mod.protocol-pulse:T,conn.*:T")
        wireplumber_env+=("WIREPLUMBER_DEBUG=4")
    fi

    export PIPEWIRE_RUNTIME_DIR="$XDG_RUNTIME_DIR"
    export PULSE_RUNTIME_PATH="$XDG_RUNTIME_DIR/anland-pulse"
    export PULSE_SERVER="unix:$PULSE_RUNTIME_PATH/native"
    mkdir -p "$audio_log_dir" "$PULSE_RUNTIME_PATH"

    if [[ ${ANLAND_PIPEWIRE_UNRESTRICTED:-0} -eq 1 ]]; then
        write_unrestricted_pipewire_config "$pipewire_config_home"
        pipewire_server_env+=("XDG_CONFIG_HOME=$pipewire_config_home")
        wireplumber_env+=("XDG_CONFIG_HOME=$pipewire_config_home")
    fi

    if [[ ! -S $PIPEWIRE_RUNTIME_DIR/pipewire-0 ]]; then
        "${pipewire_server_env[@]}" pipewire \
            > "$audio_log_dir/pipewire.log" 2>&1 &
        if ! wait_for_socket "$PIPEWIRE_RUNTIME_DIR/pipewire-0"; then
            print_warning "PipeWire failed to start. See $audio_log_dir/pipewire.log. Anland audio and camera nodes will be unavailable."
            return 0
        fi
    fi

    if ! process_uses_pipewire_runtime wireplumber; then
        "${wireplumber_env[@]}" wireplumber \
            > "$audio_log_dir/wireplumber.log" 2>&1 &
    fi

    if ! wait_for_wireplumber; then
        print_warning "WirePlumber did not become ready before GNOME startup. The first Anland connection may have no sound. See $audio_log_dir/wireplumber.log."
    fi

    if ! command -v pipewire-pulse > /dev/null 2>&1; then
        print_warning "pipewire-pulse is unavailable. Native PipeWire audio and camera forwarding remain enabled, but PulseAudio applications may have no sound."
        return 0
    fi

    if [[ ! -S $PULSE_RUNTIME_PATH/native ]]; then
        "${pipewire_client_env[@]}" pipewire-pulse \
            > "$audio_log_dir/pipewire-pulse.log" 2>&1 &
        if ! wait_for_socket "$PULSE_RUNTIME_PATH/native"; then
            print_warning "PulseAudio compatibility failed to start. See $audio_log_dir/pipewire-pulse.log."
        fi
    fi
}

stop_gnome() {
    local attempts=30
    local user_id

    user_id=$(id -u)
    pkill -x -u "$user_id" gnome-shell > /dev/null 2>&1 || true
    pkill -x -u "$user_id" gnome-session > /dev/null 2>&1 || true
    pkill -x -u "$user_id" gnome-session-b > /dev/null 2>&1 || true
    pkill -x -u "$user_id" gnome-session-service > /dev/null 2>&1 || true

    while pgrep -x -u "$user_id" gnome-shell > /dev/null 2>&1 &&
        [[ $attempts -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done
}

clean_gnome_socket() {
    rm -f -- \
        "$XDG_RUNTIME_DIR/$GNOME_WAYLAND_DISPLAY" \
        "$XDG_RUNTIME_DIR/$GNOME_WAYLAND_DISPLAY.lock"
}

prepare_termux_native() {
    local anland_pid

    if [[ -z ${TMPDIR:-} ]]; then
        print_error "TMPDIR is not set in the Termux environment."
        return 1
    fi

    export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-$TMPDIR/run}
    export ANLAND_SOCKET=${ANLAND_SOCKET:-$TMPDIR/anland/display_daemon.sock}
    install -d -m 0700 "$XDG_RUNTIME_DIR"
    install -d -m 1777 "$TMPDIR/.X11-unix"
    mkdir -p "${ANLAND_SOCKET%/*}"

    require_command anland anland

    if [[ -S $ANLAND_SOCKET ]] && anland_daemon_uses_socket "$ANLAND_SOCKET"; then
        printf '%b\n' "${GREEN}Reusing the existing Anland daemon at $ANLAND_SOCKET.${NC}"
        return 0
    fi

    stop_anland_daemon "$ANLAND_SOCKET"
    rm -f -- "$ANLAND_SOCKET"
    anland --socket "$ANLAND_SOCKET" \
        > "${ANLAND_SOCKET%/*}/anland.log" 2>&1 &
    anland_pid=$!
    if ! wait_for_socket "$ANLAND_SOCKET" || ! kill -0 "$anland_pid" 2> /dev/null; then
        print_error "The Anland daemon failed to create $ANLAND_SOCKET. See ${ANLAND_SOCKET%/*}/anland.log."
        return 1
    fi
}

prepare_container() {
    export XDG_RUNTIME_DIR=${XDG_RUNTIME_DIR:-/run/user/$(id -u)}
    export ANLAND_SOCKET=${ANLAND_SOCKET:-/tmp/anland/display_daemon.sock}
    export ANLAND_PIPEWIRE_UNRESTRICTED=${ANLAND_PIPEWIRE_UNRESTRICTED:-1}

    if [[ ! -d ${ANLAND_SOCKET%/*} ]]; then
        print_error "The shared Anland directory is missing: ${ANLAND_SOCKET%/*}. Start the Termux anland daemon and enter PRoot with --shared-tmp."
        return 1
    fi

    run_as_root chmod 0711 "${ANLAND_SOCKET%/*}"
    if ! wait_for_socket "$ANLAND_SOCKET"; then
        print_error "The Anland daemon socket is unavailable: $ANLAND_SOCKET. Start the daemon in Termux and ensure the container shares /tmp."
        return 1
    fi
    run_as_root chmod 0666 "$ANLAND_SOCKET"

    run_as_root install -d -m 0700 \
        -o "$(id -u)" -g "$(id -g)" "$XDG_RUNTIME_DIR"
    run_as_root install -d -m 1777 \
        -o "$(id -u)" -g "$(id -g)" /tmp/.X11-unix
}

find_gnome_session_file() {
    local data_dir
    local -a data_dirs

    IFS=: read -r -a data_dirs <<< "${XDG_DATA_DIRS:-/usr/local/share:/usr/share}"
    for data_dir in "${data_dirs[@]}"; do
        if [[ -r $data_dir/gnome-session/sessions/gnome.session ]]; then
            printf '%s\n' "$data_dir/gnome-session/sessions/gnome.session"
            return 0
        fi
    done

    return 1
}

xwayland_is_enabled() {
    [[ $ANLAND_GNOME_XWAYLAND -eq 1 ]] &&
        command -v Xwayland > /dev/null 2>&1
}

system_bus_is_available() {
    if command -v gdbus > /dev/null 2>&1; then
        gdbus call --system \
            --dest org.freedesktop.DBus \
            --object-path /org/freedesktop/DBus \
            --method org.freedesktop.DBus.Peer.Ping \
            > /dev/null 2>&1
    elif command -v dbus-send > /dev/null 2>&1; then
        dbus-send --system --print-reply \
            --dest=org.freedesktop.DBus \
            / org.freedesktop.DBus.Peer.Ping \
            > /dev/null 2>&1
    else
        return 1
    fi
}

session_systemd_is_available() {
    if command -v gdbus > /dev/null 2>&1; then
        gdbus call --session \
            --timeout 2 \
            --dest org.freedesktop.DBus \
            --object-path /org/freedesktop/DBus \
            --method org.freedesktop.DBus.GetNameOwner \
            org.freedesktop.systemd1 \
            > /dev/null 2>&1
    elif command -v dbus-send > /dev/null 2>&1; then
        dbus-send --session --print-reply --reply-timeout=2000 \
            --dest=org.freedesktop.DBus \
            /org/freedesktop/DBus \
            org.freedesktop.DBus.GetNameOwner \
            string:org.freedesktop.systemd1 \
            > /dev/null 2>&1
    else
        return 1
    fi
}

mutter_x11_scaling_is_available() {
    if command -v gdbus > /dev/null 2>&1; then
        gdbus call --session \
            --timeout 1 \
            --dest org.gnome.Mutter.X11 \
            --object-path /org/gnome/Mutter/X11 \
            --method org.freedesktop.DBus.Properties.Get \
            org.gnome.Mutter.X11 \
            UiScalingFactor \
            > /dev/null 2>&1
    elif command -v dbus-send > /dev/null 2>&1; then
        dbus-send --session --print-reply --reply-timeout=1000 \
            --dest=org.gnome.Mutter.X11 \
            /org/gnome/Mutter/X11 \
            org.freedesktop.DBus.Properties.Get \
            string:org.gnome.Mutter.X11 \
            string:UiScalingFactor \
            > /dev/null 2>&1
    else
        return 1
    fi
}

session_manager_initialized() {
    if command -v gdbus > /dev/null 2>&1; then
        gdbus call --session \
            --timeout 1 \
            --dest org.gnome.SessionManager \
            --object-path /org/gnome/SessionManager \
            --method org.gnome.SessionManager.Initialized \
            > /dev/null 2>&1
    elif command -v dbus-send > /dev/null 2>&1; then
        dbus-send --session --print-reply --reply-timeout=1000 \
            --dest=org.gnome.SessionManager \
            /org/gnome/SessionManager \
            org.gnome.SessionManager.Initialized \
            > /dev/null 2>&1
    else
        return 1
    fi
}

find_gnome_session_service() {
    local candidate

    if command -v gnome-session-service > /dev/null 2>&1; then
        command -v gnome-session-service
        return 0
    fi

    for candidate in \
        "${PREFIX:+${PREFIX%/}/libexec/gnome-session-service}" \
        "${PREFIX:+${PREFIX%/}/lib/gnome-session/gnome-session-service}" \
        /usr/libexec/gnome-session-service \
        /usr/lib/gnome-session/gnome-session-service; do
        if [[ -x $candidate ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

ensure_system_bus() {
    local attempts=20
    local bus_log_dir=${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}

    if system_bus_is_available; then
        return 0
    fi

    if [[ -z ${TERMUX_VERSION:-} ]] &&
        command -v service > /dev/null 2>&1 &&
        [[ -x /etc/init.d/dbus ]]; then
        mkdir -p "$bus_log_dir"
        if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
            run_as_root service dbus start || true
        else
            run_as_root service dbus start \
                > "$bus_log_dir/system-dbus.log" 2>&1 || true
        fi

        while [[ $attempts -gt 0 ]]; do
            if system_bus_is_available; then
                return 0
            fi
            sleep 0.1
            attempts=$((attempts - 1))
        done
    fi

    start_private_system_bus
}

start_private_system_bus() {
    local bus_pid

    if ! command -v dbus-daemon > /dev/null 2>&1; then
        return 1
    fi

    ANLAND_PRIVATE_SYSTEM_BUS_SOCKET="$XDG_RUNTIME_DIR/anland-system-bus"
    rm -f -- "$ANLAND_PRIVATE_SYSTEM_BUS_SOCKET"
    if ! bus_pid=$(dbus-daemon --session \
        --address="unix:path=$ANLAND_PRIVATE_SYSTEM_BUS_SOCKET" \
        --fork --nopidfile --print-pid=1); then
        ANLAND_PRIVATE_SYSTEM_BUS_SOCKET=
        return 1
    fi

    if [[ ! $bus_pid =~ ^[0-9]+$ ]]; then
        ANLAND_PRIVATE_SYSTEM_BUS_SOCKET=
        return 1
    fi

    ANLAND_PRIVATE_SYSTEM_BUS_PID=$bus_pid
    export DBUS_SYSTEM_BUS_ADDRESS="unix:path=$ANLAND_PRIVATE_SYSTEM_BUS_SOCKET"

    if ! system_bus_is_available; then
        stop_private_system_bus
        return 1
    fi

    print_warning "The container system D-Bus is unavailable; using a private compatibility bus for GNOME Shell."
}

stop_private_system_bus() {
    local attempts=20

    if [[ -n $ANLAND_PRIVATE_SYSTEM_BUS_PID ]] &&
        kill -0 "$ANLAND_PRIVATE_SYSTEM_BUS_PID" > /dev/null 2>&1; then
        kill "$ANLAND_PRIVATE_SYSTEM_BUS_PID" > /dev/null 2>&1 || true
        while kill -0 "$ANLAND_PRIVATE_SYSTEM_BUS_PID" > /dev/null 2>&1 &&
            [[ $attempts -gt 0 ]]; do
            sleep 0.1
            attempts=$((attempts - 1))
        done
    fi

    if [[ -n $ANLAND_PRIVATE_SYSTEM_BUS_SOCKET ]]; then
        rm -f -- "$ANLAND_PRIVATE_SYSTEM_BUS_SOCKET"
    fi

    ANLAND_PRIVATE_SYSTEM_BUS_PID=
    ANLAND_PRIVATE_SYSTEM_BUS_SOCKET=
}

stop_gnome_session_service() {
    local attempts=20
    local session_pid=$ANLAND_GNOME_SESSION_PID

    if [[ -n $session_pid ]] &&
        kill -0 "$session_pid" > /dev/null 2>&1; then
        kill "$session_pid" > /dev/null 2>&1 || true
        while kill -0 "$session_pid" > /dev/null 2>&1 &&
            [[ $attempts -gt 0 ]]; do
            sleep 0.1
            attempts=$((attempts - 1))
        done
        if kill -0 "$session_pid" > /dev/null 2>&1; then
            kill -KILL "$session_pid" > /dev/null 2>&1 || true
        fi
        wait "$session_pid" > /dev/null 2>&1 || true
    fi

    ANLAND_GNOME_SESSION_PID=
}

stop_termux_xsettings() {
    local attempts=20
    local xsettings_pid=$ANLAND_GNOME_XSETTINGS_PID

    if [[ -n $xsettings_pid ]] &&
        kill -0 "$xsettings_pid" > /dev/null 2>&1; then
        kill "$xsettings_pid" > /dev/null 2>&1 || true
        while kill -0 "$xsettings_pid" > /dev/null 2>&1 &&
            [[ $attempts -gt 0 ]]; do
            sleep 0.1
            attempts=$((attempts - 1))
        done
        wait "$xsettings_pid" > /dev/null 2>&1 || true
    fi

    ANLAND_GNOME_XSETTINGS_PID=
    if [[ -n $ANLAND_GNOME_XSETTINGS_SERVICE ]]; then
        rm -f -- "$ANLAND_GNOME_XSETTINGS_SERVICE"
    fi
    ANLAND_GNOME_XSETTINGS_SERVICE=
}

find_gsd_xsettings() {
    local candidate

    if command -v gsd-xsettings > /dev/null 2>&1; then
        command -v gsd-xsettings
        return 0
    fi

    for candidate in \
        "${PREFIX:+${PREFIX%/}/libexec/gsd-xsettings}" \
        "${PREFIX:+${PREFIX%/}/lib/gnome-settings-daemon/gsd-xsettings}" \
        /usr/libexec/gsd-xsettings \
        /usr/lib/gnome-settings-daemon/gsd-xsettings; do
        if [[ -x $candidate ]]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done

    return 1
}

start_termux_xsettings() {
    local xsettings_path
    local service_dir="$ANLAND_GNOME_XSETTINGS_DATA_DIR/dbus-1/services"
    local service_path="$service_dir/org.gnome.SettingsDaemon.XSettings.service"

    [[ -n ${TERMUX_VERSION:-} ]] || return 0
    xwayland_is_enabled || return 0

    if [[ -z $ANLAND_GNOME_XSETTINGS_DATA_DIR ]]; then
        print_warning "The Termux XSettings service directory is unavailable; X11 cursor scaling may be unavailable."
        return 1
    fi

    if ! xsettings_path=$(find_gsd_xsettings); then
        print_warning "Termux XSettings support is unavailable; gsd-xsettings was not found."
        return 1
    fi

    # GNOME Shell can take longer than the former ten-second timeout to
    # bring up Xwayland on a fresh Termux session. This helper is cleaned up
    # with the session, so wait until Mutter publishes the service that also
    # guarantees the D-Bus activation environment has DISPLAY and XAUTHORITY.
    while ! mutter_x11_scaling_is_available; do
        sleep 0.2
    done

    install -d -m 0700 "$service_dir"
    printf '%s\n' \
        '[D-BUS Service]' \
        'Name=org.gnome.SettingsDaemon.XSettings' \
        "Exec=$xsettings_path" \
        > "$service_path"
    ANLAND_GNOME_XSETTINGS_SERVICE=$service_path

    if command -v gdbus > /dev/null 2>&1; then
        if ! gdbus call --session \
            --timeout 2 \
            --dest org.freedesktop.DBus \
            --object-path /org/freedesktop/DBus \
            --method org.freedesktop.DBus.ReloadConfig \
            > /dev/null 2>&1; then
            print_warning "The session bus rejected its service configuration reload; X11 cursor scaling may be unavailable."
            return 1
        fi

        if ! gdbus call --session \
            --timeout 2 \
            --dest org.freedesktop.DBus \
            --object-path /org/freedesktop/DBus \
            --method org.freedesktop.DBus.StartServiceByName \
            org.gnome.SettingsDaemon.XSettings \
            0 \
            > /dev/null 2>&1; then
            print_warning "The session bus could not start gsd-xsettings; X11 cursor scaling may be unavailable."
            return 1
        fi
    elif command -v dbus-send > /dev/null 2>&1; then
        if ! dbus-send --session --print-reply --reply-timeout=2000 \
            --dest=org.freedesktop.DBus \
            /org/freedesktop/DBus \
            org.freedesktop.DBus.ReloadConfig \
            > /dev/null 2>&1; then
            print_warning "The session bus rejected its service configuration reload; X11 cursor scaling may be unavailable."
            return 1
        fi

        if ! dbus-send --session --print-reply --reply-timeout=2000 \
            --dest=org.freedesktop.DBus \
            /org/freedesktop/DBus \
            org.freedesktop.DBus.StartServiceByName \
            string:org.gnome.SettingsDaemon.XSettings \
            uint32:0 \
            > /dev/null 2>&1; then
            print_warning "The session bus could not start gsd-xsettings; X11 cursor scaling may be unavailable."
            return 1
        fi
    else
        print_warning "Neither gdbus nor dbus-send is available; cannot start gsd-xsettings."
        return 1
    fi

    if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
        printf '%s\n' 'GNOME XSettings service started for Xwayland.' >&2
    fi
}

cleanup_gnome_session() {
    stop_termux_xsettings
    stop_gnome_session_service
    stop_audio_services
    stop_private_system_bus
}

prepare_gnome_session_definition() {
    local standalone_service=${1:-0}
    local runtime_data_dir="$XDG_RUNTIME_DIR/anland-gnome-session/share"
    local runtime_config_dir="$XDG_RUNTIME_DIR/anland-gnome-session/config"
    local session_source
    local desktop_script_path=${SCRIPT_PATH//\\/\\\\}
    local shell_phase='X-GNOME-Autostart-Phase=DisplayServer'
    local component
    local -a masked_settings_daemons=()
    # GNOME Shell's non-systemd readiness notification uses this desktop ID.
    local -a session_filters=(
        -e ''
    )

    desktop_script_path=${desktop_script_path//\"/\\\"}
    if [[ $standalone_service -eq 1 ]]; then
        shell_phase='X-GNOME-Autostart-Phase=Application'
    fi

    if ! session_source=$(find_gnome_session_file); then
        return 1
    fi

    # The Termux Keyboard and Power plugins crash repeatedly. As required
    # components, they trigger gnome-session-failed. Start XSettings separately
    # after Mutter publishes the Xwayland activation environment.
    if [[ -n ${TERMUX_VERSION:-} ]]; then
        masked_settings_daemons=(
            org.gnome.SettingsDaemon.Keyboard
            org.gnome.SettingsDaemon.Power
            org.gnome.SettingsDaemon.XSettings
        )
        session_filters+=(
            -e '/^RequiredComponents=/s/org\.gnome\.SettingsDaemon\.Keyboard;//g'
            -e '/^RequiredComponents=/s/org\.gnome\.SettingsDaemon\.Power;//g'
            -e '/^RequiredComponents=/s/org\.gnome\.SettingsDaemon\.XSettings;//g'
        )
    elif ! xwayland_is_enabled; then
        masked_settings_daemons=(org.gnome.SettingsDaemon.XSettings)
        session_filters+=(
            -e '/^RequiredComponents=/s/org\.gnome\.SettingsDaemon\.XSettings;//g'
        )
    fi

    mkdir -p \
        "$runtime_data_dir/gnome-session/sessions" \
        "$runtime_config_dir/autostart"
    sed "${session_filters[@]}" \
        "$session_source" \
        > "$runtime_data_dir/gnome-session/sessions/anland.session"
    printf '%s\n' \
        '[Desktop Entry]' \
        'Type=Application' \
        'Name=GNOME Shell on Anland' \
        "Exec=\"$desktop_script_path\" --gnome-shell-session" \
        "$shell_phase" \
        'X-GNOME-Provides=windowmanager' \
        'X-GNOME-Autostart-Notify=true' \
        'NoDisplay=true' \
        > "$runtime_config_dir/autostart/org.gnome.Shell.desktop"

    for component in "${masked_settings_daemons[@]}"; do
        printf '%s\n' \
            '[Desktop Entry]' \
            'Type=Application' \
            "Name=$component" \
            'Exec=true' \
            'Hidden=true' \
            > "$runtime_config_dir/autostart/$component.desktop"
    done

    export XDG_DATA_DIRS="$runtime_data_dir:$XDG_DATA_DIRS"
    export XDG_CONFIG_DIRS="$runtime_config_dir:$XDG_CONFIG_DIRS"
}

run_gnome_shell() {
    local replace_process=${1:-0}
    local log_dir=${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}
    local -a shell_args=(
        --wayland
        --anland
        "--anland-socket=$ANLAND_SOCKET"
        "--wayland-display=$GNOME_WAYLAND_DISPLAY"
    )

    # This is a local desktop even when launched over SSH. libpulse uses
    # SSH_CONNECTION to query X11 properties synchronously, which can deadlock
    # GNOME Shell against its own Xwayland during startup.
    unset SSH_CONNECTION

    if ! xwayland_is_enabled; then
        shell_args+=(--no-x11)
    fi

    mkdir -p "$log_dir"
    if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
        if [[ $replace_process -eq 1 ]]; then
            exec gnome-shell "${shell_args[@]}"
        else
            gnome-shell "${shell_args[@]}"
        fi
    else
        if [[ $replace_process -eq 1 ]]; then
            exec gnome-shell "${shell_args[@]}" \
                > "$log_dir/gnome-shell.log" 2>&1
        else
            gnome-shell "${shell_args[@]}" \
                > "$log_dir/gnome-shell.log" 2>&1
        fi
    fi
}

run_gnome_session_service() {
    local service_path=$1
    local log_dir=${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}
    local attempts=100

    if ! command -v gdbus > /dev/null 2>&1 &&
        ! command -v dbus-send > /dev/null 2>&1; then
        print_warning "Neither gdbus nor dbus-send is available; cannot initialize the GNOME session service."
        return 1
    fi

    mkdir -p "$log_dir"
    if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
        GNOME_SESSION_DEBUG=1 "$service_path" --session=anland &
    else
        "$service_path" --session=anland \
            > "$log_dir/gnome-session-service.log" 2>&1 &
    fi
    ANLAND_GNOME_SESSION_PID=$!

    while [[ $attempts -gt 0 ]]; do
        if session_manager_initialized; then
            wait "$ANLAND_GNOME_SESSION_PID" > /dev/null 2>&1 || true
            ANLAND_GNOME_SESSION_PID=
            return 0
        fi

        if ! kill -0 "$ANLAND_GNOME_SESSION_PID" > /dev/null 2>&1; then
            wait "$ANLAND_GNOME_SESSION_PID" > /dev/null 2>&1 || true
            ANLAND_GNOME_SESSION_PID=
            return 1
        fi

        sleep 0.1
        attempts=$((attempts - 1))
    done

    if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
        print_warning "The GNOME session service did not become ready."
    else
        print_warning "The GNOME session service did not become ready; see $log_dir/gnome-session-service.log."
    fi
    stop_gnome_session_service
    return 1
}

run_gnome_session() {
    local session_service
    local standalone_service=0

    trap cleanup_gnome_session EXIT

    if ! ensure_system_bus; then
        print_error "Failed to start a system D-Bus or a private compatibility bus. GNOME Shell cannot start without one."
        return 1
    fi

    start_audio_services

    if ! session_systemd_is_available; then
        session_service=$(find_gnome_session_service || true)
        if [[ -n $session_service ]]; then
            standalone_service=1
        fi
    fi

    if ! prepare_gnome_session_definition "$standalone_service"; then
        print_error "A GNOME session definition is unavailable. Install or reinstall gnome-session and ensure its data directory is available through XDG_DATA_DIRS."
        return 1
    fi

    if [[ -n ${TERMUX_VERSION:-} ]] && xwayland_is_enabled; then
        # XSettings must start after Mutter publishes its X11 environment, but
        # before D-Bus-activated X11 applications are launched.
        ANLAND_GNOME_XSETTINGS_SERVICE="$ANLAND_GNOME_XSETTINGS_DATA_DIR/dbus-1/services/org.gnome.SettingsDaemon.XSettings.service"
        if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
            start_termux_xsettings &
        else
            mkdir -p "${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}"
            start_termux_xsettings \
                > "${ANLAND_LOG_DIR:-$XDG_RUNTIME_DIR/anland-logs}/gnome-xsettings.log" 2>&1 &
        fi
        ANLAND_GNOME_XSETTINGS_PID=$!
    fi

    if [[ $standalone_service -eq 1 ]]; then
        print_warning "The GNOME session systemd service is unavailable; using the standalone GNOME session service."
        if run_gnome_session_service "$session_service"; then
            return
        fi
        print_warning "The standalone GNOME session service failed; trying gnome-session directly."
    fi

    if command -v gnome-session > /dev/null 2>&1; then
        if [[ $ANLAND_GNOME_DEBUG -eq 1 ]]; then
            GNOME_SESSION_DEBUG=1 gnome-session --session=anland
        else
            gnome-session --session=anland
        fi
        return
    fi

    print_error "gnome-session is unavailable. Install gnome-session before starting GNOME on Anland."
    return 1
}

start_gnome() {
    validate_boolean ANLAND_GNOME_DEBUG "$ANLAND_GNOME_DEBUG"
    validate_boolean ANLAND_GNOME_XWAYLAND "$ANLAND_GNOME_XWAYLAND"
    validate_boolean ANLAND_AUDIO_DEBUG "$ANLAND_AUDIO_DEBUG"
    require_command gnome-shell gnome-shell
    require_command dbus-run-session dbus-x11
    require_command install coreutils
    require_command readlink coreutils
    require_command pkill procps
    require_command pgrep procps

    if [[ -n ${TERMUX_VERSION:-} ]]; then
        prepare_termux_native
    else
        prepare_container
    fi

    set_common_environment
    prepare_ptyxis_systemd_run_shim
    if [[ $ANLAND_HAVE_KGSL -eq 1 ]]; then
        enable_kgsl
    fi

    export ANLAND_SOCKET GNOME_WAYLAND_DISPLAY ANLAND_GNOME_DEBUG
    export ANLAND_GNOME_XWAYLAND XDG_RUNTIME_DIR SCRIPT_PATH
    stop_gnome
    stop_audio_services
    clean_gnome_socket
    if [[ -n $ANLAND_GNOME_XSETTINGS_DATA_DIR ]]; then
        rm -f -- "$ANLAND_GNOME_XSETTINGS_DATA_DIR/dbus-1/services/org.gnome.SettingsDaemon.XSettings.service"
    fi

    printf '%b\n' "${GREEN}Starting GNOME. Please switch to the \"Anland Termux\" app.${NC}"
    dbus-run-session -- "$SCRIPT_PATH" --gnome-session
}

case ${1:-} in
    --gnome-session)
        run_gnome_session
        ;;
    --gnome-shell-session)
        run_gnome_shell 1
        ;;
    -h|--help)
        show_usage
        ;;
    '')
        start_gnome
        ;;
    *)
        print_error "Unknown option: $1"
        show_usage >&2
        exit 2
        ;;
esac
