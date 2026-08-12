# IntentEngine SDK

Understands **why** users do what they do — not just what they tapped.
Feed it raw interactions (or turn on zero-integration auto-capture) and it
emits high-level intent signals with a confidence score, the evidence, and a
concrete UX suggestion.

**Nothing to connect.** No backend, no API key, no permissions. All detection
runs on-device; only signal counts and the last ~200 signals are persisted —
never coordinates, never text content.

## Signals it detects

| Signal | What it means |
|---|---|
| `RAGE_TAP` | 3+ fast taps on the same spot — the UI feels broken or too slow |
| `REPEATED_TAP` | Same target re-tapped — the first tap's result wasn't clear |
| `DOUBLE_BACK` | Two quick back presses — user is lost or urgently leaving |
| `TYPE_DELETE_LOOP` | Typing, deleting, retyping — unclear expectations or failing validation |
| `FAST_SCROLL_SCAN` | Sustained very fast scrolling — hunting, not reading → they need search/filters |
| `DRAG_ATTEMPT` | Drag gesture on a non-draggable element — the UI implies it should move |
| `HESITATION` | Screen open with no interaction — reading carefully, or stuck |
| `ZIGZAG_NAVIGATION` | Bouncing A↔B between screens — can't find what they need |

Every signal carries `confidence` (0–1), `screen`, `target`, `evidence`
(human-readable trigger) — and each `IntentType` has a built-in `meaning`
and `suggestion` string you can surface in a debug UI.

## Quick start

```kotlin
// Application.onCreate() — one line, captures taps/scrolls/back automatically:
IntentEngine.autoCapture(this)

// React to intent in real time:
IntentEngine.addListener { signal ->
    when (signal.type) {
        IntentType.FAST_SCROLL_SCAN -> showSearchBar()
        IntentType.RAGE_TAP -> log("UI unresponsive at ${signal.screen}")
        IntentType.TYPE_DELETE_LOOP -> showInputHint(signal.target)
        else -> {}
    }
}

// Or read the aggregate picture:
IntentEngine.frustrationScore()      // 0–100, rolling last 10 minutes
IntentEngine.stats()                 // lifetime counts per signal type
IntentEngine.recentSignals()         // newest first, with evidence strings
```

Optional precision (names targets, enables per-element detection):

```kotlin
Button(onClick = {…}, modifier = Modifier.intentTarget("checkout_button")) { … }
Card(modifier = Modifier.intentDragProbe("product_card")) { … }        // detects failed drags
TextField(value = v, onValueChange = { v = it; IntentEngine.onTextChanged("email", it.length) })
IntentEngine.screenChanged("checkout")   // for Compose navigation destinations
```

Everything is also feedable manually (`onTap`, `onScroll`, `onBackPressed`,
`onDragAttempt`) if you want full control instead of auto-capture. Tune all
thresholds via `IntentEngine.config = IntentConfig(...)`.

## What you get

- Real-time `IntentSignal` stream (main-thread listeners).
- `frustrationScore()` — one number for "how bad is it right now", great for
  deciding when to offer help or when NOT to show an upsell.
- Per-day and lifetime counts per signal type, `exportJson()`, `reset()`.
- Cooldowns and confidence floors so you never get signal spam.

## Limits (by design)

- Auto-capture sees hardware/3-button back; gesture-nav back swipes are
  consumed by the system — call `onBackPressed()` from your back handling
  for full fidelity.
- Signals are heuristics with confidences, not ground truth. Treat them as
  strong hints, and tune `IntentConfig` to your UI (e.g. lower
  `fastScrollPxPerSec` for dense lists).

## Layout

```
intent-engine/
└── android/intentengine/   Android library (Kotlin; Compose helpers optional)
```
