# XE24 timeline reference

Field numbers are taken from `writeTo()` in `com.google.googlex.glass.common.proto.TimelineNano` (GlassSync.apk).

## TimelineItem

| # | Type | Name | Notes |
|---|------|------|-------|
| 1 | string | id | the `_id` column |
| 2 | uint64 | creationTime | ms |
| 3 | uint64 | modifiedTime | ms; newer wins when inserting an id that exists |
| 4 | string | source | GlassLink uses `"glasslink"`; device items use `device:<id>` |
| 5 | Entity | creator | |
| 6 | Entity (repeated) | shareTarget | |
| 7 | string | inReplyTo | |
| 8 | string | text | **shown on text cards** |
| 9 | Attachment (repeated) | attachment | contentType=2, contentUrl=5 (file path), thumbnail=4 |
| 10 | Location | location | |
| 11 | MenuItem (repeated) | menuItem | |
| 12 | int32 | cloudSyncStatus | 0 = not synced, 1 = synced |
| 13 | int32 | cloudSyncProtocol | **2 = never sync** (no ↻ icon) |
| 14 | int32 | sourceType | 1 = made on Glass, 2 = companion (MyGlass) |
| 15 | bool | isDeleted | |
| 16 | NotificationConfig | notification | |
| 17 | uint64 | displayTime | sort order and footer time |
| 18 | (repeated message) | pendingAction | |
| 19 | string | speakableText | used by Read aloud |
| 20 | int32 | smsType | |
| 21 | bool | isPinned | |
| 23 | string | title | not shown on text cards |
| 24 | string | html | Mirror-style HTML card |
| 25 | string | bundleId | the column stores it SQL-escaped (`'x'`) |
| 28 | string | sendToPhoneUrl | |
| 29 | bool | isBundleCover | |
| 30 | string | sourceItemId | also a column; wiped by delete tombstones |
| 31 | string | canonicalUrl | |
| 33 | int32 | companionSyncStatus | |
| 34 | int32 | companionSyncProtocol | 2 = never sync |
| 35 | uint64 | pinTime | |
| 36 | int32 | pinScore | |
| 37 | string | sourceAccountId | |
| 38 | string | speakableType | e.g. the app name |
| 39 | uint64 | expirationTime | |
| 40 | int32 | viewType | MyGlass SMS used 1 (14 for MMS) |
| 41 | int32 | (unnamed) | |
| 42 | ProgressStatus | progressStatus | |
| 43, 45 | LinkSpec (repeated) | linkSpec, htmlLinkSpec | |
| 44 | bool | requireLinkify | |
| 46 | int32 | attributionType | MyGlass SMS used 10; the column defaults to -1 |
| 49 | message | failedDeliveryInfo | |
| 50 | bool | isDirectoryCover | |
| 51 | message | loggingAnnotations | |
| 52 | string | glanceableContent | |

## Entity

1 source, 2 id, 3 displayName, 5 shouldSync, 6 type, 7 phoneNumber, 8 email,
9 imageUrl (repeated), 10 isCommunicationTarget, 14 priority, 17 speakableName.

## MenuItem

1 id, 2 action, 3 value (MenuValue, repeated), 5 broadcastAction, 7 removeWhenSelected,
8 travelMode, 9 recipient, 10 payload, 11 voiceCommand.

Action codes:

| Code | Action | Code | Action |
|------|--------|------|--------|
| 0 | CUSTOM | 13 | OPEN_URI |
| 2 | REPLY | 14 | HANGOUT |
| 3 | DELETE | 15 | TOGGLE_PINNED |
| 4 | SHARE | 16 | READ_MORE |
| 5 | READ_ALOUD | 17 | PLAY_VIDEO |
| 6 | REPLY_ALL | 18 | SEND_MESSAGE |
| 7 | BROADCAST | 19 | VOLUME |
| 8 | VOICE_CALL | 20 | SHOW_RECIPIENTS |
| 9 | NAVIGATE | 21 | SEND |
| 10 | SEARCH | 22 | GET_MEDIA_INPUT |
| 11 | MUTE_MIC | 23 | SHARE_LOCATION |
| 12 | SEND_TO_PHONE | 24 | RETRY |
| | | 25 | PLAY_AUDIO |

## MenuValue

1 displayName, 2 iconUrl, 3 state. State 0 = DEFAULT, 1 = PENDING, 2 = CONFIRMED; DELETE uses
PENDING and CONFIRMED for the labels on its confirmation screen.

## NotificationConfig

1 level (MyGlass used 10), 2 deliveryTime (uint64).

## timeline table columns

`_id, creation_time, modified_time, display_time, delivery_time, expiration_time, pin_time (-1),
pin_score (INT_MAX), is_deleted, has_media_attachment, sync_status, sync_protocol, bundle_id,
bundle_cover_status, source, protobuf_blob, attribution_type (-1), processed_html,
processed_html_read_more, source_item_id, is_bundle_cover, is_directory_cover`

Other tables: `pending_actions`, `entity`, `post_delete_intent`.
