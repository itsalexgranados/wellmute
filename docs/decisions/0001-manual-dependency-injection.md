# 0001 — Manual dependency injection (no Hilt)

- **Status:** accepted
- **Date:** 2026-11-03

## Context

The specification (§2) leaves the choice between Hilt and manual injection open. The app is a single module with a small object graph (repositories, the Room database, DataStore, system managers), and it has to build reproducibly for F-Droid.

## Decision

We use **manual injection** with a single `AppContainer` created in `WellmuteApp.onCreate()`. `ViewModel`s get their dependencies through `viewModelFactory { initializer { … } }`, reading the container from the `Application`.

Components that Android instantiates itself (`NotificationListenerService`, `AccessibilityService`, `Worker`, `TileService`, `AppWidgetProvider`) get the container with `(applicationContext as WellmuteApp).container`.

## Consequences

- Fewer Gradle plugins and no annotation processing: faster builds that are easier to reproduce.
- No generated code to review in audits.
- If the graph grows a lot (phase 7 or later), we can migrate to Hilt without touching the UI: it's enough to replace `AppContainer`.
