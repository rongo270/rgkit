package dev.rgkit.intentengine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * What the user is probably trying to do (or feeling) — inferred from *how*
 * they interact, not just *that* they interacted.
 */
enum class IntentType(val meaning: String, val suggestion: String) {
    /** Several fast taps on the same spot — the UI feels broken or too slow. */
    RAGE_TAP(
        "User is frustrated: tapping the same spot rapidly because nothing (visible) happened.",
        "Check that this control gives instant visual feedback and isn't blocked by work on the main thread."
    ),

    /** Same target tapped again after a beat — user thinks the first tap didn't register. */
    REPEATED_TAP(
        "User re-tapped the same target: the result of the first tap wasn't clear.",
        "Make the tap result obvious (state change, ripple, navigation) within 100 ms."
    ),

    /** Two back presses in quick succession — user wants out, or is lost. */
    DOUBLE_BACK(
        "User is backing out fast: either lost, or urgently trying to leave this flow.",
        "Check what screen this happens on — it often marks a screen users regret entering."
    ),

    /** Typing, deleting, retyping — user is unsure what to write or input keeps failing. */
    TYPE_DELETE_LOOP(
        "User keeps deleting what they typed: unclear expectations or failing validation.",
        "Add examples/placeholders, relax validation, or show requirements before they type."
    ),

    /** Sustained very fast scrolling — scanning for something, not reading. */
    FAST_SCROLL_SCAN(
        "User is hunting for something specific, not browsing this content.",
        "Offer search, filters, an index, or jump-to shortcuts on this screen."
    ),

    /** Drag gesture on something that doesn't drag — user expected direct manipulation. */
    DRAG_ATTEMPT(
        "User tried to drag an element that isn't draggable — the UI suggests it should be.",
        "Either make it draggable/swipeable or remove the visual cue that implies it."
    ),

    /** Screen is open and idle — user is reading carefully, or stuck. */
    HESITATION(
        "User stopped interacting: reading carefully, confused, or the next step isn't obvious.",
        "If this screen expects an action, make the primary action clearer or add gentle guidance."
    ),

    /** Bouncing between the same screens — navigation isn't taking them where they expect. */
    ZIGZAG_NAVIGATION(
        "User is bouncing back and forth between screens — they can't find what they need.",
        "The content they expect on one of these screens probably lives on the other."
    ),
}

/** One inferred intent, with how sure the engine is and the raw evidence. */
data class IntentSignal(
    val type: IntentType,
    /** 0.0–1.0. Signals below [IntentConfig.minConfidence] are never emitted. */
    val confidence: Double,
    /** Screen name, when known (auto-capture or [IntentEngine.screenChanged]). */
    val screen: String?,
    /** Target/element id, when the caller provided one. */
    val target: String?,
    /** Human-readable summary of what triggered the signal. */
    val evidence: String,
    val at: Long = System.currentTimeMillis(),
)

/** Tuning knobs. Defaults are calibrated for phone UIs; override what you need. */
data class IntentConfig(
    val rageTapCount: Int = 3,
    val rageTapWindowMs: Long = 700,
    val rageTapRadiusPx: Float = 160f,
    val repeatedTapCount: Int = 2,
    val repeatedTapWindowMs: Long = 2_500,
    val doubleBackWindowMs: Long = 1_600,
    val typeDeleteBursts: Int = 3,
    val typeDeleteWindowMs: Long = 30_000,
    /** Scroll speed considered "scanning", in pixels/second. */
    val fastScrollPxPerSec: Float = 5_000f,
    val fastScrollMinDurationMs: Long = 800,
    val hesitationMs: Long = 12_000,
    /** A↔B round trips within the window that count as zigzag (2 = A B A B). */
    val zigzagRoundTrips: Int = 2,
    val zigzagWindowMs: Long = 25_000,
    /** Don't emit the same signal type for the same target more often than this. */
    val signalCooldownMs: Long = 5_000,
    val minConfidence: Double = 0.5,
)

