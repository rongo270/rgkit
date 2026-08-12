# ScreenshotIQ SDK

Whenever the user takes a screenshot, the SDK identifies **what was captured**
— receipt, product, error message, chat, ticket, map, code, document, social
post — extracts useful entities (total amount, date, booking code, URL, error
line) and hands your app ready-made suggested actions:

> user screenshots a receipt → *"Save to expenses?"*
> user screenshots an error → *"Report this error?"*
> user screenshots a ticket → *"Add to calendar?"*

**Nothing to connect.** OCR is on-device (bundled ML Kit model, no API key,
no network). Recognized text is delivered to your listener once and never
written to disk — only kind + confidence + timestamp are persisted for stats.

## How detection works

| Path | Android version | Needs |
|---|---|---|
| `ScreenCaptureCallback` | 14+ | nothing (normal `DETECT_SCREEN_CAPTURE` permission, auto-merged from the library manifest) |
| MediaStore observer | all | app holds `READ_MEDIA_IMAGES` (33+) or `READ_EXTERNAL_STORAGE` |

With the media permission you get full analysis; without it (on 14+) you still
get "a screenshot just happened" events — useful on its own ("Want to share
feedback?").

## Quick start

```kotlin
// Application.onCreate():
ScreenshotIQ.init(this)

ScreenshotIQ.addListener { insight ->
    when (insight.kind) {
        ScreenshotKind.RECEIPT -> offerExpenseSave(insight.entities["total"])
        ScreenshotKind.ERROR -> offerBugReport(insight.entities["error_line"])
        ScreenshotKind.TICKET -> offerCalendarAdd(insight.entities["date"])
        else -> insight.suggestions.firstOrNull()?.let { showChip(it.label) }
    }
}
```

Also works on any image, not just screenshots (e.g. a share-target):

```kotlin
ScreenshotIQ.analyze(imageUri) { insight -> … }
```

## What you get

- `ScreenshotInsight` — `kind`, `confidence` (0–1), `analyzed` flag,
  `entities` map (`total`, `date`, `code`, `url`, `error_line`),
  a 400-char `textSample`, and per-kind `suggestions`.
- Classifier: keyword/structure scoring over the OCR output — money patterns,
  timestamps, short-line ratios (chat bubbles), code-token density, street
  suffixes, booking codes.
- Lifetime `stats()` per kind, `recent()` detections, `exportJson()`, `reset()`.
- Double-fire protection when both detection paths trigger for one screenshot.

## Limits (by design)

- Classification is heuristic OCR analysis: excellent on text-heavy captures
  (receipts, errors, chats), weaker on pure images. `confidence` tells you.
- On Android 13 and below there is no capture callback, so detection requires
  the media-read permission.
- The OCR model adds ~4 MB to the APK (bundled = works offline, no key).

## Layout

```
screenshot-intelligence/
└── android/screenshotiq/   Android library (Kotlin + bundled ML Kit OCR)
```
