# Anland: Termux 开发者文档

[English](developer-guide.md) | **中文**

---

## 项目形态

本项目基于 Anland，将 Android 显示端、Termux 守护程序以及容器中的 Wayland 合成器连接起来。主要目录如下：

- `app/`：Android 显示应用。
  - Java 层负责 Activity、设置、输入、剪贴板、相机和音频等交互。
  - `app/src/main/jni/` 包含 Surface、dma-buf、Unix Socket 及 JNI 桥接等原生代码。
  - 包名为 `com.anland.termux`，应用名称为 `Anland Termux`。
  - `standard` flavor 使用共享 UID `com.termux`，并通过 `app/testkey_untrusted.jks` 与 Termux GitHub 版本兼容签名。
  - `compatible` flavor 不使用 `sharedUserId`，但保持相同的 application ID、versionCode 和签名密钥，并在 versionName 后添加 `-compatible`。
- `termux/anland/`：Termux 侧的 `anland` 守护程序，在 Android 显示端与 Wayland 生产端之间中继控制消息和文件描述符。默认套接字为 `$TMPDIR/anland/display_daemon.sock`；`TMPDIR` 未设置时回退到 `/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock`。
- `termux/anland/anland-compatible`：compatible APK 的 Termux 侧启动脚本，通过 `app_process` 从已安装的 APK 启动 `CompatibleBridge`。
- `packages/anland/`：Termux Packages 配方草稿，用于将守护程序构建为 Termux 软件包。
- `scripts/`：Termux 原生环境及 PRoot、Chroot、LXC 容器中的 KDE Plasma、GNOME 和 Weston 一键启动脚本。
- `images/`：Debian 13 和 Ubuntu 26.04 的 ARM64 PRoot 容器镜像定义；`images/packages.json` 记录 KWin、Mutter、Weston、XWayland 和 Mesa 构建产物的下载地址。
- `tools/`：Android 应用和 Termux 守护程序的本地构建入口。
- `.github/workflows/`：APK、Debian 软件包及容器镜像的 GitHub Actions 工作流。
- `docs/`：中英文用户文档和开发者文档；中文文件使用 `_zh.md` 后缀。
- `out/`：本地构建脚本生成的 APK 和守护程序输出目录，不应提交到 Git。

## 传输方式

### Standard 传输方式

Standard APK 依赖 Android 的 shared UID 机制，适用于 GitHub 发布的 Termux：

1. `standard` flavor 的 manifest 声明 `android:sharedUserId="com.termux"`，并使用 `app/testkey_untrusted.jks` 签名。安装时，Android 会校验它与已安装 Termux 的签名是否匹配；匹配后，两个应用使用同一个 Linux UID。
2. `anland` 守护程序以 Termux UID 在 `display_daemon.sock` 上监听。由于 Standard APK 也以该 UID 运行，显示端可访问这个 Unix socket，不需要跨 UID 的 socket 权限，也不需要额外的 bridge 进程。
3. `MainActivity` 配置默认或用户设置的 socket 路径后，native consumer 在非 compatible 模式下调用 `connect_to_deamon()`；该函数通过 `connect_unix()` 直接连接守护程序。此路径不启动 `anland-compatible`，也不经过 Binder。
4. 建立控制连接后，native display context 发送 consumer hello 及其显示侧文件描述符。守护程序将该连接登记为 consumer，并在 Wayland producer 连接后中继 screen 信息和所需的文件描述符。

这种方式依赖 Termux 与 Standard APK 的签名匹配。F-Droid 版 Termux 及其他使用不同签名密钥的变体无法满足该条件，Android 会拒绝安装或更新 Standard APK；这些环境应使用 Compatible APK。使用 Standard APK 时只需启动 `anland` 守护程序，不应启动 `anland-compatible`。

### Compatible 传输方式

Compatible APK 参照 Termux:X11 的独立版传输方式，不需要 F-Droid 的签名密钥：

