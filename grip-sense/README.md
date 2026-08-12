# GripSense SDK

Knows **how the user holds their phone** — and which of your controls they
strain to reach.

- **Handedness** — right thumb / left thumb / two-handed, learned from tap
  position bias in the thumb arc, vertical-swipe curvature, and two-thumb
  alternation. No sensors, no permissions — just touches.
- **Reach heatmap** — a 12×20 grid of where taps actually land.
- **Strain analysis** — share of taps in the hard-to-reach zone, and the
  exact frequently-tapped regions that cause it (`hardestHotspots()`).
- **Debug overlay** — `GripHeatmapOverlay()` paints the heatmap + reach zones
  over any screen: bright red = frequently used AND painful.

**Nothing to connect.** Zero integration beyond `init()`; only normalized
coordinates and counts are stored, on-device.

## Quick start

```kotlin
// Application.onCreate() — that's the whole integration:
GripSense.init(this)
```

Then use what it learns:

```kotlin
// Put the FAB / bottom-sheet actions on the correct side:
val (hand, confidence) = GripSense.handedness()
if (hand == Handedness.LEFT_THUMB && confidence > 0.7) alignFabStart()

// Check a planned control position before shipping it:
GripSense.zoneFor(x = 0.1, y = 0.08)     // top-left → ReachZone.HARD one-handed

// Design review:
val report = GripSense.report()
Log.i("grip", report.advice)
// "Mostly one-handed (right thumb) — keep primary actions bottom-right.
//  31% of taps strain the thumb — move the hotspots lower."
report.hotspots.forEach { Log.i("grip", "hotspot at (${it.x}, ${it.y}): ${it.taps} taps, ${it.zone}") }

// Debug overlay (Compose):
Box {
    MyScreen()
    if (BuildConfig.DEBUG && showGrip) GripHeatmapOverlay()
}
```

## How the reach model works

A thumb pivot is modeled near the bottom holding-side corner (mirrored for
left thumb); distance from the pivot maps to `EASY` / `STRETCH` / `HARD` —
the classic one-handed reach arc. `stretchTapShare()` over ~25% means the
layout is fighting the user's grip.

## Limits (by design)

- Handedness needs ~30+ taps before giving a verdict (`UNKNOWN` until then),
  and reports a confidence you should respect.
- Swipe-curvature evidence is deliberately weak-weighted (it's noisy); tap
  bias does most of the work.
- The reach model assumes phone-sized portrait screens; on tablets treat
  zones as approximate.

## Layout

```
grip-sense/
└── android/gripsense/   Android library (Kotlin; Compose only for the overlay)
```
