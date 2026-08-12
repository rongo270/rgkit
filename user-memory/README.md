# UserMemory SDK

Universal user memory for your Android and iOS apps. Stop learning every user
from scratch: remember their **preferences**, learn their **habits** and
**behavior**, and turn all of it into **smart recommendations** — with one line
per signal and a ready-made screen that shows the user everything you know.

**Nothing to connect.** No backend, no API key, no account, no network
permission. Everything is stored in a small JSON file inside the app's own
sandbox, so there is nothing to set up and nothing to disclose beyond on-device
storage. `exportJson()` / `importJson()` move that memory between apps, devices
and platforms — the format is identical on Android and iOS.

## The four things it remembers

| | What it is | You call | You read back |
|---|---|---|---|
| **Preferences** | Explicit settings the user (or you) set | `set("units", "metric")` | `getString("units")` |
| **Behavior** | What the user tends to *choose* | `observe("export_format", "pdf")` | `preferredValue("export_format")` → `"pdf"` |
| **Habits** | Recurring actions, with timing | `record("workout_logged")` | `habits()`, `habit("workout_logged")` |
| **Recommendations** | What to surface *right now* | — | `recommendations()` |

Behavior learning is **recency-weighted** (a 30-day half-life), so a learned
preference follows the user when their behavior changes instead of being frozen
by history. Habit recognition figures out *how often*, *what time of day*, and
*which weekdays* an action happens, plus current/best streaks — with no schema
from you beyond a stable name.

## Quick start

**Android** (after adding the module — see `android/usermemory/README.md`):

```kotlin
// Application.onCreate()
UserMemory.init(this)

// Explicit preference
UserMemory.set("units", "metric")

// Learn from a choice — call it every time the user picks
UserMemory.observe("export_format", "pdf")

// Habit signal — call it every time the action happens
UserMemory.record("workout_logged")

// Use what was learned
val format = UserMemory.preferredValue("export_format") ?: "pdf"
val ordered = UserMemory.suggest("export_format", listOf("pdf", "png", "csv"))
val nudges  = UserMemory.recommendations()

// Show the user everything you remember (settings or debug menu)
UserMemoryScreen()
```

**iOS** (after adding the local Swift package — see `ios/UserMemory/README.md`):

```swift
import UserMemory

// No init needed
UserMemory.set("units", "metric")
UserMemory.observe("export_format", choice: "pdf")
UserMemory.record("workout_logged")

let format  = UserMemory.preferredValue("export_format") ?? "pdf"
let ordered = UserMemory.suggest("export_format", options: ["pdf", "png", "csv"])
let nudges  = UserMemory.recommendations()

// Show the user everything you remember
NavigationLink("User memory") { UserMemoryView() }
```

## The built-in screen

`UserMemoryScreen()` (Compose) and `UserMemoryView()` (SwiftUI) render the same
layout and follow the host app's theme and light/dark mode:

- **Profile hero card** — an at-a-glance identity: engagement level ("Power
  user"), how long you've known them, and chips like *Morning person*,
  *Weekend-leaning*, *5-day streak*.
- **Suggestions** — the live output of `recommendations()`: a habit due about
  now, a streak at risk, a fading habit, a strong-enough default to preselect.
- **Habits** — a 14-day dot row, a 24-hour activity strip (when in the day it
  happens), per-week rate, streak, and a HABIT / FORMING / FADING badge.
- **Learned from choices** — each learned key with its winning value, a
  confidence bar, and the full breakdown of options and shares.
- **Preferences** — every explicit key/value with when it was last set.
- **Export / forget** — share or copy the full JSON, or wipe everything.

## Smart recommendations

`recommendations()` returns an already-ranked list. Each `Recommendation` has a
`kind`, the `subject` (habit name or choice key), a `title` + `detail` written
for display, and a `score`. The kinds:

| Kind | Fires when | Example title |
|---|---|---|
| `HABIT_DUE` | A habit usually happens around this hour and hasn't yet today | "'workout_logged' usually happens about now" |
| `STREAK_AT_RISK` | A 3+ day streak will break unless the habit happens today | "6-day 'workout_logged' streak on the line" |
| `FADING_HABIT` | A once-regular habit's activity has roughly halved | "'meditate' is fading" |
| `LEARNED_DEFAULT` | A learned choice is confident enough to preselect | "Default export_format to 'pdf'" |

## Same API on both platforms

| | Android (Kotlin) | iOS (Swift) |
|---|---|---|
| Init | `UserMemory.init(context)` | not needed |
| Set preference | `set("k", "v")` | `set("k", "v")` |
| Read preference | `getString/getBoolean/getInt/getDouble/getStrings` | `string(for:)/bool(for:)/int(for:)/double(for:)/strings(for:)` |
| Learn a choice | `observe("k", "choice")` | `observe("k", choice: "choice")` |
| Read learned | `preferred("k")`, `preferredValue("k")` | `preferred("k")`, `preferredValue("k")` |
| Rank options | `suggest("k", options)` | `suggest("k", options:)` |
| Record a habit | `record("name")` | `record("name")` |
| Read habits | `habits()`, `habit("name")` | `habits()`, `habit("name")` |
| Recommendations | `recommendations()` | `recommendations()` |
| Profile | `profile()` | `profile()` |
| Export / import | `exportJson()`, `importJson(s)` | `exportJSON()`, `importJSON(s)` |
| Forget | `reset()`, `forget("k")`, `forgetHabit("name")` | `reset()`, `forget("k")`, `forgetHabit("name")` |
| UI | `UserMemoryScreen()` | `UserMemoryView()` |

Use stable snake_case keys and habit names (`"export_format"`, `"workout_logged"`)
and keep them identical across platforms so exports line up.

## Limits (by design)

Memory never leaves the device, so it's per-device unless you move it yourself.
`exportJson()` is already the right payload to sync through your own backend, to
seed a new install from a signed-in user's cloud copy, or to hand one app's
memory to another. If you want cross-device memory automatically, that *would*
need something to connect (a small sync endpoint) — the JSON is the shape for it.

Habit day-buckets are pruned to the last 365 days; lifetime totals and per-hour
patterns are preserved.

## Layout

```
user-memory/
├── android/usermemory/   Android library module (Kotlin + Compose)
└── ios/UserMemory/       Swift package (SwiftUI), tests included
```
