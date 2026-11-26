# Void Linux PRoot with KDE Plasma 6

This guide documents a known-good Void Linux glibc PRoot setup using KDE Plasma 6,
KWin's Anland backend, and native Freedreno/KGSL rendering. It is based on a
Snapdragon 8 Gen 3 device with an Adreno 725 GPU (`/dev/kgsl-3d0`), Anland Termux
5.13.4.1, Plasma 6.8.3, and KWin 6.8.3.

Unlike the Debian/Ubuntu images, this setup does not use Weston or `termux-x11`,
and it does not require `--shared-tmp`. The launcher connects directly to the
standard Anland Termux socket under Termux's `$TMPDIR`.

## Requirements

* Official GitHub Termux build and the matching standard
  `AnlandTermux-<version>.apk`.
  The compatible F-Droid/ZeroTermux variant uses a different Binder bridge and is
  not covered here.
* A Void Linux glibc PRoot with `kde-plasma` 6 and PipeWire installed.
  Void's Plasma package only ships `startplasma-wayland`; Plasma 5's
  `startplasma-x11` is not used.
* The lfdevs Void Mesa build with the KGSL surfaceless/Wayland EGL fix.
  Stock Void Mesa 26.2.7 fails with `DRI2: failed to create screen` and makes
  KWin restart-loop.
* A KWin package built from Void sources with the Anland patch set.
  Binary KWin packages for Debian/Ubuntu are not ABI-compatible with Void glibc.

## 1. Install the matching display app

Install the Anland display APK whose version matches the Termux-side daemon. For
example, this setup used `AnlandTermux-5.13.4.1.apk`, whose default socket is:

```text
/data/data/com.termux/files/usr/tmp/anland/display_daemon.sock
```

Do not use the PRoot's `/tmp/anland/display_daemon.sock` for the standard APK:
PRoot `/tmp` is a separate tmpfs and the Android app will not see that socket.

## 2. Install patched Mesa

Install the LLVM runtime needed by the lfdevs Mesa build, then extract the
Void-targeted Mesa tarball from
<https://github.com/lfdevs/mesa-for-android-container/releases/tag/mesa-26.2.0-devel-20260709>
as root inside the PRoot:

```sh
xbps-install -y libllvm18
tar -xzf mesa-for-android-container_26.2.0-devel-20260709_void_arm64.tar.gz -C /
xbps-pkgdb -m hold mesa mesa-dri mesa-libgallium mesa-vulkan-freedreno libllvm18
```

The expected renderer is Freedreno, for example `FD725`. If the EGL test reports
`llvmpipe`, the KGSL Mesa overlay is not being loaded.

## 3. Rebuild KWin for Void

Build the stock Void `kwin` source package with the full Anland patch series and
the additional Void glibc/libudev linkage fix. Apply the patches in numeric order
under `srcpkgs/kwin/patches/` in a `void-packages` checkout, then build:

```sh
./xbps-src binary-bootstrap
./xbps-src pkg kwin
xbps-install -f host/binpkgs/kwin-6.8.3_3.aarch64.xbps
xbps-pkgdb -m hold kwin
```

The last patch restores the UDev CMake linkage disabled by the Android stubs
patch. It is required on glibc, where KWin still links against `libudev.so.1`:

```diff
--- a/CMakeLists.txt
+++ b/CMakeLists.txt
@@ -312,7 +312,7 @@
 find_package(Libeis-1.0 1.4)
 set_package_properties(Libeis-1.0 PROPERTIES TYPE OPTIONAL PURPOSE "Required for emulated input handling.")
 
-#find_package(UDev)
+find_package(UDev)
 set_package_properties(UDev PROPERTIES
     URL "https://www.freedesktop.org/wiki/Software/systemd/"
     DESCRIPTION "Linux device library."

--- a/src/CMakeLists.txt
+++ b/src/CMakeLists.txt
@@ -281,7 +281,7 @@
         KDecoration3::KDecoration
         KDecoration3::KDecoration3Private
 
-#       UDev::UDev
+        UDev::UDev
         XKB::XKB
         EGL::EGL
         epoxy::epoxy

--- a/src/plugins/gamecontroller/CMakeLists.txt
+++ b/src/plugins/gamecontroller/CMakeLists.txt
@@ -22,7 +22,7 @@
 
     KF6::I18n
 
-#   UDev::UDev
+    UDev::UDev
     PkgConfig::libevdev
     XKB::XKB
 )
```

Do not install the Debian/Ubuntu `kwin_anland` `.deb` packages inside Void.

## 4. Optional KSplash startup speedup

`startplasma-wayland` can pause for about a minute while D-Bus waits for
`org.kde.KSplash`. This user-level service shortens that wait; Plasma still
starts without it, just more slowly:

```sh
install -D -m 0644 /dev/stdin \
    "$HOME/.local/share/dbus-1/services/org.kde.KSplash.service" <<'EOF'
[D-BUS Service]
Name=org.kde.KSplash
Exec=/usr/bin/plasma_waitforname --timeout 1 org.kde.KSplash
EOF
```

## 5. Start Plasma

Copy [`scripts/startplasma-anland-void.sh`](../scripts/startplasma-anland-void.sh)
into the PRoot and run it as root:

```sh
curl -LO https://github.com/lfdevs/anland-termux/raw/refs/heads/main/scripts/startplasma-anland-void.sh
chmod +x ./startplasma-anland-void.sh
./startplasma-anland-void.sh
```

Then switch to the **Anland Termux** app on Android. `plasmashell` should appear
in roughly 10 seconds with the KSplash override, or about a minute without it.

## Verification

The Anland daemon log should show the KWin producer connecting and staying
connected:

```sh
tail -f /data/data/com.termux/files/usr/tmp/anland/anland.log
```

Expected lines include:

```text
daemon: listening on /data/data/com.termux/files/usr/tmp/anland/display_daemon.sock
daemon: producer connected
daemon: screen info 1220x1952 fmt=1
```

The KWin log must not contain `eglInitialize failed`,
`DRI2: failed to create screen`, or `could not create surfaceless EGL display`.
Those errors mean the Mesa KGSL fix is missing or stock Mesa files were restored
by an upgrade.

## Upgrade safety

Hold the patched packages so `xbps-install -u` cannot replace them:

```sh
xbps-pkgdb -m hold kwin mesa mesa-dri mesa-libgallium mesa-vulkan-freedreno libllvm18
```

If a future Void upgrade changes Qt or KF6, rebuild KWin from the Void source
with the same patch set rather than removing the hold and using stock KWin.
