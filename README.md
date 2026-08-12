# SDKs

A family of local-first mobile SDKs. Shared philosophy:

- **Nothing to connect** — no backend, no API key, no account. All learning
  and storage happens on-device in a small JSON file.
- **One-line init** — `Xxx.init(this)` in `Application.onCreate()`; most SDKs
  auto-capture everything else through lifecycle/window hooks.
- **Same shape everywhere** — `dev.rgkit.*` Kotlin library modules, minSdk 24,
  thread-safe singletons, listeners on the main thread, `exportJson()` /
  `reset()` on every SDK. Compose used only where UI is the product.
- **Honest heuristics** — every inference carries a confidence and the
  evidence used, and returns null/empty rather than guessing without data.

## Install

### Android — Maven Central

All 13 SDKs are on Maven Central under `io.github.rongo270`. The artifactId is
the SDK's folder name below — no extra repository needed, `mavenCentral()` is
already in every Android project.

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:exit-reason:0.1.0")
    implementation("io.github.rongo270:intent-engine:0.1.0")
}
```

The groupId is `io.github.rongo270`, but the Kotlin packages are `dev.rgkit.*` —
those are independent, a groupId only has to be a namespace the publisher owns:

```kotlin
import dev.rgkit.exitreason.ExitReason
```

Each module README also covers including the module from a local clone, or
copying the source file straight into an app. Releasing is documented in
[PUBLISHING.md](PUBLISHING.md).

### iOS — Swift Package Manager

Two SDKs also ship for iOS, as products of one package resolved from this
repository:

```swift
// Package.swift
dependencies: [
    .package(url: "https://github.com/rongo270/rgkit.git", from: "0.1.0"),
]
```

| Product | Platforms |
|---|---|
| `UserMemory` | iOS 15+ / macOS 13+ |
| `FeatureUsage` | iOS 15+ / macOS 13+ |

In Xcode: File → **Add Package Dependencies…** → paste the repo URL → pick the
products you want.

SPM only reads a manifest from the repository root, so the root
[`Package.swift`](Package.swift) is what consumers resolve. Each iOS SDK also
keeps its own `Package.swift` beside its sources for building it standalone.

## Catalog

| SDK | One-liner |
|---|---|
| [intent-engine](intent-engine/) | Understands *why* users act: rage taps, double-back, type-delete loops, scan-scrolling, failed drags, hesitation, zigzag navigation + a live frustration score |
| [context-moments](context-moments/) | Fuses motion, screen, audio routes, DND, network and time into the user's current *moment*: driving, walking, working, in a meeting, just woke up, watching TV |
| [screenshot-intelligence](screenshot-intelligence/) | Detects screenshots and classifies them on-device (receipt / error / chat / ticket / map / product / code), extracts entities, suggests actions |
| [exit-reason](exit-reason/) | Estimates why the user left: call, rage quit, task completed, bored, battery, screen off, crash — with evidence per verdict |
| [adaptive-ui](adaptive-ui/) | One composable renders items as grid / list / cards / carousel — a per-user Thompson-sampling bandit learns which layout wins |
| [flow-learning](flow-learning/) | On-device AI UX consultant: mines orderings, drop-offs, confusion loops, funnel leaks, common paths — and emits recommendations |
| [rhythm-engine](rhythm-engine/) | Learns the user's weekly rhythm: best time to notify, next expected open, expected session length, churn risk |
| [perceived-speed](perceived-speed/) | User-felt performance: cold start, per-screen TTI, jank, tap latency, main-thread stalls with guilty stacks — one felt-score per screen |
| [form-sense](form-sense/) | Per-field form friction: corrections, dwell, refocus, and *where users give up* — with concrete fix suggestions |
| [grip-sense](grip-sense/) | Detects handedness and thumb reach; heatmaps taps, flags controls users strain to reach; Compose debug overlay |
| [discovery-coach](discovery-coach/) | Feature-discovery nudge engine: right feature, right moment, hard anti-nag guarantees, dead-feature report |
| [feature-usage](feature-usage/) | Feature usage tracking with a full built-in stats screen (Android + iOS) |
| [user-memory](user-memory/) | Local user memory store |

## They compose

The SDKs are designed to feed each other (all optional):

```kotlin
// Frustration signals → richer exit verdicts:
IntentEngine.addListener { if (it.type == IntentType.RAGE_TAP) ExitReason.reportFrustration() }

// Hesitation moments → perfectly-timed feature tips:
IntentEngine.addListener { if (it.type == IntentType.HESITATION) DiscoveryCoach.reportCalmMoment() }

// Usage tracking → discovery bookkeeping:
FeatureUsage.track("export_pdf"); DiscoveryCoach.used("export_pdf")

// Right hour (RhythmEngine) AND right context (ContextMoments) before notifying:
val window = RhythmEngine.bestTimeToEngage().firstOrNull()
ContextMoments.sampleNow { if (it.moment !in badMoments) notifyAt(window) }
```

## Layout convention

```
<sdk-name>/
├── README.md                  what it does, quick start, limits
├── android/<module>/          Android library module (Kotlin)
│   ├── README.md              how to add it to an app
│   ├── build.gradle.kts
│   └── src/main/...
└── ios/<Name>/                Swift package (user-memory, feature-usage only)
    ├── README.md
    ├── Package.swift          standalone build of just this SDK
    ├── Sources/<Name>/
    └── Tests/<Name>Tests/
```

Every Android module is a Gradle project of this build (see
`settings.gradle.kts`) and publishes as `io.github.rongo270:<sdk-name>`. Apps
normally just declare the Maven coordinate; including a module by path still
works for local development against an unreleased change.

The iOS SDKs are exposed as products of the root [`Package.swift`](Package.swift),
because SPM resolves a manifest only from the repository root.
