# IntentEngine (Android)

Android library module. Kotlin, minSdk 24. Compose is used only for the
optional `Modifier.intentTarget` / `Modifier.intentDragProbe` helpers — the
core engine is plain Kotlin.

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:intent-engine:0.1.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":intentengine")
project(":intentengine").projectDir =
    file("/path/to/rgkit/intent-engine/android/intentengine")

// app/build.gradle.kts
dependencies {
    implementation(project(":intentengine"))
}
```

**Option C — copy the files** (`IntentEngine.kt`, `IntentEngineAuto.kt`, and
`IntentEngineCompose.kt` if the app uses Compose) into the app's source tree.

> Kotlin version note: the module applies `org.jetbrains.kotlin.plugin.compose`
> (Kotlin 2.x). On Kotlin 1.9.x remove that plugin line and set
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }`,
> or just copy `IntentEngine.kt` + `IntentEngineAuto.kt` (no Compose needed).

## Use

```kotlin
// Application.onCreate():
IntentEngine.autoCapture(this)          // init + auto-attach every Activity

// or manual control:
IntentEngine.init(this)
IntentEngine.attach(activity)           // per-activity capture
IntentEngine.screenChanged("checkout")  // Compose nav destinations

// React:
IntentEngine.addListener { s -> Log.d("intent", "${s.type} ${s.confidence} — ${s.evidence}") }

// Read:
IntentEngine.frustrationScore()   // 0–100 rolling
IntentEngine.stats()              // lifetime counts per IntentType
IntentEngine.todayCounts()
IntentEngine.recentSignals(50)
IntentEngine.exportJson()
IntentEngine.reset()

// Tune:
IntentEngine.config = IntentConfig(rageTapCount = 4, hesitationMs = 20_000)
```

Storage: `intent_engine.json` in the app's private files directory
(signal counts + recent signals only — no coordinates, no text).