1. `anland-compatible` 以 Termux UID 运行，通过 `/system/bin/app_process` 从已安装的 APK 加载 `com.anland.termux.CompatibleBridge`。
2. `CompatibleBridge` 以 Termux UID 连接 `display_daemon.sock`，持有连接的 `LocalSocket`，并向指定包发送包含 `ICompatibleBridge` Binder 的广播。
3. `MainActivity` 接收 Binder，调用 `getConnection()`，并 detach 返回的 `ParcelFileDescriptor`；`Native.nativeSetCompatibleFd()` 将重复的 fd 交给 `connect_to_deamon_with_fd()`。
4. native display context 在整个生命周期内持有该 fd。现有 fallback 协议可以在同一控制连接上重新传递 data/fence/audio fd，因此 Android 侧不需要调用 Unix `connect()`。

用户在 Termux 中运行 `anland-compatible [SOCKET_PATH]` 启动 bridge，方式与独立版 Termux:X11 启动其入口点相同。bridge 会持续运行并重复发布指定包的 Binder，使 Activity 重建后仍能获取新的重复 fd；不需要 F-Droid 签名密钥，也不需要 Termux 插件权限。

## 调试

调试变量应在启动脚本前设置。下列示例既可用于 Termux 原生环境，也可在容器中使用；PRoot-Distro 必须通过 `--shared-tmp` 进入，Chroot 或 LXC 则应确保容器与 Termux 共享 Anland Socket 所在目录。

### 一键启动脚本

#### KDE Plasma

脚本：`scripts/startplasma-anland.sh`

- `ANLAND_PLASMA_DEBUG=1`：取消 `startplasma-wayland`、KWin 和 Plasma 会话的标准输出及标准错误重定向，使启动日志直接显示在当前终端。默认值为 `0`。该变量本身不会修改 Qt 日志分类；需要更详细的 KWin 日志时，应同时设置 `QT_LOGGING_RULES`。
- `ANLAND_AUDIO_DEBUG=1`：为 PipeWire、`pipewire-pulse` 和 WirePlumber 启用详细日志，分别设置脚本内使用的 `PIPEWIRE_DEBUG` 和 `WIREPLUMBER_DEBUG`。日志保存在 Anland Socket 所在目录；默认通常为 Termux 中的 `$TMPDIR/anland/` 或容器中的 `/tmp/anland/`。

例如，同时显示 Plasma、KWin 和音频服务的调试输出：

```sh
ANLAND_PLASMA_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
QT_LOGGING_RULES='kwin*.debug=true' \
scripts/startplasma-anland.sh
```

#### Weston

脚本：`scripts/startweston-anland.sh`

- `ANLAND_WESTON_DEBUG=1`：向 Weston 添加 `--debug`，启用 `weston_debug_v1` 调试协议和 Weston 截图接口。它不会自动订阅更多日志分类。
- `ANLAND_AUDIO_DEBUG=1`：为 PipeWire、`pipewire-pulse` 和 WirePlumber 启用详细日志。
- `ANLAND_LOG_DIR=<目录>`：指定音频服务日志目录；默认是 `$XDG_RUNTIME_DIR/anland-logs`。

例如：

```sh
ANLAND_WESTON_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
ANLAND_LOG_DIR=/tmp/anland-logs \
scripts/startweston-anland.sh
```

> [!WARNING]
> Weston 的 `--debug` 会允许客户端读取调试信息和截取输出内容，也可能被恶意客户端用于阻塞合成器。只应在可信的本地调试会话中启用，调试结束后应关闭。

#### GNOME

脚本：`scripts/startgnome-anland.sh`

- `ANLAND_GNOME_DEBUG=1`：将 GNOME Shell、`gnome-session`、`gnome-session-service` 和 Termux XSettings 日志输出到当前终端，而不是重定向到文件。默认值为 `0`。
- `ANLAND_AUDIO_DEBUG=1`：为 PipeWire、`pipewire-pulse` 和 WirePlumber 启用详细日志。
- `ANLAND_GNOME_XWAYLAND=0`：禁用 XWayland；默认值为 `1`，可用于只运行 Wayland 的 GNOME 会话。
- `GNOME_WAYLAND_DISPLAY=<Socket 名称>`：修改 Wayland Socket 名称，默认值为 `wayland-anland`。
- `ANLAND_SOCKET=<路径>`：覆盖 Anland 显示守护程序的 Socket 路径。
- `ANLAND_LOG_DIR=<目录>`：修改 GNOME 会话、XSettings、D-Bus 和音频日志目录，默认值为 `$XDG_RUNTIME_DIR/anland-logs`。

