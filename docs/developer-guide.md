# Anland: Termux Developer Documentation

**English** | [中文](developer-guide_zh.md)

---

## Project Structure

Based on Anland, this project connects the Android display client, the Termux daemon, and Wayland compositors in containers. Its main directories are:

- `app/`: Android display app.
  - The Java layer handles interactions including activities, settings, input, clipboard, camera, and audio.
  - `app/src/main/jni/` contains native code for Surface, dma-buf, Unix sockets, and the JNI bridge.
  - Its package name is `com.anland.termux` and its app name is `Anland Termux`.
  - The `standard` flavor uses the shared UID `com.termux` and is signed with `app/testkey_untrusted.jks` for the GitHub version of Termux.
  - The `compatible` flavor omits `sharedUserId`, keeps the same application ID, versionCode, and signing key, and appends `-compatible` to versionName.
- `termux/anland/`: the `anland` daemon on the Termux side, which relays control messages and file descriptors between the Android display client and the Wayland producer. Its default socket is `$TMPDIR/anland/display_daemon.sock`; if `TMPDIR` is unset, it falls back to `/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock`.
- `termux/anland/anland-compatible`: Termux-side launcher for the compatible APK. It resolves the installed APK and starts `CompatibleBridge` with `app_process`.
- `packages/anland/`: draft Termux Packages recipe for building the daemon as a Termux package.
- `scripts/`: Helper startup scripts for KDE Plasma, GNOME, and Weston in the Termux native environment and PRoot, Chroot, and LXC containers.
- `images/`: ARM64 PRoot container image definitions for Debian 13 and Ubuntu 26.04. `images/packages.json` records download URLs for KWin, Mutter, Weston, XWayland, and Mesa build artifacts.
- `tools/`: local build entry points for the Android app and the Termux daemon.
- `.github/workflows/`: GitHub Actions workflows for APKs, Debian packages, and container images.
- `docs/`: English and Chinese user and developer documentation; Chinese files use the `_zh.md` suffix.
- `out/`: local build output directory for APKs and the daemon; do not commit it to Git.

## Transports

### Standard Transport

The standard APK relies on Android's shared-UID mechanism and is intended for the GitHub release of Termux:

1. The `standard` flavor manifest declares `android:sharedUserId="com.termux"` and is signed with `app/testkey_untrusted.jks`. During installation, Android verifies that its signature matches the installed Termux package; when it does, both apps run under the same Linux UID.
2. The `anland` daemon listens on `display_daemon.sock` as the Termux UID. Because the standard APK runs with that UID too, its display client can access the Unix socket without cross-UID socket permissions or an extra bridge process.
3. After `MainActivity` configures the default or user-selected socket path, the native consumer calls `connect_to_deamon()` outside compatible mode. That function calls `connect_unix()` to connect directly to the daemon. This path neither starts `anland-compatible` nor uses Binder.
4. Once the control connection is established, the native display context sends the consumer hello and its display-side file descriptors. The daemon registers it as the consumer, then relays screen information and required file descriptors when the Wayland producer connects.

This transport depends on the Termux and standard APK signatures matching. F-Droid Termux and variants signed with a different key cannot meet that condition, so Android rejects installation or updates of the standard APK; use the compatible APK in those environments. With the standard APK, start only the `anland` daemon, not `anland-compatible`.

### Compatible Transport

The compatible APK follows the standalone Termux:X11 transport and does not need
the F-Droid signing key:

1. `anland-compatible` runs under the Termux UID and loads
   `com.anland.termux.CompatibleBridge` from the installed APK with
   `/system/bin/app_process`.
2. `CompatibleBridge` connects to `display_daemon.sock` as the Termux UID,
   keeps the connected `LocalSocket` open, and sends a package-targeted
   broadcast containing an `ICompatibleBridge` Binder.
3. `MainActivity` receives the Binder, calls `getConnection()`, and detaches the
   returned `ParcelFileDescriptor`. `Native.nativeSetCompatibleFd()` gives the
   duplicate fd to `connect_to_deamon_with_fd()`.
4. The native display context owns that fd for its lifetime. Its existing
   fallback protocol can redeposit fresh data/fence/audio fds over the same
   control connection, so no Android-side Unix `connect()` is needed.