/**
 * IntentEngine — understands *why* users do what they do.
 *
 * Feed it raw interactions (or let auto-capture do it — see [attach] /
 * [autoCapture] in IntentEngineAuto.kt) and it emits [IntentSignal]s like
 * RAGE_TAP, TYPE_DELETE_LOOP or FAST_SCROLL_SCAN, each with a confidence and
 * a concrete suggestion.
 *
 * Everything runs on-device. Only signal counts and the last few signals are
 * persisted (no coordinates, no text, no per-event history).
 */
object IntentEngine {

    private const val TAG = "IntentEngine"
    private const val FILE_NAME = "intent_engine.json"
    private const val RECENT_LIMIT = 200

    var config: IntentConfig = IntentConfig()

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "IntentEngine-io") }

    /**
     * Time source for every detector window. Production always reads the wall
     * clock; unit tests swap it so a gesture burst can be laid down exactly
     * instead of being raced against real milliseconds.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(IntentSignal) -> Unit>()

    private var appContext: Context? = null
    private var loaded = false

    // ---- persisted state --------------------------------------------------
    private val totals = HashMap<IntentType, Int>()
    private val daily = HashMap<String, HashMap<IntentType, Int>>() // dayKey -> type -> count
    private val recent = ArrayDeque<IntentSignal>()

    // ---- detector state (in-memory only) ----------------------------------
    private data class Tap(val t: Long, val x: Float, val y: Float, val target: String?)
    private val taps = ArrayDeque<Tap>()
    private val backPresses = ArrayDeque<Long>()
    private data class Scroll(val t: Long, val dy: Float)
    private val scrolls = ArrayDeque<Scroll>()
    private val typing = HashMap<String, TypingState>()
    private val screenVisits = ArrayDeque<Pair<Long, String>>()
    private val lastEmit = HashMap<String, Long>() // "type|target" -> time
    private var currentScreen: String? = null
    private var hesitationFiredOn: String? = null

    private class TypingState {
        var lastLength = 0
        var deleteBursts = ArrayDeque<Long>() // time of each delete burst start
        var deletingRun = 0
        var typedSinceBurst = 0
    }

    private val hesitationRunnable = Runnable { fireHesitation() }

    // ------------------------------------------------------------------ init

    /** Call once, e.g. in Application.onCreate(). Safe to call again. */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = app
        }
        io.execute { load(app) }
    }

    // ------------------------------------------------------- event reporting

    /** Report a tap. Coordinates may be local to the target or global — just be consistent. */
    fun onTap(x: Float, y: Float, target: String? = null) {
        val now = clock()
        touch(now)
        synchronized(lock) {
            taps.addLast(Tap(now, x, y, target))
            while (taps.size > 12) taps.removeFirst()
        }
        detectRageTap(now)
        detectRepeatedTap(now, target)
    }

    /** Report a back press (system back or your own up/back button). */
    fun onBackPressed() {
        val now = clock()
        touch(now)
        synchronized(lock) {
            backPresses.addLast(now)
            while (backPresses.size > 6) backPresses.removeFirst()
        }
        detectDoubleBack(now)
    }

    /** Report scroll movement (signed pixels since the last call). */
    fun onScroll(deltaYPx: Float) {
        val now = clock()
        touch(now)
        synchronized(lock) {
            scrolls.addLast(Scroll(now, deltaYPx))
            while (scrolls.isNotEmpty() && now - scrolls.first.t > 3_000) scrolls.removeFirst()
        }
        detectFastScroll(now)
    }

    /**
     * Report the new text length of a field after each change
     * (Compose: from onValueChange; Views: use [watchTextLengths] in IntentEngineAuto.kt).
     */
    fun onTextChanged(fieldId: String, newLength: Int) {
        val now = clock()
        touch(now)
        var fire = false
        var bursts = 0
        synchronized(lock) {
            val state = typing.getOrPut(fieldId) { TypingState() }
            val delta = newLength - state.lastLength
            state.lastLength = newLength
            if (delta < 0) {
                state.deletingRun += -delta
                // A "burst" = at least 2 chars deleted after having typed something.
                if (state.deletingRun >= 2 && state.typedSinceBurst >= 2) {
                    state.deleteBursts.addLast(now)
                    state.typedSinceBurst = 0
                    state.deletingRun = 0
                    while (state.deleteBursts.isNotEmpty() &&
                        now - state.deleteBursts.first > config.typeDeleteWindowMs
                    ) state.deleteBursts.removeFirst()
                    bursts = state.deleteBursts.size
                    if (bursts >= config.typeDeleteBursts) {
                        fire = true
                        state.deleteBursts.clear()
                    }
                }
            } else if (delta > 0) {
                state.typedSinceBurst += delta
                state.deletingRun = 0
            }
        }
        if (fire) emit(
            IntentType.TYPE_DELETE_LOOP,
            confidence = min(0.95, 0.55 + 0.15 * (bursts - config.typeDeleteBursts + 1)),
            target = fieldId,
            evidence = "$bursts type-and-delete rounds on '$fieldId' within " +
                "${config.typeDeleteWindowMs / 1000}s"
        )
    }

    /** Report a drag/swipe attempt on an element that is not draggable. */
    fun onDragAttempt(target: String? = null) {
        touch(clock())
        emit(
            IntentType.DRAG_ATTEMPT,
            confidence = 0.7,
            target = target,
            evidence = "Drag gesture on non-draggable ${target ?: "element"}"
        )
    }

    /**
     * Report that the visible screen changed. Auto-capture calls this for you
     * with the Activity class name; call it yourself for Compose destinations.
     */
    fun screenChanged(name: String) {
        val now = clock()
        synchronized(lock) {
            currentScreen = name
            hesitationFiredOn = null
            screenVisits.addLast(now to name)
            while (screenVisits.isNotEmpty() && now - screenVisits.first.first > config.zigzagWindowMs) {
                screenVisits.removeFirst()
            }
        }
        detectZigzag()
        resetHesitationTimer()
    }

    /** Tell the engine the app went to background (auto-capture does this for you). */
    fun appBackgrounded() {
        main.removeCallbacks(hesitationRunnable)
        synchronized(lock) { hesitationFiredOn = currentScreen } // don't fire after return
        save()
    }

    // --------------------------------------------------------------- reading

    /** Listen for signals. Called on the main thread. Returns the listener for removal. */
    fun addListener(listener: (IntentSignal) -> Unit): (IntentSignal) -> Unit {
        listeners.add(listener)
        return listener
    }

    fun removeListener(listener: (IntentSignal) -> Unit) {
        listeners.remove(listener)
    }

    /** Lifetime signal counts per type. */
    fun stats(): Map<IntentType, Int> = synchronized(lock) { totals.toMap() }

    /** Signal counts for today. */
    fun todayCounts(): Map<IntentType, Int> =
        synchronized(lock) { daily[dayKey(clock())]?.toMap() ?: emptyMap() }

    /** The most recent signals, newest first. */
    fun recentSignals(limit: Int = 50): List<IntentSignal> =
        synchronized(lock) { recent.toList().takeLast(limit).reversed() }

    /**
     * Rolling frustration score 0–100 for the last [windowMs] (default 10 min).
     * 0 = calm. 25+ = friction worth looking at. 60+ = the user is having a bad time.
     */
    fun frustrationScore(windowMs: Long = 600_000): Int {
        val cutoff = clock() - windowMs
        val weights = mapOf(
            IntentType.RAGE_TAP to 22.0,
            IntentType.REPEATED_TAP to 10.0,
            IntentType.DOUBLE_BACK to 12.0,
            IntentType.TYPE_DELETE_LOOP to 12.0,
            IntentType.DRAG_ATTEMPT to 8.0,
            IntentType.ZIGZAG_NAVIGATION to 14.0,
            IntentType.FAST_SCROLL_SCAN to 5.0,
            IntentType.HESITATION to 4.0,
        )
        var score = 0.0
        synchronized(lock) {
            for (s in recent) {
                if (s.at < cutoff) continue
                score += (weights[s.type] ?: 5.0) * s.confidence
            }
        }
        return min(100.0, score).toInt()
    }

    /** Full state as pretty JSON (counts + recent signals). */
    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    /** Wipe all persisted data and in-memory detector state. */
    fun reset() {
        synchronized(lock) {
            totals.clear(); daily.clear(); recent.clear()
            taps.clear(); backPresses.clear(); scrolls.clear()
            typing.clear(); screenVisits.clear(); lastEmit.clear()
        }
        save()
    }

    // ------------------------------------------------------------- detectors

    private fun detectRageTap(now: Long) {
        var count = 0
        var spanMs = 0L
        var target: String? = null
        synchronized(lock) {
            val inWindow = taps.filter { now - it.t <= config.rageTapWindowMs }
            if (inWindow.size < config.rageTapCount) return
            val last = inWindow.last()
            val close = inWindow.filter {
                hypot((it.x - last.x).toDouble(), (it.y - last.y).toDouble()) <= config.rageTapRadiusPx
            }
            if (close.size < config.rageTapCount) return
            count = close.size
            spanMs = last.t - close.first().t
            target = last.target
            taps.clear() // don't double-count the same burst
        }
        // The burst is reported the moment it crosses the threshold, so `count`
        // is all but always exactly rageTapCount. What actually separates a
        // panicked hammering from three unlucky taps is how tightly they
        // landed, so the confidence rides on the span, not on the count.
        val tightness = 1.0 - (spanMs.toDouble() / config.rageTapWindowMs).coerceIn(0.0, 1.0)
        emit(
            IntentType.RAGE_TAP,
            confidence = min(0.95, 0.6 + 0.35 * tightness),
            target = target,
            evidence = "$count taps within ${spanMs}ms in the same spot"
        )
    }

    private fun detectRepeatedTap(now: Long, target: String?) {
        if (target == null) return
        var count = 0
        synchronized(lock) {
            count = taps.count {
                it.target == target && now - it.t <= config.repeatedTapWindowMs
            }
        }
        // Slower than a rage burst but still "why didn't that work?"
        if (count == config.repeatedTapCount + 1) {
            emit(
                IntentType.REPEATED_TAP,
                confidence = 0.55,
                target = target,
                evidence = "'$target' tapped $count times in ${config.repeatedTapWindowMs / 1000}s"
            )
        }
    }

    private fun detectDoubleBack(now: Long) {
        var count = 0
        var gapMs = 0L
        synchronized(lock) {
            val inWindow = backPresses.filter { now - it <= config.doubleBackWindowMs }
            count = inWindow.size
            if (count < 2) return
            gapMs = now - inWindow[inWindow.size - 2]
            backPresses.clear() // the pair is one signal, not two
        }
        // Same reasoning as [detectRageTap]: the pair fires as soon as it
        // happens, so the gap between the two presses is the part that varies.
        emit(
            IntentType.DOUBLE_BACK,
            confidence = if (gapMs <= config.doubleBackWindowMs / 3) 0.9 else 0.7,
            target = null,
            evidence = "$count back presses ${gapMs}ms apart"
        )
    }

    private fun detectFastScroll(now: Long) {
        var speed = 0f
        var span = 0L
        synchronized(lock) {
            if (scrolls.size < 4) return
            span = now - scrolls.first.t
            if (span < config.fastScrollMinDurationMs) return
            val distance = scrolls.sumOf { abs(it.dy).toDouble() }.toFloat()
            speed = distance / (span / 1000f)
            if (speed < config.fastScrollPxPerSec) return
            scrolls.clear()
        }
        emit(
            IntentType.FAST_SCROLL_SCAN,
            confidence = min(0.9, 0.55 + 0.35 * (speed / config.fastScrollPxPerSec - 1f)).toDouble(),
            target = null,
            evidence = "Scrolled at ${speed.toInt()} px/s for ${span}ms"
        )
    }

    private fun detectZigzag() {
        var trips = 0
        var a: String? = null
        var b: String? = null
        synchronized(lock) {
            val names = screenVisits.map { it.second }
            if (names.size < config.zigzagRoundTrips * 2) return
            // Count A→B→A→B alternations at the tail of the visit list.
            val last = names.last()
            val prev = names.getOrNull(names.size - 2) ?: return
            if (last == prev) return
            var i = names.size - 1
            var alternations = 0
            while (i >= 1) {
                val expected = if ((names.size - 1 - i) % 2 == 0) last else prev
                if (names[i] != expected) break
                alternations++
                i--
            }
            trips = alternations / 2
            if (trips < config.zigzagRoundTrips) return
            a = prev; b = last
            screenVisits.clear()
        }
        emit(
            IntentType.ZIGZAG_NAVIGATION,
            confidence = min(0.9, 0.6 + 0.15 * (trips - config.zigzagRoundTrips)),
            target = "$a<->$b",
            evidence = "Bounced between '$a' and '$b' $trips times"
        )
    }

    private fun fireHesitation() {
        val screen = synchronized(lock) {
            if (hesitationFiredOn == currentScreen) return
            hesitationFiredOn = currentScreen
            currentScreen
        }
        emit(
            IntentType.HESITATION,
            confidence = 0.55,
            target = null,
            screenOverride = screen,
            evidence = "No interaction for ${config.hesitationMs / 1000}s on '${screen ?: "unknown"}'"
        )
    }

    /** Any interaction resets the hesitation timer. */
    private fun touch(now: Long) {
        resetHesitationTimer()
    }

    private fun resetHesitationTimer() {
        main.removeCallbacks(hesitationRunnable)
        main.postDelayed(hesitationRunnable, config.hesitationMs)
    }

    // --------------------------------------------------------------- emit/io

    private fun emit(
        type: IntentType,
        confidence: Double,
        target: String?,
        evidence: String,
        screenOverride: String? = null,
    ) {
        if (confidence < config.minConfidence) return
        val now = clock()
        val key = "${type.name}|${target ?: ""}"
        val signal: IntentSignal
        synchronized(lock) {
            val last = lastEmit[key] ?: 0L
            if (now - last < config.signalCooldownMs) return
            lastEmit[key] = now
            signal = IntentSignal(
                type = type,
                confidence = (confidence * 100).toInt() / 100.0,
                screen = screenOverride ?: currentScreen,
                target = target,
                evidence = evidence,
                at = now,
            )
            totals[type] = (totals[type] ?: 0) + 1
            val day = daily.getOrPut(dayKey(now)) { HashMap() }
            day[type] = (day[type] ?: 0) + 1
            recent.addLast(signal)
            while (recent.size > RECENT_LIMIT) recent.removeFirst()
        }
        main.post { for (l in listeners) runCatching { l(signal) } }
        save()
    }

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val root = JSONObject(file.readText())
                val t = root.optJSONObject("totals") ?: JSONObject()
                for (name in t.keys()) {
                    runCatching { totals[IntentType.valueOf(name)] = t.getInt(name) }
                }
                val d = root.optJSONObject("daily") ?: JSONObject()
                for (day in d.keys()) {
                    val types = d.getJSONObject(day)
                    val map = HashMap<IntentType, Int>()
                    for (name in types.keys()) {
                        runCatching { map[IntentType.valueOf(name)] = types.getInt(name) }
                    }
                    daily[day] = map
                }
                val r = root.optJSONArray("recent") ?: JSONArray()
                for (i in 0 until r.length()) {
                    val o = r.getJSONObject(i)
                    runCatching {
                        recent.addLast(
                            IntentSignal(
                                type = IntentType.valueOf(o.getString("type")),
                                confidence = o.getDouble("confidence"),
                                screen = o.optString("screen").ifEmpty { null },
                                target = o.optString("target").ifEmpty { null },
                                evidence = o.optString("evidence"),
                                at = o.getLong("at"),
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
        val root = JSONObject()
        val t = JSONObject()
        for ((type, count) in totals) t.put(type.name, count)
        root.put("totals", t)
        val d = JSONObject()
        for ((day, map) in daily) {
            val types = JSONObject()
            for ((type, count) in map) types.put(type.name, count)
            d.put(day, types)
        }
        root.put("daily", d)
        val r = JSONArray()
        for (s in recent) {
            r.put(
                JSONObject()
                    .put("type", s.type.name)
                    .put("confidence", s.confidence)
                    .put("screen", s.screen ?: "")
                    .put("target", s.target ?: "")
                    .put("evidence", s.evidence)
                    .put("at", s.at)
            )
        }
        root.put("recent", r)
        return root
    }

    internal fun dayKey(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))
}
