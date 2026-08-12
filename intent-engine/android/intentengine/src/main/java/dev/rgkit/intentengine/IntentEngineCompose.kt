package dev.rgkit.intentengine

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Compose helpers. Both observe on the Initial pass and never consume events,
 * so they can wrap anything (buttons, cards, whole screens) without changing
 * behavior.
 */

/**
 * Names this element for the engine: taps on it are reported with [target],
 * which enables REPEATED_TAP detection and gives RAGE_TAP signals a target name.
 *
 * ```kotlin
 * Button(onClick = { … }, modifier = Modifier.intentTarget("checkout_button")) { … }
 * ```
 */
fun Modifier.intentTarget(target: String): Modifier = pointerInput(target) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        IntentEngine.onTap(down.position.x, down.position.y, target)
        // Swallow the rest of the gesture stream without consuming anything.
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.all { !it.pressed }) break
        }
    }
}

/**
 * Detects drag attempts on an element that is NOT draggable — the user pressed
 * and pulled it beyond the touch slop, which means the UI looks draggable.
 * Put it on static cards, list rows, or images users might try to reorder/swipe.
 */
fun Modifier.intentDragProbe(target: String): Modifier = pointerInput(target) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var maxDistance = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            val d = (change.position - down.position).getDistance()
            if (d > maxDistance) maxDistance = d
            if (!change.pressed) {
                if (maxDistance > viewConfiguration.touchSlop * 4) {
                    IntentEngine.onDragAttempt(target)
                }
                break
            }
        }
    }
}