The user starts `anland-compatible [SOCKET_PATH]` from Termux, just as the
standalone Termux:X11 command starts its entry point. The bridge keeps running
and republishes its package-targeted Binder so an Activity recreated later can
obtain another duplicate fd; no F-Droid signing key or Termux plugin permission
is involved.

## Debugging

Set debugging variables before running the startup script. The examples below work in both the Termux native environment and containers. PRoot-Distro must be entered with `--shared-tmp`; Chroot and LXC containers must share the directory containing the Anland socket with Termux.

### Helper Startup Scripts

#### KDE Plasma

Script: `scripts/startplasma-anland.sh`

- `ANLAND_PLASMA_DEBUG=1`: stops redirecting standard output and standard error from `startplasma-wayland`, KWin, and the Plasma session, so startup logs are displayed directly in the current terminal. The default is `0`. This variable does not change Qt logging categories; for more detailed KWin logs, also set `QT_LOGGING_RULES`.
- `ANLAND_AUDIO_DEBUG=1`: enables verbose logging for PipeWire, `pipewire-pulse`, and WirePlumber by setting the script’s `PIPEWIRE_DEBUG` and `WIREPLUMBER_DEBUG` variables. Logs are saved in the directory containing the Anland socket, usually `$TMPDIR/anland/` in Termux or `/tmp/anland/` in containers.

For example, to display debug output from Plasma, KWin, and audio services at the same time:

```sh
ANLAND_PLASMA_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
QT_LOGGING_RULES='kwin*.debug=true' \
scripts/startplasma-anland.sh
```

#### Weston

Script: `scripts/startweston-anland.sh`

- `ANLAND_WESTON_DEBUG=1`: adds `--debug` to Weston, enabling the `weston_debug_v1` debugging protocol and the Weston screenshot interface. It does not automatically subscribe to additional log scopes.
- `ANLAND_AUDIO_DEBUG=1`: enables verbose logging for PipeWire, `pipewire-pulse`, and WirePlumber.
- `ANLAND_LOG_DIR=<directory>`: sets the directory for audio-service logs; the default is `$XDG_RUNTIME_DIR/anland-logs`.

For example:

```sh
ANLAND_WESTON_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
ANLAND_LOG_DIR=/tmp/anland-logs \
scripts/startweston-anland.sh
```

> [!WARNING]
> Weston’s `--debug` lets clients read debugging information and capture output, and may also allow malicious clients to block the compositor. Enable it only in trusted local debugging sessions, and disable it when debugging is complete.

#### GNOME

Script: `scripts/startgnome-anland.sh`

- `ANLAND_GNOME_DEBUG=1`: prints GNOME Shell, `gnome-session`, `gnome-session-service`, and Termux XSettings logs to the current terminal instead of redirecting them to files. The default is `0`.
- `ANLAND_AUDIO_DEBUG=1`: enables verbose PipeWire, `pipewire-pulse`, and WirePlumber logs.
- `ANLAND_GNOME_XWAYLAND=0`: disables XWayland. The default is `1`; use this for a Wayland-only GNOME session.
- `GNOME_WAYLAND_DISPLAY=<socket name>`: changes the Wayland socket name. The default is `wayland-anland`.
- `ANLAND_SOCKET=<path>`: overrides the Anland display-daemon socket path.
- `ANLAND_LOG_DIR=<directory>`: changes the directory for GNOME session, XSettings, D-Bus, and audio logs. The default is `$XDG_RUNTIME_DIR/anland-logs`.

The helper starts GNOME through `dbus-run-session`, prepares an Anland GNOME session definition, starts a private compatibility system bus when the container system bus is unavailable, and falls back to a standalone GNOME session service when a user systemd manager is not available. In Termux native sessions it starts the XSettings service after Mutter publishes the XWayland environment so X11 applications can receive the correct scaling information.

For example, to start GNOME with session and audio logs in the terminal:

```sh
ANLAND_GNOME_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
ANLAND_LOG_DIR=/tmp/anland-gnome-logs \
scripts/startgnome-anland.sh
```

### KWin

KWin uses Qt logging categories. When using the helper startup script, you must also set `ANLAND_PLASMA_DEBUG=1`; otherwise, the script discards KWin’s standard output and standard error.

