# 0006 — Bedtime mode: a system mode, scheduled by Wellmute

- **Status:** accepted
- **Date:** 2026-11-05

## Context

Bedtime mode applies Do Not Disturb, grayscale, a dimmed wallpaper, dark theme and no always-on display, on a schedule or by hand (SPEC §3.4). Since Android 15 apps can do this without ADB through a system mode: an `AutomaticZenRule` with `ZenDeviceEffects`. Such a mode also appears in Settings → Modes and Quick Settings, and Android keeps its effects applied even when Wellmute isn't running.

Findings on a moto g54 (Android 15):

- **`TYPE_BEDTIME` is reserved.** Creating a rule of that type fails with "Only the 'Wellbeing' package can use AutomaticZenRules with TYPE_BEDTIME": it belongs to the system wellbeing app (Google's Digital Wellbeing where present). The device effects themselves are not restricted.
- **Apps can't use Android's own mode scheduler.** A rule pointing at the system's schedule condition (`condition://android/schedule?…` owned by `ScheduleConditionProvider`) is accepted, but Android drops the owner, so nothing ever switches it on.

## Decision

- Bedtime is a system mode of type `TYPE_OTHER`, with the effects the user picked. Do Not Disturb uses `INTERRUPTION_FILTER_PRIORITY` without a policy of its own, so it follows the user's Do Not Disturb settings; with Do Not Disturb off, the mode uses `INTERRUPTION_FILTER_ALL` and only applies the screen effects.
- **Wellmute runs the schedule.** At each start and end it sets the mode's state (`setAutomaticZenRuleState`, source "schedule") and plans the next switch with an exact alarm. `BedtimeAlarmReceiver` handles the alarm and re-plans after a restart or a clock or time zone change.
- The alarm uses **`USE_EXACT_ALARM`**: granted at install, no extra setup step. It is meant for apps whose core job needs on-time alarms; Google Play restricts it, but Wellmute ships on F-Droid and Accrescent. `SCHEDULE_EXACT_ALARM` would work too but needs the user to grant "Alarms & reminders".
- Turning Bedtime off by hand during a window keeps it off until the next start, because the schedule only acts at the window's edges, matching Digital Wellbeing.

## Consequences

- One more permission in docs/PRIVACY.md.
- If Android stops Wellmute, the alarm still fires and restarts it; the mode's effects stay applied in between.
- If GrapheneOS ever lets apps use the system scheduler, the alarm can be dropped without changing the screen or the settings.
