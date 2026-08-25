# ExitReason (Android)

Android library module. Pure Kotlin, minSdk 24, zero dependencies, zero
permissions.

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:exit-reason:0.2.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":exitreason")
project(":exitreason").projectDir =
    file("/path/to/rgkit/exit-reason/android/exitreason")

// app/build.gradle.kts
dependencies {
    implementation(project(":exitreason"))
}
```

**Option C — copy the file** (`ExitReason.kt`) into the app's source tree.

## Use

```kotlin
// Application.onCreate():
ExitReason.init(this)

// Mark natural completion points so TASK_COMPLETED can be detected:
ExitReason.markTaskCompleted()

// Optional: feed frustration from your own detection (or IntentEngine):
ExitReason.reportFrustration()

// Listen (main thread; also fires on launch for CRASH / KILLED_BY_SYSTEM):
ExitReason.addListener { r ->
    Log.d("exit", "${r.reason} (${r.confidence}) after ${r.sessionMs / 1000}s — ${r.details["why"]}")
}

// Read:
ExitReason.lastExit()
ExitReason.history(50)          // newest first
ExitReason.distribution()       // Map<ExitReasonType, Int>
ExitReason.exportJson()
ExitReason.reset()

// Tune:
ExitReason.config = ExitConfig(quickBounceMs = 10_000, boredIdleMs = 60_000)
```

Storage: `exit_reason.json` in the app's private files directory.
