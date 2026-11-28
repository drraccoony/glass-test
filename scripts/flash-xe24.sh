#!/usr/bin/env bash
# Flash Google's final XE24 firmware onto Glass Explorer Edition (Gen 1).
#
# Usage: scripts/flash-xe24.sh [--lock] [--skip-unlock] [--yes] <signed-glass_1-img-5585826.zip>
#
# Download the zip first from https://developers.google.com/glass/downloads/system
# This ERASES everything on Glass. Run "scripts/glasslink.sh setup" first for fastboot and USB access.
set -euo pipefail

TOOLS_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/glasslink"
EXPECTED_ZIP="signed-glass_1-img-5585826.zip"

say()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<USAGE
Usage: $0 [options] <path to $EXPECTED_ZIP>

Options:
  --lock          Re-lock the bootloader after flashing
  --skip-unlock   Skip "fastboot oem unlock" (bootloader already unlocked)
  --yes           Don't ask for confirmation

Get the zip from https://developers.google.com/glass/downloads/system
USAGE
    exit "${1:-0}"
}

LOCK=0 SKIP_UNLOCK=0 ASSUME_YES=0 ZIP=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --lock) LOCK=1 ;;
        --skip-unlock) SKIP_UNLOCK=1 ;;
        --yes|-y) ASSUME_YES=1 ;;
        -h|--help) usage 0 ;;
        -*) warn "Unknown option: $1"; usage 1 ;;
        *) [[ -z "$ZIP" ]] || usage 1; ZIP="$1" ;;
    esac
    shift
done
[[ -n "$ZIP" ]] || usage 1
[[ -f "$ZIP" ]] || die "File not found: $ZIP"
[[ "$(basename "$ZIP")" == "$EXPECTED_ZIP" ]] || warn "Expected $EXPECTED_ZIP, got $(basename "$ZIP"). Continuing anyway."

find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then command -v "$name"; return 0; fi
    if [[ -x "$TOOLS_DIR/platform-tools/$name" ]]; then echo "$TOOLS_DIR/platform-tools/$name"; return 0; fi
    return 1
}
FASTBOOT="$(find_tool fastboot)" || die "fastboot not found. Run: scripts/glasslink.sh setup"
ADB="$(find_tool adb || true)"
command -v unzip >/dev/null 2>&1 || die "unzip is required"

# Extract the images into a temporary folder.
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
say "Extracting $(basename "$ZIP")"
unzip -q "$ZIP" -d "$WORK" || die "Couldn't unzip $ZIP"

find_img() {
    local f
    f="$(find "$WORK" -type f -name "$1" | head -n 1)"
    [[ -n "$f" ]] || die "$1 not found in the zip. Is this the right file?"
    echo "$f"
}
BOOT_IMG="$(find_img boot.img)"
SYSTEM_IMG="$(find_img system.img)"
RECOVERY_IMG="$(find_img recovery.img)"
say "Found boot.img, system.img and recovery.img"

fastboot_serial() {
    "$FASTBOOT" devices 2>/dev/null | awk 'NF { print $1; exit }'
}

wait_for_fastboot() {
    local i
    for ((i = 0; i < "${1:-120}"; i++)); do
        SERIAL="$(fastboot_serial)"
        [[ -n "$SERIAL" ]] && return 0
        sleep 1
    done
    return 1
}

# Get Glass into fastboot mode.
SERIAL="$(fastboot_serial)"
if [[ -z "$SERIAL" && -n "$ADB" ]]; then
    adb_glass="$("$ADB" devices | awk 'NR > 1 && $2 == "device" { print $1 }' | while read -r s; do
        model="$("$ADB" -s "$s" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
        [[ "$model" == *Glass* ]] && { echo "$s"; break; }
    done)"
    if [[ -n "$adb_glass" ]]; then
        say "Glass found over adb ($adb_glass), rebooting it into fastboot"
        "$ADB" -s "$adb_glass" reboot bootloader
    fi
fi
if [[ -z "$SERIAL" ]]; then
    cat <<'HOWTO'

Glass isn't in fastboot mode yet. On a new Glass (no debug mode):
  1. Power Glass off completely.
  2. Hold the camera button, then press the power button.
  3. Keep holding the camera button until the LED comes on, then release.
  4. Plug Glass into this computer over USB.

Waiting up to 2 minutes for Glass to appear in fastboot...
HOWTO
    wait_for_fastboot 120 || die "No fastboot device found. Check the cable and USB rule (scripts/glasslink.sh setup), or try: sudo fastboot devices"
fi
say "Glass in fastboot: $SERIAL"
fb() { "$FASTBOOT" -s "$SERIAL" "$@"; }

# Confirm before wiping anything.
if [[ "$ASSUME_YES" -eq 0 ]]; then
    printf '\n\033[1;31mThis will ERASE ALL DATA on Glass and flash XE24.\033[0m\n'
    printf 'Unlocking or flashing voids the warranty and can leave Glass unusable if interrupted.\n'
    printf 'Keep Glass plugged in and charged until it finishes.\n\n'
    read -r -p 'Type "flash" to continue: ' answer
    [[ "$answer" == "flash" ]] || die "Cancelled"
fi

if [[ "$SKIP_UNLOCK" -eq 0 ]]; then
    say "Unlocking the bootloader (Glass asks for the command twice)"
    fb oem unlock || true
    sleep 2
    wait_for_fastboot 60 || die "Glass dropped out of fastboot after the first unlock"
    fb oem unlock || true
    sleep 2
    wait_for_fastboot 60 || die "Glass dropped out of fastboot after unlocking"
fi

say "Flashing boot"
fb flash boot "$BOOT_IMG"
say "Flashing system (this takes a few minutes)"
fb flash system "$SYSTEM_IMG"
say "Flashing recovery"
fb flash recovery "$RECOVERY_IMG"

say "Erasing cache and userdata"
fb erase cache
fb erase userdata

if [[ "$LOCK" -eq 1 ]]; then
    say "Re-locking the bootloader"
    fb oem lock
fi

say "Rebooting. The first boot can take several minutes."
fb reboot

cat <<'DONE'

Done. When Glass finishes booting:
  - Turn on debug: Settings > Device info > Turn on debug
  - Then install GlassLink: scripts/glasslink.sh install-glass
DONE
