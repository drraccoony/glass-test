---
name: glass-xe24-internals
description: Reverse-engineered internals of Google Glass Explorer Edition firmware XE24 - the native timeline (TimelineProvider, TimelineItem protobuf, notify broadcast, menus, deletes), system-app permissions, and how to re-derive them by decompiling GlassSync/GlassHome. Use when writing to the Glass timeline, changing NativeTimeline, touching anything in com.google.glass.*, or investigating other built-in Glass behavior.
---

# Glass XE24 internals

These findings come from decompiling `/system/priv-app/GlassSync.apk` and `GlassHome.apk`
pulled from a real Glass on XE24 (build 5585826), then testing on that device.
GlassLink's implementation is `glass/src/main/java/dev/glassrevival/glass/timeline/NativeTimeline.java`.
Every protobuf field number is listed in [reference.md](reference.md).

## The native timeline

- **Storage:** content provider `com.google.glass.sync.timeline`, run by the `com.google.glass.sync` app.
  The database is `/data/data/com.google.glass.sync/databases/timeline.db`, table `timeline`.
- **Permission:** `com.google.glass.sync.permission.TIMELINE_PROVIDER`, protection level `signature|system`.
  Normal apps can't get it. An app in `/system/priv-app` gets it (see "System apps" below).
- **The GDK has no public way to add cards.** XE24's `glass-core.jar` only has `LiveCard`.
  `TimelineManager` was removed in XE16, and the Mirror API is shut down.
- **URIs:**
  - `content://com.google.glass.sync.timeline/timeline` hides expired items.
  - `.../timeline/withExpired` returns everything, including deleted-card records (tombstones).
  - `.../timeline/<id>` addresses a single item.
- **The card itself** is in the `protobuf_blob` column (a `TimelineNano.TimelineItem`, the Mirror API format).
  The other columns are copies of blob fields used for sorting and filtering. Fill them the way
  `TimelineItemDatabaseSerializationHelper.toContentValues` does. NativeTimeline mirrors that exactly.
- **Inserting an id that already exists** replaces the row only if the new `modified_time` is greater.
  The provider ignores inserts over a deleted card's tombstone, so delete the tombstone first.
- **`delete()` on the provider is a hard delete.**

## Making Glass chime and show a card

Inserting a row is silent. The home screen alerts only when it receives this broadcast:

- action `com.google.glass.action.NOTIFY_TIMELINE_ITEM`
- target package `com.google.glass.home`
- **No permission is required.** It's registered by `home.NotificationService.TimelineUpdateBroadcastReceiver`.
- extras:
  - `item`: the blob bytes
  - `item_id`: a `com.google.glass.timeline.TimelineItemId` (Serializable)
  - `num_notifications`: int, e.g. 1
- GlassHome uses its own copy of `TimelineItemId` to read `item_id`. Your copy needs the same
  class name, the same field names and types, and `serialVersionUID = 9213358818717748552L`.
  See `glass/src/main/java/com/google/glass/timeline/TimelineItemId.java`.
- The fields are: `String bundleId`, `long displayTime`, `boolean isBundleCover`, `boolean isPinned`, `String itemId`.
- **Glass drops alerts while it isn't being worn** (`DonState.isDonned`). The card is still added.
- **What happens after the broadcast** (`home.NotificationService`):
  - **Screen off:** Glass plays the notification chime and opens a 5-second window
    (`WearConstants.NO_RESPONSE_TIMEOUT_MS`). The screen stays dark during the window.
  - **During the window,** one of these wakes Glass and jumps to the card:
    - looking at the display, if the Glass setting `notification_glance_enabled` is on
      (and Labs `LASER_RLU` is enabled)
    - tilting the head up, if `head_wake_up_enabled` is on
    - a tap on the touchpad, always
  - **Screen already on:** Glass only plays the chime.
  - **If Labs `DN` is enabled,** Glass opens a speech window instead.
  - So Glass never turns the screen on by itself for a timeline notification.
  - Read these settings with `content query --uri content://com.google.android.glass.settings/settings`.
- **Bundled cards:** if the item has a `bundleId`, GlassHome looks up the bundle's cover card and
  sends nothing if it can't find one. GlassLink doesn't use bundles.
- **Glass's own alerts:** `TimelineNotificationService` only scans `delivery_time` at startup and at
  times it scheduled. It doesn't notice new inserts, which is why you send the broadcast yourself.

## Card content and display

- **Text cards** show only `text` (field 8). `title` (field 23) isn't displayed, so put the sender or
  subject on the first line of `text`.
