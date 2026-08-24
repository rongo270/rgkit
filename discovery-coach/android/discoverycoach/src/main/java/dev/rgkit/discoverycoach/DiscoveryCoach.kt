package dev.rgkit.discoverycoach

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** A feature the coach can teach. Register once per launch. */
data class DiscoverableFeature(
    /** Stable id, e.g. "swipe_to_archive". */
    val id: String,
    /** Short name shown in nudge UI. */
    val title: String,
    /** The one-sentence tip ("Swipe left on any item to archive it"). */
    val tip: String,
    /** 1 (nice to know) … 5 (users who find this stay). */
    val priority: Int = 3,
    /** Don't nudge before the user has had this many sessions since registration. */
    val minSessionsBeforeNudge: Int = 2,
    /** Feature ids that must be used before this one makes sense. */
    val prerequisites: List<String> = emptyList(),
)

/** A concrete "show this tip now" decision. */
data class Nudge(
    val feature: DiscoverableFeature,
    /** Why the engine picked it — good for logs and debugging. */
    val reason: String,
)

/** Per-feature discovery status for reporting. */
data class FeatureDiscovery(
    val id: String,
    val title: String,
    val discovered: Boolean,
    val usedCount: Int,
    val timesNudged: Int,
    val timesDismissed: Int,
)

/** The full discovery picture. */
data class DiscoveryReport(
    val registered: Int,
    val discovered: Int,
    /** discovered / registered, percent. */
    val discoveryPercent: Int,
    /** Nudges that led to first use ÷ nudges shown. */
    val nudgeSuccessPercent: Int,
    /** Nudged 3+ times, still unused — candidates for redesign or removal. */
    val deadFeatures: List<FeatureDiscovery>,
    val features: List<FeatureDiscovery>,
)

data class CoachConfig(
    /** Global minimum gap between any two nudges. */
    val minGapMs: Long = 4 * 3_600_000,
    /** Hard cap of nudges per calendar day. */
    val maxPerDay: Int = 2,
    /** At most one nudge per app session. */
    val onePerSession: Boolean = true,
    /** Per-feature re-nudge backoff by times already shown (days). */
    val backoffDays: List<Int> = listOf(1, 3, 7, 14),
    /** Dismissed this many times → suppressed for 30 days. */
    val dismissLimit: Int = 2,
)

/**
 * DiscoveryCoach — most users never find most features. The coach knows which
 * features *this* user hasn't discovered, and picks the right feature at the
 * right moment — without ever becoming the app that nags.
 *
 *  - **Right feature**: priority × ripeness (sessions since registration),
 *    prerequisites respected ("don't teach filters before search"), dismissals
 *    punished, twice-dismissed = suppressed a month.
 *  - **Right moment**: you call [maybeNudge] at natural pause points (or feed
 *    [reportCalmMoment] from an idle/hesitation detector) — the engine applies
 *    all frequency rules and either returns one [Nudge] or null.
 *  - **Honest feedback loop**: [nudgeShown] / [nudgeAccepted] /
 *    [nudgeDismissed] feed success stats; [discoveryReport] shows discovery %,
 *    nudge success %, and the dead features nobody wants.
 *
 * All state on-device, one JSON file.
 */
object DiscoveryCoach {

    private const val TAG = "DiscoveryCoach"
    private const val FILE_NAME = "discovery_coach.json"

