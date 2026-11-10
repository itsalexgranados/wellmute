# 0003 — How screen time is counted

- **Status:** accepted (emulator, moto g54 5G with Android 15, and Pixel 9 with GrapheneOS build 2026110800)
- **Date:** 2026-11-04, updated 2026-11-11

## Context

Screen time is calculated from raw usage events (`UsageStatsManager.queryEvents()`), not from Android's pre-aggregated stats. The question is when an app's time stops counting:

- **Resumed:** only while the app is in front and taking input. Its time stops when anything covers it, even a dialog from another app (for example a permission prompt).
- **Visible:** until the app is stopped, so it keeps counting while visible behind a dialog, in split screen or in picture-in-picture.

`UsageCalculator` supports both modes, and the debug-only Usage lab shows both next to Android's own `totalTimeVisible`.

## Findings

### Android 17 emulator

| App | Resumed | Visible | Android |
|---|---|---|---|
| Settings | 20s | 22s | 22s |
| Contacts (with a permission dialog on top) | 0s | 14s | 15s |
| Permission controller | 13s | 14s | 14s |

With resumed counting, Contacts got almost no time even though it was on screen the whole time. Visible counting matched Android within about 1 second per app.

### moto g54 5G, Android 15

| App | Resumed | Visible | Android |
|---|---|---|---|
| Settings | 4m 36s | 5m 11s | 5m 11s |
| Accessibility Suite | 48s | 48s | 48s |
| System UI | 28s | 29s | 29s |
| Camera | 2s | 2s | 20m 15s |

- **Activities can't be tracked by class name.** Settings opens many screens that share one class (`SubSettings`), and a new one resumes before the old one stops. Tracking by class name ended the session early (3m 22s instead of 5m 11s) and inflated opens (14 instead of 5). The public API doesn't expose the instance ID, so the calculator now counts resumes and pauses per package. Android almost always pairs them; see the 2026-11-11 verification below for when it doesn't.
- **Android's pre-aggregated stats are not a reliable reference.** On this phone the daily bucket ran 11:00–21:56, not midnight to midnight, and `queryAndAggregateUsageStats()` mixed in a longer bucket: it reported 20 minutes of Camera, while the raw events show 3 seconds. This confirms SPEC §4: calculate from events, never from aggregated stats.

### Other fixes found while testing

- Moving between screens inside one app briefly leaves none of its activities resumed. A resume within 2 seconds of the app's last session continues the visit instead of counting a new open.
- Only the default launcher is excluded from screen time. Settings also registers a fallback home screen and must still count.
- The app currently open always reads higher than Android's number, because Android only adds a session once it ends. Expected, not an error.

### Pixel 9, GrapheneOS (build 2026110800)

Tested in everyday use and confirmed. The unlock count matched a manual count. GrapheneOS has no Digital Wellbeing, so there is no per-app number from the system to compare against.

### Verification on the moto g54, 2026-11-11

Timed scenarios on the phone, then Android's raw events (`dumpsys usagestats`) run through `UsageCalculator`, compared with a stopwatch, with Android's own `totalTimeVisible` and with what Wellmute shows.

**Bug found and fixed.** Android sometimes logs an activity going from resumed straight to stopped, with no pause in between. It happened for Notion, Maps, VLC and Obtainium opened from their home screen icons and left quickly. The calculator waited for a pause that never came, so the app kept counting until the screen turned off: a 7-second visit showed as 7 minutes and growing. Now a stop with no pause pending counts as that pause (tracked per activity class, so Settings' shared `SubSettings` class still works). Unit tests cover both sequences seen on the phone.

Results after the fix (all within 1–2 s of the stopwatch):

| Scenario | Expected | Wellmute |
|---|---|---|
| App open, left with Home | 60 s | 61 s |
| App sent to background, another app used 40 s | 20 s / 40 s | 21 s / 41 s; the background app adds nothing |
| App left, then 40 s idle on the home screen | 20 s | 21 s; it doesn't keep counting |
| App left with Back | 30 s | 31 s |
| Notification shade pulled down over the app for 15 s | 50 s | 52 s (still visible, so it counts) |
| Another app opened on top, without going home | 20 s / 20 s | 21 s / 21 s |
| App left open, untouched, 3 min | 180 s | 181 s |
| Screen turned off with the app open, 40 s; then 8 s on the lock screen | 20 s | 21 s; nothing while off or locked |
| Wellmute itself and the launcher | not counted | not counted |
| 8 apps opened and closed fast (3 s, 1 s, and immediately), by Home key, home gesture and back gesture, from launcher icons and by intent, ~100 visits | ~3 s per visit | every open counted once, no app kept growing in a minute idle afterwards |

Against Android's own daily `totalTimeVisible` for the same window (its daily bucket started at 11:33 the day before, not at midnight), every app matched within 5 seconds: Camera 14:18 vs 14:23, Calculator 13:26 vs 13:24, Clock 6:54 vs 6:52, Compass 2:04 vs 2:03, Notion 1:18 vs 1:20. Wellmute's Dashboard showed the same numbers as the calculation.

Two behaviours that match Android and are kept:
- While the Recents screen shows the app, Android doesn't pause it, so that time counts.
- When switching apps, Android stops the old app about a second after the new one starts; that second counts for both. Android's own totals count it the same way.

## Decision

Count **visible** time, with activities counted per package. It matches what the system reports and what the user actually sees.

How it works now (`UsageCalculator`, `UsageRepository`):

- Every screen that shows or stores usage counts visible time: the Dashboard, each app's usage, the hourly history, app timers, usage reminders and Focus insights. Resumed counting is only in the debug-only Usage lab, for comparison.
- An app counts while any of its activities is resumed, so in split screen both apps count. After its last pause it keeps counting until it is stopped, or until it resumes again (a dialog on top went away).
- Turning the screen off, or shutting down, ends every session: some apps never report a pause then. So a video playing with the screen off doesn't count.
- A resume within 2 seconds of the app's last session continues the visit instead of counting a new open.
- A stop with no pause before it counts as the missing pause, so the app stops counting when it leaves the screen.
- Events are read from 24 hours before the day starts, so a session running at midnight is split between the two days instead of being lost.
- Left out of every stat: the default launcher (only the default one; Settings' fallback home screen still counts) and Wellmute itself, so checking your screen time doesn't add to it.
- Unlocks are counted from the keyguard being dismissed (`KEYGUARD_HIDDEN`).
- The app currently open reads higher than Android's own number, because Android only adds a session once it ends. Expected, not an error.

## Not measured with numbers yet

- A whole normal day of use against the Digital Wellbeing app on the Motorola (SPEC §9). Short sessions already match Android's own totals (above).
- The same timed scenarios on the Pixel with GrapheneOS, after the fix.
- Picture-in-picture.
