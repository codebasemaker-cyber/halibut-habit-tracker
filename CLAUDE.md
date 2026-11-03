# CLAUDE.md

Offline Android habit & value tracker ("Halibut Habit Tracker") for the owner's Pixel 9 Pro Fold,
installed by sideloaded APK. Replaces a printed monthly Excel sheet.

## Read first

- **DESIGN.md is the agreed spec; every decision in it is final.** Read it before changing behaviour.
  Items marked *assumption* stay as written unless the owner says otherwise — notably §3.4: for
  every-N tasks a late completion credits the next pending deadline and restarts the cycle from
  the completion date.
- README.md: build commands and code layout.

## Hard constraints (never break)

- **No `INTERNET` permission.** The manifest strips it (`tools:node="remove"`) and the
  `check<Variant>NoInternet` task in `app/build.gradle.kts` fails the build if it reappears.
  Android cloud backup is disabled (`data_extraction_rules.xml`).
- All data stays on the device. Exports are user-initiated files only: backups (zip of one CSV per
  table + `manifest.csv`), which must round-trip losslessly — `BackupTest` checks this — and the
  one-page month PDF (`export/MonthPdf`, DESIGN.md §9.10). Any new column/table must be
  added to the backup and to that test.
- Scores are always computed by the engine, never stored or cached.
- Room schema changes need a version bump and a migration (schemas are exported to `app/schemas/`);
  users have real data in v1.

## Stack

Kotlin 2.0.21, Jetpack Compose (Material 3, dynamic color), Room (KSP), WorkManager, AlarmManager
exact alarms (`USE_EXACT_ALARM`), Navigation Compose. minSdk 34, targetSdk/compileSdk 35, JDK 17
bytecode, AGP 8.7.3, Gradle 8.14.3. Versions in `gradle/libs.versions.toml`.

## Layout

| Path | Contents |
|---|---|
| `core/domain` | Pure Kotlin, no Android. `WeekCalendar` (effective-dated week start), `TaskEvaluator` (instances, derived ● – ✕ states, gating), `MonthGrid` (grid + scores), `Sleep` math. |
| `app/…/data` | Room entities (UUID ids, ISO date strings, `updatedAt` on every row, tombstones), `AppDao`, `Repository`, `AppSnapshot`. |
| `app/…/backup` | `Csv` (`\N` = null), `BackupManager` (REPLACE with safety backup, or MERGE: newer `updatedAt` wins, tombstones respected), `BackupWorker`. |
| `app/…/notify` | `ReminderLogic`, `ReminderScheduler` (one exact alarm per reminder minute, end-of-day summary, reschedule on boot/time/zone/update), `Notifier`. |
| `app/…/export` | `MonthPdf` / `MonthPage`: the whole Month tab on one US Letter landscape page. `MonthPdfTest` writes previews to `app/build/screenshots/pdf-*.png` (PdfDocument itself cannot run under Robolectric). |
| `app/…/ui` | `MonthScreen`, `DayScreen`, `Editors` (cell / week-result / value / sleep), Tasks, Trackers, Settings, `AppRoot` (rail on wide, bottom bar on compact; starts on Month unfolded, Day on cover screen). |

## Build & test

```bash
./gradlew :core:domain:test :app:testDebugUnitTest   # 45 tests (v1.1.0), all must pass
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease                        # needs keystore.properties
```

- Robolectric screenshot tests write PNGs to `app/build/screenshots/` — look at them after UI changes.
- On this Windows machine the toolchain is portable under `~/tools` (JDK 17, Android SDK, gh);
  set `JAVA_HOME` and `ANDROID_HOME` accordingly, or keep `sdk.dir` in the git-ignored `local.properties`.

## Release signing

- Key: `halibut-release.jks`, alias `halibut`, cert SHA-256 `5cf9868c…f86d3`. Kept outside the repo.
  **Never commit it or `keystore.properties`** (both git-ignored). Updates must be signed with this
  key or they won't install over the existing app.
- `keystore.properties` in the repo root: `storeFile`, `storePassword`, `keyAlias=halibut`, `keyPassword`.
- Verify a built APK: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`
  and compare the SHA-256 digest.

## Workflow

- Bug fixes go on a branch with a PR and a test that reproduces the bug.
- Every release bumps `versionCode` (+1) and `versionName` in `app/build.gradle.kts`, is signed with
  the release key, tagged `vX.Y.Z`, and published as a GitHub release with `app-release.apk` attached.
- Repo: `codebasemaker-cyber/halibut-habit-tracker` (public — nothing secret in commits or releases).

## Status / not yet verified on a real device

Releases: v1.0.0 (`127f8f2`), v1.0.1 (`dec1ae9`), v1.1.0 (`3d500d0`); next versionCode is 4. Unverified on the
Pixel 9 Pro Fold: the month PDF file write, fold/unfold behaviour,
notifications and exact alarms, backup folder picker + persisted permission, auto-backup timing.

## Deferred

- Health Connect + auto-complete-from-tracker (`task_version.autoRule` is reserved for it).