    var config: CoachConfig = CoachConfig()

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "DiscoveryCoach-io") }

    /**
     * Time source for cooldowns, backoff and session gaps. Production reads the
     * wall clock; unit tests swap it so weeks of nudge history fit in one run.
     */
    internal var clock: () -> Long = { System.currentTimeMillis() }
    private val main = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var loaded = false
    private var nudgeListener: ((Nudge) -> Unit)? = null

    private class FeatureState {
        var usedCount = 0
        var firstUsedAt = 0L
        var registeredAtSession = -1
        var shownCount = 0
        var lastShownAt = 0L
        var dismissCount = 0
        var usedAfterNudge = false
    }

    private val catalog = LinkedHashMap<String, DiscoverableFeature>()
    private val states = HashMap<String, FeatureState>()
    private var sessionCount = 0
    private var lastSessionStartAt = 0L
    private var lastNudgeAt = 0L
    private var nudgesToday = 0
    private var nudgeDay = ""
    private var nudgedThisSession = false

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). Sessions are counted automatically. */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = app
        }
        io.execute { load(app) }
        (app as? Application)?.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityStarted(activity: Activity) {
                if (started == 0) onSessionStart()
                started++
            }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) started = 0
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** Register the teachable features (safe to call every launch). */
    fun register(features: List<DiscoverableFeature>) {
        synchronized(lock) {
            for (f in features) {
                catalog[f.id] = f
                val state = states.getOrPut(f.id) { FeatureState() }
                if (state.registeredAtSession < 0) state.registeredAtSession = sessionCount
            }
        }
        save()
    }

    /**
     * The user used a feature. Call from the feature itself (or bridge your
     * existing tracking: `FeatureUsage.track(id)` → `DiscoveryCoach.used(id)`).
     */
    fun used(featureId: String) {
        synchronized(lock) {
            val state = states.getOrPut(featureId) { FeatureState() }
            state.usedCount++
            if (state.firstUsedAt == 0L) {
                state.firstUsedAt = clock()
                if (state.shownCount > 0) state.usedAfterNudge = true
            }
        }
        save()
    }

    // ---------------------------------------------------------------- nudging

    /**
     * Ask for a nudge at a natural pause point (screen settled, task finished,
     * list end reached). Returns null when nothing should be shown — which is
     * most of the time, by design.
     */
    fun maybeNudge(now: Long = clock()): Nudge? {
        val nudge = synchronized(lock) { pickLocked(now) } ?: return null
        return nudge
    }

    /**
     * Feed calm/idle moments (e.g. IntentEngine HESITATION, or after a success
     * animation). If a listener is set and a nudge is due, it's delivered on
     * the main thread.
     */
    fun reportCalmMoment() {
        val listener = nudgeListener ?: return
        val nudge = maybeNudge() ?: return
        main.post { runCatching { listener(nudge) } }
    }

    /** Receive nudges pushed from [reportCalmMoment]. */
    fun setNudgeListener(listener: ((Nudge) -> Unit)?) {
        nudgeListener = listener
    }

    /** You displayed the nudge. Starts cooldowns. */
    fun nudgeShown(featureId: String) {
        val now = clock()
        synchronized(lock) {
            val state = states.getOrPut(featureId) { FeatureState() }
            state.shownCount++
            state.lastShownAt = now
            lastNudgeAt = now
            val day = dayKey(now)
            if (day != nudgeDay) { nudgeDay = day; nudgesToday = 0 }
            nudgesToday++
            nudgedThisSession = true
        }
        save()
    }

    /** The user tapped "show me" / used the feature from the nudge. */
    fun nudgeAccepted(featureId: String) {
        synchronized(lock) {
            states.getOrPut(featureId) { FeatureState() }.usedAfterNudge = true
        }
        used(featureId)
    }

    /** The user dismissed the nudge. Twice → suppressed for 30 days. */
    fun nudgeDismissed(featureId: String) {
        synchronized(lock) {
            states.getOrPut(featureId) { FeatureState() }.dismissCount++
        }
        save()
    }

    // --------------------------------------------------------------- reading

    fun discoveryReport(): DiscoveryReport = synchronized(lock) {
        val features = catalog.values.map { f ->
            val s = states[f.id] ?: FeatureState()
            FeatureDiscovery(
                id = f.id,
                title = f.title,
                discovered = s.usedCount > 0,
                usedCount = s.usedCount,
                timesNudged = s.shownCount,
                timesDismissed = s.dismissCount,
            )
        }
        val discovered = features.count { it.discovered }
        val shown = states.values.count { it.shownCount > 0 }
        val converted = states.values.count { it.shownCount > 0 && it.usedAfterNudge }
        DiscoveryReport(
            registered = catalog.size,
            discovered = discovered,
            discoveryPercent = if (catalog.isEmpty()) 0 else discovered * 100 / catalog.size,
            nudgeSuccessPercent = if (shown == 0) 0 else converted * 100 / shown,
            deadFeatures = features.filter { !it.discovered && it.timesNudged >= 3 },
            features = features,
        )
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    /** Forget everything, catalog included — [register] again after calling this. */
    fun reset() {
        synchronized(lock) {
            catalog.clear(); states.clear()
            sessionCount = 0; lastNudgeAt = 0; nudgesToday = 0
            nudgeDay = ""; nudgedThisSession = false; lastSessionStartAt = 0
        }
        save()
    }

    // --------------------------------------------------------------- engine

    /** Must hold [lock]. */
    private fun pickLocked(now: Long): Nudge? {
        // Global frequency gates first — the "never nags" guarantees.
        if (config.onePerSession && nudgedThisSession) return null
        if (now - lastNudgeAt < config.minGapMs) return null
        if (dayKey(now) == nudgeDay && nudgesToday >= config.maxPerDay) return null

        var best: DiscoverableFeature? = null
        // Ranking only — eligibility is decided by the gates below. Starting at
        // zero silently made a heavily-nudged, twice-dismissed feature
        // ineligible forever, since its score goes negative and never recovers.
        var bestScore = Double.NEGATIVE_INFINITY
        var bestReason = ""
        for (f in catalog.values) {
            val s = states[f.id] ?: continue
            if (s.usedCount > 0) continue // already discovered
            val sessionsSince = max(0, sessionCount - max(0, s.registeredAtSession))
            if (sessionsSince < f.minSessionsBeforeNudge) continue
            if (f.prerequisites.any { (states[it]?.usedCount ?: 0) == 0 }) continue
            // Dismiss suppression.
            if (s.dismissCount >= config.dismissLimit &&
                now - s.lastShownAt < 30L * 86_400_000
            ) continue
            // Per-feature backoff by times shown.
            if (s.shownCount > 0) {
                val backoffIndex = min(s.shownCount - 1, config.backoffDays.size - 1)
                val required = config.backoffDays[backoffIndex] * 86_400_000L
                if (now - s.lastShownAt < required) continue
            }
            val score = f.priority * 2.0 +
                min(3.0, sessionsSince * 0.2) -
                s.shownCount * 1.5 -
                s.dismissCount * 2.0
            if (score > bestScore) {
                bestScore = score
                best = f
                bestReason = "priority ${f.priority}, unused after $sessionsSince sessions" +
                    (if (s.shownCount > 0) ", nudged ${s.shownCount}× before" else "")
            }
        }
        val feature = best ?: return null
        return Nudge(feature, bestReason)
    }

    /** A new app session began (the lifecycle callback calls this for you). */
    internal fun onSessionStart() {
        val now = clock()
        synchronized(lock) {
            if (now - lastSessionStartAt < 30_000) return // rotation, not a new session
            lastSessionStartAt = now
            sessionCount++
            nudgedThisSession = false
        }
        save()
    }

    private fun dayKey(time: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = time }
        return "%04d-%02d-%02d".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
        )
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
                sessionCount = root.optInt("sessionCount")
                lastNudgeAt = root.optLong("lastNudgeAt")
                nudgesToday = root.optInt("nudgesToday")
                nudgeDay = root.optString("nudgeDay")
                val s = root.optJSONObject("states") ?: JSONObject()
                for (id in s.keys()) {
                    val o = s.getJSONObject(id)
                    val state = FeatureState()
                    state.usedCount = o.optInt("usedCount")
                    state.firstUsedAt = o.optLong("firstUsedAt")
                    state.registeredAtSession = o.optInt("registeredAtSession", -1)
                    state.shownCount = o.optInt("shownCount")
                    state.lastShownAt = o.optLong("lastShownAt")
                    state.dismissCount = o.optInt("dismissCount")
                    state.usedAfterNudge = o.optBoolean("usedAfterNudge")
                    states[id] = state
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
        val s = JSONObject()
        for ((id, state) in states) {
            s.put(
                id,
                JSONObject()
                    .put("usedCount", state.usedCount)
                    .put("firstUsedAt", state.firstUsedAt)
                    .put("registeredAtSession", state.registeredAtSession)
                    .put("shownCount", state.shownCount)
                    .put("lastShownAt", state.lastShownAt)
                    .put("dismissCount", state.dismissCount)
                    .put("usedAfterNudge", state.usedAfterNudge)
            )
        }
        return JSONObject()
            .put("sessionCount", sessionCount)
            .put("lastNudgeAt", lastNudgeAt)
            .put("nudgesToday", nudgesToday)
            .put("nudgeDay", nudgeDay)
            .put("states", s)
    }
}
