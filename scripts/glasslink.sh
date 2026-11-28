#!/usr/bin/env bash
# GlassLink helper for Linux (tested with Fedora-based distros such as Bazzite).
# Installs adb if needed, sets up USB access for Glass, and installs/inspects the apps.
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/glasslink"
GLASS_PKG="dev.glassrevival.glass"
PHONE_PKG="dev.glassrevival.companion"
UDEV_RULE="/etc/udev/rules.d/51-glasslink.rules"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

find_adb() {
    if command -v adb >/dev/null 2>&1; then ADB="$(command -v adb)"; return 0; fi
    if [[ -x "$TOOLS_DIR/platform-tools/adb" ]]; then ADB="$TOOLS_DIR/platform-tools/adb"; return 0; fi
    return 1
}

need_adb() {
    find_adb || die "adb not found. Run: $0 setup"
}

# Pick a device whose model matches (or doesn't match) "Glass".
serial_for() {
    local want="$1" serial model
    while read -r serial state _; do
        [[ "$state" == "device" ]] || continue
        model="$("$ADB" -s "$serial" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')"
        if [[ "$want" == glass && "$model" == *Glass* ]]; then echo "$serial"; return 0; fi
        if [[ "$want" == phone && "$model" != *Glass* ]]; then echo "$serial"; return 0; fi
    done < <("$ADB" devices | tail -n +2)
    return 1
}

glass_serial() {
    [[ -n "${GLASS_SERIAL:-}" ]] && { echo "$GLASS_SERIAL"; return; }
    serial_for glass || die "No Glass found over adb. Plug it in, turn on debug mode, then run: $0 devices"
}

phone_serial() {
    [[ -n "${PHONE_SERIAL:-}" ]] && { echo "$PHONE_SERIAL"; return; }
    serial_for phone || die "No phone found over adb. Enable USB debugging on the phone and plug it in."
}

pick_apk() {
    local given="$1" built="$2" name="$3"
    for f in "$given" "$REPO_DIR/$built" "$PWD/$name" "$REPO_DIR/$name"; do
        [[ -n "$f" && -f "$f" ]] && { echo "$f"; return 0; }
    done
    die "Can't find $name. Build it with ./gradlew assembleDebug or pass the APK path."
}

cmd_setup() {
    if find_adb; then
        say "adb found: $ADB"
    else
        say "Downloading Android platform-tools into $TOOLS_DIR"
        mkdir -p "$TOOLS_DIR"
        local zip="$TOOLS_DIR/platform-tools.zip"
        curl -fSL -o "$zip" https://dl.google.com/android/repository/platform-tools-latest-linux.zip
        rm -rf "$TOOLS_DIR/platform-tools"
        unzip -q "$zip" -d "$TOOLS_DIR"
        rm -f "$zip"
        find_adb || die "platform-tools download failed"
        say "Installed $ADB"
        say "Tip: add it to your PATH with:  echo 'export PATH=\"$TOOLS_DIR/platform-tools:\$PATH\"' >> ~/.bashrc"
    fi

    if [[ -f "$UDEV_RULE" ]]; then
        say "USB rule already present: $UDEV_RULE"
    else
        say "Adding a udev rule so your user can reach Glass over USB (needs sudo)"
        # 18d1 is Google's USB vendor ID, which Glass uses in debug and fastboot modes.
        sudo tee "$UDEV_RULE" >/dev/null <<'RULE'
SUBSYSTEM=="usb", ATTR{idVendor}=="18d1", MODE="0660", TAG+="uaccess"
RULE
        sudo udevadm control --reload-rules
        sudo udevadm trigger
        say "Unplug and replug Glass now"
    fi
}

cmd_devices() {
    need_adb
    "$ADB" devices -l
}

cmd_install_glass() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" glass/build/outputs/apk/debug/glass-debug.apk glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    cmd_start
}

# Add-on: turn-by-turn from Google Maps on the phone. Needs GlassLink on both devices.
cmd_install_nav() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" nav-glass/build/outputs/apk/debug/nav-glass-debug.apk nav-glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    say "Start navigation in Google Maps on your phone; the card appears next to the clock"
}

# Add-on: recipes and instructions shared from the phone ("Send to Glass").
cmd_install_recipes() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" recipes-glass/build/outputs/apk/debug/recipes-glass-debug.apk recipes-glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    say "On your phone, share a recipe page and choose Send to Glass"
}

cmd_install_weather() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" weather-glass/build/outputs/apk/debug/weather-glass-debug.apk weather-glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    say "Say \"ok glass, show me the weather\" on Glass"
}

