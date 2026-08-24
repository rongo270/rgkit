package dev.rgkit.gripsense

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Window
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** How the user most likely holds the phone. */
enum class Handedness { RIGHT_THUMB, LEFT_THUMB, TWO_HANDED, UNKNOWN }

/** Thumb-reach comfort for a point on screen (one-handed model). */
enum class ReachZone { EASY, STRETCH, HARD }

/** A screen region users tap a lot but strain to reach. */
data class Hotspot(
    /** Cell center, normalized 0–1. */
    val x: Double,
    val y: Double,
    val taps: Int,
    val zone: ReachZone,
)

/** The full ergonomic picture. */
data class GripReport(
    val handedness: Handedness,
    /** 0–1 confidence in the handedness verdict. */
    val confidence: Double,
    /** Share of all taps that land in the HARD zone. 0.25+ = real strain. */
    val stretchTapShare: Double,
    /** Frequently-tapped hard-to-reach regions — your "move this control" list. */
    val hotspots: List<Hotspot>,
    val totalTaps: Int,
    val advice: String,
)

/**
 * GripSense — knows how the user *holds* their phone.
 *
 * Watches touches (auto, zero integration) to learn:
 *  - **Handedness** — right thumb / left thumb / two-handed, from tap-position
 *    bias in the thumb arc, swipe curvature, and two-thumb alternation.
 *  - **Reach heatmap** — a 12×20 grid of where taps actually land.
 *  - **Strain** — how many taps land in the hard-to-reach zone, and *which*
 *    frequently-used regions are the problem.
 *
 * Use it to place FABs/nav on the correct side, trigger one-hand mode, or as a
 * design review tool via the [GripHeatmapOverlay] debug composable.
 * All data is normalized coordinates and counts — on-device only.
 */
object GripSense {