- `QT_LOGGING_RULES='kwin*.debug=true'`: enables all debugging categories whose names start with `kwin`, producing a large volume of logs.
- `QT_LOGGING_RULES='kwin_core.debug=true;kwin_backend_anland.debug=true;kwin_scene_opengl.debug=true'`: enables only core, Anland backend, and OpenGL scene logs. This is suitable for most display issues in this project.
- `KWIN_GL_DEBUG=1`: lets KWin receive all OpenGL debugging messages when the driver supports OpenGL debug output. It is usually used with `kwin_scene_opengl.debug=true`.
- `KWIN_XWAYLAND_DEBUG=1`: sets `WAYLAND_DEBUG=1` for the XWayland instance started by KWin, printing Wayland protocol traffic between XWayland and KWin.
- `WAYLAND_DEBUG=1 <Wayland client>`: traces Wayland protocol traffic only for the specified client. This is usually easier to analyze than enabling protocol logging for the entire desktop.

A common combination for debugging the Anland backend and OpenGL initialization:

```sh
ANLAND_PLASMA_DEBUG=1 \
KWIN_GL_DEBUG=1 \
QT_LOGGING_RULES='kwin_core.debug=true;kwin_backend_anland.debug=true;kwin_scene_opengl.debug=true' \
scripts/startplasma-anland.sh 2>&1 | tee kwin-anland.log
```

### Mutter

Mutter is the GNOME compositor started by `gnome-shell` in the Anland session. The GNOME helper passes the Anland-specific `--anland`, `--anland-socket`, and `--wayland-display` options to GNOME Shell, which starts Mutter with the Anland backend. Use `ANLAND_GNOME_DEBUG=1` when debugging so GNOME Shell and Mutter output is not redirected to log files.

- `MUTTER_DEBUG=<topics>`: enables Mutter debug topics. Useful topics include `backend`, `render`, `wayland`, `input`, `kms`, `screen-cast`, `remote-desktop`, `x11`, and `startup`; multiple topics are comma-separated.
- `MUTTER_DEBUG_PAINT=<topics>`: enables paint diagnostics such as `opaque-region`, `disable-direct-scanout`, and `sync-cursor-primary`.
- `MUTTER_VERBOSE=1`: enables verbose Mutter logging when the build includes verbose-mode support.
- `COGL_DEBUG=show-source,performance`: enables Cogl graphics diagnostics, including shader-source and performance output. Use only the topics needed for the issue.
- `G_DEBUG=fatal-warnings,fatal-criticals`: makes GLib warnings and critical messages stop the process, which is useful when attaching GDB but can terminate the desktop session.

GNOME Shell also includes Looking Glass. Press `Alt+F2`, enter `lg`, and use it to inspect Mutter state, enable debug topics at runtime, show damage, or run GNOME Shell JavaScript. This is usually more convenient than restarting the entire session for a small compositor or shell investigation.

For example, to inspect Anland backend, rendering, and Wayland initialization:

```sh
ANLAND_GNOME_DEBUG=1 \
MUTTER_DEBUG=backend,render,wayland \
scripts/startgnome-anland.sh 2>&1 | tee mutter-anland.log
```

### Weston

`startweston-anland.sh` passes additional command-line arguments through to Weston unchanged. In addition to `ANLAND_WESTON_DEBUG=1`, you can use Weston’s built-in logging options:

- `--log=<file>`: appends Weston logs to the specified file.
- `--logger-scopes=<scope list>`: writes the specified scopes directly to the log. Common scopes include the general `log`, Wayland protocol traffic `proto`, and latency analysis `timeline`; available scopes depend on the Weston version and loaded backends.
- `--flight-rec-scopes=<scope list>`: writes the specified scopes to a ring buffer, which is useful for retaining recent information before a crash.
- `--wait-for-debugger`: pauses Weston after startup so that GDB can be attached. Send `SIGCONT` after attaching to continue execution.

For example, to write general logs and protocol traffic to a file:

```sh
scripts/startweston-anland.sh \
    --log=/tmp/weston.log \
    --logger-scopes=log,proto
```

The `proto` log can contain window titles, input, and client interactions. Review and remove sensitive information before sharing it.

### Mesa

Mesa debugging variables are inherited by KWin, Weston, XWayland, and the applications they start, and can be used with either helper startup script:

