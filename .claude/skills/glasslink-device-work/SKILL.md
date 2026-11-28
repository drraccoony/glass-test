---
name: glasslink-device-work
description: Build, install, test and debug GlassLink on the real Glass and phone - JDK and SDK paths, glasslink.sh commands, driving and screenshotting Glass over adb, posting test notifications, KitKat tool quirks, and the add-on hub. Use when building or installing the apps, checking a change on the devices, reading logs, or writing an add-on app that uses the hub.
---

# Working with GlassLink on the devices

## Build

- **This machine has no system Java.** Use the one the setup script installed:
  ```bash
  export JAVA_HOME=~/.local/share/glasslink/jdk
  ./gradlew :protocol:test assembleDebug
  ```
- **SDK:** `~/.local/share/glasslink/sdk` (set in `local.properties`). It includes build-tools 35 (`d8`) and platform 35.
- **Modules:** `protocol` (plain Java, unit tests), `hub-api` (Android library), `glass` (API 19),
  `companion` (phone, API 26+), `gdk-stubs` (compile-only GDK).

## Install

| Command | What it does |
|---|---|
| `./scripts/glasslink.sh install-glass` | `adb install -r`, then opens the app and prints the pair code |
| `./scripts/glasslink.sh install-phone` | the same for the phone |
| `./scripts/glasslink.sh install-glass-system` | one-time system install so the Glass timeline works; reboots Glass and keeps settings |
| `./scripts/glasslink.sh uninstall-glass-system` | removes the system copy; reboots Glass |
| `./scripts/glasslink.sh logs [glass\|phone]` | follow GlassLink's logs |

- **After the system install, `install-glass` still works.** Glass treats it as an updated system app
  and keeps the timeline permission.
- **Serials:** with both devices plugged in, the script tells them apart by model name. Set
  `GLASS_SERIAL` or `PHONE_SERIAL` to override. On this setup Glass is `015ECD9B1801300E` and the phone
  (Galaxy Fold) is `RFGL73Y435R`.
- **Don't print or paste the pair code** into anything outside the terminal.

## Quirks on Glass (Android 4.4)

- **adb runs as root** (XE24 with debug mode on).
- **No `adb exec-out`.** Take screenshots like this:
  ```bash
  adb -s $G shell screencap -p /sdcard/s.png && adb -s $G pull /sdcard/s.png <scratchpad>/s.png && adb -s $G shell rm /sdcard/s.png
  ```
  Then view the PNG with the Read tool. The screen is 640×360.
- **Busybox `stat` has no `-c`.** Get an app's uid from `dumpsys package <pkg> | grep userId=`.
- **`content query` prints long columns cut to 32 bits.** Values like `display_time=551349347` are fine.
  Check against `modified_time`, which also shows the current time cut down.
- **In scripts, give every `adb shell` inside a `while read` loop `</dev/null`.** Otherwise it reads the rest of the loop's input.
- **Driving the UI from adb:**
  - `input keyevent 26` is power, to wake the screen
  - `22` and `21` swipe forward and back
  - `23` taps (select)
  - `4` swipes down (back)
  - The home clock is the first card; the timeline is to its right.
- **The stack trace "Server socket error ... read failed"** after reinstalling or reconnecting is
  the old Bluetooth listener being cut off. It isn't a crash.

## Quirks on the phone (Samsung, Android 14+)

- **Post a test notification:** `adb -s $P shell "cmd notification post -t 'Title' some_tag 'Body text'"`.
  It's forwarded like any app's notification. Its `when` is not a real timestamp.
- **This phone's `cmd notification` has no `cancel`.** Clear test notifications by hand.

## The add-on hub

Other apps can share the Glass↔phone link. Details are in the README section "Add-on apps" and in `hub-api/`.

- **On the wire:** any frame with a `"ch"` field goes straight to the app that registered that channel.
  The core protocol never uses `ch`.