cmd_install_music() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" music-glass/build/outputs/apk/debug/music-glass-debug.apk music-glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    say "Play something on your phone; the card shows up next to the clock"
}

cmd_install_airports() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" airports-glass/build/outputs/apk/debug/airports-glass-debug.apk airports-glass-debug.apk)"
    serial="$(glass_serial)"
    say "Installing $(basename "$apk") on Glass ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    say "Pick airports in GlassLink on your phone (Airports tab), then say \"ok glass, show airports\""
}

cmd_install_phone() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" companion/build/outputs/apk/debug/companion-debug.apk companion-debug.apk)"
    serial="$(phone_serial)"
    say "Installing $(basename "$apk") on phone ($serial)"
    "$ADB" -s "$serial" install -r "$apk"
    "$ADB" -s "$serial" shell am start -n "$PHONE_PKG/.MainActivity" >/dev/null
    say "GlassLink is open on your phone"
}

SYSTEM_APK="/system/priv-app/GlassLink.apk"

# Waits for a device to come back after a reboot and finish booting.
wait_for_boot() {
    local serial="$1" i
    "$ADB" -s "$serial" wait-for-device
    for i in $(seq 1 90); do
        [[ "$("$ADB" -s "$serial" shell getprop sys.boot_completed </dev/null 2>/dev/null | tr -d '\r')" == 1 ]] && return 0
        sleep 2
    done
    die "Glass didn't finish booting. Check it, then run: $0 start"
}

# Installs GlassLink in /system/priv-app so it may write to Glass's own timeline.
# XE24 debug mode gives adb root, so this needs no other tools. Settings (pair code)
# are kept. Later updates can use install-glass as usual; Glass keeps the system grant.
cmd_install_glass_system() {
    need_adb
    local apk serial
    apk="$(pick_apk "${1:-}" glass/build/outputs/apk/debug/glass-debug.apk glass-debug.apk)"
    serial="$(glass_serial)"
    local sh=("$ADB" -s "$serial" shell)
    [[ "$("${sh[@]}" id </dev/null | tr -d '\r')" == uid=0* ]] || die "adb on Glass isn't root. Is debug mode on?"

    say "Saving GlassLink settings"
    "${sh[@]}" "rm -rf /data/local/tmp/glasslink-prefs; cp -r /data/data/$GLASS_PKG/shared_prefs /data/local/tmp/glasslink-prefs 2>/dev/null" </dev/null || true

    if "${sh[@]}" pm path "$GLASS_PKG" </dev/null | grep -q "/data/app"; then
        say "Removing the regular install"
        "${sh[@]}" pm uninstall "$GLASS_PKG" </dev/null >/dev/null
    fi

    say "Copying $(basename "$apk") to $SYSTEM_APK"
    "$ADB" -s "$serial" push "$apk" /data/local/tmp/GlassLink.apk >/dev/null
    "${sh[@]}" "mount -o rw,remount /system && cp /data/local/tmp/GlassLink.apk $SYSTEM_APK && chmod 644 $SYSTEM_APK && chown root:root $SYSTEM_APK; rc=\$?; rm /data/local/tmp/GlassLink.apk; mount -o ro,remount /system; exit \$rc" </dev/null \
        || die "Couldn't write to /system"

    say "Rebooting Glass so it picks up the system app"
    "$ADB" -s "$serial" reboot
    wait_for_boot "$serial"

    say "Restoring settings"
    # The app's new user id. Glass's busybox stat has no -c, so ask the package manager.
    local uid
    uid="$("${sh[@]}" dumpsys package "$GLASS_PKG" </dev/null | sed -n 's/.*userId=\([0-9]*\).*/\1/p' | head -1)"
    [[ -n "$uid" ]] || die "Couldn't find GlassLink's user id. Settings are saved in /data/local/tmp/glasslink-prefs"
    local prefs="/data/data/$GLASS_PKG/shared_prefs"
    "${sh[@]}" "if [ -d /data/local/tmp/glasslink-prefs ]; then am force-stop $GLASS_PKG && mkdir -p $prefs && cp /data/local/tmp/glasslink-prefs/* $prefs/ && chown $uid:$uid $prefs $prefs/* && chmod 771 $prefs && chmod 660 $prefs/* && rm -rf /data/local/tmp/glasslink-prefs; fi" </dev/null \
        || warn "Couldn't restore settings. They are saved in /data/local/tmp/glasslink-prefs on Glass"

    glass_location_setup "$serial"

    if "${sh[@]}" dumpsys package "$GLASS_PKG" </dev/null | grep -q "com.google.glass.sync.permission.TIMELINE_PROVIDER"; then
        say "GlassLink can now write to the Glass timeline"
    else
        warn "Glass didn't grant the timeline permission. Check: adb shell dumpsys package $GLASS_PKG"
    fi
    cmd_start
}

