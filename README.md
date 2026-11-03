# Halibut Habit Tracker

Offline Android habit & value tracker for the Pixel 9 Pro Fold. It replaces a printed monthly
spreadsheet: a scored habit grid (daily, weekly, X-per-week, every-N-days and monthly tasks)
plus unscored daily values (sleep, steps, weigh-in, …).

- **No network access.** The `INTERNET` permission is stripped and the build fails if it reappears.
- **All data stays on the phone.** Backups are `.zip` files (one CSV per table) written to a folder you choose,
  e.g. one synced by Syncthing. Restore can replace everything or merge.
- **Adaptive.** Month grid on the inner screen, day checklist on the cover screen; both on any size.
- **Monthly printout.** "PDF" on the Month screen saves the whole month (habits, values, weekly, monthly, summary) on one Letter landscape page.

See [DESIGN.md](DESIGN.md) for the full specification.

## Install

Download `Halibut-<version>.apk` (e.g. `Halibut-1.1.0.apk`) from the latest release on the phone and open it. Android asks once to allow installs from
your browser or Files app. Later versions install over it **only if signed with the same key**,
so keep the release keystore safe (it is not in this repo).

## Build

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew :core:domain:test :app:testDebugUnitTest   # engine, backup round-trip and screenshot tests
./gradlew :app:assembleDebug                          # debug APK
./gradlew :app:assembleRelease                        # needs keystore.properties (see below)
```

`keystore.properties` (git-ignored) in the project root:

```
storeFile=/path/to/halibut-release.jks
storePassword=…
keyAlias=halibut
keyPassword=…
```

The Robolectric screenshot tests write PNGs to `app/build/screenshots/`, including previews of the month PDF page (`pdf-*.png`).

## Layout

| Path | Contents |
|---|---|
| `core/domain` | Pure Kotlin: weeks, recurrence, scoring, month grid, sleep math. No Android. |
| `app/…/data` | Room entities, DAO, repository, mapping to the domain model. |
| `app/…/backup` | Zip/CSV backup, replace & merge restore, auto-backup worker. |
| `app/…/notify` | Exact-alarm reminders, end-of-day summary, backup notifications. |
| `app/…/ui` | Compose screens: month grid, day checklist, editors, tasks, trackers, settings. |
| `app/…/export` | One-page month PDF (`MonthPdf`, drawn by `MonthPage`). |
