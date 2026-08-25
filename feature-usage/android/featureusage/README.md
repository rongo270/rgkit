# FeatureUsage (Android)

Android library module. Kotlin + Compose (Material 3), minSdk 24, no external
dependencies beyond the Compose BOM.

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:feature-usage:0.2.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":featureusage")
project(":featureusage").projectDir =
    file("/path/to/rgkit/feature-usage/android/featureusage")

// app/build.gradle.kts
dependencies {
    implementation(project(":featureusage"))
}
```

**Option C — copy the two files** (`FeatureUsage.kt`, `FeatureUsageScreen.kt`)
into the app's source tree. Zero build changes if the app already uses Compose.

> Kotlin version note: the module's `build.gradle.kts` applies
> `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.x). If a host app is still on
> Kotlin 1.9.x, remove that plugin line and add
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }` instead.

## Use

```kotlin
// Application.onCreate() — once:
FeatureUsage.init(this)

// Record a use — anywhere, any thread. Disk writes are on a background thread.
FeatureUsage.track("export_pdf")               // returns the new total (1 = first use)
FeatureUsage.track("import_photos", 12)        // batch: 12 uses at once
FeatureUsage.begin("editor")                   // timed session…
FeatureUsage.end("editor")                     // …records one use + time spent

// See the numbers — drop the ready-made screen into a debug menu.
// Three tabs (Features / Timeline / Insights), search, sorting, activity
// chart with 7d/30d/90d switch, live updates, and a per-feature detail view
// with charts, a 12-week heatmap, streaks and time spent:
FeatureUsageScreen()

// Or read the data yourself:
val top = FeatureUsage.stats().firstOrNull()   // most used feature
FeatureUsage.count("export_pdf")               // total uses
FeatureUsage.recentEvents(50)                  // last 50 uses, newest first
FeatureUsage.insights()                        // generated findings (see below)
FeatureUsage.stat("export_pdf")?.apply {
    countLastDays(7)          // uses in the last week
    trendPercent()            // last 7 days vs the 7 before, e.g. 25 or -50 (null = no baseline)
    currentStreakDays()       // consecutive days of use, ending today
    bestStreakDays()          // longest ever run
    activeDays                // distinct days with at least one use
    averagePerActiveDay()     // uses per active day
    averageSessionMs          // avg begin()/end() session length
    hourlyCounts()            // 24 buckets — when in the day is it used?
    weekdayCounts()           // 7 buckets, Monday first
    isStale()                 // unused for 30+ days?
}

// React to changes (the built-in screen uses this to live-update):
val listener = { refreshMyUi() }
FeatureUsage.addChangeListener(listener)
FeatureUsage.removeChangeListener(listener)

// Turn recording off entirely, e.g. in release builds:
FeatureUsage.enabled = BuildConfig.DEBUG

// Export / import / wipe:
FeatureUsage.exportJson()   // full history (per-day + per-hour), pretty-printed
FeatureUsage.exportCsv()    // one summary row per feature, incl. 7-day trend
FeatureUsage.importJson(s)  // merge an export back in; returns features merged
FeatureUsage.reset()        // or reset("export_pdf")
```

`insights()` returns ready-to-show findings — trending up/down, streaks, stale
features, peak hour, busiest weekday, usage concentration, new this week — each
with a `title`, a `detail` line, a `kind`, and the `feature` it refers to when
it is about one. They appear as-is in the screen's Insights tab.

Storage: `feature_usage.json` (stats) and `feature_usage_events.json` (the last
500 events, for the timeline) in the app's private files directory. Files
written by older versions of this SDK load unchanged.

## Tests

```sh
# from an app project that includes the module:
./gradlew :featureusage:testReleaseUnitTest
```
