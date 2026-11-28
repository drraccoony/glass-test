# GlassLink

A replacement companion for Google Glass Explorer Edition (Gen 1). The original
MyGlass app is gone, so this project gives Glass a new phone link that mirrors
your Android phone's notifications onto the display.

| Module | Runs on | What it does |
| --- | --- | --- |
| `glass/` | Glass (Android 4.4, API 19) | Bluetooth server, notification cards, alerts |
| `companion/` | Android phone (8.0 or newer) | Reads notifications and sends them to Glass |
| `protocol/` | Both | Shared message format, with unit tests |
| `hub-api/` | Both | Library that lets other apps share the link (see [Add-on apps](#add-on-apps)) |
| `nav-glass/` | Glass | GlassLink Navigation: turn-by-turn from Google Maps on the phone |
| `recipes-glass/` | Glass | Recipes: step through recipes and instructions shared from the phone |
| `airports-glass/` | Glass | Airports: the latest METAR for each of your airports |
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

To flash it from Linux, download `signed-glass_1-img-5585826.zip` from
[Google's Glass system images page](https://developers.google.com/glass/downloads/system), then run:

```bash
./scripts/flash-xe24.sh ~/Downloads/signed-glass_1-img-5585826.zip
```

The script puts Glass into fastboot, or tells you how to do it with the buttons. It then
unlocks the bootloader, flashes boot, system and recovery, wipes the device and reboots.
Add `--lock` to re-lock the bootloader afterwards.

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

## Glass timeline (optional)

GlassLink can also put notifications into Glass's own timeline, the cards to the
right of the clock, the way MyGlass did. They chime and wake Glass like native
notifications. Tap one for **Reply** (if the app supports it), **Read aloud** or **Dismiss**.

Glass only lets system apps write to the timeline. XE24 in debug mode gives adb root,
so the script can install GlassLink as one (Glass reboots once):

```bash
./scripts/glasslink.sh install-glass-system
```

After that, `install-glass` updates it as usual and Glass keeps the permission.
Turn it off with **Show in the Glass timeline** in the phone app, or undo the system
install with `./scripts/glasslink.sh uninstall-glass-system`. Glass only shows
alerts while it's being worn. Otherwise the card is added quietly.

## Navigation (optional)

**GlassLink Navigation** puts Google Maps turn-by-turn on Glass: a live card next to the
clock with the turn arrow, the distance, the instruction, a route progress bar and your
arrival time. Glass chimes and wakes as a turn gets close (about 500 ft). Tap the card to
stop navigation, which also ends it in Maps on the phone.

```bash
./scripts/glasslink.sh install-nav
```

Then start navigation in Google Maps on the phone as usual. The companion reads Maps'
navigation notification and sends each step to Glass. It needs notification access, which
GlassLink already has.

## Recipes (optional)

Share a recipe page (from Chrome or any app) or some text on the phone and choose
**Send to Glass**. Recipe sites are read from the recipe data they embed (schema.org),
so you get the title, photo, ingredients and steps without the clutter. With plain text,
each line becomes a step.

On Glass you get a title card, ingredient cards and one card per step. The screen stays
on. Swipe, or say **"ok glass"** and then **go forward**, **go back**, **show
instructions**, **show ingredients**, **read aloud**, **start a timer** (when a step
mentions a time), **stop the timer** or **stop this**. Glass recognizes only its own
built-in phrases offline, which is why it isn't "next step". The timer rings even if you
leave the app. **"ok glass, show recipe"** reopens the last one
where you left off.

```bash
./scripts/glasslink.sh install-recipes
```

## Airports (optional)

**Airports** shows the latest METAR for up to six airports, one card each, with the
raw report and its flight category in color (VFR, MVFR, IFR, LIFR). On the phone, open
the **Airports** tab, turn on **Show METARs on Glass** and enter codes like `KLVN, KMSP`.
Reports come from aviationweather.gov and refresh every 10 minutes while Glass is
connected. On Glass, say **"ok glass, show airports"**. Tap a card to fetch right away.

```bash
./scripts/glasslink.sh install-airports
```

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

## Add-on apps

Other apps can use the GlassLink connection, so a feature can ship as its own Glass
app and phone app. GlassLink on Glass and the phone companion each run a **hub**. An
add-on picks a **channel** name, such as `dev.glassrevival.nav`, and exchanges JSON
messages with the app on the other device that uses the same channel. On the wire,
those frames carry a `"ch"` field. Neither hub looks inside them.

Add `implementation project(':hub-api')` and sign the app with the GlassLink key.
The hub is protected by a signature permission, so apps signed with any other key are refused.

```java
HubClient nav = new HubClient(context, "dev.glassrevival.nav", new HubClient.Callback() {
    @Override public void onMessage(JSONObject msg) { /* from the other device */ }
    @Override public void onConnectionChanged(boolean connected) { /* Glass <-> phone link */ }
});
nav.connect();                                   // binds to the hub, starts it if needed
nav.send(new JSONObject().put("type", "step").put("street", "Main St"));
nav.disconnect();
```

To be woken when a message arrives while the app isn't bound, extend `HubReceiver`
and declare it like this. The hub tries bound clients first, then receivers.

```xml
<receiver android:name=".NavReceiver" android:exported="true"
    android:permission="dev.glassrevival.permission.HUB">
    <intent-filter>
        <action android:name="dev.glassrevival.hub.action.MESSAGE" />
    </intent-filter>
    <meta-data android:name="dev.glassrevival.hub.CHANNELS" android:value="dev.glassrevival.nav" />
</receiver>
```

Messages go out only while Glass and the phone are linked. `send()` returns false
otherwise, and nothing is queued. A message can be up to 512 KB.

## Signing

Both apps, and any add-ons, are signed with `keystore/glasslink-dev.jks` (password `glasslink`).
It is committed on purpose so builds from your PC and from CI can update each
other. It is a development key and protects nothing.