    private const val TAG = "GripSense"
    private const val FILE_NAME = "grip_sense.json"
    internal const val COLS = 12
    internal const val ROWS = 20

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "GripSense-io") }

    private var appContext: Context? = null
    private var loaded = false

    // ---- learned state ----
    private val grid = Array(ROWS) { IntArray(COLS) }
    private var leftVotes = 0.0
    private var rightVotes = 0.0
    private var twoHandVotes = 0.0
    private var totalTaps = 0

    // ---- live gesture state ----
    private var lastTapAt = 0L
    private var lastTapX = -1.0

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). Touch capture attaches automatically. */
    fun init(application: Application) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = application
        }
        io.execute { load(application) }
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = attach(activity)
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) { save() }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Attach to a single activity manually (idempotent; init() does this for you). */
    fun attach(activity: Activity) {
        val window = activity.window ?: return
        val current = window.callback ?: return
        if (current is GripWindowCallback) return
        window.callback = GripWindowCallback(current, activity)
    }

    // --------------------------------------------------------------- reading

    /** Current handedness verdict with confidence. */
    fun handedness(): Pair<Handedness, Double> = synchronized(lock) { verdictLocked() }

    /** Reach comfort of a normalized point for the current handedness. */
    fun zoneFor(x: Double, y: Double): ReachZone {
        val (hand, _) = handedness()
        return zoneFor(x, y, hand)
    }

    /** Reach comfort of a normalized point for a given grip. */
    fun zoneFor(x: Double, y: Double, hand: Handedness): ReachZone {
        // Thumb pivot near the bottom corner; distances in "screen heights",
        // x compressed because phones are tall. Mirrors for left thumb.
        val pivotX = when (hand) {
            Handedness.LEFT_THUMB -> 0.12
            else -> 0.88 // right-thumb model is also the sane default for UNKNOWN/TWO_HANDED
        }
        val dx = (x - pivotX) * 0.62 // aspect compensation (unit square vs tall screen)
        val dy = y - 1.04
        val d = hypot(dx, dy)
        return when {
            d < 0.42 -> ReachZone.EASY
            d < 0.66 -> ReachZone.STRETCH
            else -> ReachZone.HARD
        }
    }

    /** Share of all recorded taps that landed in the HARD zone. */
    fun stretchTapShare(): Double = synchronized(lock) {
        if (totalTaps == 0) return 0.0
        val (hand, _) = verdictLocked()
        var hard = 0L
        var all = 0L
        forEachCell { row, col, count ->
            all += count
            if (zoneFor(cellX(col), cellY(row), hand) == ReachZone.HARD) hard += count
        }
        if (all == 0L) 0.0 else (hard * 100 / all) / 100.0
    }

    /** Frequently-tapped cells in STRETCH/HARD zones — the "move this" list. */
    fun hardestHotspots(top: Int = 5): List<Hotspot> = synchronized(lock) {
        val (hand, _) = verdictLocked()
        val spots = ArrayList<Hotspot>()
        forEachCell { row, col, count ->
            if (count == 0) return@forEachCell
            val zone = zoneFor(cellX(col), cellY(row), hand)
            if (zone != ReachZone.EASY) {
                spots += Hotspot(cellX(col), cellY(row), count, zone)
            }
        }
        spots.sortedByDescending { it.taps * (if (it.zone == ReachZone.HARD) 2 else 1) }.take(top)
    }

    /** Raw tap heatmap, `[row][col]`, row 0 = top of screen. */
    fun heatmap(): Array<IntArray> = synchronized(lock) {
        Array(ROWS) { r -> grid[r].clone() }
    }

    /** Everything, with human-readable advice. */
    fun report(): GripReport {
        val (hand, confidence) = handedness()
        val stretch = stretchTapShare()
        val hotspots = hardestHotspots()
        val side = if (hand == Handedness.LEFT_THUMB) "left" else "right"
        val advice = buildString {
            when (hand) {
                Handedness.UNKNOWN -> append("Not enough data yet for a grip verdict. ")
                Handedness.TWO_HANDED -> append("Mostly two-handed use — top-of-screen controls are fine. ")
                else -> append("Mostly one-handed ($side thumb) — keep primary actions bottom-$side. ")
            }
            if (stretch >= 0.25) {
                append("${(stretch * 100).toInt()}% of taps strain the thumb — move the hotspots lower.")
            } else if (stretch > 0) {
                append("Strain is low (${(stretch * 100).toInt()}% hard-zone taps).")
            }
        }
        return GripReport(hand, confidence, stretch, hotspots, synchronized(lock) { totalTaps }, advice)
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) {
            for (row in grid) row.fill(0)
            leftVotes = 0.0; rightVotes = 0.0; twoHandVotes = 0.0; totalTaps = 0
        }
        save()
    }

    // ------------------------------------------------------------- recording

    /**
     * Time source for the two-thumb alternation window. Production reads the
     * wall clock; unit tests swap it to place taps exactly.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }

    internal fun recordTap(x: Double, y: Double) {
        val now = clock()
        synchronized(lock) {
            val col = min(COLS - 1, max(0, (x * COLS).toInt()))
            val row = min(ROWS - 1, max(0, (y * ROWS).toInt()))
            grid[row][col]++
            totalTaps++

            // Handedness evidence 1: taps in the bottom thumb arc are biased
            // toward the holding side.
            if (y > 0.55) {
                if (x < 0.38) leftVotes += 1.0
                if (x > 0.62) rightVotes += 1.0
            }
            // Handedness evidence 2: fast alternation across the width = two thumbs.
            if (lastTapX >= 0 && now - lastTapAt < 350 && abs(x - lastTapX) > 0.55) {
                twoHandVotes += 1.0
            }
            lastTapAt = now
            lastTapX = x
        }
        if (totalTaps % 25 == 0) save()
    }

    /** Vertical swipes bow toward the holding side's opposite corner. Weak signal, small weight. */
    internal fun recordSwipeBow(bowX: Double) {
        synchronized(lock) {
            if (bowX < -0.008) rightVotes += 0.5  // path bows left → right thumb
            if (bowX > 0.008) leftVotes += 0.5
        }
    }

    /** Must hold [lock]. */
    private fun verdictLocked(): Pair<Handedness, Double> {
        val oneHand = leftVotes + rightVotes
        if (totalTaps < 30 || oneHand + twoHandVotes < 12) return Handedness.UNKNOWN to 0.0
        if (twoHandVotes >= 12 && twoHandVotes > oneHand * 0.5) {
            val c = min(0.9, twoHandVotes / (oneHand + twoHandVotes))
            return Handedness.TWO_HANDED to (c * 100).toInt() / 100.0
        }
        if (oneHand == 0.0) return Handedness.UNKNOWN to 0.0
        val rightShare = rightVotes / oneHand
        return when {
            rightShare >= 0.6 -> Handedness.RIGHT_THUMB to ((min(0.95, rightShare) * 100).toInt() / 100.0)
            rightShare <= 0.4 -> Handedness.LEFT_THUMB to ((min(0.95, 1 - rightShare) * 100).toInt() / 100.0)
            else -> Handedness.UNKNOWN to 0.4
        }
    }

    private inline fun forEachCell(block: (row: Int, col: Int, count: Int) -> Unit) {
        for (row in 0 until ROWS) for (col in 0 until COLS) block(row, col, grid[row][col])
    }

    private fun cellX(col: Int): Double = (col + 0.5) / COLS
    private fun cellY(row: Int): Double = (row + 0.5) / ROWS

    // --------------------------------------------------------- touch capture

    private class GripWindowCallback(
        private val wrapped: Window.Callback,
        activity: Activity,
    ) : Window.Callback by wrapped {

        private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        private val decor = activity.window.decorView
        private var downX = 0f
        private var downY = 0f
        private var sumMidX = 0.0
        private var midSamples = 0
        private var moved = false

        override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
            if (event != null) observe(event)
            return wrapped.dispatchTouchEvent(event)
        }

        private fun observe(event: MotionEvent) {
            val w = decor.width.toFloat()
            val h = decor.height.toFloat()
            if (w <= 0 || h <= 0) return
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x; downY = event.y
                    sumMidX = 0.0; midSamples = 0; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > touchSlop) {
                        moved = true
                    }
                    sumMidX += event.x / w
                    midSamples++
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        recordTap((event.x / w).toDouble(), (event.y / h).toDouble())
                    } else {
                        val dy = abs(event.y - downY)
                        val dx = abs(event.x - downX)
                        // Mostly-vertical swipe of meaningful length → curvature sample.
                        if (dy > h * 0.15 && dy > dx * 2 && midSamples >= 4) {
                            val chordMidX = ((downX + event.x) / 2 / w).toDouble()
                            val pathMidX = sumMidX / midSamples
                            recordSwipeBow(pathMidX - chordMidX)
                        }
                    }
                }
            }
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
                val root = JSONObject(file.readText())
                val g = root.optJSONArray("grid") ?: JSONArray()
                for (r in 0 until min(ROWS, g.length())) {
                    val rowArr = g.getJSONArray(r)
                    for (c in 0 until min(COLS, rowArr.length())) {
                        grid[r][c] = rowArr.getInt(c)
                    }
                }
                leftVotes = root.optDouble("leftVotes", 0.0)
                rightVotes = root.optDouble("rightVotes", 0.0)
                twoHandVotes = root.optDouble("twoHandVotes", 0.0)
                totalTaps = root.optInt("totalTaps", 0)
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
        val g = JSONArray()
        for (row in grid) {
            val rowArr = JSONArray()
            for (v in row) rowArr.put(v)
            g.put(rowArr)
        }
        return JSONObject()
            .put("grid", g)
            .put("leftVotes", (leftVotes * 10).toInt() / 10.0)
            .put("rightVotes", (rightVotes * 10).toInt() / 10.0)
            .put("twoHandVotes", (twoHandVotes * 10).toInt() / 10.0)
            .put("totalTaps", totalTaps)
    }
}
