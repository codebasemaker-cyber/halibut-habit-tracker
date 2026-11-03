# Habit & Value Tracker — Design

Offline Android app that replaces a printed monthly Excel tracker
(`Sleep_and_habit_tracker_YYYY-MM.xlsx`, sheets "Habit Tracker" and "Sleep Tracker").
Target device: Pixel 9 Pro Fold. Installed by APK (not Play Store).

Status: **v1.0.0 implemented.** Decisions below are final unless marked *assumption*.

---

## 1. Hard constraints

- Fully offline: **no `INTERNET` permission** (stripped from the merged manifest with
  `tools:node="remove"`; a build check fails if it reappears), no analytics, no cloud.
- All data local (Room/SQLite). Backups go to a user-chosen folder (synced by Syncthing).
- Adaptive layouts: month grid is the default on the inner (expanded) screen, per-day
  checklist on the cover (compact) screen; both reachable on any size. State survives
  fold/unfold (ViewModels + `rememberSaveable`).

## 2. Tech stack

| Concern | Choice |
|---|---|
| Language/UI | Kotlin, Jetpack Compose, Material 3, window size classes, M3 adaptive list-detail |
| Storage | Room (KSP). Settings live in Room too, so they round-trip through backups |
| Dates | `java.time` (minSdk 34) |
| Background | WorkManager (auto-backup), AlarmManager exact alarms (reminders) |
| Files | Storage Access Framework, persisted tree URI; zip + RFC 4180 CSV written in-house |
| PDF | `android.graphics.pdf.PdfDocument` (one-page month export, §9.10) |
| Modules | `:core:domain` (pure Kotlin/JVM: recurrence + scoring, no Android); `:app` with packages `data` (Room), `backup`, `notify`, `ui` |

## 3. Section 1 — Habit Tracker (scored)

### 3.1 Schedule types

| Type | Instance due | Default points | Where marks are stored |
|---|---|---|---|
| Daily | each day | 1 | that day |
| Weekly | last day of the week | 5 | the week's due date |
| X times per week | last day of the week | 5 | each day worked; week result is derived |
| Every N days | rolling, see 3.4 | configurable | completion day |
| Monthly | last day of month | 20 | last day of month |

Storing weekly/monthly marks **on the due date** makes carry-over automatic: the week
ending Nov 1 is one record dated Nov 1, shown in October's grid (flagged "not scored this
month") and scored in November.

### 3.2 Weeks

- Week start day is a **setting with effective dates** (default Monday, so weeks end Sunday).
- Under a week-start version effective from `E`, weeks begin at `E` and on every
  subsequent week-start day. When a new version starts at `E2`, the week running under the
  old rule is truncated at `E2 - 1` (a short *transition week*), scored normally.
- The UI only offers effective dates that fall on the new week-start day.

### 3.3 Cell states

| Display | Meaning |
|---|---|
| ● | completed |
| – | skipped / not needed / goal already met |
| X | due or overdue and not completed |
| (blank) | unrecorded — no data yet; scores as not earned |

- Manual marks: `DONE`, `SKIP`, `MISSED`, `BLANK` (force unrecorded).
- Derived states (only for X-per-week and every-N) are computed. A manual mark always wins;
  a manual mark placed on a cell that showed a derived state is saved with `isOverride`
  and shown with a corner marker.
- **Gating:** on past days, derived states are only shown if that day has at least one
  Section 1 mark (any task). Today and future days are not gated. Gating affects display
  only, never scoring.
- Daily tasks never get a derived X. A "Close out day" action turns the day's remaining
  blanks into manual X.

### 3.4 Every N days (rolling)

- First due date = the start date chosen when creating the task.
- A completion is a `DONE` or `SKIP` mark.
- Algorithm (`lastReset` starts at `start - N`):
  1. Let `D` be the current due date and `c` the first completion after `lastReset`.
  2. If `c <= D`: the instance at `D` is **earned**; `lastReset = c`; next `D = c + N`.
  3. Otherwise the instance at `D` is **missed**; next `D = D + N` (misses keep accruing
     every N days while overdue — "option B").