该脚本通过 `dbus-run-session` 启动 GNOME，准备 Anland GNOME 会话定义；当容器系统 D-Bus 不可用时启动私有兼容系统总线；当用户 systemd 管理器不可用时回退到独立的 GNOME 会话服务。在 Termux 原生会话中，脚本会等待 Mutter 发布 XWayland 环境后再启动 XSettings 服务，使 X11 应用获得正确的缩放信息。

例如，在终端中直接查看 GNOME 会话和音频日志：

```sh
ANLAND_GNOME_DEBUG=1 \
ANLAND_AUDIO_DEBUG=1 \
ANLAND_LOG_DIR=/tmp/anland-gnome-logs \
scripts/startgnome-anland.sh
```

### KWin

KWin 使用 Qt 日志分类。使用一键脚本时必须同时设置 `ANLAND_PLASMA_DEBUG=1`，否则启动脚本会丢弃 KWin 的标准输出和标准错误。

- `QT_LOGGING_RULES='kwin*.debug=true'`：启用所有以 `kwin` 开头的调试分类，日志量较大。
- `QT_LOGGING_RULES='kwin_core.debug=true;kwin_backend_anland.debug=true;kwin_scene_opengl.debug=true'`：只启用核心、Anland 后端和 OpenGL 场景日志，适合本项目的大多数显示问题。
- `KWIN_GL_DEBUG=1`：在驱动支持 OpenGL 调试输出时，允许 KWin 接收全部 OpenGL 调试消息；通常与 `kwin_scene_opengl.debug=true` 配合使用。
- `KWIN_XWAYLAND_DEBUG=1`：为 KWin 启动的 XWayland 设置 `WAYLAND_DEBUG=1`，输出 XWayland 与 KWin 之间的 Wayland 协议通信。
- `WAYLAND_DEBUG=1 <Wayland 客户端>`：只跟踪指定客户端的 Wayland 协议通信，通常比为整个桌面启用协议日志更容易分析。

针对 Anland 后端和 OpenGL 初始化的常用组合：

```sh
ANLAND_PLASMA_DEBUG=1 \
KWIN_GL_DEBUG=1 \
QT_LOGGING_RULES='kwin_core.debug=true;kwin_backend_anland.debug=true;kwin_scene_opengl.debug=true' \
scripts/startplasma-anland.sh 2>&1 | tee kwin-anland.log
```

### Mutter

Mutter 是 GNOME 会话中的合成器，由 `gnome-shell` 启动。GNOME 一键脚本会向 GNOME Shell 传递 Anland 专用的 `--anland`、`--anland-socket` 和 `--wayland-display` 参数，使 Mutter 使用 Anland 后端。调试时应设置 `ANLAND_GNOME_DEBUG=1`，否则 GNOME Shell 和 Mutter 输出会被重定向到日志文件。

- `MUTTER_DEBUG=<主题列表>`：启用 Mutter 调试主题。常用主题包括 `backend`、`render`、`wayland`、`input`、`kms`、`screen-cast`、`remote-desktop`、`x11` 和 `startup`，多个主题以逗号分隔。
- `MUTTER_DEBUG_PAINT=<主题列表>`：启用绘制诊断，例如 `opaque-region`、`disable-direct-scanout` 和 `sync-cursor-primary`。
- `MUTTER_VERBOSE=1`：在构建启用了 verbose mode 时启用详细的 Mutter 日志。
- `COGL_DEBUG=show-source,performance`：启用 Cogl 图形诊断，包括 Shader 源码和性能输出；应只启用定位问题所需的主题。
- `G_DEBUG=fatal-warnings,fatal-criticals`：让 GLib warning 和 critical 消息终止进程，适合附加 GDB，但可能直接终止桌面会话。

