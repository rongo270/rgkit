# UserMemory (Android)

Android library module. Kotlin + Compose (Material 3), minSdk 24, no external
dependencies beyond the Compose BOM.

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:user-memory:0.1.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":usermemory")
project(":usermemory").projectDir =
    file("/path/to/rgkit/user-memory/android/usermemory")

// app/build.gradle.kts
dependencies {
    implementation(project(":usermemory"))
}
```

**Option C — copy the two files** (`UserMemory.kt`, `UserMemoryScreen.kt`) into
the app's source tree. Zero build changes if the app already uses Compose.

> Kotlin version note: the module's `build.gradle.kts` applies
> `org.jetbrains.kotlin.plugin.compose` (Kotlin 2.x). If a host app is still on
> Kotlin 1.9.x, remove that plugin line and add
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }` instead.

## Use

```kotlin
// Application.onCreate() — once:
UserMemory.init(this)
```

### Preferences (explicit settings)

```kotlin
UserMemory.set("units", "metric")           // String, Boolean, Int, Long, Double, List<String>
UserMemory.set("haptics", true)
UserMemory.set("rest_seconds", 90)

UserMemory.getString("units")               // "metric" (or null)
UserMemory.getString("units", "imperial")   // with a default
UserMemory.getBoolean("haptics", false)
UserMemory.getInt("rest_seconds", 60)
UserMemory.preferences()                     // all of them, for your own UI
UserMemory.remove("units")
```

### Behavior learning (implicit, recency-weighted)

```kotlin
// Call every time the user makes the choice — recent choices count more.
UserMemory.observe("export_format", "pdf")

UserMemory.preferredValue("export_format")            // "pdf"
UserMemory.preferred("export_format")                 // Learned: choices, shares, confidence
UserMemory.suggest("export_format", listOf("pdf", "png", "csv"))  // learned options first
UserMemory.learned()                                  // every learned key, most confident first
```

### Habits (recurring actions)

```kotlin
// Call every time the action happens — any thread; disk writes are backgrounded.
UserMemory.record("workout_logged")

val h = UserMemory.habit("workout_logged")
h?.strength()          // 0..1 — how established
h?.perWeek()           // ~ times per week over 4 weeks
h?.typicalHour()       // e.g. 7  (null if no clear pattern)
h?.dayPart()           // DayPart.MORNING
h?.typicalWeekdays()   // e.g. [5, 6] → weekends (Mon=0 … Sun=6)
h?.currentStreakDays() // consecutive days up to today
h?.isHabit()           // regular and recent?
h?.isFading()          // was regular, now dropping off?
UserMemory.habits()    // all recorded actions, most established first
```

### Recommendations & profile

```kotlin
UserMemory.recommendations()   // ranked list of what to surface right now
UserMemory.profile()           // engagement, peak part of day, streak, counts…
```

### The screen, export, and forgetting

```kotlin
UserMemoryScreen()             // ready-made; profile + suggestions + habits + learned + prefs

UserMemory.exportJson()        // full memory, pretty-printed (cross-platform format)
UserMemory.importJson(json)    // replace memory from an export; returns false if unparseable

UserMemory.reset()             // forget everything
UserMemory.forget("units")     // forget one key (preference + what was learned about it)
UserMemory.forgetHabit("workout_logged")
```

Storage: `user_memory.json` in the app's private files directory.
