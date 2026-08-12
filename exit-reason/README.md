# ExitReason SDK

Instead of only knowing `onPause()`, know **why the user left**:

| Reason | Detected from |
|---|---|
| `INCOMING_CALL` | phone audio mode went to ringing / in-call at exit |
| `TASK_COMPLETED` | your `markTaskCompleted()` shortly before exit |
| `RAGE_QUIT` | tap/back burst right before leaving (or an external frustration signal) |
| `QUICK_BOUNCE` | opened and left within seconds, almost no interaction |
| `LOST_INTEREST` | long idle before leaving — they drifted off |
| `SCREEN_OFF` | screen was off just after exit (pocketed / fell asleep / power button) |
| `BATTERY_LOW` | battery critical and not charging |
| `SWITCHED_AWAY` | active use, then instant exit — notification or app switch |
| `CRASH` / `KILLED_BY_SYSTEM` | `ApplicationExitInfo` on next launch (Android 11+) |

Every `ExitReport` carries a confidence and a `details` map with the raw
evidence ("idle_before_exit_s", "battery_pct", "why"), so you can audit each
verdict.

**Nothing to connect.** No backend, no API key, no permissions. One `init()`
— lifecycle, touch and back tracking are wired automatically.

## Quick start

```kotlin
// Application.onCreate():
ExitReason.init(this)

// At natural completion points:
ExitReason.markTaskCompleted()      // order placed, message sent, workout logged…

// React (fires shortly after each exit, and on launch for crash reports):
ExitReason.addListener { report ->
    when (report.reason) {
        ExitReasonType.RAGE_QUIT -> flagScreenForReview(report.lastScreen)
        ExitReasonType.QUICK_BOUNCE -> maybeFixNotificationDeepLink()
        ExitReasonType.CRASH -> showApologyOnNextOpen()
        else -> {}
    }
}

// Aggregate view — what usually ends sessions for this user:
ExitReason.distribution()    // Map<ExitReasonType, Int>
ExitReason.lastExit()
ExitReason.history(50)
```

Optionally feed it richer frustration data (pairs perfectly with the
IntentEngine SDK):

```kotlin
IntentEngine.addListener { s ->
    if (s.type == IntentType.RAGE_TAP) ExitReason.reportFrustration()
}
```

## What you get

- `ExitReport` per exit: reason, confidence 0–1, session length, last screen,
  interaction count, evidence details.
- Crash/ANR/system-kill attribution on next launch (Android 11+), stitched
  into the same history.
- `distribution()` for "what usually ends sessions" — a retention goldmine
  (lots of `RAGE_QUIT` on one screen = fix that screen; lots of
  `QUICK_BOUNCE` = your notifications over-promise).
- History (last 300) persisted on-device, `exportJson()`, `reset()`.

## Limits (by design)

- Verdicts are heuristics ranked by evidence strength; `SWITCHED_AWAY` is the
  honest fallback when nothing stronger is observable (Android does not tell
  apps which app came next, and shouldn't).
- Call detection reads the audio mode only — no phone-state permission, so a
  ringing phone that the user ignores after backgrounding may be missed.

## Layout

```
exit-reason/
└── android/exitreason/   Android library (pure Kotlin, no dependencies)
```
