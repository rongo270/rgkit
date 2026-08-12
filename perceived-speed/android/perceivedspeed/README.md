# PerceivedSpeed (Android)

Android library module. Pure Kotlin, minSdk 24, zero dependencies, zero
permissions.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":perceivedspeed")
project(":perceivedspeed").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/perceived-speed/android/perceivedspeed")

// app/build.gradle.kts
dependencies {
    implementation(project(":perceivedspeed"))
}
```

**Option B — copy the file** (`PerceivedSpeed.kt`) into the app's source tree.

## Use

```kotlin
// Application.onCreate() — first line for accurate cold-start measurement:
PerceivedSpeed.init(this)

// Optional: name Compose destinations (Activities are automatic):
PerceivedSpeed.screen("checkout")

// Read:
PerceivedSpeed.coldStartMillis()       // median of last 30 cold starts
PerceivedSpeed.overallScore()          // 0–100, frame-weighted across screens
PerceivedSpeed.screenReport("checkout")
PerceivedSpeed.worstScreens(10)        // worst felt-score first
PerceivedSpeed.recentStalls(20)        // stalls with guilty stack tops
PerceivedSpeed.exportJson()
PerceivedSpeed.reset()
```

`ScreenSpeed` fields: `ttiMedianMs`, `jankPercent`, `frozenFrames`,
`inputLatencyP95Ms`, `stalls`, `framesObserved`, `feltScore` (0–100).

Storage: `perceived_speed.json` in the app's private files directory.