- **HTML cards** (`html`, field 24) take priority over `text` and are what GlassLink uses (`CardHtml.java`).
  - **Rendering:** `html.PagedWebView` wraps the HTML in a page, links Glass's stylesheet
    (`glass://GLASS_CSS`, which is `base_style_kitkat_1.css` in GlassSync's assets) and loads
    `file:///android_asset/cards_compiled.js`. Both are on the device, so they work offline.
  - **Size:** cards are 640×360. Mirror classes such as `text-auto-size` (fits text to the space,
    done by the JS), `text-small` and `muted` all work.
  - **Images:** `data:` URIs load directly. `attachment:N` and `cid:` load from the item's attachments,
    and `http(s)` URLs are fetched over the network. `file:` only works for whitelisted paths.
  - **Message layout:** `<article class="author"><header><img><h1>name</h1><h2>sub</h2></header><section>…</section></article>`.
    The `<img>` is a 64 px circle.
  - **Footer:** Glass draws the time footer itself.
  - **Testing over adb:** DPAD keys (`input keyevent 22`) often don't move the timeline off an HTML card.
    Post a newer notification so the card you want is first, right of the clock.
- **The footer** shows the relative time from `displayTime`.
- **Sync icon:** Glass shows a ↻ "syncing" icon unless `cloudSyncProtocol = 2` (never sync).
  Set the `sync_protocol` column and field 13 to 2. Also set `companionSyncProtocol` (field 34) to 2.
  This also stops Glass from trying to upload the card.
- **Order:** newest `display_time` comes first, right of the clock. Apps send their own `when`,
  sometimes nonsense, so check it's plausible before using it.
- **Menu items** (field 11, a `MenuItem` message):
  - `action` uses these codes: 2 REPLY (goes through the dead MyGlass companion, don't use),
    3 DELETE, 5 READ_ALOUD, 7 BROADCAST, 8 VOICE_CALL, 9 NAVIGATE, 13 OPEN_URI.
  - `value` (MenuValue, field 3) with `displayName` (field 1) overrides the label,
    e.g. "Dismiss" instead of "Delete".
  - **A custom label hides the item from the "ok glass" voice menu** unless the item also has
    `voiceCommand` (MenuItem field 11).
    - `voiceCommand` must be the name of a `com.google.android.glass.app.ContextualMenus.Command`
      value from glass-core.jar, for example `DISMISS_THIS` ("Dismiss this"), `DELETE_THIS`,
      `REPLY`, `REPLY_TO_ALL` or `READ_ALOUD`.
    - BROADCAST (7) and CUSTOM (0) items appear in the voice menu only when they have a valid `voiceCommand`.
    - The filtering is in `timeline.VoiceOptionsHelper.populateVoiceMenuItems`.
    - The spoken phrases are strings in `/system/framework/glass-framework-res.apk`
      (`aapt2 dump strings`).
  - BROADCAST sends a broadcast with action = `broadcastAction` (field 5) and extras
    `itemId` (card id) and `menu_item_id`. GlassLink's Reply uses this, handled by `TimelineMenuReceiver`.
    The broadcast is unprotected, so a receiver must treat it as untrusted.
- **User deletes:** Glass doesn't remove the row. It writes a tombstone over it: `is_deleted=1`,
  same `_id` and `source`, but `source_item_id` and other fields are wiped. To notice deletes, watch
  the provider with a ContentObserver and query `withExpired` for `is_deleted=1`. Keep your own map
  from card id to your key, because the tombstone has lost it.
- **How MyGlass built SMS cards** is the best template: `companion/CompanionTimelineUtils.getCompanionSmsTimelineItem`.
  It used a creator Entity, text, speakableText and speakableType, menu items, and
  `NotificationConfig{level=10, deliveryTime=1}`.

## System apps (priv-app) on XE24

- **adb on XE24 in debug mode runs as root** (`uid=0`, `ro.secure=0`). No root exploit is needed.
- **Installing:** copy the APK to `/system/priv-app/` (remount `/system` read-write), set it to `644 root:root`,
  then reboot. It gets the `SYSTEM` and `PRIVILEGED` flags and `signature|system` permissions.
- **If a regular install already exists, uninstall it first.** Back up `shared_prefs` before uninstalling,
  then restore them with the new uid's ownership (`dumpsys package <pkg>` shows `userId=`).
- **Updating later:** `adb install -r` makes it an "updated system app"
  (flags `UPDATED_SYSTEM_APP PRIVILEGED`) and it **keeps** the permissions.
- `scripts/glasslink.sh install-glass-system` and `uninstall-glass-system` automate all of this.

## Re-deriving or digging further

1. **Pull the APKs:** `adb pull /system/priv-app/GlassSync.apk` (and `GlassHome.apk`). These
   include a full `classes.dex`; they aren't odexed.
2. **Decompile** with jadx into the session scratchpad, not the repo. Decompiled Google code stays out of git.
   - Proto field numbers are clearest in each message's `writeTo()` (`output.writeX(N, field)`).
   - Enum constants are `public static final int` in the message class.
3. **Read a class's actual serialVersionUID on the device:**
   - compile a tiny `Main` that loads the class with `new PathClassLoader("/system/priv-app/GlassHome.apk", ...)`
     and prints `ObjectStreamClass.lookup(c).getSerialVersionUID()`
   - turn it into a dex with `d8 --min-api 19`, zip it as `classes.dex`, and push it to `/data/local/tmp`
   - run it with `CLASSPATH=/data/local/tmp/x.jar app_process /data/local/tmp Main`, then delete it
4. **Read-only checks:**
   - `content query --uri content://com.google.glass.sync.timeline/timeline/withExpired ...` (as root)
   - or pull `timeline.db` together with its `-wal` and `-shm` files and open them with sqlite
5. Raw blobs can be decoded without a schema using a generic protobuf wire-format dumper.

## The original MyGlass protocol (still on Glass)

`com.google.glass.companion` is a system app on Glass: the Glass end of MyGlass's Bluetooth link.
It sits idle today. Its message is `companion.Proto.Envelope` (in GlassSync's decompiled sources).
In the field names, C2G means phone→Glass and G2C means Glass→phone.

**Header fields:** 1 version, 2 serialNumber, 3 timeMillis, 4 uptimeMillis.

**Messages, with what each did in MyGlass:**

| # | Field | What it did |
|---|---|---|
| 5 | timelineItem (repeated) | notifications, SMS |
| 6 | locationRequestG2C | Glass asks for the phone's location |
| 7 | locationMessageC2G | phone sends its location |
| 8 | messageC2G | |
| 9 | timezoneC2G | |
| 10 | navigationRequestC2G | start Glass's built-in navigation |
| 11 | urlG2C | open a link on the phone |
| 12 | setupWifiC2G | |
| 13 | companionInfo | |
| 14 | error | |
| 15 | screenshot | screencast |
| 16 | command | |
| 17 | (repeated) | |
| 18 | apiRequestC2G | |
| 19 | apiResponseG2C | |
| 20 | motionC2G | remote touch: MotionEvent fields with a pointer list |
| 21 | glassInfoRequestC2G | |
| 22 | glassInfoResponseG2C | |
| 23 | inputBoxRequestG2C | Glass asks the phone for typed text |
| 24 | inputBoxResponseC2G | |
| 25 | glassSetupRequestC2G | |
| 26 | glassSetupResponseG2C | |
| 27 | setupWifiRequestG2C | |
| 28 | photoG2C | send a photo to the phone |
| 29 | updateCredentialRequestG2C | |
| 30 | androidDeviceInfo | |
| 31 | companionDeviceInfoC2G | |
| 32 | companionFeatureInfoC2G | |
| 33 | glassFeatureInfoG2C | |
| 34 | wallpaperC2G | |
| 35 | openEndedInputRequestG2C | Glass asks for free text |
| 36 | openEndedInputResponseC2G | |
| 37 | multimediaMessage | |
| 38 | setWallpaperRequestC2G | |
| 39 | setWallpaperResponseG2C | |
| 40 | getWallpaperInfoC2G | |
| 41 | wallpaperInfoG2C | |

**Location on Glass:**
- **The phone used to be the only source.** Glass XE has no GPS provider, and its GMS Wi-Fi
  "network" provider is normally off.
- **GlassHome's `location/LocationService`** serves Glass apps through `GlassLocationManager`
  (provider names `remote_gps` and `remote_network`). It has two sources:
  - **Local:** Android's `LocationManager`.
    - "network" is always used when that provider exists.
    - "gps" is used only when the Labs flag `GPS_ENABLE_LOCAL` is on.
  - **Remote:** MyGlass, through `CompanionLocationProxy`. It's reached over a binder to
    `com.google.glass.companion` (`RemoteCompanionProxy`), which you can't inject into from outside.
- **Labs flags** are system properties named `persist.lab.<feature name, lowercase>`, for example
  `setprop persist.lab.gps_enable_local true`. Set them as root over adb; they survive reboots.
- **What GlassLink does** (`PhoneLocation.java`): it registers mock "gps" and "network" providers with
  `LocationManager.addTestProvider`, which replaces any real provider with that name, and sets each fix
  from the phone. This needs:
  - `ACCESS_MOCK_LOCATION`, which Glass grants
  - the secure setting `mock_location` set to 1
  - a fix with both `time` and `elapsedRealtimeNanos` set
- **Permissions of an updated system app:** after an `adb install -r` update, a system app only gets
  the system permissions its original `/system` copy requested. That's why `WRITE_SECURE_SETTINGS`
  isn't granted, and why the script sets `mock_location` itself.

**Not yet investigated:**
- how `com.google.glass.companion` connects (RFCOMM UUID, framing)
- what handshake it expects
- whether it still needs Google account checks on XE24

## LiveCard (GDK, XE24)

**API** (from `dexdump` of glass-core.jar; stub in `gdk-stubs`):
- `new LiveCard(Context, String tag)`
- `attach(Service)`
- `setViews(RemoteViews)`
- `setAction(PendingIntent)`. This must be set before `publish`.
- `setVoiceActionEnabled(boolean)`
- `setDirectRenderingEnabled(boolean)`, plus `getSurfaceHolder()` and `setRenderer(GlRenderer)` for drawing directly
- `publish(PublishMode.SILENT | REVEAL)`. REVEAL jumps the timeline to the card.
- `unpublish()`, `isPublished()`
- `navigate()`, which brings the card to the front
- `setPinned(String)`

**The card's views** are drawn in the home screen's process, so they're limited to
RemoteViews-safe widgets. The usual pattern, which `nav-glass` uses: tapping the card opens a
translucent activity that calls `openOptionsMenu()` in `onAttachedToWindow()` and finishes in
`onOptionsMenuClosed()`.

## In-app voice commands and head gestures (GDK, XE24)

- **Enabling the voice menu:** `getWindow().requestFeature(WindowUtils.FEATURE_VOICE_COMMANDS)`
  (the value is 11). Build the menu in `onCreatePanelMenu(11, menu)`; the item titles are the
  phrases to say.
- **Custom phrases:** these need `com.google.android.glass.permission.DEVELOPMENT`. Without it,
  only `ContextualMenus.Command` phrases work. Several suit recipes: SHOW_INGREDIENTS,
  SHOW_INSTRUCTIONS, START_A_TIMER, READ_ALOUD, GO_BACK and GO_FORWARD.
- **Don't refresh the voice menu.** Calling `invalidatePanelMenu(11)`, or returning true from
  `onPreparePanel` for feature 11, makes XE24 open the command list and listen without "ok glass".
  Accidental speech then fires commands.
  - Build the voice menu once, keep it fixed, and ignore commands that don't apply.
  - Only the tap menu (`FEATURE_OPTIONS_PANEL`) should be dynamic.
  - The log shows "Open top-level voice menu" for the normal "ok glass" prompt too, so a
    screenshot is the way to tell the two apart.
- **Head gestures:** `com.google.android.glass.head.HeadGestureManager.from(ctx).register(HeadGesture.X, listener)`,
  where X is one of ACCEL_TAP, GLOBAL_LOOK_UP, NUDGE or RELATIVE_LOOK_UP.
- **Card layouts in `CardBuilder`:** ALERT, AUTHOR, CAPTION, COLUMNS, COLUMNS_FIXED, EMBED_BEHIND,
  EMBED_INSIDE, MENU, TEXT, TEXT_FIXED and TITLE (TITLE takes a background image via `addImage`).
- **Fonts:** the KitKat Roboto font has no emoji or ⏱ glyph; use words instead.
- **Footnotes and the voice prompt:** with the voice menu enabled, the "ok glass" prompt sits in
  the middle of the bottom edge. Keep footnotes (bottom left) and timestamps (bottom right)
  short, or they run under it.
- **TITLE layout** fits about 22 characters on one line. For longer titles over an image, use
  CAPTION, which wraps.
- **TEXT layout** shrinks text to fit, but cuts it off with "…" at about 6 lines of 35
  characters (~170–200 characters). Split longer text across cards.
- **`CardScrollView.setSelection`** is ignored before the view has laid out its cards, for
  example in `onCreate`, or `onNewIntent` right after a data change. Call it again in
  `scroller.post(…)`.

## Other built-in components worth knowing

- **`com.google.glass.sync`:** TimelineProvider, StylesheetProvider (CSS for Mirror HTML cards),
  and `companion/*`, the old MyGlass Bluetooth protocol (`Proto`, `RemoteCompanionProxy`).
- **`com.google.glass.home`:** the timeline UI, NotificationService, and menu handling
  (`TimelineMenuSelectionHandler`, `TimelineMenuUtils.shouldShowMenuItem`).
- **Notable permissions:**
  - `com.google.android.glass.permission.TIMELINE_HOST` is signature-only, so it can't be obtained.
  - `com.google.android.glass.permission.DEVELOPMENT` lets an app register its own voice triggers.
