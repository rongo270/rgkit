# GripSense (Android)

Android library module. Kotlin, minSdk 24. Compose is used only for the
optional `GripHeatmapOverlay` debug composable — the engine is plain Kotlin.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":gripsense")
project(":gripsense").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/grip-sense/android/gripsense")

// app/build.gradle.kts
dependencies {
    implementation(project(":gripsense"))
}
```

**Option B — copy the files** (`GripSense.kt`, plus `GripSenseOverlay.kt` if
the app uses Compose).

> Kotlin version note: the module applies `org.jetbrains.kotlin.plugin.compose`
> (Kotlin 2.x). On Kotlin 1.9.x remove that plugin line and set
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }`,
> or copy only `GripSense.kt`.

## Use

```kotlin
// Application.onCreate():
GripSense.init(this)

// Read:
GripSense.handedness()          // Pair<Handedness, confidence 0–1>
GripSense.zoneFor(0.5, 0.9)     // ReachZone for a normalized point
GripSense.stretchTapShare()     // fraction of taps in the HARD zone
GripSense.hardestHotspots(5)    // frequently-tapped strained regions
GripSense.heatmap()             // Array(20 rows) of IntArray(12 cols)
GripSense.report()              // everything + human-readable advice
GripSense.exportJson()
GripSense.reset()

// Compose debug overlay:
GripHeatmapOverlay()
```

Storage: `grip_sense.json` in the app's private files directory
(grid counts + vote counters only).
