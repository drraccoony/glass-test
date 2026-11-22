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
        model="$("$ADB" -s "$serial" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
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
    start) cmd_start ;;
    info) cmd_info ;;
    logs) shift; cmd_logs "${1:-glass}" ;;
    *) usage ;;
esac
