# FlowLearning (Android)

Android library module. Pure Kotlin, minSdk 24, zero dependencies, zero
permissions.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":flowlearning")
project(":flowlearning").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/flow-learning/android/flowlearning")

// app/build.gradle.kts
dependencies {
    implementation(project(":flowlearning"))
}
```

**Option B — copy the file** (`FlowLearning.kt`) into the app's source tree.

## Use

```kotlin
// Application.onCreate():
FlowLearning.init(this)

// Track steps (screens and meaningful actions, stable snake_case):
FlowLearning.track("home")
FlowLearning.track("product_detail")
FlowLearning.track("add_to_cart")

// Funnels (optional):
FlowLearning.defineFunnel("checkout", listOf("cart", "address", "payment", "done"))

// Read the findings:
FlowLearning.insights().forEach {
    println("${it.type}: ${it.title} — ${it.recommendation} (${it.sampleSessions} sessions)")
}
FlowLearning.transitionsFrom("home")      // next-step probabilities
FlowLearning.commonPaths(3, 10)           // frequent journeys
FlowLearning.sessionCount()
FlowLearning.exportJson()
FlowLearning.reset()

// Tune:
FlowLearning.config = FlowConfig(sessionGapMs = 120_000, minSupport = 8)
```

Storage: `flow_learning.json` in the app's private files directory
(dictionary-coded step ids — compact even with hundreds of sessions).
