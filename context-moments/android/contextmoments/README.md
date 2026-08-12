# ContextMoments (Android)

Android library module. Pure Kotlin, minSdk 24, no external dependencies.
Declares only `ACCESS_NETWORK_STATE` (normal permission).

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":contextmoments")
project(":contextmoments").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/context-moments/android/contextmoments")

// app/build.gradle.kts
dependencies {
    implementation(project(":contextmoments"))
}
```

**Option B — copy the file** (`ContextMoments.kt`) into the app's source tree.
Add `ACCESS_NETWORK_STATE` to the app manifest in that case.

## Use

```kotlin
// Application.onCreate():
ContextMoments.init(this)

// Optional opt-ins (request the runtime permissions yourself first):
ContextMoments.config = MomentsConfig(
    intervalMs = 30_000,
    enableCalendar = false,      // READ_CALENDAR → IN_MEETING gets much better
    enableAmbientAudio = false,  // RECORD_AUDIO → loudness level only
)

// Continuous:
ContextMoments.start()
ContextMoments.addListener { snap ->
    Log.d("moments", "${snap.moment} (${snap.confidence}) — ${snap.signals}")
}
ContextMoments.stop()

// One-shot (e.g. inside a WorkManager worker deciding on a notification):
ContextMoments.sampleNow { snap -> /* main thread */ }

// Read:
ContextMoments.current()
ContextMoments.history(50)     // (time, moment, confidence), newest first
ContextMoments.exportJson()
ContextMoments.reset()
```

Storage: `context_moments.json` in the app's private files directory
(moment transitions only — no raw sensor data, ever).