- Consequence of rule 2 after misses: a late completion credits the next pending deadline
  and restarts the cycle from the completion date.
  Example, N = 7, due Oct 10, done Oct 31: Oct 10, 17, 24 missed; Oct 31 earned; next due Nov 7.
  Example, N = 7, due Oct 10, done Oct 13: Oct 10 missed; Oct 17 earned (credited by Oct 13); next due Oct 20.
  *(assumption — our reading of "recorded as an X and pushed over to credit toward the next deadline")*
- Display: days inside a cycle before the due date show derived –; the due date shows X once
  it has passed; every overdue day shows X until the completion.
- Changing N or points takes effect for instances due on/after the version's effective date;
  the chain is not re-anchored. Switching a task *to* every-N uses that version's start date.
- An instance is scored in the month containing its due date.

### 3.5 X times per week (goal G, points P)

- Only `DONE` marks on days in the week count. Extra marks are shown, not scored.
- Week score = `P × min(done, G) / G` (partial credit). Shown with one decimal.
- Once `done ≥ G`, the week's remaining unmarked days show derived –.
- After the week ends with `done < G`, the due-date cell (if unmarked) shows derived X.
- The week result is a badge on the grid and can be overridden (`DONE` = full, `SKIP` =
  full if dashes count, `MISSED` = 0).
- A transition (short) week keeps the same goal.

### 3.6 Scoring

- Instance earned: `DONE` → points; `SKIP` → points if "dashes count" is on for that date;
  `MISSED`/blank → 0. X-per-week as in 3.5.
- Possible = points of every instance whose due date is in the month and on which the task
  is active (`effectiveFrom ≤ due < archivedOn`).
- Two figures per month: **To date** (instances due before today, plus today's instances that
  already have a mark) and **Month** (all instances in the month).
- Version used for an instance = the version in effect on its due date. New versions default
  to the 1st of the month but can start on any date.
- "Dashes count as earned": on/off, with a sub-setting *apply to all history* (one version
  from the beginning of time) or *apply from a date forward* (new effective-dated version).
- Scores are always computed, never cached, so retroactive entry stays consistent
  (entering an old every-N completion can change later months — expected).

### 3.7 Thresholds

Label + setting only, versioned with the task. `task_version.autoRule` is reserved for a
later "complete when a Section 2 value ≥ threshold" feature (not built in v1).

## 4. Section 2 — Values & Info (not scored)

Types: number, checkbox, time, time range, rating (1…max, default max 10), short text, computed.

Seed trackers:

| Tracker | Type |
|---|---|
| Energy Dip? | time (e.g. 4:00pm) |
| Day Productivity | rating 1–10 |
| Calories | number |
| Protein (g) | number |
| Steps | number |
| Med 1, Med 2 | checkbox |
| Sleep | time range, attributed to wake date (overridable), optional notes, optional naps |
| Total Hours | computed: main sleep duration (naps excluded), overridable |
| Quality | rating 1–10 |
| Waking Energy | rating 1–10 |
| Weigh-in | number, stored in lb; display unit lb/kg preference; usually weekly, any day allowed |

- Sleep: bed and wake entered together, shown "10:30pm – 7:00am". Durations are computed
  zone-aware (e.g. the Nov 1, 2026 DST night is an hour longer).
- Naps: any number per date, each with its own range and notes; attributed to their start date.
- Computed values: v1 only `SLEEP_DURATION`.
- Trackers follow the same versioning/archive/delete rules as tasks. Type changes are only
  allowed between compatible types; otherwise archive and create a new tracker.

## 5. History & editing

- Every day/month editable; no locking. Jump to any month; fast "archive entry" mode.
- Tasks/trackers: backdatable effective-from, archived-on (hidden from future entry, kept
  in past months), and permanent delete (with confirmation; changes past scores).
- Changes apply from a chosen effective date forward.

## 6. Data model (Room)

