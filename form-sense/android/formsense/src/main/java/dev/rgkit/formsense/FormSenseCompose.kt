package dev.rgkit.formsense

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Compose wiring: one modifier per field + one call in onValueChange.
 *
 * ```kotlin
 * val form = FormSense.form("signup")
 *
 * OutlinedTextField(
 *     value = email,
 *     onValueChange = { email = it; form.field("email").textChanged(it.length) },
 *     modifier = Modifier.senseField(form, "email"),
 *     isError = emailError != null,
 * )
 * if (emailError != null) LaunchedEffect(emailError) { form.field("email").errorShown() }
 *
 * Button(onClick = { form.submitted(); submit() }) { Text("Create account") }
 * ```
 */
fun Modifier.senseField(form: FormSense.FormTracker, fieldId: String): Modifier =
    onFocusChanged { state ->
        if (state.isFocused) form.field(fieldId).focused()
        else form.field(fieldId).blurred()
    }
