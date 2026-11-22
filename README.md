# GlassLink

A replacement companion for Google Glass Explorer Edition (Gen 1). The original
MyGlass app is gone, so this project gives Glass a new phone link that mirrors
your Android phone's notifications onto the display.

| Module | Runs on | What it does |
| --- | --- | --- |
| `glass/` | Glass (Android 4.4, API 19) | Bluetooth server, notification cards, alerts |
| `companion/` | Android phone (8.0 or newer) | Reads notifications and sends them to Glass |
| `protocol/` | Both | Shared message format, with unit tests |
| `scripts/glasslink.sh` | Your Linux PC | adb setup, USB access, install, logs |

## Features

- New notifications wake Glass, chime, and show as a card with the app icon,
  title and text. The card closes on its own after 10 seconds if you don't touch it.
- Swipe through recent notifications. Tap a card to dismiss it, which also clears
  it on the phone. Clearing it on the phone removes it from Glass.
- Message apps show the last few messages with sender names.
- Quiet notification channels and Do Not Disturb still show on Glass, without the chime.
- Ongoing notifications (music, downloads, navigation) and anything an app marks as
  phone-only are skipped. You can mute individual apps.
- When the link drops, the phone reconnects automatically. Glass then resyncs to
  whatever is still active on the phone.
- Glass shows its battery level in the phone's status notification.
- Both apps start again after a reboot.

## Before you start: Glass firmware

A stock Explorer Edition gets stuck on a "download MyGlass" screen because the
pairing service no longer exists. In 2020 Google published a final firmware
(XE24) that removes the Google account requirement. Flash it first with Google's
official instructions for Glass Explorer Edition. You will need `adb` and
`fastboot`, which `scripts/glasslink.sh setup` installs.

After flashing, turn on debug mode on Glass: **Settings > Device info > Turn on debug**.

## Setup on Linux (Bazzite)

```bash
git clone <this repo> && cd glass-test
./scripts/glasslink.sh setup        # installs adb if missing, adds a USB rule (asks for sudo)
```

Get the APKs. Either build them (needs JDK 17+ and the Android SDK):

```bash
./gradlew assembleDebug
```

or download `glasslink-apks` from the latest run on the repo's **Actions** tab.

Install on Glass (plugged in over USB):

```bash
./scripts/glasslink.sh install-glass
```

This opens GlassLink on Glass and prints the **Glass address** and **pair code**.
They are also on the first card in the app.

Install on your phone (USB debugging on, plugged in). Installing with adb also
avoids Android's "restricted settings" block on notification access.

```bash
./scripts/glasslink.sh install-phone
```

If both devices are plugged in at once, the script tells them apart by model name.
Set `GLASS_SERIAL` or `PHONE_SERIAL` to choose one explicitly.

## Pairing

1. On the phone, open GlassLink and allow **Notification access** and **Bluetooth**.
2. Enter the Glass address (or choose Glass from paired devices) and the pair code.
3. Tap **Save and connect**. The status at the top turns green.
4. Tap **Send test**. Glass should wake and show the card.

Pairing Glass in the phone's Bluetooth settings is optional. Without it, the
phone connects directly by address and Glass still requires the pair code. With
it, the Bluetooth link is also encrypted. To pair, tap the status card on Glass
to make it discoverable for 5 minutes, then pair from the phone.

## Using it on Glass

| Gesture | Action |
| --- | --- |
| Swipe forward / back | Next or previous card |
| Tap a notification | Dismiss it here and on the phone |
| Tap the status card | Make Glass discoverable (while not connected) |
| Swipe down | Close |

You can also open it by voice: **"ok glass, show notifications"**. With debug mode
on, `./scripts/glasslink.sh start` opens it over USB.

If swipe directions feel backwards on your unit, swap `onNext()` and
`onPrevious()` in `glass/src/main/java/dev/glassrevival/glass/Gestures.java`.

## Troubleshooting

- **Phone says "Glass not reachable"**: GlassLink must have been opened on Glass
  at least once since install, and Bluetooth must be on. Check the logs with
  `./scripts/glasslink.sh logs`.
- **"Glass rejected the pair code"**: run `./scripts/glasslink.sh info` and
  re-enter the code on the phone.
- **Notification access is greyed out**: open App info for GlassLink on the phone,
  tap the three-dot menu, choose **Allow restricted settings**, then try again.
- **Forwarding stops after a while**: set the phone's battery setting for
  GlassLink to **Unrestricted**.
- **adb shows "no permissions"**: run `./scripts/glasslink.sh setup` again, then
  unplug and replug Glass.

## Protocol

Glass listens on RFCOMM (service UUID `6b3c1f6e-2f0a-4c55-9d0e-6a9f4e1c2b77`) and
the phone connects. Each message is a 4-byte big-endian length followed by UTF-8
JSON. The phone must send `hello` with the pair code first. See
`protocol/src/main/java/dev/glassrevival/protocol/Protocol.java` for every
message type.

## Signing

Both apps are signed with `keystore/glasslink-dev.jks` (password `glasslink`).
It is committed on purpose so builds from your PC and from CI can update each
other. It is a development key and protects nothing.
