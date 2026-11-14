#!/bin/bash
# Drives the Android TV emulator for visual tests on a machine WITHOUT hardware acceleration (no /dev/kvm),
# where the emulator runs on QEMU's software CPU. See README.md for why each step is there.
#
#   emu.sh start            start the emulator (detached), wait for boot, then `prepare`
#   emu.sh prepare          one-time tuning of a booted emulator (safe to run again)
#   emu.sh serve            serve tools/emulator/www at http://10.0.2.2:8089/ for the emulator
#   emu.sh install [apk]    install the debug APK
#   emu.sh run [url]        (re)start Sofa, on its home page or on a URL, with the WebView fix applied
#   emu.sh key <k>...       press keys: up down left right ok back menu home enter (returns once the app handled them)
#   emu.sh shot [dir]       take a screenshot, print its path
#   emu.sh settle           wait until the emulator is idle enough to take keys
#   emu.sh stop             stop the emulator
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/android-sdk}"
export ANDROID_AVD_HOME="${ANDROID_AVD_HOME:-/opt/avd}"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"
AVD="${AVD:-tv}"
PKG="${PKG:-app.sofabrowser.debug}"
WORK="${EMU_WORK:-/tmp/sofa-emu}"
PORT=8089
# The one WebView build the in-memory fix was written for: the offsets below are this build's.
WEBVIEW="113.0.5672.136"
LIB_IN_APK=$((0x899000))   # where libmonochrome.so (stored, not compressed) starts inside TrichromeLibrary.apk
GATE=$((0x2506f6e))        # in adler32(): the `je` that skips the SSSE3 path when the CPU has no SSSE3
mkdir -p "$WORK"