- **Running apps** use `HubClient(context, channel, callback)`, then `connect()`, `send(JSONObject)` and `disconnect()`.
- **To be woken by a message,** declare a `HubReceiver` with `<meta-data android:name="dev.glassrevival.hub.CHANNELS">`.
- **Who can use it:** the hub is protected by the signature permission `dev.glassrevival.permission.HUB`,
  so add-on apps must be signed with `keystore/glasslink-dev.jks`.
- **Message ordering on Glass:** add-on frames are only sent after `welcome`
  (the `Session.welcomed` flag in `LinkService`), because the phone expects `welcome` first.

## Apps tab (installing add-ons from the phone)

- **What it is:** the companion bundles the add-on APKs (`copyAddOns` in `companion/build.gradle`
  puts them in `assets/addons/<package>.apk`). Glass reports installed `dev.glassrevival.*` apps
  with the SHA-1 of each APK (`apps` message, `glass/.../AddOns.java`), so any rebuild shows "Update available".
- **Needs the system install with the current manifest:** permissions come from the `/system`
  copy, so after `INSTALL_PACKAGES`/`DELETE_PACKAGES` were added it took one more
  `install-glass-system`. Check with
  `adb -s $G shell dumpsys package dev.glassrevival.glass | grep -E "INSTALL_PACKAGES|DELETE_PACKAGES"`.
- **Driving the phone UI from adb:** find buttons with `uiautomator dump` and tap the middle of their
  `bounds`. Screenshots: `adb -s $P shell screencap -p /sdcard/p.png`, then pull. `exec-out screencap`
  prepends a multiple-displays warning on the Fold, which corrupts the PNG.
- **APK size matters:** an install crosses Bluetooth at about 5 KB/s. The companion bundles the
  add-ons' **release** builds. Debug builds are packaged in place and collect holes; Weather's
  debug APK grew to 267 KB with 60 KB of content. Glass apps only generate hdpi PNGs from vector drawables.
- **`am force-stop` doesn't stop the companion** (it's running again right away), so editing its
  `shared_prefs` from adb gets overwritten by the running app.

## Navigation add-on (`nav-glass`)

- **What it is:** a separate Glass app that uses the hub on channel `dev.glassrevival.nav`.
- **Phone side:** `companion/.../NavProvider.java` reads Google Maps' navigation notification
  (category `navigation`). The message format is documented in that class.
- **Install:** `./scripts/glasslink.sh install-nav`
- **Testing from adb:** start a real navigation on the phone with
  `adb -s $P shell "am start -a android.intent.action.VIEW -d 'google.navigation:q=LAT,LON&mode=d' -p com.google.android.apps.maps"`.
  Stop it with `am force-stop com.google.android.apps.maps`, or with the card's Stop menu.
  - To see the raw notification: `dumpsys notification --noredact`.
  - In 2026 the title looked like `350 ft · Turn right onto X`, the subText like `Arrive 1:11 AM`,
    and the large icon was the turn arrow.
- **Reinstalling** the nav app kills its service. The next step from the phone wakes it through
  `NavReceiver`. To force a new step, restart the Maps navigation.

## Native timeline mode

- `LinkService` sends notifications to `NativeTimeline` when GlassLink is a system app and the phone's
  "Show in the Glass timeline" switch is on (settings field `timeline`).
- Otherwise it uses GlassLink's own cards and pop-up.
- For how the timeline itself works, see the `glass-xe24-internals` skill.

## Checking a change end to end

1. Build, then run `install-glass` and/or `install-phone`. Watch both logs for `Connected to Glass`
   and `Paired with`.
2. Post a test notification on the phone.
3. Check that the card was written:
   `adb -s $G shell "content query --uri content://com.google.glass.sync.timeline/timeline/withExpired --projection _id:is_deleted --where \"source='glasslink'\""`
4. Wake Glass, swipe right, take a screenshot, then tap to see the menu.
5. Alerts (chime and wake) only fire while Glass is being worn. Ask the user to put it on to check them.