All IDs are UUID strings; every row has `updatedAt` (for merge restore).

```
task(id, sortOrder, archivedOn?, updatedAt)
task_version(id, taskId, effectiveFrom, name, scheduleType, points, thresholdLabel?,
             timesPerWeek?, intervalDays?, anchorDate?, reminderTime?, autoRule?, updatedAt)
task_mark(taskId, date, state, isOverride, note?, updatedAt)        PK(taskId, date)
week_result_override(taskId, dueDate, state, updatedAt)             PK(taskId, dueDate)

tracker(id, sortOrder, archivedOn?, updatedAt)
tracker_version(id, trackerId, effectiveFrom, name, type, unit?, ratingMax?, computedFn?,
                sourceTrackerId?, updatedAt)
tracker_value(trackerId, date, seq, kind{MAIN,NAP}, num?, bool?, text?, timeMinutes?,
              rangeStart?, rangeEnd?, note?, isOverride, source{MANUAL,HEALTH_CONNECT}, updatedAt)
              PK(trackerId, date, seq)

setting_version(key, effectiveFrom, value, updatedAt)   -- weekStart, dashCounts
setting(key, value, updatedAt)                          -- non-scoring prefs (weight unit, …)
tombstone(tableName, rowId, deletedAt)
-- last backup time/error are kept as device-only rows in `setting` and never overwritten by a restore
```

## 7. Notifications

- Per-task reminder time (exact alarms via `USE_EXACT_ALARM`, fine for sideloaded APK).
  Skipped at fire time if the task is already done. Same-minute reminders are grouped.
- Every-N tasks remind daily while overdue.
- What "needs doing" means at reminder time: daily — not marked today; X-per-week — goal not yet met
  and not done today; every-N — due today or overdue; weekly/monthly — only on the due date, if unmarked.
- Optional end-of-day summary; backup reminder when the last good backup is older than N+1 days.
- `POST_NOTIFICATIONS` requested on first run (Android 13+). Alarms rescheduled on boot,
  time/zone change and app update.

## 8. Backup & restore

- Format: one `.zip` per backup, `habit-backup-YYYY-MM-DD_HHmm.zip`, containing
  `manifest.csv` (schema version, app version, row counts) and one CSV per table.
- Auto backup every N days, keep last K. Guarantee: WorkManager runs it once due, typically
  within hours but not at an exact time; it also runs on app open if overdue; a notification
  fires if no successful backup for N+1 days or folder access is lost.
- Restore: **Replace** (default; takes a safety backup first) or **Merge** (by ID, newer
  `updatedAt` wins, tombstones respected). A preview shows adds/updates/deletes first.
- Round trip is lossless: definitions, versions, marks, overrides, trackers, values, settings.

## 9. Screens

1. Month grid — tabs Daily | Weekly & Monthly | Values, pinned score footer; pinned date
   column/headers, rotated headers, three zoom levels; tap cycles blank→●→–→X, long-press edits.
2. Day checklist — swipe between days; dailies, X-per-week progress, upcoming weekly/monthly,
   due/overdue every-N, Section 2 inputs.
3. Cell editor (bottom sheet) — derived vs override, clear override, note.
4. Jump to date / archive entry mode.
5. Tasks — list/detail, version timeline, "Change from date…", archive, delete.
6. Trackers — same as tasks.
7. Month summary — per-task breakdown, Section 2 stats (basis for PDF).
10. Month PDF (v1.1) — "PDF" on the Month screen saves `habit-tracker-YYYY-MM.pdf` through the
    system save dialog (no network). One US Letter landscape page holds the whole Month tab: one row
    per day with the daily-grid habits and that day's values side by side; a right column with
    Weekly (plus X-per-week results), Monthly and the Summary; the score in the header. Columns
    and the right column shrink to fit, so it is always one page.
8. Backup & restore.
9. Settings & first run (permissions, backup folder, seed from sheet).

## 10. Capacity

No hard limits. Layout budgeted for 20 daily, 20 weekly, 10 monthly tasks.
