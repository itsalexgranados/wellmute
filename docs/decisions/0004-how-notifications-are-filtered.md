# 0004 — How notifications are filtered per app

- **Status:** accepted (moto g54 5G with Android 15, and Pixel 9 with GrapheneOS build 2026110800)
- **Date:** 2026-11-04, updated 2026-11-11

## Context

Bedtime and paused apps must hide some apps' notifications without the phone making any sound for them (SPEC §4, risk 1). A notification listener only learns about a notification after it is posted, so a sound might play before the listener cancels it. SPEC §4 asks to compare two strategies:

- **(a) Blocklist:** nothing is silenced up front; the listener cancels blocked apps' notifications as they arrive.
- **(b) Allowlist:** a system mode (`AutomaticZenRule` with a `ZenPolicy` that silences everything except priority channels) silences everything; the listener reposts allowed apps' notifications on a Wellmute channel that may break through the mode (`setBypassDnd(true)`).

The debug-only Notification lab implements both and logs how long each notification lived before the listener finished acting on it.

## Test (moto g54 5G, Android 15)

Test notifications were posted by Wellmute itself on a high-importance channel with the default sound. Ringer in sound mode, notification volume 2/7, Do Not Disturb off (except the lab's own mode in allowlist tests).

| Strategy | Notifications | Time to act | Heard |
|---|---|---|---|
| Off (baseline) | 2 passed through | 233–236 ms | normal sound |
| Blocklist | 8 cancelled (5 with sound on) | 231–252 ms | nothing |
| Allowlist, app allowed | 1 reposted | 251 ms | sound |
| Allowlist, app not allowed | 1 left silent | 237 ms | nothing |

Notes:
- The ~235 ms is consistent across all tests: it is the time for Android to deliver the notification to the listener, not our processing.
- Android accepted the relay channel's "break through modes" setting once Wellmute had Do Not Disturb access. The channel must be created after that access is granted, because the setting can't be changed later.
- The lab's `AutomaticZenRule` appears in Settings → Modes like any system mode, as SPEC §3.5 expects.

### Pixel 9, GrapheneOS (build 2026110800)

Tested and confirmed: notifications Wellmute hides make no sound, as on the Motorola, also with the screen locked.

## Decision

Use **(a) blocklist**: the listener takes notifications away as they arrive. It left no audible sound in any test, and it keeps allowed apps' notifications untouched: their own icon, sound, actions, inline reply and conversation features. Reposting (b) loses most of that, so it was not built into the app; it stays only in the debug-only Notification lab.

How it works now (`WellmuteNotificationListener`, `BedtimeMode.silences`, `NotificationSnoozer`):

- **Paused apps** (timer used up, Focus mode): their notifications are cancelled as they arrive, and the ones already showing are cleared when the app gets paused.
- **Bedtime, "Allowed notifications":** with "Only let chosen apps through" on, every other app's notifications are taken away as they arrive, even ones Android's mode would let through (for example an app allowed to interrupt modes). Never touched: Wellmute's own notifications, ongoing ones (music, navigation), group summaries, calls and alarms (by category), and protected apps (Phone, Settings and the rest in `ProtectedApps`).
- **Delayed, not deleted:** by default ("Deliver them when Bedtime ends") a silenced notification is snoozed with Android's own snooze until Bedtime's scheduled end, or an hour at a time with no end in sight. Android brings it back by itself, even if Wellmute isn't running. If Bedtime ends early, Wellmute brings back only the ones it snoozed, never ones the user snoozed. Turning that option off deletes them instead.
- **Counting:** only notifications that reach you are counted. Ones Wellmute hides aren't; ones Do Not Disturb keeps out of the shade aren't either; delayed ones count when they come back.
- Wellmute's own Do Not Disturb schedules were removed (docs/decisions/0007): Android's Modes handle that.

## Not measured with numbers yet

- Real apps instead of Wellmute's own test notification: messaging apps with custom sounds, and vibration-only notifications.
- Louder notification volume.
