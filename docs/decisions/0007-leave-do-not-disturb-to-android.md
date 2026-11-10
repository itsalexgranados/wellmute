# 0007 — Leave Do Not Disturb schedules to Android's Modes

- **Status:** accepted
- **Date:** 2026-11-06

## Context

Wellmute had its own Do Not Disturb: a built-in mode plus schedules (Work, Study…), each a system mode with its own people, sounds, display options and an app rule (block these apps, or allow only these).

Testing on a Pixel showed the limits of building that on top of Android:

- **"Apps that can interrupt" is one list for the whole phone.** It's each app's notification channels being allowed to break through Do Not Disturb. A mode can only choose to honour that list or not; apps can't read or change it for other apps (only the system and companion-device apps can).
- **Display options only apply to notifications a mode silences.** Notifications from apps on that list aren't silenced, so options like "Hide from the pull-down shade" didn't apply to them, which looked like a bug.
- **Wellmute can't make an app ring during a mode.** Its notification listener can only leave a notification as it is or remove it (removing as it arrives makes no sound: docs/decisions/0004). So "allow only these apps" could hide the others but not make the allowed ones sound, unless Wellmute reposted them on its own channel and lost their icon, actions and replies.

Android's Modes already does schedules, people, sounds and display options well.

## Decision

- **Remove Wellmute's Do Not Disturb** (screens, schedules, Quick Settings tile). Schedules are set up in Android's Modes.
- **Bedtime gains "Only let chosen apps through"** (an allow list, off by default). Bedtime keeps honouring Android's "apps that can interrupt", and Wellmute removes the notifications of every app not chosen as they arrive. That gives fewer apps at night than in Modes, e.g. a banking app can interrupt but the calendar can't. A block list was tried first, but picking dozens of apps to block was impractical.
- **Delayed, not lost:** what Bedtime hides is snoozed with Android's own snooze until Bedtime's scheduled end (an hour at a time when it has none), and brought back early if Bedtime ends sooner. Android does the waiting, so they come back even if Wellmute isn't running. Turning it off deletes them instead.
- **Focus** keeps pausing the apps you don't want to use, and hides their notifications.
- **Import** of Android's Do Not Disturb settings into Bedtime was tried and removed: with Do Not Disturb left to Modes it no longer made sense.
- On update, Wellmute removes the modes, alarms and saved schedules that earlier versions created (`LegacyDndCleanup`). The `dnd_schedules` table stays in the database only for that.

## Consequences

- Fewer screens and less to keep in sync with Android.
- No per-schedule app lists any more; for a schedule that blocks apps, use Focus with its schedule.
- An app can be silenced only by removing its notifications, not by keeping them silently in the shade; delaying them until Bedtime ends covers that.
