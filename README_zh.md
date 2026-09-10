# Anland: Termux

[English](README.md) | **中文**

---

在 Termux 中使用 [Anland](https://github.com/SuperTurtleDev/anland/tree/legacy)，支持 **Termux 原生环境**以及 **PRoot/Chroot/LXC** 容器。

## 特性

* 支持 Termux 原生环境以及 PRoot 容器。这意味着即使你设备没有 Root 权限，也可以体验 Anland 加持下的 Wayland 桌面。
* 基于自动构建的 Docker 工作流，提供开箱即用的 [PRoot-Distro](https://github.com/termux/proot-distro) 容器镜像。
* 对于搭载高通骁龙处理器的设备，支持使用 Freedreno 及 Turnip 驱动进行硬件加速。

## 兼容性

### Wayland 合成器的支持情况

| | KWin | Weston | Mutter |
| :---: | :---: | :---: | :---: |
| Termux 原生环境 | ✔️ | ✔️ | ✔️ |
| Debian 13 | ✔️ | ✔️ | ✔️ |
| Ubuntu 26.04 LTS | ✔️ | ✔️ | ✔️ |

### 处理器的支持情况

* 大部分主流的高通骁龙处理器应该能正常运行 Anland: Termux。
* 其他处理器（如联发科天玑、Google Tensor、三星猎户座等）目前只能使用软件渲染（即 LLVMpipe），而且不一定能成功进入 Wayland 桌面。目前我只测试了 Google Tensor G1，它可以在 PRoot 或 Chroot 容器里成功进入桌面。对于其他处理器，你可以按照“用户指南”一节的说明先行尝试。
  **欢迎在 [Issues](https://github.com/lfdevs/anland-termux/issues) 里反馈本项目在你的设备上的运行情况。**

### Termux 来源及变体

* 如果 Termux 来自 [GitHub 官方仓库](https://github.com/termux/termux-app/releases)，请使用 `AnlandTermux-<version>.apk`。它使用 `sharedUserId` 且作为 Termux 的一部分运行，因此 Android 会一直将它们视为同一个应用，而且不会降低其速度。
* 如果 Termux 来自 [F-Droid](https://f-droid.org/packages/com.termux/) 或 ZeroTermux 等变体，请改用 `AnlandTermux-<version>-compatible.apk`。它不使用 `sharedUserId`，而是由 Termux 侧的 `anland-compatible` bridge 持有守护程序 socket，再通过 Binder 传递 fd。详情请参阅[用户指南](docs/user-guide_zh.md)。

## 用户指南

Anland: Termux 的安装和使用说明请参见：[Anland：Termux 用户指南](docs/user-guide_zh.md)

## 开发者文档

请参见：[Anland: Termux 开发者文档](docs/developer-guide_zh.md)

## 致谢

* [**Anland**](https://github.com/SuperTurtleDev/anland/tree/legacy): 为在 Android 上运行 Linux Wayland 应用提出了全新的解决方案，同时也是本项目的上游。
* [**Termux:X11**](https://github.com/termux/termux-x11): 为跨 Android 应用的 Unix Socket 通信提供了思路，同时也为扩展栏等增强功能的实现提供了参考。
* [**PRoot-Distro**](https://github.com/termux/proot-distro): 为在 Android 上运行使用 Docker 打包的容器镜像提供了支持。