GNOME Shell 还内置 Looking Glass。按 `Alt+F2`，输入 `lg` 后，可以查看 Mutter 状态、在运行时启用调试主题、显示 damage 或执行 GNOME Shell JavaScript。对于小范围的合成器或 Shell 问题，通常无需重启整个会话。

例如，查看 Anland 后端、渲染和 Wayland 初始化日志：

```sh
ANLAND_GNOME_DEBUG=1 \
MUTTER_DEBUG=backend,render,wayland \
scripts/startgnome-anland.sh 2>&1 | tee mutter-anland.log
```

### Weston

`startweston-anland.sh` 会将额外命令行参数原样传给 Weston，因此除了 `ANLAND_WESTON_DEBUG=1` 外，还可以使用 Weston 自带的日志选项：

- `--log=<文件>`：将 Weston 日志追加到指定文件。
- `--logger-scopes=<分类列表>`：将指定分类直接写入日志。常用分类包括通用的 `log`、Wayland 协议通信 `proto` 和延迟分析 `timeline`；实际可用分类取决于 Weston 版本和已加载的后端。
- `--flight-rec-scopes=<分类列表>`：将指定分类写入环形缓冲区，适合保留崩溃前的近期信息。
- `--wait-for-debugger`：启动后暂停 Weston，便于附加 GDB；附加后发送 `SIGCONT` 继续运行。

例如，将通用日志和协议通信写入文件：

```sh
scripts/startweston-anland.sh \
    --log=/tmp/weston.log \
    --logger-scopes=log,proto
```

`proto` 日志可能包含窗口标题、输入及客户端交互等信息，分享日志前应检查并清理敏感内容。

### Mesa

Mesa 的调试变量会被 KWin、Weston、XWayland 及其启动的应用继承，可与两个一键脚本组合使用：

- `MESA_DEBUG=1`：启用 Mesa 错误信息。
- `MESA_LOG_LEVEL=debug`：允许输出 debug 级别日志。
- `EGL_LOG_LEVEL=debug`：启用 EGL 加载、配置和上下文相关的详细日志。
- `LIBGL_DEBUG=verbose`：输出 LibGL/GLX 驱动加载信息，适合排查 XWayland 下的 OpenGL 应用。
- `MESA_LOG_FILE_AUTO=1`：在 `/tmp/` 中为各进程创建独立的 `mesa_<进程名>_<PID>_*.log`，避免多个桌面进程共用一个日志文件。
- `TU_DEBUG=startup`：输出 Turnip Vulkan 驱动的启动和设备初始化信息。
- `FD_MESA_DEBUG=msgs,perf`：输出 Freedreno Gallium 驱动消息及性能警告。

例如，记录 Plasma/KWin 启动期间的 Mesa 和 EGL 日志：

```sh
ANLAND_PLASMA_DEBUG=1 \
MESA_DEBUG=1 \
MESA_LOG_LEVEL=debug \
EGL_LOG_LEVEL=debug \
MESA_LOG_FILE_AUTO=1 \
scripts/startplasma-anland.sh
```

排查高通 GPU 卡死或渲染错误时，还可按现象单独尝试 `TU_DEBUG=flushall`、`TU_DEBUG=syncdraw`、`FD_MESA_DEBUG=flush` 等选项。这些选项会强制同步、刷新缓存或改变渲染路径，可能显著降低性能并掩盖时序问题，因此不应作为正常启动配置，也不应一次启用大量选项。

> [!NOTE]
> 两个启动脚本会根据 `/dev/kgsl-3d0` 和 `/dev/dri/renderD128` 自动选择图形路径，并会清除继承的 `MESA_LOADER_DRIVER_OVERRIDE`、`TURNIP_KMD`、`GALLIUM_DRIVER` 等驱动选择变量。不要用这些变量绕过脚本的设备检测来收集日志；如需强制图形路径，应先确认 Termux 原生环境和容器环境的设备节点及驱动能力。

## 构建

### Android 显示应用

环境要求：

```text
Android Gradle Plugin 9.4.0
Gradle 9.7.1
Android NDK 29.0.14206865
minSdk 30
compileSdk 37
compileSdkMinor 2
targetSdk 37
```