# Lets Glass apps use the phone's location: allow mock locations (GlassLink provides
# "gps" and "network" from the phone) and turn on the Glass Labs flag that makes Glass's
# own location service read the local "gps" provider. Both survive reboots.
glass_location_setup() {
    local serial="$1"
    "$ADB" -s "$serial" shell "settings put secure mock_location 1; setprop persist.lab.gps_enable_local true" </dev/null
    say "Glass apps can now use the phone's location"
}

# Puts GlassLink back to a regular install.
cmd_uninstall_glass_system() {
    need_adb
    local serial
    serial="$(glass_serial)"
    say "Removing $SYSTEM_APK"
    "$ADB" -s "$serial" shell "mount -o rw,remount /system && rm -f $SYSTEM_APK; mount -o ro,remount /system" </dev/null
    "$ADB" -s "$serial" reboot
    wait_for_boot "$serial"
    say "Done. Install the regular app again with: $0 install-glass"
}

cmd_start() {
    need_adb
    local serial
    serial="$(glass_serial)"
    "$ADB" -s "$serial" shell am start -n "$GLASS_PKG/.MainActivity" >/dev/null
    say "GlassLink is open on Glass"
    cmd_info
}

cmd_info() {
    need_adb
    local serial prefs code addr
    serial="$(glass_serial)"
    addr="$("$ADB" -s "$serial" shell settings get secure bluetooth_address 2>/dev/null | tr -d '\r')"
    # Debug builds allow run-as, so we can read the pair code straight from the app's prefs.
    prefs="$("$ADB" -s "$serial" shell run-as "$GLASS_PKG" cat shared_prefs/glasslink.xml 2>/dev/null | tr -d '\r' || true)"
    code="$(sed -n 's/.*name="pair_code">\([0-9]*\)<.*/\1/p' <<<"$prefs")"
    echo
    echo "  Glass address : ${addr:-unknown (shown on the GlassLink status card)}"
    echo "  Pair code     : ${code:-not created yet (open the app once)}"
    echo
    echo "  Enter both in the GlassLink app on your phone."
}

cmd_logs() {
    need_adb
    local target="${1:-glass}" serial
    if [[ "$target" == phone ]]; then serial="$(phone_serial)"; else serial="$(glass_serial)"; fi
    "$ADB" -s "$serial" logcat -v time -s GlassLink:V AndroidRuntime:E
}

usage() {
    cat <<USAGE
Usage: $0 <command>

  setup                 Install adb if missing and add a USB rule for Glass
  devices               List connected devices
  install-glass [apk]   Install the Glass app and open it
  install-phone [apk]   Install the phone companion and open it
  install-nav [apk]     Install GlassLink Navigation on Glass (turn-by-turn from Maps)
  install-recipes [apk] Install Recipes on Glass (recipes and instructions from the phone)
  install-airports [apk] Install Airports on Glass (METARs for your airports)
  install-weather [apk] Install Weather on Glass (the forecast)
  install-music [apk]   Install Now Playing on Glass (media remote)
  install-glass-system [apk]
                        Install the Glass app as a system app, so notifications
                        can go into Glass's own timeline (reboots Glass)
  uninstall-glass-system
                        Remove the system copy again (reboots Glass)
  start                 Open GlassLink on Glass and print the pairing info
  info                  Print the Glass Bluetooth address and pair code
  logs [glass|phone]    Follow GlassLink logs

With both Glass and the phone plugged in, devices are told apart by model name.
Set GLASS_SERIAL or PHONE_SERIAL to choose one explicitly.
USAGE
}

case "${1:-}" in
    setup) cmd_setup ;;
    devices) cmd_devices ;;
    install-glass) shift; cmd_install_glass "${1:-}" ;;
    install-phone) shift; cmd_install_phone "${1:-}" ;;
    install-nav) shift; cmd_install_nav "${1:-}" ;;
    install-recipes) shift; cmd_install_recipes "${1:-}" ;;
    install-airports) shift; cmd_install_airports "${1:-}" ;;
    install-weather) shift; cmd_install_weather "${1:-}" ;;
    install-music) shift; cmd_install_music "${1:-}" ;;
    install-glass-system) shift; cmd_install_glass_system "${1:-}" ;;
    uninstall-glass-system) cmd_uninstall_glass_system ;;
    start) cmd_start ;;
    info) cmd_info ;;
    logs) shift; cmd_logs "${1:-glass}" ;;
    *) usage ;;
esac
