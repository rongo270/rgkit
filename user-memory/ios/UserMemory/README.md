# UserMemory (iOS)

Swift package. iOS 15+ / macOS 13+ (share button appears on iOS 16+). No
dependencies.

## Add to an app

**Option A — Swift Package Manager (recommended):**

Xcode → File → **Add Package Dependencies…** → paste
`https://github.com/rongo270/rgkit.git` → pick the `UserMemory` product.

Or in a `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/rongo270/rgkit.git", from: "0.1.0"),
],
targets: [
    .target(
        name: "YourApp",
        dependencies: [.product(name: "UserMemory", package: "rgkit")]
    ),
]
```

The SPM manifest consumers resolve is the `Package.swift` at the **repository
root** — SPM does not read manifests from subdirectories. The one next to these
sources is for building this SDK standalone.

**Option B — local clone:** Xcode → **Add Package Dependencies…** →
**Add Local…** → pick the repo root → add the `UserMemory` product.

**Option C — copy the sources** (`Sources/UserMemory/`) into your app target.

## Use

```swift
import UserMemory
// No setup needed — the first call loads and persists automatically.
```

### Preferences (explicit settings)

```swift
UserMemory.set("units", "metric")           // String, Bool, Int, Double, [String]
UserMemory.set("haptics", true)
UserMemory.set("rest_seconds", 90)

UserMemory.string(for: "units")              // "metric" (or nil)
UserMemory.string(for: "units", default: "imperial")
UserMemory.bool(for: "haptics", default: false)
UserMemory.int(for: "rest_seconds", default: 60)
UserMemory.preferences()                     // all of them, for your own UI
UserMemory.removePreference("units")
```

### Behavior learning (implicit, recency-weighted)

```swift
// Call every time the user makes the choice — recent choices count more.
UserMemory.observe("export_format", choice: "pdf")

UserMemory.preferredValue("export_format")               // "pdf"
UserMemory.preferred("export_format")                    // Learned: choices, shares, confidence
UserMemory.suggest("export_format", options: ["pdf", "png", "csv"])  // learned options first
UserMemory.learned()                                     // every learned key, most confident first
```

### Habits (recurring actions)

```swift
// Call every time the action happens — any thread.
UserMemory.record("workout_logged")

let h = UserMemory.habit("workout_logged")
h?.strength()          // 0..1 — how established
h?.perWeek()           // ~ times per week over 4 weeks
h?.typicalHour()       // e.g. 7  (nil if no clear pattern)
h?.dayPart()           // .morning
h?.typicalWeekdays()   // e.g. [5, 6] → weekends (Mon=0 … Sun=6)
h?.currentStreakDays() // consecutive days up to today
h?.isHabit()           // regular and recent?
h?.isFading()          // was regular, now dropping off?
UserMemory.habits()    // all recorded actions, most established first
```

### Recommendations & profile

```swift
UserMemory.recommendations()   // ranked list of what to surface right now
UserMemory.profile()           // engagement, peak part of day, streak, counts…
```

### The screen, export, and forgetting

```swift
NavigationLink("User memory") { UserMemoryView() }   // ready-made screen

UserMemory.exportJSON()        // full memory, pretty-printed (cross-platform format)
UserMemory.importJSON(json)    // replace memory from an export; returns false if unparseable

UserMemory.reset()             // forget everything
UserMemory.forget("units")     // forget one key (preference + what was learned about it)
UserMemory.forgetHabit("workout_logged")
```

Storage: `Application Support/UserMemory/memory.json` in the app sandbox.
`UserMemory.configure(directory:)` overrides the location (used by tests).

## Cross-platform

`exportJSON()` and `importJSON(_:)` use the same schema as the Android SDK
(epoch-millis timestamps throughout), so a memory export from one platform
imports cleanly on the other. Keep keys and habit names identical across
platforms so they line up.

## Tests

```sh
cd user-memory/ios/UserMemory && swift test
```
