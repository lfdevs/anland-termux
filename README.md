# Anland: Termux

**English** | [中文](README_zh.md)

---

Use [Anland](https://github.com/SuperTurtleDev/anland/tree/legacy) in Termux, including **Termux native** and **PRoot/Chroot/LXC** containers.

## Features

* Supports Termux native environments and PRoot containers. This means you can experience an Anland-powered Wayland desktop even if your device is not rooted.
* Provides ready-to-use [PRoot-Distro](https://github.com/termux/proot-distro) container images built through automated Docker workflows.
* Supports hardware acceleration with the Freedreno and Turnip drivers on devices with Qualcomm Snapdragon processors.

## Compatibility

### Wayland compositor support

| | KWin | Weston | Mutter |
| :---: | :---: | :---: | :---: |
| Termux native | ✔️ | ✔️ | ✔️ |
| Debian 13 | ✔️ | ✔️ | ✔️ |
| Ubuntu 26.04 LTS | ✔️ | ✔️ | ✔️ |

### Processor support

* Most mainstream Qualcomm Snapdragon processors should be able to run Anland: Termux.
* Other processors, such as MediaTek Dimensity, Google Tensor, and Samsung Exynos, can currently use only software rendering (LLVMpipe) and may not be able to enter the Wayland desktop. So far, only the Google Tensor G1 has been tested by me; it can successfully enter the desktop in PRoot or Chroot containers. For other processors, you can also follow the instructions in the User Guide to have a try.
  **It would be helpful to share how this project works on your device in [Issues](https://github.com/lfdevs/anland-termux/issues).**

### Termux sources and variants

* If Termux comes from the [official GitHub repository](https://github.com/termux/termux-app/releases), use `AnlandTermux-<version>.apk`. It uses `sharedUserId` and runs as part of Termux, so Android always treats them as the same app without reducing performance.
* If Termux comes from [F-Droid](https://f-droid.org/packages/com.termux/) or a variant such as ZeroTermux, use `AnlandTermux-<version>-compatible.apk` instead. It does not use `sharedUserId`; a Termux-side `anland-compatible` bridge holds the daemon socket and transfers its fd through Binder. See the [user guide](docs/user-guide.md) for details.

## User Guide

For instructions on installing and using Anland: Termux, see [Anland: Termux User Guide](docs/user-guide.md).

## Developer Documentation

See [Anland: Termux Developer Documentation](docs/developer-guide.md)

## Acknowledgements

* [**Anland**](https://github.com/SuperTurtleDev/anland/tree/legacy): Introduced a new solution for running Linux Wayland applications on Android and is this project's upstream.
* [**Termux:X11**](https://github.com/termux/termux-x11): Inspired the Unix socket communication between Android apps and provided a reference for implementing enhancements such as the toolbar.
* [**PRoot-Distro**](https://github.com/termux/proot-distro): Enables Docker-packaged container images to run on Android.