构建脚本：

```sh
tools/build-app.sh
```

该脚本构建 `standardDebug` flavor。compatible flavor 使用：

```sh
tools/build-compatible-app.sh
```

构建产物：

```text
out/AnlandTermux-<version>.apk
out/AnlandTermux-<version>-compatible.apk
```

两个 flavor 都使用 `app/testkey_untrusted.jks`，并保持相同的 `versionCode`；compatible flavor 的 `-compatible` versionName 后缀由 Gradle 添加。

### Anland 守护程序

构建脚本：

```sh
tools/build-termux-anland.sh
```

在 Termux 中运行时，输出文件是一个 Termux 可执行文件：

```text
out/anland
```

`make -C termux/anland install` 和软件包配方还会安装 compatible APK 所需的 `anland-compatible` 启动脚本。

Termux 软件包的配方草稿位于：

```text
packages/anland/build.sh
```

关联的 Pull request：https://github.com/lfdevs/termux-packages/pull/11

### XWayland

- 仓库：https://github.com/lfdevs/xwayland
- Debian 分支：`debian-unstable`
- Ubuntu 分支：`ubuntu/resolute`

在对应发行版的 Linux 容器内检出相应分支后，使用 `gbp` 构建 Debian 软件包：

```sh
sudo apt update
sudo apt build-dep -y xwayland
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Termux 软件包关联的 Pull request：https://github.com/lfdevs/termux-packages/pull/13

### KWin

- 仓库：https://github.com/lfdevs/kwin
- Debian 分支：`debian-unstable`
- Ubuntu 分支：`ubuntu/resolute`

在对应发行版的 Linux 容器内检出相应分支后，使用 `gbp` 构建 Debian 软件包：

```sh
sudo apt update
sudo apt build-dep -y kwin
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Termux 软件包关联的 Pull request：https://github.com/lfdevs/termux-packages/pull/12

### Weston

- 仓库：https://github.com/lfdevs/weston
- Debian 分支：`debian-unstable`
- Ubuntu 分支：`ubuntu/resolute`

在对应发行版的 Linux 容器内检出相应分支后，使用 `gbp` 构建 Debian 软件包：