- `MESA_DEBUG=1`: enables Mesa error messages.
- `MESA_LOG_LEVEL=debug`: allows debug-level logs to be emitted.
- `EGL_LOG_LEVEL=debug`: enables detailed logs for EGL loading, configuration, and contexts.
- `LIBGL_DEBUG=verbose`: prints LibGL/GLX driver-loading information, useful for troubleshooting OpenGL applications under XWayland.
- `MESA_LOG_FILE_AUTO=1`: creates a separate `mesa_<process name>_<PID>_*.log` for each process in `/tmp/`, preventing multiple desktop processes from sharing one log file.
- `TU_DEBUG=startup`: prints Turnip Vulkan driver startup and device-initialization information.
- `FD_MESA_DEBUG=msgs,perf`: prints Freedreno Gallium driver messages and performance warnings.

For example, to record Mesa and EGL logs while Plasma/KWin starts:

```sh
ANLAND_PLASMA_DEBUG=1 \
MESA_DEBUG=1 \
MESA_LOG_LEVEL=debug \
EGL_LOG_LEVEL=debug \
MESA_LOG_FILE_AUTO=1 \
scripts/startplasma-anland.sh
```

When troubleshooting Qualcomm GPU hangs or rendering errors, you can also try options such as `TU_DEBUG=flushall`, `TU_DEBUG=syncdraw`, or `FD_MESA_DEBUG=flush` individually, according to the symptom. These options force synchronization, flush caches, or alter rendering paths; they can significantly reduce performance and mask timing issues. Do not use them in normal startup configurations or enable many of them at once.

> [!NOTE]
> Both startup scripts select a graphics path automatically based on `/dev/kgsl-3d0` and `/dev/dri/renderD128`, and clear inherited driver-selection variables such as `MESA_LOADER_DRIVER_OVERRIDE`, `TURNIP_KMD`, and `GALLIUM_DRIVER`. Do not use these variables to bypass the scripts’ device detection when collecting logs. If you need to force a graphics path, first confirm the device nodes and driver capabilities in both the Termux native environment and containers.

## Build

### Android Display App

Requirements:

```text
Android Gradle Plugin 9.4.0
Gradle 9.7.1
Android NDK 29.0.14206865
minSdk 30
compileSdk 37
compileSdkMinor 2
targetSdk 37
```

Build script:

```sh
tools/build-app.sh
```

This builds the `standardDebug` flavor. The compatible flavor is built with:

```sh
tools/build-compatible-app.sh
```

Build artifacts:

```text
out/AnlandTermux-<version>.apk
out/AnlandTermux-<version>-compatible.apk
```

Both flavors use `app/testkey_untrusted.jks` and the same `versionCode`. The
compatible flavor gets its `-compatible` versionName suffix from Gradle.

### Anland Daemon

Build script:

```sh
tools/build-termux-anland.sh
```

When this is run inside Termux, the output is a Termux executable:

```text
out/anland
```

`make -C termux/anland install` and the package recipe also install the
`anland-compatible` launcher required by the compatible APK.

The draft Termux package recipe is located at:

```text
packages/anland/build.sh
```

Related pull request: https://github.com/lfdevs/termux-packages/pull/11

### XWayland

- Repository: https://github.com/lfdevs/xwayland
- Debian branch: `debian-unstable`
- Ubuntu branch: `ubuntu/resolute`

After checking out the appropriate branch in a Linux container for the corresponding distribution, use `gbp` to build the Debian package:

```sh
sudo apt update
sudo apt build-dep -y xwayland
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Related Termux package pull request: https://github.com/lfdevs/termux-packages/pull/13

### KWin

- Repository: https://github.com/lfdevs/kwin
- Debian branch: `debian-unstable`
- Ubuntu branch: `ubuntu/resolute`

After checking out the appropriate branch in a Linux container for the corresponding distribution, use `gbp` to build the Debian package:

```sh
sudo apt update
sudo apt build-dep -y kwin
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Related Termux package pull request: https://github.com/lfdevs/termux-packages/pull/12

### Weston

- Repository: https://github.com/lfdevs/weston
- Debian branch: `debian-unstable`
- Ubuntu branch: `ubuntu/resolute`

After checking out the appropriate branch in a Linux container for the corresponding distribution, use `gbp` to build the Debian package:

```sh
sudo apt update
sudo apt build-dep -y weston
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Related Termux package pull request: https://github.com/lfdevs/termux-packages/pull/14

### Mutter

- Repository: https://github.com/lfdevs/mutter
- Debian branch: `debian/trixie`
- Ubuntu branch: `ubuntu/resolute-updates`

After checking out the appropriate branch in a Linux container for the corresponding distribution, use `gbp buildpackage` to build the Debian package:

```sh
sudo apt update
sudo apt build-dep -y mutter
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Related Termux package pull request: https://github.com/lfdevs/termux-packages/pull/21

