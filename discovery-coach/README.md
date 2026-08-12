# DiscoveryCoach SDK

Most users never find most features. DiscoveryCoach knows **which features
this user hasn't discovered** and teaches them the right one at the right
moment — without ever becoming the app that nags.

**Nothing to connect.** All state is one on-device JSON file.

## How it decides

- **Right feature** — score = priority × ripeness (sessions since the feature
  was registered), with prerequisites respected ("don't teach filters before
  search"), previous nudges penalized, and twice-dismissed features suppressed
  for 30 days.
- **Right moment** — you ask at natural pause points (`maybeNudge()`), or feed
  calm moments (`reportCalmMoment()` — pairs perfectly with IntentEngine's
  `HESITATION` signal). The engine answers `null` most of the time, by design.
- **Never nags** — hard guarantees: one nudge per session, max 2 per day,
  minimum 4 h between nudges, per-feature backoff of 1 → 3 → 7 → 14 days.
  All tunable via `CoachConfig`.

## Quick start

```kotlin
// Application.onCreate():
DiscoveryCoach.init(this)
DiscoveryCoach.register(listOf(
    DiscoverableFeature("swipe_archive", "Swipe to archive",
        "Swipe left on any item to archive it", priority = 5),
    DiscoverableFeature("filters", "Search filters",
        "Narrow results with filters", priority = 4,
        prerequisites = listOf("search")),
))

// Whenever a feature is actually used (or bridge your FeatureUsage.track calls):
DiscoveryCoach.used("swipe_archive")

// At a natural pause (screen settled, task done):
DiscoveryCoach.maybeNudge()?.let { nudge ->
    showTipCard(nudge.feature.title, nudge.feature.tip,
        onShown = { DiscoveryCoach.nudgeShown(nudge.feature.id) },
        onTry = { DiscoveryCoach.nudgeAccepted(nudge.feature.id) },
        onDismiss = { DiscoveryCoach.nudgeDismissed(nudge.feature.id) })
}
```

Push-style instead of ask-style:

```kotlin
DiscoveryCoach.setNudgeListener { nudge -> showTipCard(nudge) }
IntentEngine.addListener { s ->
    if (s.type == IntentType.HESITATION) DiscoveryCoach.reportCalmMoment()
}
```

## The feedback loop

```kotlin
val report = DiscoveryCoach.discoveryReport()
report.discoveryPercent      // how much of the app this user has found
report.nudgeSuccessPercent   // nudges that led to a first use
report.deadFeatures          // nudged 3+, still unused → redesign or delete
```

That last list is gold: features that even free advertising can't sell.

## Limits (by design)

- The coach only knows what you tell it: `register()` the catalog and call
  `used()` honestly, or discovery stats lie.
- Per-device state; a reinstall starts discovery over (which is usually
  correct — it's a new user experience).

## Layout

```
discovery-coach/
└── android/discoverycoach/   Android library (pure Kotlin, no dependencies)
```
