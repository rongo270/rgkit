# DiscoveryCoach (Android)

Android library module. Pure Kotlin, minSdk 24, zero dependencies, zero
permissions.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":discoverycoach")
project(":discoverycoach").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/discovery-coach/android/discoverycoach")

// app/build.gradle.kts
dependencies {
    implementation(project(":discoverycoach"))
}
```

**Option B — copy the file** (`DiscoveryCoach.kt`) into the app's source tree.

## Use

```kotlin
// Application.onCreate():
DiscoveryCoach.init(this)
DiscoveryCoach.register(listOf(
    DiscoverableFeature(
        id = "swipe_archive",
        title = "Swipe to archive",
        tip = "Swipe left on any item to archive it",
        priority = 5,                    // 1–5
        minSessionsBeforeNudge = 2,
        prerequisites = emptyList(),
    ),
))

// Feature usage:
DiscoveryCoach.used("swipe_archive")

// Ask-style:
DiscoveryCoach.maybeNudge()?.let { nudge -> /* show it */ }

// Push-style:
DiscoveryCoach.setNudgeListener { nudge -> /* show it */ }
DiscoveryCoach.reportCalmMoment()

// Feedback (drives cooldowns + success stats):
DiscoveryCoach.nudgeShown("swipe_archive")
DiscoveryCoach.nudgeAccepted("swipe_archive")
DiscoveryCoach.nudgeDismissed("swipe_archive")

// Read:
DiscoveryCoach.discoveryReport()   // discovery %, nudge success %, dead features
DiscoveryCoach.exportJson()
DiscoveryCoach.reset()

// Tune the anti-nag rules:
DiscoveryCoach.config = CoachConfig(maxPerDay = 1, minGapMs = 8 * 3_600_000)
```

Storage: `discovery_coach.json` in the app's private files directory.
