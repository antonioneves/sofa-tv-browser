# Testing on the emulator without hardware acceleration

`emu.sh` drives the Android TV emulator for visual tests on a machine that has no `/dev/kvm` (a cloud container,
for instance). There the emulator runs on QEMU's software CPU, which is slow and has a bug that breaks WebView.
On a machine with KVM none of this is needed: start the emulator the normal way.

It expects the Android SDK in `/opt/android-sdk`, an AVD named `tv` (Android TV, API 34, x86) in `/opt/avd`,
and `gcc` and `python3` on the host. `ANDROID_HOME`, `ANDROID_AVD_HOME`, `AVD` and `PKG` override the defaults.

```
tools/emulator/emu.sh start                          # boot, tune, wait until idle: about 7 minutes
tools/emulator/emu.sh serve                          # a local test page at http://10.0.2.2:8089/
./gradlew :app:assembleStandardDebug -q
tools/emulator/emu.sh install
tools/emulator/emu.sh run http://10.0.2.2:8089/      # or `run` alone for the home page
tools/emulator/emu.sh key menu down down ok
tools/emulator/emu.sh shot                           # prints the screenshot's path
```

## What it works around

**Pages crashed, in a loop.** WebView 113, the one in the API 34 TV image, checks V8's snapshot with zlib's
adler32. On a CPU with SSSE3 that takes a SIMD path which ends in `phaddd xmm6,xmm6`, and the emulator's QEMU
computes `phaddd` wrongly when both operands are the same register. The checksum comes out wrong, V8 stops the
renderer (`SIGTRAP` in `CrRendererMain`, about 20 seconds after a page starts), Sofa reloads the page, and the
cycle repeats for ever, each crash writing a full dump to the log. Nothing in Sofa causes it: a build from before
any of this crashes the same way, and so does WebView with its renderer in the app's process.

Hiding SSSE3 from the guest is not possible (Android's `init` then dies on its own SSSE3 code), and V8 has no
switch for the check. So `emu.sh run` patches one byte in the running app: the jump in `adler32()` that skips
the SIMD path when the CPU has no SSSE3 is made unconditional. For that the renderer has to run inside the
app's process (`cmd webviewupdate disable-multiprocess`), and the app must be debuggable, since the patch is
done as the app's user through `run-as`. The offsets belong to WebView 113.0.5672.136: with any other version
`run` says so and patches nothing, and `poke` refuses when the byte it finds is not the one it expects.

**A local test page.** The container's network gives `426 Upgrade Required` over HTTP and an untrusted
certificate over HTTPS, so real sites show Sofa's certificate warning. `emu.sh serve` serves `www/` to the
emulator instead.

**One host thread.** Without KVM, QEMU runs all the guest's cores on a single host thread, so the emulator is
started with 2 cores, not 4. Multi-threaded emulation (`-accel tcg,thread=multi`) does not boot with this
QEMU, and it cannot load the snapshots it saves, so every start is a cold boot.

**Background apps.** Google's apps (Assistant, Play Store, Play services, YouTube) and a crash-looping Bluetooth
service keep the emulated CPU busy for minutes after boot. `prepare` disables them and turns the system's
window animations off. The settings stay in the AVD.

**Keys and timing.** `adb shell input keyevent` returns only once the app has handled the key, so no long
sleeps are needed after it: a second or two for the drawing is enough. Right after boot and after an install
the CPU is swamped and keys run into "app not responding", so `start` and `install` wait for the load to drop.
`adb screencap` comes out black with the software renderer; `shot` uses the emulator console's screenshot.

## Limits

The emulator stays slow: 15 to 35 seconds to start Sofa, a few seconds for a menu to open. Animations cannot
be judged on it. The patch only covers the adler32 path: other code that uses `phaddd` the same way would still
compute wrong values, which could show as a rendering glitch in a page but not in Sofa's own screens.
