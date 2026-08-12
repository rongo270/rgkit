# FormSense (Android)

Android library module. Kotlin, minSdk 24. Compose is used only for the
optional `Modifier.senseField` helper — the core tracker is plain Kotlin.

## Add to an app

**Option A — Maven Central (recommended):**

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.github.rongo270:form-sense:0.1.0")
}
```

**Option B — include the module from a local clone:**

```kotlin
// settings.gradle.kts
include(":formsense")
project(":formsense").projectDir =
    file("/path/to/rgkit/form-sense/android/formsense")

// app/build.gradle.kts
dependencies {
    implementation(project(":formsense"))
}
```

**Option C — copy the files** (`FormSense.kt`, plus `FormSenseCompose.kt` if
the app uses Compose).

> Kotlin version note: the module applies `org.jetbrains.kotlin.plugin.compose`
> (Kotlin 2.x). On Kotlin 1.9.x remove that plugin line and set
> `composeOptions { kotlinCompilerExtensionVersion = "<your version>" }`,
> or copy only `FormSense.kt`.

## Use

```kotlin
// Application.onCreate():
FormSense.init(this)

val form = FormSense.form("signup")

// Per field — Compose:
OutlinedTextField(
    value = email,
    onValueChange = { email = it; form.field("email").textChanged(it.length) },
    modifier = Modifier.senseField(form, "email"),
)
// Per field — Views: call focused()/blurred()/textChanged(len)/errorShown() yourself.

form.field("email").errorShown()   // when validation fails
form.submitted()                   // on success
form.abandoned()                   // explicit give-up (auto on app background)
form.discard()                     // don't count this attempt

// Read:
FormSense.report("signup")   // FormReport: conversion %, median time, fields worst-first
FormSense.reports()          // all forms, lowest conversion first
FormSense.exportJson()
FormSense.reset()
```

Storage: `form_sense.json` in the app's private files directory
(counts and durations only — never text).
