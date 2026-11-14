# Changelog

## Unreleased

- **Setup:** the notification access page now says what really happens to held notifications: Android's own snooze keeps them, and Wellmute keeps nothing of them.
- **Texts:** details are no longer separated by "·". The app uses commas, colons ("Calculator: 10 min today") and brackets ("48 min (Instagram)") instead.

## 0.4.0: first public version

Tested on a Pixel 9 with GrapheneOS 2026110800 (Android 17) and a moto g54 5G with Android 15.

- **Setup:** three short pages on what Wellmute does and how it keeps things private, then one step per permission (what it's for, what it's never used for, Allow or Skip for now). If Android blocks a permission for an app installed from a file ("Restricted setting"), the setup explains how to unblock it.
- **Dashboard:** screen time, unlocks and notifications by day, week, month or year, with arrows and a calendar. Bars are split by kind of app (Social, Entertainment, Games, Productivity, Other) and compared with the period before ("⬇ 19% from last week"). Also: first pickup, longest session, use after Bedtime and use during Focus.
- **App usage:** each app's screen time, notifications and opens by hour, day, week, month or year, with its timer and its category, which you can change.
- **History from day one:** your last 7 days or so of screen time and unlocks appear as soon as you allow usage access, and history is kept on the phone for as long as you use Wellmute. Wellmute doesn't count its own use.
- **App timers:** a daily limit per app; when it runs out the app is paused until midnight. An optional bubble warns you 5 minutes before.
- **Usage reminders:** an optional bubble while you use an app ("Calculator: 10 min today"), every 5 to 60 minutes.
- **Focus mode:** pause distracting apps now or on a schedule, with 5-minute breaks and a bubble a minute before a break ends. Focus insights show time in Focus, breaks, blocked attempts and the apps you used.
- **Bedtime mode:** grayscale, dim wallpaper, dark theme, no always-on display and Do Not Disturb on a schedule, as an Android system mode. It can turn on only while charging, end with your first alarm, and hand over to Do Not Disturb if it ends early. Allowed notifications lets only chosen apps through and brings the rest back when Bedtime ends. Blocked apps can't be opened during Bedtime, with "Use it for 5 minutes".
- **Wellmute's notifications:** "Bedtime mode is on until 07:00", "Focus mode is on until 17:00" and a weekly summary, each optional.
- Home screen widget and Quick Settings tiles for Focus and Bedtime.
- No internet permission and no Google services needed.
