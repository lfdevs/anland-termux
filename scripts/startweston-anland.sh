#!/usr/bin/env bash

set -euo pipefail

RED='\033[31m'
GREEN='\033[32m'
YELLOW='\033[33m'
NC='\033[0m'

WESTON_SOCKET=${WESTON_SOCKET:-wayland-anland}
ANLAND_WESTON_SCALE=${ANLAND_WESTON_SCALE:-1}
ANLAND_WESTON_START_ATTEMPTS=${ANLAND_WESTON_START_ATTEMPTS:-3}
ANLAND_WESTON_RETRY_WINDOW=${ANLAND_WESTON_RETRY_WINDOW:-60}
ANLAND_HAVE_KGSL=0

if [[ -r /dev/kgsl-3d0 ]]; then
    ANLAND_HAVE_KGSL=1
fi

show_usage() {
    printf '%s\n' \
        'Usage: startweston-anland.sh [--] [WESTON_OPTIONS...]' \
        '' \
        'Environment overrides:' \
        '  ANLAND_SOCKET              Display daemon socket path' \
        '  WESTON_SOCKET              Wayland socket name (default: wayland-anland)' \
        '  ANLAND_WESTON_SCALE        Output scale as a positive integer (default: 1)' \
        '  ANLAND_WESTON_XWAYLAND=0   Disable Xwayland' \
        '  ANLAND_WESTON_DEBUG=1      Enable the Weston debug extension' \
        '  ANLAND_WESTON_START_ATTEMPTS  Maximum startup attempts (default: 3)' \
        '  ANLAND_WESTON_RETRY_WINDOW    Retry crashes within this many seconds (default: 60)' \
        '  ANLAND_AUDIO_DEBUG=1       Enable verbose PipeWire/WirePlumber logs' \
        '  ANLAND_LOG_DIR             Directory for PipeWire service logs'
}

print_error() {
    printf '%b\n' "${RED}$*${NC}" >&2
}

print_warning() {
    printf '%b\n' "${YELLOW}$*${NC}" >&2
}

validate_weston_scale() {
    if [[ ! $ANLAND_WESTON_SCALE =~ ^[1-9][0-9]*$ ]]; then
        print_error "ANLAND_WESTON_SCALE must be a positive integer: $ANLAND_WESTON_SCALE"
        return 1
    fi
}

validate_retry_settings() {
    if [[ ! $ANLAND_WESTON_START_ATTEMPTS =~ ^[1-9][0-9]*$ ]]; then
        print_error "ANLAND_WESTON_START_ATTEMPTS must be a positive integer: $ANLAND_WESTON_START_ATTEMPTS"
        return 1
    fi

    if [[ ! $ANLAND_WESTON_RETRY_WINDOW =~ ^[1-9][0-9]*$ ]]; then
        print_error "ANLAND_WESTON_RETRY_WINDOW must be a positive integer: $ANLAND_WESTON_RETRY_WINDOW"
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
        [[ $socket_path == "$TMPDIR/anland/display_daemon.sock" ]]
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
    unset DISPLAY SSH_CONNECTION
    unset ANLAND ANLAND_NO_DRM_DEVICE ANLAND_DRM_DEVICE EGL_PLATFORM
    unset MESA_LOADER_DRIVER_OVERRIDE TURNIP_KMD GALLIUM_DRIVER
    unset FD_FORCE_KGSL XWAYLAND_FORCE_KGSL_SURFACELESS

    export QT_QPA_PLATFORM=wayland
    export XDG_CURRENT_DESKTOP=Weston
    export XDG_SESSION_DESKTOP=Weston
    export XDG_SESSION_TYPE=wayland
    export WAYLAND_DISPLAY="$WESTON_SOCKET"
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

stop_weston() {
    local attempts=20

    pkill -x -u "$(id -u)" weston > /dev/null 2>&1 || true
    while pgrep -x -u "$(id -u)" weston > /dev/null 2>&1 &&
        [[ $attempts -gt 0 ]]; do
        sleep 0.1
        attempts=$((attempts - 1))
    done
}

clean_weston_socket() {
    rm -f -- \
        "$XDG_RUNTIME_DIR/$WESTON_SOCKET" \
        "$XDG_RUNTIME_DIR/$WESTON_SOCKET.lock"
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
    run_as_root install -d -m 1777 /tmp/.X11-unix
}

run_weston_session() {
    local -a weston_args=(
        --backend=anland
        --renderer=gl
        "--disp-sock=$ANLAND_SOCKET"
        "--socket=$WESTON_SOCKET"
        "--scale=$ANLAND_WESTON_SCALE"
    )

    trap stop_audio_services EXIT
    start_audio_services

    if [[ ${ANLAND_WESTON_XWAYLAND:-1} -eq 1 ]]; then
        if command -v Xwayland > /dev/null 2>&1; then
            weston_args+=(--xwayland)
        else
            print_warning "Xwayland is unavailable; starting a Wayland-only Weston session."
        fi
    fi

    if [[ ${ANLAND_WESTON_DEBUG:-0} -eq 1 ]]; then
        weston_args+=(--debug)
    fi

    weston "${weston_args[@]}" "$@"
}

run_weston_with_retries() {
    local attempt=1
    local status elapsed started_at

    while true; do
        started_at=$SECONDS
        if dbus-run-session -- "$BASH" "${BASH_SOURCE[0]}" --weston-session "$@"; then
            return 0
        else
            status=$?
        fi
        elapsed=$((SECONDS - started_at))

        if [[ $attempt -ge $ANLAND_WESTON_START_ATTEMPTS ]] ||
            [[ $elapsed -gt $ANLAND_WESTON_RETRY_WINDOW ]] ||
            [[ $status -ne 134 && $status -ne 139 ]]; then
            return "$status"
        fi

        attempt=$((attempt + 1))
        print_warning "Weston crashed during startup (status $status after ${elapsed}s); retrying automatically ($attempt/$ANLAND_WESTON_START_ATTEMPTS)."
        stop_weston
        stop_audio_services
        clean_weston_socket
        sleep 1
    done
}

start_weston() {
    validate_weston_scale
    validate_retry_settings
    require_command weston weston
    require_command dbus-run-session dbus
    require_command install coreutils
    require_command pkill procps
    require_command pgrep procps

    if [[ -n ${TERMUX_VERSION:-} ]]; then
        prepare_termux_native
    else
        prepare_container
    fi

    set_common_environment
    if [[ $ANLAND_HAVE_KGSL -eq 1 ]]; then
        enable_kgsl
    fi

    export ANLAND_SOCKET WESTON_SOCKET ANLAND_WESTON_SCALE XDG_RUNTIME_DIR
    export ANLAND_WESTON_START_ATTEMPTS ANLAND_WESTON_RETRY_WINDOW
    stop_weston
    stop_audio_services
    clean_weston_socket

    printf '%b\n' "${GREEN}Starting Weston. Please switch to the \"Anland Termux\" app.${NC}"
    run_weston_with_retries "$@"
}

case ${1:-} in
    --weston-session)
        shift
        run_weston_session "$@"
        ;;
    -h|--help)
        show_usage
        ;;
    --)
        shift
        start_weston "$@"
        ;;
    *)
        start_weston "$@"
        ;;
esac
