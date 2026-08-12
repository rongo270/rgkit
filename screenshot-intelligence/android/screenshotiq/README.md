# ScreenshotIQ (Android)

Android library module. Kotlin, minSdk 24. One dependency:
`com.google.mlkit:text-recognition` (bundled on-device OCR, ~4 MB, no key).

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:screenshot-intelligence:0.1.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":screenshotiq")
project(":screenshotiq").projectDir =
    file("/path/to/rgkit/screenshot-intelligence/android/screenshotiq")

// app/build.gradle.kts
dependencies {
    implementation(project(":screenshotiq"))
}
```

For full content analysis the app must hold the image-read permission —
request it like any runtime permission:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />           <!-- 33+ -->
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"
                 android:maxSdkVersion="32" />
```

Without it, Android 14+ still delivers event-only detections
(`insight.analyzed == false`).

## Use

```kotlin
// Application.onCreate():
ScreenshotIQ.init(this)

ScreenshotIQ.addListener { insight ->
    Log.d("shot", "${insight.kind} ${insight.confidence} ${insight.entities}")
    insight.suggestions.forEach { action -> /* show as chips */ }
}

// Classify any image through the same pipeline:
ScreenshotIQ.analyze(uri) { insight -> … }

// Read / manage:
ScreenshotIQ.stats()        // lifetime counts per ScreenshotKind
ScreenshotIQ.recent(50)     // (time, kind, confidence), newest first
ScreenshotIQ.exportJson()
ScreenshotIQ.reset()

// Tune:
ScreenshotIQ.config = ScreenshotConfig(enableContentAnalysis = true, freshnessMs = 20_000)
```

Storage: `screenshot_iq.json` in the app's private files directory
(kind counts + timestamps only — never the screenshot text or pixels).
