# AdaptiveUI SDK

Instead of shipping one fixed interface, the SDK **generates the layout each
user engages with most**. Give it your items and one item composable; it
renders them as a grid, list, cards or carousel — and a per-user
Thompson-sampling bandit learns which style wins, from real engagement.

**Nothing to connect.** Learning is a tiny on-device JSON file of per-style
counts and average rewards. No backend, no experiments dashboard, no
identifiers.

## Quick start

```kotlin
// Application.onCreate():
AdaptiveUi.init(this)

// Anywhere you show a collection — one composable, one item slot:
AdaptiveCollection(id = "products", items = products) { product, style ->
    ProductTile(product, compact = style == LayoutStyle.LIST)
}
```

That's the whole integration. Each showing is a bandit trial:

- **Reward** = item taps + scroll depth, with a quick-abandon penalty
  (glanced 2 s, touched nothing → that layout bounced them).
- **Choice** = Thompson sampling — the best-performing style wins most
  showings, underexplored styles still get occasional tries, and the choice
  keeps adapting if the user's taste changes.
- Engagement is observed on the Initial pointer pass and **never consumes
  touch events** — your click handlers work untouched.

## Control & inspection

```kotlin
AdaptiveCollection(id = "products", items = products,
    allowed = setOf(LayoutStyle.GRID, LayoutStyle.LIST)) { p, s -> … }   // restrict styles

AdaptiveUi.force("products", LayoutStyle.GRID)   // pin (user preference / A-B)
AdaptiveUi.force("products", null)               // unpin, resume learning

AdaptiveUi.stats("products")        // per-style: showings + avg engagement
AdaptiveUi.explanation("products")  // "currently favors List. List: avg 61% over 24 showings; …"
AdaptiveUi.reset("products")        // forget one collection (or reset() for all)
```

Building a fully custom UI? Drive the engine directly:

```kotlin
val style = AdaptiveUi.beginSession("feed")
// render your own layout for `style` …
AdaptiveUi.recordItemClick("feed")
AdaptiveUi.recordScrollDepth("feed", 0.7)
AdaptiveUi.endSession("feed")
```

## Why a bandit and not an A/B test

An A/B test finds one global winner and stops. The bandit optimizes **per
user** (grandma gets the list, power users get the dense grid), needs no
traffic thresholds, and never stops adapting. `stats()` still gives you the
A/B-style readout whenever you want it.

## Limits (by design)

- Per-device learning: a fresh install starts exploring again (first few
  showings will vary — that's the exploration working).
- The reward is a proxy for "this layout works". For collections where taps
  aren't the goal (e.g. read-only dashboards), drive the manual API with your
  own reward via `recordItemClick`/`recordScrollDepth` semantics.

## Layout

```
adaptive-ui/
└── android/adaptiveui/   Android library (Kotlin + Compose, Material 3)
```
