# RhythmEngine SDK

Learns **when this user lives inside your app** — their personal weekly rhythm
— and turns it into decisions you can act on:

```kotlin
RhythmEngine.bestTimeToEngage()        // "their best hours in the next 24h" → smart notification timing
RhythmEngine.nextExpectedOpenAt()      // when they'll come back on their own (don't notify before that!)
RhythmEngine.expectedSessionMinutes()  // don't start a 10-min flow in a 90-second window
RhythmEngine.churnRisk()               // 0–1: how unusual the current silence is
RhythmEngine.isUnusuallyQuiet()        // gap > their own 90th percentile → win-back moment
RhythmEngine.engagementTrend()         // opens last 7 days vs the 7 before, in %
RhythmEngine.weeklyPattern()           // 7×24 heatmap matrix of their rhythm
```

**Nothing to connect.** One `init()` — opens and session lengths are tracked
automatically through lifecycle callbacks. Learning is a small on-device JSON
file with exponential decay, so the rhythm follows the user when their life
changes (new job, new timezone habits).

## Quick start

```kotlin
// Application.onCreate():
RhythmEngine.init(this)
```

That's the entire integration. Then, wherever you schedule engagement:

```kotlin
// In your notification worker:
val best = RhythmEngine.bestTimeToEngage(withinHours = 24).firstOrNull()
if (best != null && best.score > 0.5) scheduleNotificationAt(best.startAt)

// Only chase users who are ACTUALLY fading (not on their normal weekend break):
if (RhythmEngine.churnRisk() > 0.7) enqueueWinBackFlow()

// Adapt content depth to the time they have:
val minutes = RhythmEngine.expectedSessionMinutes() ?: 5.0
showFeed(longForm = minutes > 8)
```

## How it learns

- **Hour-of-week histogram (168 buckets)** of app opens, exponentially decayed
  (~1.5%/day) — recent behavior dominates, old habits fade out.
- **Gap distribution** (last 200 gaps between opens) — churn risk is the
  current gap's percentile against *this user's own* history, blended with the
  week-over-week trend. A weekly user isn't "churning" after 3 quiet days; a
  daily user is.
- **Session lengths per hour of day** (median, ±1 h pooling) — evening couch
  sessions vs morning coffee checks get different answers.
- Everything returns null/empty until there's enough data (≥10 opens) —
  honest silence instead of noise.

## Limits (by design)

- Timing quality grows with usage history; the first week is warm-up.
- Per-device learning (per user, really — which is exactly what timing needs).
- `bestTimeToEngage` says when they're receptive; pair with the
  ContextMoments SDK's `sampleNow` for "and not in a meeting right now".

## Layout

```
rhythm-engine/
└── android/rhythmengine/   Android library (pure Kotlin, no dependencies)
```
