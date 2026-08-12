# FeatureUsage SDK

Local-first feature usage tracking for your Android and iOS apps, with a
built-in stats screen. One line to record a use, one line to see everything.

**Nothing to connect.** No backend, no API key, no account, no network
permission. Usage is stored in small JSON files inside the app's own sandbox,
so there is nothing to set up and nothing to disclose beyond on-device storage.
(That also means the numbers are per-device — see "Limits" below.)

## Quick start

**Android** (after adding the module — see `android/featureusage/README.md`):

```kotlin
// Application.onCreate()
FeatureUsage.init(this)

// wherever a feature is used
FeatureUsage.track("export_pdf")

// anywhere you want to see the numbers (e.g. a debug menu destination)
FeatureUsageScreen()
```

**iOS** (after adding the local Swift package — see `ios/FeatureUsage/README.md`):

```swift
import FeatureUsage

// wherever a feature is used (no init needed)
FeatureUsage.track("export_pdf")

// anywhere you want to see the numbers
NavigationLink("Feature usage") { FeatureUsageView() }
```

## What you get

- `track("name")` — thread-safe, background disk writes, safe to call on every
  tap. Returns the new total (1 = first use ever, handy for one-time tips).
  `track("name", count)` records a batch at once ("imported 12 photos").
- **Timed sessions** — `begin("editor")` … `end("editor")` records a use plus
  the elapsed time, giving total time spent and average session length.
- **Stats screen** (Compose / SwiftUI, identical layout), in three tabs:
  - **Features** — summary tiles (features / events / last 7 days with ▲/▼
    trend / today), an app-wide activity chart with a 7d/30d/90d switch,
    search, four sorts (most used / recent / trending / A–Z), and per-feature
    rows with rank, usage bar, share-of-total %, 7-day sparkline, trend arrow,
    time spent, and **NEW** / **UNUSED 30d+** badges.
  - **Timeline** — the last 500 uses as a live feed, grouped by day.
  - **Insights** — auto-generated findings: trending up/down, streaks, stale
    features, peak hour, busiest weekday, usage concentration, new this week.
  - **Tap any feature for a detail view**: 30-day chart, 12-week calendar
    heatmap, hour-of-day and day-of-week charts, streaks, time spent, and
    per-feature delete.
  - Live-updates while visible, and follows the host app's theme and
    light/dark mode.
- **Export / import** — JSON (full history) and CSV via share sheet or
  clipboard; `importJson` **merges an export back in**, so you can combine
  usage from several devices (see "Limits").
- **Insights API** for your own UI or logic: `stats()` / `stat(name)` /
  `count(name)` / `recentEvents()` / `insights()`, plus per-feature
  `trendPercent()`, `currentStreakDays()`, `bestStreakDays()`, `activeDays`,
  `averagePerActiveDay()`, `averageSessionMs`, `hourlyCounts()`,
  `weekdayCounts()`, `dailyCounts()`, `isStale()`.
- **Change listeners** for live UIs, and a global `enabled` switch to no-op
  everything (e.g. in release builds).
- `reset()` / `reset(name)` to wipe data (also available in the UI).
- Per-day history kept for 365 days (older buckets pruned; totals preserved).
  Files written by older SDK versions load unchanged.

## Same API on both platforms

| | Android (Kotlin) | iOS (Swift) |
|---|---|---|
| Init | `FeatureUsage.init(context)` | not needed |
| Record | `FeatureUsage.track("name")` | `FeatureUsage.track("name")` |
| Record batch | `track("name", 12)` | `track("name", count: 12)` |
| Timed session | `begin("name")` / `end("name")` | `begin(_:)` / `end(_:)` |
| Read | `stats()`, `stat(name)`, `count(name)` | `stats()`, `stat(_:)`, `count(for:)` |
| Timeline | `recentEvents(limit)` | `recentEvents(limit:)` |
| Insights | `insights()`, and per-feature `trendPercent()`, `currentStreakDays()`, `bestStreakDays()`, `activeDays`, `averagePerActiveDay()`, `averageSessionMs`, `hourlyCounts()`, `weekdayCounts()` | same names |
| Export | `exportJson()`, `exportCsv()` | `exportJSON()`, `exportCSV()` |
| Import/merge | `importJson(json)` | `importJSON(_:)` |
| Live updates | `addChangeListener { }` | `didChangeNotification` |
| Disable | `FeatureUsage.enabled = false` | `FeatureUsage.enabled = false` |
| Wipe | `reset()`, `reset(name)` | `reset()`, `reset(_:)` |
| UI | `FeatureUsageScreen()` | `FeatureUsageView()` |

Use stable snake_case feature names (`"export_pdf"`, not `"Export PDF!"`) and
keep them identical across platforms so exports line up.

## Limits (by design)

Data never leaves the device on its own, so you see one device's usage at a
time — great for dogfooding, debug builds, and your own devices. To combine a
few devices by hand, export JSON on one device (share or copy) and
**Import from clipboard** on another — totals, daily/hourly buckets and
durations merge. If you later want fleet-wide numbers across all users
automatically, that *would* need something to connect (a small sync endpoint
or an analytics service); the JSON export is already the right payload shape
for that.

## Layout

```
feature-usage/
├── android/featureusage/   Android library module (Kotlin + Compose), 32 unit tests
└── ios/FeatureUsage/       Swift package (SwiftUI), 35 unit tests
```

Both suites cover the same behaviour — recording, persistence and reload,
pruning, streaks and trends, timed sessions, the event log, import/merge, and
concurrent tracking — so the two platforms stay in step.
