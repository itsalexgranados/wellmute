# Privacy

Wellmute has **no network access**. The app does not declare the `INTERNET` permission, so Android itself prevents it from sending or receiving anything over the network. CI fails the build if that permission ever appears in the merged manifest.

- No analytics, no telemetry, no remote crash reporting.
- No accounts, no ads, no third-party SDKs.
- All data stays in the app's private storage on your device.
- The database is excluded from cloud backups and device-to-device transfer.

## Permissions and what they are used for

| Permission | Used for | Not used for |
|---|---|---|
| Usage access (`PACKAGE_USAGE_STATS`) | Screen time per app, app opens and unlocks, for the dashboard and app timers. | Nothing else. Data never leaves the device. |
| See all installed apps (`QUERY_ALL_PACKAGES`) | Showing the name and icon of every app in your usage stats, including ones without a launcher icon. | Nothing else. The list of your apps never leaves the device. |
| Notification access (notification listener) | Counting notifications per app, and hiding notifications from paused apps and from the apps you silence during Bedtime. | Notification content is not read for any other purpose and is never sent anywhere. Hidden notifications are delayed with Android's own snooze, so Wellmute stores nothing of them. |
| Do Not Disturb access (`ACCESS_NOTIFICATION_POLICY`) | Creating and switching Bedtime as a real system mode. | Changing any other system setting. |
| Accessibility service | Detecting which app is in the foreground, to show the pause screen over paused apps, and drawing the small bubble over the app in use (usage reminders, timer warnings, the end of a Focus break). | Reading screen content, typing, or performing actions on your behalf. Nothing is stored. |
| `READ_CONTACTS` (optional, asked only when you open it) | Showing the names of your starred contacts under "Who can reach you" in Bedtime. On GrapheneOS you can limit it to chosen contacts with Contact Scopes. | Changing, storing or sharing contacts. Starring and unstarring happen in your Contacts app. |
| Ignore battery optimizations | Keeping timers and modes reliable in the background. | — |
| Post notifications (optional: Wellmute works without it, and setup lets you skip it) | A quiet notification while Bedtime or Focus mode is on, and a weekly summary. Each can be turned off in Settings → Notifications. Timer warnings are a bubble, not a notification. | Ads, promotions or anything sent from outside: every notification is made on the phone. |
| Run at startup (`RECEIVE_BOOT_COMPLETED`) and keep awake (`WAKE_LOCK`) | Saving your daily usage history every few hours, and restoring Bedtime mode's schedule, after a restart. | — |
| Exact alarms (`USE_EXACT_ALARM`) | Turning Bedtime mode on and off at exactly the times you schedule. | Waking the phone at any other time. |

## Restricted settings (Android 13+)

If you install the APK manually (not from F-Droid or Accrescent), Android blocks usage access, notification access and accessibility for the app until you allow restricted settings: **Settings → Apps → Wellmute → ⋮ → Allow restricted settings**. The onboarding explains this step when it detects the case.
