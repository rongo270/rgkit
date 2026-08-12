package dev.rgkit.intentengine

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import android.widget.EditText
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Zero-integration capture: wraps each Activity's Window.Callback to observe
 * taps, scrolls and (hardware / 3-button) back presses without interfering
 * with dispatch. Gesture-navigation back swipes are consumed by the system
 * before apps see them — report those via [IntentEngine.onBackPressed] from
 * your back handling for full fidelity.
 */

/** One call in Application.onCreate(): init + auto-attach to every activity. */
fun IntentEngine.autoCapture(app: Application) {
    init(app)
    app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
        private var visible = 0
        override fun onActivityResumed(activity: Activity) {
            attach(activity)
            screenChanged(activity.javaClass.simpleName)
        }
        override fun onActivityStarted(activity: Activity) { visible++ }
        override fun onActivityStopped(activity: Activity) {
            visible--
            if (visible <= 0) appBackgrounded()
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    })
}

/** Attach capture to a single activity (alternative to [autoCapture]). Idempotent. */
fun IntentEngine.attach(activity: Activity) {
    val window = activity.window ?: return
    val current = window.callback ?: return
    if (current is IntentWindowCallback) return
    window.callback = IntentWindowCallback(current, activity)
}

/**
 * Watch an EditText so TYPE_DELETE_LOOP detection works with classic Views.
 * (Compose apps call IntentEngine.onTextChanged from onValueChange instead.)
 */
fun IntentEngine.watchTextLengths(editText: EditText, fieldId: String) {
    editText.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            IntentEngine.onTextChanged(fieldId, s?.length ?: 0)
        }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    })
}

private class IntentWindowCallback(
    private val wrapped: Window.Callback,
    activity: Activity,
) : Window.Callback by wrapped {

    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastY = 0f
    private var isScrolling = false

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) observe(event)
        return wrapped.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null &&
            event.keyCode == KeyEvent.KEYCODE_BACK &&
            event.action == KeyEvent.ACTION_UP &&
            !event.isCanceled
        ) {
            IntentEngine.onBackPressed()
        }
        return wrapped.dispatchKeyEvent(event)
    }

    private fun observe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                lastY = event.rawY
                downTime = event.eventTime
                isScrolling = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = event.rawY - lastY
                val moved = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                if (moved > touchSlop) isScrolling = true
                if (isScrolling && abs(dy) > 0f) IntentEngine.onScroll(dy)
                lastY = event.rawY
            }
            MotionEvent.ACTION_UP -> {
                val duration = event.eventTime - downTime
                val moved = hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble())
                if (!isScrolling && duration < 400 && moved <= touchSlop * 2) {
                    IntentEngine.onTap(event.rawX, event.rawY, target = null)
                }
            }
        }
    }
}
