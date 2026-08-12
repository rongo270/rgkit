# FeatureUsage (iOS)

Swift package. iOS 15+ (share buttons appear on iOS 16+). No dependencies.

## Add to an app

**Option A — Swift Package Manager (recommended):**

Xcode → File → **Add Package Dependencies…** → paste
`https://github.com/rongo270/rgkit.git` → pick the `FeatureUsage` product.

Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/rongo270/rgkit.git", from: "0.1.0"),
],
targets: [
    .target(
        name: "YourApp",
        dependencies: [.product(name: "FeatureUsage", package: "rgkit")]
    ),
]
```

The SPM manifest consumers resolve is the `Package.swift` at the **repository
root** — SPM does not read manifests from subdirectories. The one next to these
sources is for building this SDK standalone.

**Option B — local clone:** Xcode → **Add Package Dependencies…** →
**Add Local…** → pick the repo root → add the `FeatureUsage` product.

**Option C — copy the sources** (`Sources/FeatureUsage/`) into your app target.

## Use

```swift
import FeatureUsage

// Record a use — anywhere, any thread. No setup needed.
FeatureUsage.track("export_pdf")                // returns the new total (1 = first use)
FeatureUsage.track("import_photos", count: 12)  // batch: 12 uses at once
FeatureUsage.begin("editor")                    // timed session…
FeatureUsage.end("editor")                      // …records one use + time spent

// See the numbers — drop the ready-made screen into a debug menu.
// Three tabs (Features / Timeline / Insights), search, sorting, activity
// chart with 7d/30d/90d switch, live updates, and a per-feature detail view
// with charts, a 12-week heatmap, streaks and time spent:
NavigationLink("Feature usage") { FeatureUsageView() }

// Or read the data yourself:
let top = FeatureUsage.stats().first          // most used feature
FeatureUsage.count(for: "export_pdf")         // total uses
FeatureUsage.recentEvents(limit: 50)          // last 50 uses, newest first
FeatureUsage.insights()                       // generated findings (see below)
if let stat = FeatureUsage.stat("export_pdf") {
    stat.count(lastDays: 7)       // uses in the last week
    stat.trendPercent()           // last 7 days vs the 7 before, e.g. 25 or -50 (nil = no baseline)
    stat.currentStreakDays()      // consecutive days of use, ending today
    stat.bestStreakDays()         // longest ever run
    stat.activeDays               // distinct days with at least one use
    stat.averagePerActiveDay()    // uses per active day
    stat.averageSessionMs         // avg begin()/end() session length
    stat.hourlyCounts()           // 24 buckets — when in the day is it used?
    stat.weekdayCounts()          // 7 buckets, Monday first
    stat.isStale()                // unused for 30+ days?
}

// React to changes (the built-in screen uses this to live-update):
NotificationCenter.default.addObserver(
    forName: FeatureUsage.didChangeNotification, object: nil, queue: .main
) { _ in refreshMyUI() }

// Turn recording off entirely, e.g. in release builds:
#if !DEBUG
FeatureUsage.enabled = false
#endif

// Export / import / wipe:
FeatureUsage.exportJSON()    // full history (per-day + per-hour), pretty-printed
FeatureUsage.exportCSV()     // one summary row per feature, incl. 7-day trend
FeatureUsage.importJSON(s)   // merge an export back in; returns features merged
FeatureUsage.reset()         // or reset("export_pdf")
```

`insights()` returns ready-to-show findings — trending up/down, streaks, stale
features, peak hour, busiest weekday, usage concentration, new this week — each
with a `title`, a `detail` line, a `kind`, and the `feature` it refers to when
it is about one. They appear as-is in the screen's Insights tab.

Storage: `Application Support/FeatureUsage/usage.json` (stats) and
`events.json` (the last 500 events, for the timeline) in the app sandbox.
`FeatureUsage.configure(directory:)` overrides the location (used by tests).
Files written by older versions of this SDK load unchanged.

## Tests

```sh
cd feature-usage/ios/FeatureUsage && swift test
```
