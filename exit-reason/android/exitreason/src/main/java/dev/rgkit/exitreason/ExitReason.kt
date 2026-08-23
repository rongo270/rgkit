package dev.rgkit.exitreason

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** The engine's best estimate of why the user left. */
enum class ExitReasonType(val label: String) {
    TASK_COMPLETED("Task completed"),
    RAGE_QUIT("Rage quit"),
    QUICK_BOUNCE("Opened and left immediately"),
    LOST_INTEREST("Drifted off / got bored"),
    INCOMING_CALL("Interrupted by a phone call"),
    SCREEN_OFF("Screen turned off / pocketed"),
    BATTERY_LOW("Battery critically low"),
    SWITCHED_AWAY("Switched to another app / notification"),
    CRASH("App crashed or ANRed"),
    KILLED_BY_SYSTEM("Killed by the system (low memory)"),
    UNKNOWN("Unknown"),
}

/** One analyzed exit. `details` holds the raw evidence used for the verdict. */
data class ExitReport(
    val reason: ExitReasonType,
    /** 0–1 — how strongly the evidence points at this reason. */
    val confidence: Double,
    val at: Long,
    val sessionMs: Long,
    val lastScreen: String?,
    val interactionCount: Int,
    val details: Map<String, String>,
)

data class ExitConfig(
    /** Session shorter than this with almost no interaction ⇒ QUICK_BOUNCE. */
    val quickBounceMs: Long = 15_000,
    /** Idle for longer than this right before leaving ⇒ LOST_INTEREST. */
    val boredIdleMs: Long = 45_000,
    /** A markTaskCompleted() within this window before exit ⇒ TASK_COMPLETED. */
    val taskCompletedWindowMs: Long = 30_000,
    /** Battery at or below this (not charging) counts as critically low. */
    val lowBatteryPercent: Int = 10,
    /** Wait this long after background before deciding (lets call audio settle). */
    val settleMs: Long = 1_200,
)

/**
 * ExitReason — instead of only knowing `onPause()`, know *why* the user left.
 *
 * One `init()` in the Application and every background transition produces an
 * [ExitReport]: incoming call, rage quit, task completed, quick bounce, lost
 * interest, screen off, battery, app switch — plus CRASH / KILLED_BY_SYSTEM
 * detected on the next launch via [ApplicationExitInfo].
 *
 * Everything is heuristic, on-device, and persisted locally.
 */
object ExitReason {

    private const val TAG = "ExitReason"
    private const val FILE_NAME = "exit_reason.json"
    private const val HISTORY_LIMIT = 300

    var config: ExitConfig = ExitConfig()

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ExitReason-io") }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(ExitReport) -> Unit>()

    private var appContext: Context? = null
    private var loaded = false

    // ---- session state ----
    private var startedActivities = 0
    private var foregroundSince = 0L
    private var lastInteractionAt = 0L
    private var interactionCount = 0
    private var lastScreen: String? = null
    private var taskCompletedAt = 0L
    private var frustrationAt = 0L
    private val recentTaps = ArrayDeque<Long>()
    private val recentBacks = ArrayDeque<Long>()
    private var pendingBackground: Runnable? = null
    private var lastCleanExitAt = 0L

