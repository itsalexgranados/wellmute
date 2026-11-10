# 0005 — Focus mode is calculated, not switched

- **Status:** accepted
- **Date:** 2026-11-04

## Context

Focus mode pauses a list of distracting apps, now or on a schedule, with breaks of 5, 15 or 30 minutes that end by themselves (SPEC §3.3). SPEC §4 suggests building modes on `AutomaticZenRule`, with schedules run by exact alarms or the system's schedule condition.

Focus mode doesn't silence anything system-wide: it only pauses apps and hides their notifications, which Wellmute already does for app timers (docs/decisions/0004). A system mode would add a second copy of "is Focus on" that could drift from ours, and exact alarms need either a permission the user must grant (`SCHEDULE_EXACT_ALARM`) or one meant for alarm and calendar apps (`USE_EXACT_ALARM`).

## Decision

Focus mode stores only what the user chose: turned on by hand, turned off until the end of the current window, a break end time, the schedule, and the distracting apps (`FocusSettings`, in DataStore). Whether it is on is **calculated for a given moment**: on a break → off; turned on by hand → on; inside a scheduled window and not turned off for it → on.

Pausing reuses the timers' path (`PauseEnforcer`): an app is checked when it comes to the front, and its notifications when they arrive. To catch the moment Focus turns on by itself with a distracting app already open, the enforcer computes the next moment the state can change (`FocusSettings.nextChange`) and re-checks exactly then, while Wellmute's process is running. The accessibility service keeps it running whenever pausing is possible at all.

No `AutomaticZenRule`, no alarms, no new permission.

## Consequences

- Nothing to keep in sync, and schedule edits apply instantly.
- Focus mode does not appear in Settings → Modes. If it should, a rule can be added later as a mirror of the calculated state, without changing how pausing works.
- The Quick Settings tile (phase 8) just turns Focus on or off in `FocusSettings`.
- If Android stops Wellmute's process, Focus starting at its scheduled time is noticed at the next app switch instead of on the dot. Opening any app still gets paused within the 1-second target.