### Mesa

- Repository: https://github.com/lfdevs/mesa-for-android-container
- Branch: `dev/adreno-main`

For build instructions, refer to that project’s developer documentation. This branch contains Freedreno and Turnip changes for Android containers; the generated archives are inputs when building the Debian 13 and Ubuntu 26.04 container images.

Related Termux package pull request: https://github.com/termux/termux-packages/pull/30162

## GitHub Actions

The repository provides seven workflows in [`.github/workflows/`](../.github/workflows/). Packages and container images target ARM64. The packages, APKs, checksums, and container images produced by the workflows are for releases and subsequent integration verification; they do not replace runtime tests of display, input, audio, and other behavior on a physical Android device.

> [!NOTE]
> The `tag` used by package-build workflows (referred to below as TAG) is passed directly to `git clone -b`. Workflows do not verify whether a TAG belongs to the selected distribution, nor do they automatically replace the default TAG in the input field when the distribution changes.
>
> **The TAG must correspond to the Linux distribution selected by `distribution`:**
>
> - For `Debian 13` (trixie), XWayland, KWin, and Weston must use TAGs created from the `debian-unstable` branch.
> - For `Ubuntu 26.04` (resolute), they must use TAGs created from the `ubuntu/resolute` branch.
> - For Mutter, Debian 13 must use TAGs created from `debian/trixie`, while Ubuntu 26.04 must use TAGs created from `ubuntu/resolute-updates`.
> - A mismatched TAG can build a package for one distribution with the build dependencies of another, causing the build to fail or producing a package that cannot be used in the target image.
> - The default TAGs displayed by the workflows are examples for convenience only. Before triggering a build, confirm the actual TAG to build in the corresponding source repository.

### Build Anland APK

Workflow file: [`build-anland-apk.yml`](../.github/workflows/build-anland-apk.yml)

Builds the Android APK, computes a SHA-256 checksum, and saves the APK and `sha256sums.txt` as build artifacts retained for 90 days.

Triggers:

- Pull requests: runs automatically when the target branch is `termux` and `app/**` changes, building the GitHub-provided PR merge ref.
- Manual: run with `workflow_dispatch`.

Manual input:

- `ref`: required Git reference to build; may be a branch, TAG, or commit. The default is `termux`.

The build environment is fixed to JDK 21, Gradle 9.7.1, and Android NDK 29.0.14206865, and invokes both `tools/build-app.sh` and `tools/build-compatible-app.sh`. Pull-request builds add `-debug-<short SHA>` before the compatible suffix, for example `AnlandTermux-5.13.2-debug-70d1b85-compatible.apk`.

### Build Docker Images

Workflow file: [`build-images.yml`](../.github/workflows/build-images.yml)

Combines Dockerfiles in this repository with the KWin, Mutter, or Weston, XWayland, and Mesa ARM64 build artifacts recorded in `images/packages.json`, then builds and pushes PRoot container images to GHCR. It also generates build provenance for image digests.

This workflow is manual only. Its inputs are:

- `distribution`: required Linux distribution; either `Debian 13` or `Ubuntu 26.04`. The default is `Ubuntu 26.04`.
- `desktop`: required desktop environment; `KDE Plasma`, `GNOME`, or `Weston`. The default is `KDE Plasma`.

Distribution, Dockerfile, and image-TAG mappings:

| Distribution | Codename | KDE Plasma Dockerfile / TAG | GNOME Dockerfile / TAG | Weston Dockerfile / TAG |
| --- | --- | --- | --- | --- |
| Debian 13 | `trixie` | `images/debian-plasma.Dockerfile` / `trixie-anland-plasma` | `images/debian-gnome.Dockerfile` / `trixie-anland-gnome` | `images/debian-weston.Dockerfile` / `trixie-anland-weston` |
| Ubuntu 26.04 | `resolute` | `images/ubuntu-plasma.Dockerfile` / `resolute-anland-plasma` | `images/ubuntu-gnome.Dockerfile` / `resolute-anland-gnome` | `images/ubuntu-weston.Dockerfile` / `resolute-anland-weston` |

