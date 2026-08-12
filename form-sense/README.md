# FormSense SDK

Forms are where conversions die, and standard analytics can't see why.
FormSense measures **per-field friction** and tells you exactly what to fix:

> `phone` — friction 78/100: *users give up here; heavy retyping — add the
> right keyboard type or input mask*
> `email` — friction 41/100: *users keep returning to it — validation
> surprises shown too late*

**Nothing to connect.** All aggregates live on-device. No keystrokes and no
text content are ever recorded — only lengths, counts and times.

## What it measures, per field

- **Dwell** — average focused time per visit
- **Correction ratio** — deleted chars ÷ typed chars (0.15 normal, 0.4+ struggle)
- **Refocus** — how often users come back to the field per attempt
- **Errors** — validation errors shown
- **Abandon share** — which field users were on when they gave up ← the killer metric

…rolled into a 0–100 friction score with a rule-based suggestion, plus
form-level starts / submits / conversion % / median completion time.

## Quick start (Compose)

```kotlin
// Application.onCreate():
FormSense.init(this)          // also auto-abandons live forms on app background

// In your form:
val form = FormSense.form("signup")

OutlinedTextField(
    value = email,
    onValueChange = { email = it; form.field("email").textChanged(it.length) },
    modifier = Modifier.senseField(form, "email"),
)

Button(onClick = { form.submitted(); doSignup() }) { Text("Create account") }
// errorShown() when validation fails; abandoned() happens automatically.
```

Classic Views work the same via `focused()` / `blurred()` / `textChanged(len)`
from your own listeners.

## Reading the results

```kotlin
val report = FormSense.report("signup") ?: return
Log.i("forms", "signup: ${report.conversionPercent}% conversion, " +
    "median ${report.medianCompletionMs / 1000}s")
report.fields.forEach { f ->
    Log.i("forms", "${f.fieldId}: friction ${f.frictionScore}/100 — ${f.suggestion}")
}

FormSense.reports()      // every form, lowest conversion first
FormSense.exportJson()
```

## Limits (by design)

- Attribution is heuristic: the abandon lands on the last touched field —
  usually right, occasionally the victim of the field before it.
- Per-device aggregates; export JSON if you want fleet-level numbers.

## Layout

```
form-sense/
└── android/formsense/   Android library (Kotlin; Compose only for the one modifier)
```
