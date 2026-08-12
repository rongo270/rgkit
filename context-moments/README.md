# ContextMoments SDK

Understands the user's **current context**, not just their events: driving,
walking, working, in a meeting, just woke up, watching TV, out and about.
One call returns the current *moment* with a confidence score and the full
signal breakdown — perfect for deciding **whether now is a good time** to
notify, upsell, ask for a review, or start a heavy flow.

**Nothing to connect.** No backend, no API key. All fusion runs on-device.
No location, no microphone, no calendar unless you explicitly opt in — and
even then only a loudness number / busy-or-not boolean is derived; raw data
is never stored.

## What it fuses

- **Motion** — duty-cycled accelerometer bursts (2.5 s out of every 30 s)
  classified into still / walking / running / in-vehicle via variance +
  step-frequency analysis. No Google Play Services needed.
- **Screen & device** — brightness, charging, battery, screen-on after a 4 h+
  dark gap (the "just woke up" detector).
- **Audio routes** — wired/Bluetooth headphones (workout, driving hints).
- **Ringer / Do-Not-Disturb** — meeting and focus hints.
- **Network** — wifi vs cellular (home/work vs out).
- **Time patterns** — hour, weekday, commute windows.
- **Opt-in: calendar** — is a busy event happening right now (READ_CALENDAR).
- **Opt-in: ambient loudness** — 0.5 s RMS level only (RECORD_AUDIO).

## Quick start

```kotlin
// Application.onCreate():
ContextMoments.init(this)

// Continuous (foreground use):
ContextMoments.start()
ContextMoments.addListener { snap ->
    when (snap.moment) {
        Moment.COMMUTING -> enableAudioMode()       // hands-free UX
        Moment.IN_MEETING -> suppressSounds()
        Moment.JUST_WOKE_UP -> showMorningSummary()
        else -> {}
    }
}

// One-shot from a background worker — "should I send this notification now?"
ContextMoments.sampleNow { snap ->
    if (snap.moment !in setOf(Moment.SLEEPING, Moment.IN_MEETING, Moment.COMMUTING)) {
        sendNotification()
    }
}

// Read anytime:
ContextMoments.current()            // last MomentSnapshot (moment, confidence, scores, signals)
ContextMoments.history()            // recent moment transitions
```

Opt-ins (request the runtime permission yourself first):

```kotlin
ContextMoments.config = MomentsConfig(enableCalendar = true, enableAmbientAudio = true)
```

## What you get

- `MomentSnapshot` — winning `Moment`, `confidence` 0–1, the full per-moment
  scoreboard, and every raw `SignalState` value that went into it (great for
  debugging why it decided what it decided).
- Stability hysteresis — listeners only fire after a moment wins 2 rounds,
  so you never flap between states.
- Transition history persisted on-device, `exportJson()`, `reset()`.
- Battery friendly: sensors are duty-cycled and run only while `start()`ed;
  `sampleNow` is a single ~3 s burst.

## Limits (by design)

- These are heuristics: expect ~"usually right", not ground truth. Always
  branch on `confidence` for consequential decisions.
- SLEEPING can only be observed from background sampling (the app isn't
  foreground while the user sleeps).
- Motion classification without Play Services is coarse; a phone on a car
  mount reads IN_VEHICLE well, a phone in a bag reads noisier.

## Layout

```
context-moments/
└── android/contextmoments/   Android library (pure Kotlin, no Compose)
```