    private val history = ArrayDeque<ExitReport>()

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). */
    fun init(application: Application) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = application
        }
        io.execute {
            load(application)
            checkPreviousProcessDeath(application)
        }
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(lock) {
                    pendingBackground?.let { main.removeCallbacks(it); pendingBackground = null }
                    if (startedActivities == 0) beginSession()
                    startedActivities++
                }
            }

            override fun onActivityResumed(activity: Activity) {
                synchronized(lock) { lastScreen = activity.javaClass.simpleName }
                attachTouchTracking(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                synchronized(lock) {
                    startedActivities--
                    if (startedActivities <= 0) {
                        startedActivities = 0
                        // Debounce: config changes stop+start within a few 100 ms.
                        val r = Runnable { onBackground() }
                        pendingBackground = r
                        main.postDelayed(r, 700)
                    }
                }
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // -------------------------------------------------------------- host API

    /** Call at natural completion points (order placed, message sent, level done). */
    fun markTaskCompleted() {
        synchronized(lock) { taskCompletedAt = System.currentTimeMillis() }
    }

    /** Optional: feed frustration detected elsewhere (e.g. IntentEngine RAGE_TAP). */
    fun reportFrustration() {
        synchronized(lock) { frustrationAt = System.currentTimeMillis() }
    }

    /** Fired on the main thread when an exit has been analyzed (and for crash
     *  reports discovered on the next launch). */
    fun addListener(listener: (ExitReport) -> Unit): (ExitReport) -> Unit {
        listeners.add(listener); return listener
    }

    fun removeListener(listener: (ExitReport) -> Unit) { listeners.remove(listener) }

    /** The most recent analyzed exit, if any. */
    fun lastExit(): ExitReport? = synchronized(lock) { history.lastOrNull() }

    /** Recent exits, newest first. */
    fun history(limit: Int = 50): List<ExitReport> =
        synchronized(lock) { history.toList().takeLast(limit).reversed() }

    /** Lifetime distribution — which reasons dominate for this user/device. */
    fun distribution(): Map<ExitReasonType, Int> = synchronized(lock) {
        val map = HashMap<ExitReasonType, Int>()
        for (r in history) map[r.reason] = (map[r.reason] ?: 0) + 1
        map
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) { history.clear() }
        save()
    }

    // ------------------------------------------------------------- lifecycle

    private fun beginSession() {
        foregroundSince = System.currentTimeMillis()
        lastInteractionAt = foregroundSince
        interactionCount = 0
        taskCompletedAt = 0L
        frustrationAt = 0L
        recentTaps.clear()
        recentBacks.clear()
    }

    private fun onBackground() {
        val exitAt = System.currentTimeMillis()
        // Snapshot session facts now; decide after things settle (call audio,
        // screen state need a beat to become observable).
        val sessionMs: Long
        val idleMs: Long
        val interactions: Int
        val screen: String?
        val completedRecently: Boolean
        val frustratedRecently: Boolean
        val rageTaps: Int
        val rageBacks: Int
        synchronized(lock) {
            sessionMs = exitAt - foregroundSince
            idleMs = exitAt - lastInteractionAt
            interactions = interactionCount
            screen = lastScreen
            completedRecently = taskCompletedAt > 0 &&
                exitAt - taskCompletedAt <= config.taskCompletedWindowMs
            frustratedRecently = frustrationAt > 0 && exitAt - frustrationAt <= 10_000
            rageTaps = recentTaps.count { exitAt - it <= 2_500 }
            rageBacks = recentBacks.count { exitAt - it <= 2_000 }
        }
        main.postDelayed({
            io.execute {
                val report = decide(
                    exitAt, sessionMs, idleMs, interactions, screen,
                    completedRecently, frustratedRecently, rageTaps, rageBacks
                )
                synchronized(lock) {
                    history.addLast(report)
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                    lastCleanExitAt = exitAt
                }
                save()
                main.post { for (l in listeners) runCatching { l(report) } }
            }
        }, config.settleMs)
    }

    /**
     * Pure given the collected session facts — with no [appContext] the
     * environment probes below fall back to their defaults, which is what
     * lets unit tests drive the precedence rules directly.
     */
    internal fun decide(
        exitAt: Long,
        sessionMs: Long,
        idleMs: Long,
        interactions: Int,
        screen: String?,
        completedRecently: Boolean,
        frustratedRecently: Boolean,
        rageTaps: Int,
        rageBacks: Int,
    ): ExitReport {
        val context = appContext
        val details = LinkedHashMap<String, String>()
        details["session_s"] = (sessionMs / 1000).toString()
        details["idle_before_exit_s"] = (idleMs / 1000).toString()
        details["interactions"] = interactions.toString()

        // Environment at (just after) exit.
        var callState = "none"
        var screenOn = true
        var batteryPct = -1
        var charging = false
        var powerSave = false
        if (context != null) {
            try {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                callState = when (audio?.mode) {
                    AudioManager.MODE_RINGTONE -> "ringing"
                    AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> "in_call"
                    else -> "none"
                }
            } catch (e: Exception) { /* keep default */ }
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                screenOn = pm?.isInteractive != false
                powerSave = pm?.isPowerSaveMode == true
            } catch (e: Exception) { /* keep default */ }
            try {
                val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                if (intent != null) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) batteryPct = level * 100 / scale
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            } catch (e: Exception) { /* keep default */ }
        }
        details["call_state"] = callState
        details["screen_on"] = screenOn.toString()
        if (batteryPct >= 0) details["battery_pct"] = batteryPct.toString()

        fun report(reason: ExitReasonType, confidence: Double) = ExitReport(
            reason, (confidence * 100).toInt() / 100.0, exitAt, sessionMs, screen,
            interactions, details
        )

        // Ordered by evidence strength.
        if (callState != "none") {
            details["why"] = "phone audio went into $callState around exit"
            return report(ExitReasonType.INCOMING_CALL, if (callState == "ringing") 0.9 else 0.8)
        }
        if (completedRecently) {
            details["why"] = "markTaskCompleted() shortly before exit"
            return report(ExitReasonType.TASK_COMPLETED, 0.9)
        }
        if ((rageTaps >= 4 || rageBacks >= 2 || frustratedRecently) && sessionMs < 4 * 60_000) {
            details["why"] = "burst before exit: $rageTaps taps / $rageBacks backs in <2.5s" +
                if (frustratedRecently) " + external frustration signal" else ""
            return report(ExitReasonType.RAGE_QUIT, if (rageTaps >= 6 || rageBacks >= 3) 0.85 else 0.7)
        }
        if (batteryPct in 0..config.lowBatteryPercent && !charging) {
            details["why"] = "battery at $batteryPct%" + if (powerSave) " with power saver on" else ""
            return report(ExitReasonType.BATTERY_LOW, if (powerSave) 0.7 else 0.55)
        }
        if (!screenOn) {
            details["why"] = "screen was off shortly after exit"
            return report(ExitReasonType.SCREEN_OFF, 0.75)
        }
        if (sessionMs <= config.quickBounceMs && interactions <= 2) {
            details["why"] = "left after ${sessionMs / 1000}s with $interactions interactions"
            return report(ExitReasonType.QUICK_BOUNCE, 0.8)
        }
        if (idleMs >= config.boredIdleMs) {
            details["why"] = "idle for ${idleMs / 1000}s before leaving"
            return report(ExitReasonType.LOST_INTEREST, 0.6)
        }
        details["why"] = "was actively using the app, then left — likely a notification or app switch"
        return report(ExitReasonType.SWITCHED_AWAY, 0.5)
    }

    // -------------------------------------------- crash / system-kill on boot

    private fun checkPreviousProcessDeath(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 3)
            val lastKnown = synchronized(lock) { history.lastOrNull()?.at ?: 0L }
            for (info in exits) {
                if (info.timestamp <= lastKnown) continue
                val reason = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitReasonType.CRASH
                    ApplicationExitInfo.REASON_ANR -> ExitReasonType.CRASH
                    ApplicationExitInfo.REASON_LOW_MEMORY -> ExitReasonType.KILLED_BY_SYSTEM
                    else -> continue
                }
                val report = ExitReport(
                    reason = reason,
                    confidence = 0.95,
                    at = info.timestamp,
                    sessionMs = -1,
                    lastScreen = null,
                    interactionCount = -1,
                    details = linkedMapOf(
                        "why" to "ApplicationExitInfo: ${info.description ?: info.reason.toString()}"
                    ),
                )
                synchronized(lock) {
                    history.addLast(report)
                    while (history.size > HISTORY_LIMIT) history.removeFirst()
                }
                main.post { for (l in listeners) runCatching { l(report) } }
            }
            save()
        } catch (e: Exception) {
            Log.w(TAG, "Exit info check failed", e)
        }
    }

    // -------------------------------------------------------- touch tracking

    private fun attachTouchTracking(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is ExitWindowCallback) return
        window.callback = ExitWindowCallback(current)
    }

    private class ExitWindowCallback(
        private val wrapped: Window.Callback,
    ) : Window.Callback by wrapped {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event?.actionMasked == MotionEvent.ACTION_UP) {
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    lastInteractionAt = now
                    interactionCount++
                    recentTaps.addLast(now)
                    while (recentTaps.size > 16) recentTaps.removeFirst()
                }
            }
            return wrapped.dispatchTouchEvent(event)
        }

        override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
            if (event != null && event.keyCode == KeyEvent.KEYCODE_BACK &&
                event.action == KeyEvent.ACTION_UP && !event.isCanceled
            ) {
                val now = System.currentTimeMillis()
                synchronized(lock) {
                    recentBacks.addLast(now)
                    while (recentBacks.size > 8) recentBacks.removeFirst()
                }
            }
            return wrapped.dispatchKeyEvent(event)
        }
    }

    // ------------------------------------------------------------ persistence

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val arr = JSONObject(file.readText()).optJSONArray("history") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    runCatching {
                        val details = LinkedHashMap<String, String>()
                        val d = o.optJSONObject("details") ?: JSONObject()
                        for (k in d.keys()) details[k] = d.getString(k)
                        history.addLast(
                            ExitReport(
                                reason = ExitReasonType.valueOf(o.getString("reason")),
                                confidence = o.getDouble("confidence"),
                                at = o.getLong("at"),
                                sessionMs = o.getLong("sessionMs"),
                                lastScreen = o.optString("lastScreen").ifEmpty { null },
                                interactionCount = o.optInt("interactions", -1),
                                details = details,
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load state", e)
            }
        }
    }

    private fun save() {
        val context = appContext ?: return
        val json = synchronized(lock) { toJson().toString() }
        io.execute {
            try {
                File(context.filesDir, FILE_NAME).writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save state", e)
            }
        }
    }

    private fun toJson(): JSONObject {
        val arr = JSONArray()
        for (r in history) {
            val d = JSONObject()
            for ((k, v) in r.details) d.put(k, v)
            arr.put(
                JSONObject()
                    .put("reason", r.reason.name)
                    .put("confidence", r.confidence)
                    .put("at", r.at)
                    .put("sessionMs", r.sessionMs)
                    .put("lastScreen", r.lastScreen ?: "")
                    .put("interactions", r.interactionCount)
                    .put("details", d)
            )
        }
        return JSONObject().put("history", arr)
    }
}
