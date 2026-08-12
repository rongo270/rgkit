# FlowLearning SDK

Traditional analytics tell you *"Button A clicked, Button B clicked."*
FlowLearning discovers **behavioral patterns** and tells you what to do about
them — an AI UX consultant that lives inside the app:

> **"92% visit 'price' before 'reviews'"** → *surface pricing higher*
> **"21% of sessions end at 'cart'"** → *the checkout entry is leaking*
> **"'product' → 'shipping' → straight back (44%)"** → *shipping info is missing on the product screen*
> **"'checkout' funnel leaks 57% at 'address' → 'payment'"** → *fix exactly that hop*

**Nothing to connect.** Events, mining and insights all stay on-device in one
small JSON file. No dashboard, no SQL, no analyst.

## Quick start

```kotlin
// Application.onCreate():
FlowLearning.init(this)

// One line per screen/action (stable snake_case names):
FlowLearning.track("product_detail")
FlowLearning.track("add_to_cart")

// Optional — declare funnels for per-step conversion:
FlowLearning.defineFunnel("checkout", listOf("cart", "address", "payment", "done"))

// Whenever you want the findings (debug menu, log dump, weekly toast to yourself):
FlowLearning.insights().forEach { insight ->
    Log.i("flow", "${insight.title}\n  ${insight.detail}\n  → ${insight.recommendation}")
}
```

Sessions are cut automatically (90 s gaps + app background). Immediate
duplicate steps are collapsed, so tracking inside recomposition is safe.

## What the miner finds

| Insight | Question it answers |
|---|---|
| `ORDERING` | What do users insist on seeing *first*? |
| `DROP_OFF` | Which screen do journeys die on? |
| `LOOP` | Where do users go in and bounce straight back (missed expectations)? |
| `FUNNEL_LEAK` | Which exact hop of a declared funnel loses the most users? |
| `COMMON_PATH` | What's the highway through the app (worth a shortcut)? |
| `ENTRY_POINT` | What's the app's *real* front door? |

Each `Insight` has a `title`, `detail` (the numbers), `recommendation`
(the advice), `strength` 0–1 and `sampleSessions`, sorted strongest first.

Lower-level reads for your own analysis:

```kotlin
FlowLearning.transitionsFrom("home")     // [(next step, probability)…]
FlowLearning.commonPaths(length = 4)     // frequent exact journeys
FlowLearning.sessionCount()
FlowLearning.exportJson()
```

## Limits (by design)

- Per-device: these are *this user's* patterns (or your whole beta fleet's,
  one device at a time via `exportJson()`). Great for dogfooding and finding
  structural UX issues; not a population dashboard.
- Insights need support: nothing is reported under ~12 supporting sessions
  (`FlowConfig.minSupport`), so early output is empty rather than wrong.
- Keeps the last 400 sessions (`maxSessions`) — mining stays instant.

## Layout

```
flow-learning/
└── android/flowlearning/   Android library (pure Kotlin, no dependencies)
```
