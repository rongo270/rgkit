package dev.rgkit.perceivedspeed

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import android.view.MotionEvent
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** User-felt performance report for one screen. */
data class ScreenSpeed(
    val screen: String,
    /** Median time from screen shown to "settled and responsive" (ms). */
    val ttiMedianMs: Long,
    /** % of frames over 2 frame deadlines (visible stutter). */
    val jankPercent: Double,
    /** Frames over 700 ms — the user saw the app freeze. */
    val frozenFrames: Long,
    /** 95th percentile touch-down → next-frame latency (ms). */
    val inputLatencyP95Ms: Long,
    /** Main-thread stalls (>1 s unresponsive) seen on this screen. */
    val stalls: Int,
    val framesObserved: Long,
    /** 0–100: how fast this screen *feels*. 90+ great · <70 users notice · <50 users complain. */
    val feltScore: Int,
)

/** One detected main-thread stall. */
data class StallEvent(
    val screen: String?,
    val durationMs: Long,
    /** Top of the main-thread stack when the stall was caught — the likely culprit. */
    val topFrames: List<String>,
    val at: Long = System.currentTimeMillis(),
)

/**
 * PerceivedSpeed — measures performance the way users *feel* it, not the way
 * profilers report it:
 *
 *  - **Cold start** — process start → first drawn frame.
 *  - **Per-screen TTI** — screen shown → first stretch of smooth frames
 *    ("it settled and responds now").
 *  - **Jank & freezes** — dropped-frame % and >700 ms frozen frames, per screen.
 *  - **Input latency** — touch-down → next rendered frame, p95 per screen.
 *  - **Stalls** — main thread unresponsive >1 s, with the guilty stack top.
 *  - **Felt score 0–100 per screen** — one number to rank what to fix first.
 *
 * One `init()`. Zero configuration. Overhead is a frame callback and a 2.5 s
 * watchdog ping — negligible. All data stays on-device.
 */
object PerceivedSpeed {