```sh
sudo apt update
sudo apt build-dep -y weston
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Termux 软件包关联的 Pull request：https://github.com/lfdevs/termux-packages/pull/14

### Mutter

- 仓库：https://github.com/lfdevs/mutter
- Debian 分支：`debian/trixie`
- Ubuntu 分支：`ubuntu/resolute-updates`

在对应发行版的 Linux 容器内检出相应分支后，使用 `gbp buildpackage` 构建 Debian 软件包：

```sh
sudo apt update
sudo apt build-dep -y mutter
sudo apt install -y git ccache build-essential devscripts fakeroot quilt git-buildpackage pristine-tar
origtargz
gbp buildpackage -uc -us -jauto --git-ignore-branch --git-no-pristine-tar
```

Termux 软件包关联的 Pull request：https://github.com/lfdevs/termux-packages/pull/21

### Mesa

- 仓库：https://github.com/lfdevs/mesa-for-android-container
- 分支：`dev/adreno-main`

构建方法请参考该项目的开发文档。该分支包含供 Android 容器使用的 Freedreno 和 Turnip 修改，生成的归档包会作为构建 Debian 13 和 Ubuntu 26.04 容器镜像时的输入。

Termux 软件包关联的 Pull requests：https://github.com/termux/termux-packages/pull/30162

## GitHub Actions

仓库在 [`.github/workflows/`](../.github/workflows/) 中提供 7 条工作流。软件包和容器镜像均面向 ARM64；工作流生成的软件包、APK、校验和或容器镜像用于发布和后续集成验证，不能替代 Android 实机上的显示、输入、音频等运行时测试。

> [!NOTE]
> 软件包构建工作流的 `tag`（下文写作 TAG）会直接传给 `git clone -b`，工作流不会自动检查该 TAG 是否属于所选发行版，也不会在切换发行版时自动替换输入框中的默认 TAG。
>
> **TAG 必须与 `distribution` 选择的 Linux 发行版相对应：**
>
> - 选择 `Debian 13`（trixie）时，XWayland、KWin 和 Weston 应使用从 `debian-unstable` 分支创建的 TAG。
> - 选择 `Ubuntu 26.04`（resolute）时，应使用从 `ubuntu/resolute` 分支创建的 TAG。
> - 对于 Mutter，Debian 13 应使用从 `debian/trixie` 创建的 TAG，Ubuntu 26.04 应使用从 `ubuntu/resolute-updates` 创建的 TAG。
> - TAG 不匹配时，可能在错误发行版的构建依赖中编译另一发行版的软件包，导致构建失败，或生成不能用于目标镜像的软件包。
> - 工作流中显示的 TAG 默认值只是便于填写的示例。触发构建前应到对应源码仓库确认实际需要构建的 TAG。

### Build Anland APK

工作流文件：[`build-anland-apk.yml`](../.github/workflows/build-anland-apk.yml)

用于构建 Android APK、计算 SHA-256 校验和，并将 APK 与 `sha256sums.txt` 保存为 90 天有效的构建产物。

触发方式：

- Pull request：当目标分支为 `termux` 且 APK、Termux bridge、构建脚本或工作流发生变化时自动运行，构建 GitHub 提供的 PR 合并引用。
- 手动触发：通过 `workflow_dispatch` 运行。

手动输入：

- `ref`：必填，要构建的 Git 引用，可以是分支、TAG 或 commit；默认值为 `termux`。

构建环境固定使用 JDK 21、Gradle 9.7.1 和 Android NDK 29.0.14206865，并同时调用 `tools/build-app.sh` 与 `tools/build-compatible-app.sh`。Pull request 构建会在 compatible 后缀之前加入 `-debug-<短 SHA>`，例如 `AnlandTermux-5.13.2-debug-70d1b85-compatible.apk`。

### Build Docker Images

工作流文件：[`build-images.yml`](../.github/workflows/build-images.yml)

用于组合当前仓库中的 Dockerfile 与 `images/packages.json` 记录的 KWin、Mutter 或 Weston、XWayland、Mesa ARM64 构建产物，构建并推送 PRoot 容器镜像到 GHCR，同时为镜像摘要生成构建来源证明。

仅支持手动触发，输入选项如下：

- `distribution`：必填，Linux 发行版；可选 `Debian 13`、`Ubuntu 26.04`，默认 `Ubuntu 26.04`。
- `desktop`：必填，桌面环境；可选 `KDE Plasma`、`GNOME`、`Weston`，默认 `KDE Plasma`。

发行版、Dockerfile 与镜像 TAG 的映射如下：

| 发行版 | 发行版代号 | KDE Plasma Dockerfile / TAG | GNOME Dockerfile / TAG | Weston Dockerfile / TAG |
| --- | --- | --- | --- | --- |
| Debian 13 | `trixie` | `images/debian-plasma.Dockerfile` / `trixie-anland-plasma` | `images/debian-gnome.Dockerfile` / `trixie-anland-gnome` | `images/debian-weston.Dockerfile` / `trixie-anland-weston` |
| Ubuntu 26.04 | `resolute` | `images/ubuntu-plasma.Dockerfile` / `resolute-anland-plasma` | `images/ubuntu-gnome.Dockerfile` / `resolute-anland-gnome` | `images/ubuntu-weston.Dockerfile` / `resolute-anland-weston` |

最终镜像名称为 `ghcr.io/<仓库所有者>/debian:<TAG>` 或 `ghcr.io/<仓库所有者>/ubuntu:<TAG>`。这条工作流没有手动 TAG 输入；镜像 TAG 由所选发行版和桌面环境自动生成。

### Build XWayland Packages

工作流文件：[`build-xwayland.yml`](../.github/workflows/build-xwayland.yml)

用于从 <https://github.com/lfdevs/xwayland> 的指定 TAG 构建 XWayland ARM64 Debian 软件包，移除开发包和调试符号包后，上传 `.deb` 文件及 `sha256sums.txt`，保留 90 天。

仅支持手动触发，输入选项如下：

- `distribution`：必填；可选 `Debian 13`、`Ubuntu 26.04`，默认 `Ubuntu 26.04`。
- `tag`：必填；默认 `anland-1.11-ubuntu-2_24.1.10-90`，该默认值对应 Ubuntu 26.04。选择 Debian 13 时必须改为 XWayland 的 Debian TAG。

工作流在所选发行版环境中安装 XWayland 构建依赖，使用 `gbp buildpackage` 构建，并通过 ccache 加速后续运行。

### Build KWin Packages

工作流文件：[`build-kwin.yml`](../.github/workflows/build-kwin.yml)

用于从 <https://github.com/lfdevs/kwin> 的指定 TAG 构建 KWin ARM64 Debian 软件包，移除开发包和调试符号包后，上传 `.deb` 文件及 `sha256sums.txt`，保留 90 天。

仅支持手动触发，输入选项如下：

- `distribution`：必填；可选 `Debian 13`、`Ubuntu 26.04`，默认 `Ubuntu 26.04`。
- `tag`：必填；默认 `anland-5.8-4_6.6.4-0ubuntu92`，该默认值对应 Ubuntu 26.04。选择 Debian 13 时必须改为 KWin 的 Debian TAG。

工作流在所选发行版环境中安装 KWin 构建依赖，使用 `gbp buildpackage` 构建，并通过 ccache 加速后续运行。

### Build Mutter Packages

工作流文件：[`build-mutter.yml`](../.github/workflows/build-mutter.yml)

用于从 <https://github.com/lfdevs/mutter> 的指定 TAG 构建 Mutter ARM64 Debian 软件包，移除开发包、调试符号包和测试包后，上传 `.deb` 文件及 `sha256sums.txt`，保留 90 天。

仅支持手动触发，输入选项如下：

- `distribution`：必填；可选 `Debian 13`、`Ubuntu 26.04`，默认 `Debian 13`。
- `tag`：必填；默认 `anland-5.13-debian-48.7-90`，该默认值对应 Debian 13。选择 Ubuntu 26.04 时必须改为 Mutter 的 `ubuntu/resolute-updates` TAG。

工作流在 Debian trixie 或 Ubuntu resolute 容器中构建，使用 ccache，并通过带 ccache 配置的 `debuild` 构建器调用 `gbp buildpackage`。

### Build Weston Packages

工作流文件：[`build-weston.yml`](../.github/workflows/build-weston.yml)

用于从 <https://github.com/lfdevs/weston> 的指定 TAG 构建 Weston ARM64 Debian 软件包，移除开发包和调试符号包后，上传 `.deb` 文件及 `sha256sums.txt`，保留 90 天。

仅支持手动触发，输入选项如下：

- `distribution`：必填；可选 `Debian 13`、`Ubuntu 26.04`，默认 `Debian 13`。
- `tag`：必填；默认 `anland-5.13-debian-14.0.2-91`，该默认值对应 Debian 13。选择 Ubuntu 26.04 时必须改为 Weston 的 Ubuntu TAG。

工作流在所选发行版容器中安装 Weston 构建依赖，使用带 `--git-no-pristine-tar` 选项的 `gbp buildpackage` 构建，并通过 ccache 加速后续运行。

### Build Wayland Protocols Packages

> [!NOTE]
> 该工作流已过时，仅适用于 Anland: Termux 1.11。

工作流文件：[`build-wayland-protocols.yml`](../.github/workflows/build-wayland-protocols.yml)

用于从 <https://github.com/lfdevs/wayland-protocols> 的指定 TAG 构建 Wayland Protocols ARM64 Debian 软件包，上传 `.deb` 文件及 `sha256sums.txt`，保留 90 天。

仅支持手动触发，输入选项如下：

- `distribution`：必填；当前只能选择 `Debian 13`，默认也是 `Debian 13`。
- `tag`：必填；默认 `anland-1.11-debian-1.44-90`，必须使用与 Debian 13 对应的 TAG。

工作流在 Debian trixie 容器中安装构建依赖，并使用 `gbp buildpackage` 构建。