The final image name is `ghcr.io/<repository owner>/debian:<TAG>` or `ghcr.io/<repository owner>/ubuntu:<TAG>`. This workflow has no manual TAG input; the image TAG is generated automatically from the selected distribution and desktop environment.

### Build XWayland Packages

Workflow file: [`build-xwayland.yml`](../.github/workflows/build-xwayland.yml)

Builds XWayland ARM64 Debian packages from a specified TAG in <https://github.com/lfdevs/xwayland>. It removes development and debug-symbol packages, then uploads the `.deb` files and `sha256sums.txt`, which are retained for 90 days.

This workflow is manual only. Its inputs are:

- `distribution`: required; either `Debian 13` or `Ubuntu 26.04`. The default is `Ubuntu 26.04`.
- `tag`: required. The default, `anland-1.11-ubuntu-2_24.1.10-90`, is for Ubuntu 26.04. When selecting Debian 13, you must replace it with an XWayland Debian TAG.

The workflow installs XWayland build dependencies in the selected distribution environment, builds with `gbp buildpackage`, and uses ccache to speed up subsequent runs.

### Build KWin Packages

Workflow file: [`build-kwin.yml`](../.github/workflows/build-kwin.yml)

Builds KWin ARM64 Debian packages from a specified TAG in <https://github.com/lfdevs/kwin>. It removes development and debug-symbol packages, then uploads the `.deb` files and `sha256sums.txt`, which are retained for 90 days.

This workflow is manual only. Its inputs are:

- `distribution`: required; either `Debian 13` or `Ubuntu 26.04`. The default is `Ubuntu 26.04`.
- `tag`: required. The default, `anland-5.8-4_6.6.4-0ubuntu92`, is for Ubuntu 26.04. When selecting Debian 13, you must replace it with a KWin Debian TAG.

The workflow installs KWin build dependencies in the selected distribution environment, builds with `gbp buildpackage`, and uses ccache to speed up subsequent runs.

### Build Mutter Packages

Workflow file: [`build-mutter.yml`](../.github/workflows/build-mutter.yml)

Builds Mutter ARM64 Debian packages from a specified TAG in <https://github.com/lfdevs/mutter>. It removes development, debug-symbol, and test packages, then uploads the `.deb` files and `sha256sums.txt`, which are retained for 90 days.

This workflow is manual only. Its inputs are:

- `distribution`: required; either `Debian 13` or `Ubuntu 26.04`. The default is `Debian 13`.
- `tag`: required. The default, `anland-5.13-debian-48.7-90`, is for Debian 13. When selecting Ubuntu 26.04, you must replace it with a Mutter TAG from `ubuntu/resolute-updates`.

The workflow builds inside a Debian trixie or Ubuntu resolute container, uses ccache, and invokes `gbp buildpackage` with a ccache-aware `debuild` builder.

### Build Weston Packages

Workflow file: [`build-weston.yml`](../.github/workflows/build-weston.yml)

Builds Weston ARM64 Debian packages from a specified TAG in <https://github.com/lfdevs/weston>. It removes development and debug-symbol packages, then uploads the `.deb` files and `sha256sums.txt`, which are retained for 90 days.

This workflow is manual only. Its inputs are:

- `distribution`: required; either `Debian 13` or `Ubuntu 26.04`. The default is `Debian 13`.
- `tag`: required. The default, `anland-5.13-debian-14.0.2-91`, is for Debian 13. When selecting Ubuntu 26.04, you must replace it with a Weston Ubuntu TAG.

The workflow installs Weston build dependencies in the selected distribution container, builds with `gbp buildpackage` using the `--git-no-pristine-tar` option, and uses ccache to speed up subsequent runs.

### Build Wayland Protocols Packages

> [!NOTE]
> This workflow is outdated and applies only to Anland: Termux 1.11.

Workflow file: [`build-wayland-protocols.yml`](../.github/workflows/build-wayland-protocols.yml)

Builds Wayland Protocols ARM64 Debian packages from a specified TAG in <https://github.com/lfdevs/wayland-protocols>, then uploads the `.deb` files and `sha256sums.txt`, which are retained for 90 days.

This workflow is manual only. Its inputs are:

- `distribution`: required; currently only `Debian 13` is available and is also the default.
- `tag`: required. The default, `anland-1.11-debian-1.44-90`, must be a TAG for Debian 13.

The workflow installs build dependencies in a Debian trixie container and builds with `gbp buildpackage`.
