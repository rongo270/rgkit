# RhythmEngine (Android)

Android library module. Pure Kotlin, minSdk 24, zero dependencies, zero
permissions.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":rhythmengine")
project(":rhythmengine").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/rhythm-engine/android/rhythmengine")

// app/build.gradle.kts
dependencies {
    implementation(project(":rhythmengine"))
}
```

**Option B — copy the file** (`RhythmEngine.kt`) into the app's source tree.

## Use

```kotlin
// Application.onCreate() — that's the whole integration:
RhythmEngine.init(this)

// Queries (all safe to call anytime; null/empty until enough data):
RhythmEngine.bestTimeToEngage(withinHours = 24, top = 3)
    // → [EngageWindow(startAt, hourOfDay, score 0–1)…], best first
RhythmEngine.nextExpectedOpenAt()        // epoch millis or null
RhythmEngine.expectedSessionMinutes()    // for "now"; or pass a future time
RhythmEngine.churnRisk()                 // 0–1
RhythmEngine.isUnusuallyQuiet()          // Boolean
RhythmEngine.engagementTrend()           // % change, last 7d vs previous 7d
RhythmEngine.weeklyPattern()             // Array(7) of DoubleArray(24), 0–1
RhythmEngine.totalOpens()

RhythmEngine.exportJson()
RhythmEngine.reset()
```

Storage: `rhythm_engine.json` in the app's private files directory.