booted() { [ "$(timeout 8 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

start() {
    if pgrep -f qemu-system >/dev/null; then echo "emulator already running"; else
        rm -f "$ANDROID_AVD_HOME/$AVD.avd/"*.lock
        # -accel off: there is no KVM here. -cores 2: the software CPU runs every guest core on ONE host thread,
        # so more cores only add overhead. setsid: the emulator must outlive the shell that started it.
        # Snapshots are not used: this QEMU cannot load the ones it saves in this mode.
        (cd "$WORK" && setsid nohup emulator -avd "$AVD" -no-window -no-audio -no-boot-anim -no-snapshot \
            -gpu swiftshader_indirect -accel off -memory 3072 -cores 2 > "$WORK/emu.log" 2>&1 < /dev/null &)
    fi
    local t0; t0=$(date +%s)
    until booted; do
        [ $(( $(date +%s) - t0 )) -gt 600 ] && { echo "no boot after 10 minutes, see $WORK/emu.log"; exit 1; }
        sleep 5
    done
    echo "booted after $(( $(date +%s) - t0 ))s"
    prepare
    settle
}

# Right after boot, and after an install, the emulated CPU is swamped: keys sent then run into
# "app not responding" and Android kills the app. Wait until the load is back down.
settle() {
    local t0 l; t0=$(date +%s)
    while [ $(( $(date +%s) - t0 )) -lt 420 ]; do
        l=$(timeout 8 adb shell cat /proc/loadavg 2>/dev/null | cut -d' ' -f1)
        [ -n "$l" ] && awk -v l="$l" 'BEGIN { exit !(l < 2.0) }' && break
        sleep 6
    done
    echo "settled after $(( $(date +%s) - t0 ))s (load $l)"
}

prepare() {
    booted || { echo "emulator is not booted"; exit 1; }
    # Google's background apps keep the single emulated CPU busy for minutes after boot, and Bluetooth crash-loops.
    local noise="com.google.android.katniss com.android.vending com.google.android.gms com.google.android.gsf
        com.google.android.youtube.tv com.google.android.youtube.tvmusic com.google.android.play.games
        com.google.android.tvrecommendations com.google.android.backdrop com.google.android.apps.mediashell
        com.google.android.tv.remote.service com.google.android.tv.bugreportsender com.google.android.adservices.api
        com.google.android.ext.adservices.api com.google.android.healthconnect.controller
        com.google.android.federatedcompute com.google.android.ondevicepersonalization.services
        com.google.android.syncadapters.calendar com.google.android.configupdater com.android.tv
        com.google.android.bluetooth com.google.android.nearby.halfsheet com.google.android.devicelockcontroller
        com.android.providers.calendar"
    adb shell "for p in $(echo $noise); do pm disable-user --user 0 \$p >/dev/null 2>&1; done
        settings put global window_animation_scale 0; settings put global transition_animation_scale 0
        settings put global stay_on_while_plugged_in 3"
    # The WebView fix patches the app's own process, so the renderer has to live in it.
    adb shell cmd webviewupdate disable-multiprocess >/dev/null
    local v; v=$(adb shell dumpsys webviewupdate | sed -n 's/.*Current WebView package.*, \(.*\))/\1/p' | tr -d '\r')
    [ "$v" = "$WEBVIEW" ] || echo "WARNING: WebView is $v, the fix is for $WEBVIEW: \`run\` will refuse to patch"
    gcc -O1 -static -o "$WORK/poke" "$HERE/poke.c" || { echo "could not build poke"; exit 1; }
    cat > "$WORK/wvpatch.sh" <<EOF
# Runs on the device as the app's user. Waits for WebView's library in the app's process, then patches it.
i=0
while [ \$i -lt 300 ]; do
  i=\$((i+1))
  pid=\$(pidof $PKG | cut -d' ' -f1)
  if [ -n "\$pid" ]; then
    grep "TrichromeLibrary.apk" /proc/\$pid/maps 2>/dev/null | while read range perms off rest; do
      s=\$((0x\${range%-*})); e=\$((0x\${range#*-})); o=\$((0x\$off))
      if [ $((LIB_IN_APK + GATE)) -ge \$o ] && [ $((LIB_IN_APK + GATE)) -lt \$((o + e - s)) ]; then
        printf '%x %s\n' \$((s + $((LIB_IN_APK + GATE)) - o)) \$pid; fi
    done > ./wvaddr
    # The device shell counts in 32 bits: an address above 2 GB comes out sign-extended.
    if [ -s ./wvaddr ]; then read addr p < ./wvaddr; addr=\${addr#ffffffff}; ./poke \$p \$addr 74 eb; exit \$?; fi
  fi
  sleep 0.2
done
echo "WebView's library never loaded"; exit 1
EOF
    adb push "$WORK/poke" /data/local/tmp/poke >/dev/null 2>&1 && adb push "$WORK/wvpatch.sh" /data/local/tmp/wvpatch.sh >/dev/null 2>&1
    if adb shell pm path "$PKG" >/dev/null 2>&1; then stage; else echo "Sofa is not installed yet: run \`emu.sh install\`"; fi
    echo "prepared"
}

# The patch tool has to run as the app's user, from the app's own folder.
stage() { adb shell "run-as $PKG sh -c 'cp /data/local/tmp/poke ./poke; cp /data/local/tmp/wvpatch.sh ./wvpatch.sh; chmod 700 ./poke'"; }

serve() {
    if curl -s --noproxy '*' -o /dev/null "http://127.0.0.1:$PORT/"; then echo "already serving"; else
        (cd "$HERE/www" && setsid nohup python3 -m http.server $PORT --bind 0.0.0.0 > "$WORK/www.log" 2>&1 < /dev/null &)
    fi
    echo "http://10.0.2.2:$PORT/"
}

install() {
    adb install -r "${1:-$REPO/app/build/outputs/apk/standard/debug/app-standard-debug.apk}" | tail -1
    stage
    settle
}

run() {
    local v; v=$(adb shell dumpsys webviewupdate | sed -n 's/.*Current WebView package.*, \(.*\))/\1/p' | tr -d '\r')
    adb shell am force-stop "$PKG"
    adb logcat -c
    if [ -n "${1:-}" ]; then adb shell am start -a android.intent.action.VIEW -d "$1" "$PKG" >/dev/null
    else adb shell am start -n "$PKG/app.sofabrowser.MainActivity" >/dev/null; fi
    if [ "$v" = "$WEBVIEW" ]; then adb shell "run-as $PKG sh ./wvpatch.sh"
    else echo "WebView is $v, not $WEBVIEW: not patched, pages may crash"; fi
    # A screenshot before the first frame is black, and a key sent before it can be lost.
    local i
    for i in $(seq 1 60); do
        adb logcat -d -s ActivityTaskManager:I | grep -q "Displayed $PKG" && break
        sleep 2
    done
    sleep 2
}

key() {
    declare -A K=([up]=19 [down]=20 [left]=21 [right]=22 [ok]=23 [back]=4 [menu]=82 [home]=3 [enter]=66)
    local codes="" k
    for k in "$@"; do [ -n "${K[$k]:-}" ] || { echo "unknown key: $k"; exit 2; }; codes+="${K[$k]} "; done
    # `input` returns only once the app has handled the key: no long sleeps needed after it.
    adb shell input keyevent $codes
}

shot() {
    local dir="${1:-$WORK/shots}"; mkdir -p "$dir"
    # `adb screencap` comes out black with this renderer; the console's screenshot works.
    adb emu screenrecord screenshot "$dir" >/dev/null
    ls -t "$dir"/Screenshot_*.png | head -1
}

cmd="${1:-}"; shift || true
case "$cmd" in
    start) start ;; prepare) prepare ;; serve) serve ;; install) install "$@" ;; run) run "$@" ;;
    key) key "$@" ;; shot) shot "$@" ;; settle) settle ;; stop) adb emu kill ;;
    *) sed -n '2,14p' "$0"; exit 2 ;;
esac
