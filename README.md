# Habit Tracker

Offline Android habit & value tracker for the Pixel 9 Pro Fold. Replaces a printed monthly
spreadsheet: a scored habit grid (daily, weekly, X-per-week, every-N-days, monthly tasks)
plus unscored daily values (sleep, steps, weigh-in, …).

- No network access (the `INTERNET` permission is stripped and checked at build time).
- All data stays on the device; backups are `.zip` files written to a folder you choose.

See [DESIGN.md](DESIGN.md) for the full specification.

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew :core:domain:test      # scoring engine tests
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
```

## Modules

| Module | Contents |
|---|---|
| `core/domain` | Pure Kotlin: weeks, recurrence, scoring, month grid, sleep math. No Android. |
| `app` | Compose UI (in progress). |
