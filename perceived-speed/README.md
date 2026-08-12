# PerceivedSpeed SDK

Profilers report averages; users feel moments. PerceivedSpeed measures your
app **the way users experience it** and gives every screen one honest number:

| Metric | What the user felt |
|---|---|
| **Cold start** | process start → first drawn frame |
| **TTI per screen** | screen shown → "it settled and responds now" (first smooth-frame stretch) |
| **Jank %** | visible stutter while scrolling/animating |
| **Frozen frames** | frames over 700 ms — the app visibly hung |
| **Input latency p95** | finger down → next rendered frame |
| **Stalls** | main thread unresponsive >1 s, **with the guilty stack top captured** |
| **Felt score 0–100** | all of the above, per screen — your fix-first ranking |

**Nothing to connect.** One `init()`, zero configuration, on-device only.
Overhead is a frame callback and a 2.5 s watchdog ping — negligible.

## Quick start

```kotlin
// Application.onCreate() — first line, for accurate cold start:
PerceivedSpeed.init(this)

// Compose navigation? Name destinations (Activities are tracked automatically):
PerceivedSpeed.screen("checkout")
```

Read the results (debug menu, log dump, CI device test):

```kotlin
PerceivedSpeed.coldStartMillis()          // median, last 30 launches
PerceivedSpeed.overallScore()             // one number for the whole app

PerceivedSpeed.worstScreens(5).forEach { s ->
    Log.i("speed", "${s.screen}: felt ${s.feltScore}/100 — " +
        "TTI ${s.ttiMedianMs}ms, jank ${s.jankPercent}%, " +
        "tap latency p95 ${s.inputLatencyP95Ms}ms, ${s.stalls} stalls")
}

PerceivedSpeed.recentStalls().forEach { stall ->
    Log.w("speed", "froze ${stall.durationMs}ms on ${stall.screen}: ${stall.topFrames.first()}")
}
```

## Why "felt score" works

The score starts at 100 and subtracts what users actually notice: stutter
(jank %), laggy taps (p95 latency over 60 ms), slow settling (TTI over
700 ms), visible freezes, and hard stalls. 90+ feels great, below 70 users
notice, below 50 they complain in reviews. It's deliberately opinionated —
a ranking you can act on, not a wall of percentiles.

## Limits (by design)

- Watchdog stack capture shows the top of the main thread *when the stall was
  caught* — usually the culprit, occasionally an innocent bystander of one.
- TTI is heuristic ("5 consecutive on-budget frames"); async content that
  pops in later isn't counted against it.
- Per-device data. For fleet numbers, ship `exportJson()` through whatever
  reporting you already have.

## Layout

```
perceived-speed/
└── android/perceivedspeed/   Android library (pure Kotlin, no dependencies)
```
