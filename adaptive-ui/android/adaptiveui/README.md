# AdaptiveUI (Android)

Android library module. Kotlin + Compose (Material 3), minSdk 24, no external
dependencies beyond the Compose BOM.

## Add to an app

**Option A — include the module:**

```kotlin
// settings.gradle.kts
include(":adaptiveui")
project(":adaptiveui").projectDir =
    file("/Users/rongo/Desktop/progrems/sdks/adaptive-ui/android/adaptiveui")

// app/build.gradle.kts
dependencies {
    implementation(project(":adaptiveui"))
}
```

**Option B — copy the two files** (`AdaptiveUi.kt`, `AdaptiveCollection.kt`)
into the app's source tree (app must already use Compose + Material 3).

> Kotlin version note: the module applies `org.jetbrains.kotlin.plugin.compose`
> (Kotlin 2.x). On Kotlin 1.9.x remove that plugin line and set
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }`.

## Use

```kotlin
// Application.onCreate():
AdaptiveUi.init(this)

// Drop-in composable:
AdaptiveCollection(id = "products", items = products) { product, style ->
    ProductTile(product, compact = style == LayoutStyle.LIST)
}

// Manual engine (custom UIs):
val style = AdaptiveUi.beginSession("feed", allowed = setOf(LayoutStyle.GRID, LayoutStyle.LIST))
AdaptiveUi.recordItemClick("feed")
AdaptiveUi.recordScrollDepth("feed", 0.7)
AdaptiveUi.endSession("feed")

// Inspect / control:
AdaptiveUi.stats("products")          // Map<LayoutStyle, ArmStats(sessions, meanReward)>
AdaptiveUi.explanation("products")    // human-readable belief
AdaptiveUi.force("products", LayoutStyle.GRID)   // pin; null to unpin
AdaptiveUi.exportJson()
AdaptiveUi.reset()                    // or reset("products")
```

Storage: `adaptive_ui.json` in the app's private files directory.