    private const val TAG = "PerceivedSpeed"
    private const val FILE_NAME = "perceived_speed.json"
    private const val FRAME_BUDGET_MS = 16.7
    private const val JANK_MS = 33.4
    private const val FROZEN_MS = 700.0
    private const val STALL_MS = 1_000L

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "PerceivedSpeed-io") }
    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var loaded = false

    private class ScreenAgg {
        var frames = 0L
        var janky = 0L
        var frozen = 0L
        var stalls = 0
        val ttiSamples = ArrayDeque<Long>()      // last 20
        val latencySamples = ArrayDeque<Long>()  // last 50
    }

    private val screens = HashMap<String, ScreenAgg>()
    private val coldStarts = ArrayDeque<Long>()  // last 30
    private val stallLog = ArrayDeque<StallEvent>() // last 50

    // ---- live state ----
    @Volatile private var currentScreen: String? = null
    private var foreground = false
    private var startedActivities = 0
    private var frameLoopRunning = false
    private var lastFrameNanos = 0L
    private var resumedAtMs = 0L
    private var awaitingTti = false
    private var goodFrameRun = 0
    @Volatile private var pendingTouchDownMs = 0L
    private var coldStartRecorded = false

    // ------------------------------------------------------------------ init

    /** Call once, first line of Application.onCreate() for best cold-start data. */
    fun init(application: Application) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = application
        }
        io.execute { load(application) }
        startWatchdog()
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(lock) {
                    startedActivities++
                    foreground = true
                }
                ensureFrameLoop()
            }

            override fun onActivityResumed(activity: Activity) {
                onScreenShown(activity.javaClass.simpleName)
                attachTouchProbe(activity)
                if (!coldStartRecorded) recordColdStart()
            }

            override fun onActivityStopped(activity: Activity) {
                synchronized(lock) {
                    startedActivities--
                    if (startedActivities <= 0) {
                        startedActivities = 0
                        foreground = false
                    }
                }
                save()
            }

            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Report a logical screen change (Compose navigation destinations).
     * Activity changes are handled automatically.
     */
    fun screen(name: String) = onScreenShown(name)

    // --------------------------------------------------------------- reading

    /** Median cold start (process start → first frame), or null before any. */
    fun coldStartMillis(): Long? = synchronized(lock) {
        if (coldStarts.isEmpty()) null else coldStarts.toList().sorted()[coldStarts.size / 2]
    }

    /** Report for one screen, or null if never observed. */
    fun screenReport(name: String): ScreenSpeed? = synchronized(lock) {
        screens[name]?.let { buildReport(name, it) }
    }

    /** All screens, worst felt-score first — your fix-first list. */
    fun worstScreens(top: Int = 10): List<ScreenSpeed> = synchronized(lock) {
        screens.map { (name, agg) -> buildReport(name, agg) }
            .sortedBy { it.feltScore }
            .take(top)
    }

    /** Average felt score across screens weighted by frames observed. */
    fun overallScore(): Int = synchronized(lock) {
        var weighted = 0.0
        var weight = 0.0
        for ((name, agg) in screens) {
            if (agg.frames == 0L) continue
            val report = buildReport(name, agg)
            weighted += report.feltScore * agg.frames
            weight += agg.frames
        }
        if (weight == 0.0) 100 else (weighted / weight).toInt()
    }

    /** Recent main-thread stalls with their guilty stack tops, newest first. */
    fun recentStalls(limit: Int = 20): List<StallEvent> = synchronized(lock) {
        stallLog.toList().takeLast(limit).reversed()
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) { screens.clear(); coldStarts.clear(); stallLog.clear() }
        save()
    }

    // ---------------------------------------------------------- measurement

    private fun onScreenShown(name: String) {
        synchronized(lock) {
            currentScreen = name
            resumedAtMs = SystemClock.uptimeMillis()
            awaitingTti = true
            goodFrameRun = 0
            screens.getOrPut(name) { ScreenAgg() }
        }
    }

    private fun recordColdStart() {
        coldStartRecorded = true
        val startMs = try {
            Process.getStartElapsedRealtime()
        } catch (e: Throwable) { return }
        Choreographer.getInstance().postFrameCallback {
            val cold = SystemClock.elapsedRealtime() - startMs
            if (cold in 1..60_000) {
                synchronized(lock) {
                    coldStarts.addLast(cold)
                    while (coldStarts.size > 30) coldStarts.removeFirst()
                }
                save()
                Log.d(TAG, "Cold start: ${cold}ms")
            }
        }
    }

    private fun ensureFrameLoop() {
        main.post {
            if (frameLoopRunning) return@post
            frameLoopRunning = true
            lastFrameNanos = 0
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val isForeground = synchronized(lock) { foreground }
            if (!isForeground) {
                frameLoopRunning = false
                lastFrameNanos = 0
                return
            }
            val frameMs = frameTimeNanos / 1_000_000
            // Input latency: touch down → this frame.
            val down = pendingTouchDownMs
            if (down > 0 && frameMs >= down) {
                pendingTouchDownMs = 0
                addLatencySample(frameMs - down)
            }
            if (lastFrameNanos > 0) {
                val deltaMs = (frameTimeNanos - lastFrameNanos) / 1e6
                observeFrame(deltaMs, frameMs)
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /**
     * One observed frame: [deltaMs] since the previous frame, [nowMs] its
     * timestamp. Internal rather than private so the jank/TTI accounting can
     * be driven without a Choreographer.
     */
    internal fun observeFrame(deltaMs: Double, nowMs: Long) {
        synchronized(lock) {
            val agg = screens[currentScreen ?: return] ?: return
            agg.frames++
            if (deltaMs > JANK_MS) agg.janky++
            if (deltaMs > FROZEN_MS) agg.frozen++
            // TTI: first run of 5 consecutive on-budget frames after screen shown.
            if (awaitingTti) {
                if (deltaMs <= FRAME_BUDGET_MS * 2) {
                    goodFrameRun++
                    if (goodFrameRun >= 5) {
                        awaitingTti = false
                        val tti = max(0, nowMs - resumedAtMs)
                        if (tti < 30_000) {
                            agg.ttiSamples.addLast(tti)
                            while (agg.ttiSamples.size > 20) agg.ttiSamples.removeFirst()
                        }
                    }
                } else {
                    goodFrameRun = 0
                }
            }
        }
    }

    /** Touch-down → next frame, in ms. Internal for the same reason as [observeFrame]. */
    internal fun addLatencySample(latencyMs: Long) {
        if (latencyMs !in 0..5_000) return
        synchronized(lock) {
            val agg = screens[currentScreen ?: return] ?: return
            agg.latencySamples.addLast(latencyMs)
            while (agg.latencySamples.size > 50) agg.latencySamples.removeFirst()
        }
    }

    private fun attachTouchProbe(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is SpeedWindowCallback) return
        window.callback = SpeedWindowCallback(current)
    }

    private class SpeedWindowCallback(
        private val wrapped: Window.Callback,
    ) : Window.Callback by wrapped {
        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event?.actionMasked == MotionEvent.ACTION_DOWN) {
                pendingTouchDownMs = event.eventTime
            }
            return wrapped.dispatchTouchEvent(event)
        }
    }

    // --------------------------------------------------------------- watchdog

    private fun startWatchdog() {
        val thread = HandlerThread("PerceivedSpeed-watchdog").also { it.start() }
        val watchdog = Handler(thread.looper)
        val check = object : Runnable {
            override fun run() {
                val isForeground = synchronized(lock) { foreground }
                if (isForeground) {
                    val postedAt = SystemClock.uptimeMillis()
                    val done = java.util.concurrent.atomic.AtomicBoolean(false)
                    main.post { done.set(true) }
                    watchdog.postDelayed({
                        if (!done.get()) {
                            // Main thread is stuck. Grab the stack now, then wait it out.
                            val stack = Looper.getMainLooper().thread.stackTrace
                                .take(6).map { it.toString() }
                            var waited = 0L
                            while (!done.get() && waited < 8_000) {
                                SystemClock.sleep(100)
                                waited += 100
                            }
                            val duration = SystemClock.uptimeMillis() - postedAt
                            if (duration >= STALL_MS) recordStall(duration, stack)
                        }
                        watchdog.postDelayed(this, 2_500)
                    }, STALL_MS)
                } else {
                    watchdog.postDelayed(this, 2_500)
                }
            }
        }
        watchdog.postDelayed(check, 5_000)
    }

    /** A main-thread stall the watchdog caught. Internal so tests can stage one. */
    internal fun recordStall(durationMs: Long, stack: List<String>) {
        val event: StallEvent
        synchronized(lock) {
            val screen = currentScreen
            event = StallEvent(screen, durationMs, stack)
            stallLog.addLast(event)
            while (stallLog.size > 50) stallLog.removeFirst()
            screen?.let { screens[it]?.let { agg -> agg.stalls++ } }
        }
        save()
        Log.w(TAG, "Main thread stalled ${durationMs}ms on ${event.screen}: ${stack.firstOrNull()}")
    }

    // ----------------------------------------------------------------- score

    private fun buildReport(name: String, agg: ScreenAgg): ScreenSpeed {
        val jankPct = if (agg.frames == 0L) 0.0 else agg.janky * 100.0 / agg.frames
        val tti = percentileLong(agg.ttiSamples, 50)
        val p95 = percentileLong(agg.latencySamples, 95)

        var score = 100.0
        score -= min(35.0, jankPct * 1.2)                          // stutter
        score -= min(20.0, max(0.0, (p95 - 60.0)) / 8.0)           // laggy taps
        score -= min(20.0, max(0.0, (tti - 700.0)) / 150.0)        // slow to settle
        score -= min(25.0, agg.frozen * 4.0)                       // visible freezes
        score -= min(25.0, agg.stalls * 6.0)                       // hard stalls
        return ScreenSpeed(
            screen = name,
            ttiMedianMs = tti.toLong(),
            jankPercent = (jankPct * 10).toInt() / 10.0,
            frozenFrames = agg.frozen,
            inputLatencyP95Ms = p95.toLong(),
            stalls = agg.stalls,
            framesObserved = agg.frames,
            feltScore = max(0.0, score).toInt(),
        )
    }

    private fun percentileLong(samples: ArrayDeque<Long>, pct: Int): Double {
        if (samples.isEmpty()) return 0.0
        val sorted = samples.toList().sorted()
        val index = min(sorted.size - 1, (sorted.size * pct) / 100)
        return sorted[index].toDouble()
    }

    // ------------------------------------------------------------ persistence

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val root = JSONObject(file.readText())
                val s = root.optJSONObject("screens") ?: JSONObject()
                for (name in s.keys()) {
                    val o = s.getJSONObject(name)
                    val agg = ScreenAgg()
                    agg.frames = o.optLong("frames")
                    agg.janky = o.optLong("janky")
                    agg.frozen = o.optLong("frozen")
                    agg.stalls = o.optInt("stalls")
                    val tti = o.optJSONArray("tti") ?: JSONArray()
                    for (i in 0 until tti.length()) agg.ttiSamples.addLast(tti.getLong(i))
                    val lat = o.optJSONArray("latency") ?: JSONArray()
                    for (i in 0 until lat.length()) agg.latencySamples.addLast(lat.getLong(i))
                    screens[name] = agg
                }
                val cold = root.optJSONArray("coldStarts") ?: JSONArray()
                for (i in 0 until cold.length()) coldStarts.addLast(cold.getLong(i))
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
        val s = JSONObject()
        for ((name, agg) in screens) {
            val tti = JSONArray(); for (v in agg.ttiSamples) tti.put(v)
            val lat = JSONArray(); for (v in agg.latencySamples) lat.put(v)
            s.put(
                name,
                JSONObject()
                    .put("frames", agg.frames)
                    .put("janky", agg.janky)
                    .put("frozen", agg.frozen)
                    .put("stalls", agg.stalls)
                    .put("tti", tti)
                    .put("latency", lat)
            )
        }
        val cold = JSONArray(); for (v in coldStarts) cold.put(v)
        return JSONObject().put("screens", s).put("coldStarts", cold)
    }
}
