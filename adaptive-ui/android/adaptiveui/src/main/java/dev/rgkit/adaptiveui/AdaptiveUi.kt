package dev.rgkit.adaptiveui

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The layouts the engine can choose between. */
enum class LayoutStyle(val label: String) {
    GRID("Grid"),
    LIST("List"),
    CARDS("Cards"),
    CAROUSEL("Carousel"),
}

/** Learning stats for one (collection, style) arm. */
data class ArmStats(
    val sessions: Int,
    val meanReward: Double,
)

/**
 * AdaptiveUi — instead of shipping one fixed layout, let each user's own
 * behavior pick the best one.
 *
 * A per-collection Thompson-sampling bandit chooses between [LayoutStyle]s.
 * Each time a collection is shown counts as one trial; the reward is real
 * engagement (item taps + scroll depth, with a quick-abandon penalty). Styles
 * that work for *this user* win more showings; underexplored styles still get
 * occasional tries, so the choice keeps adapting as taste changes.
 *
 * Use the drop-in [AdaptiveCollection] composable (AdaptiveCollection.kt),
 * or drive the engine manually with [beginSession] / [recordItemClick] /
 * [recordScrollDepth] / [endSession] for custom UIs.
 *
 * All learning is local: a small JSON file of per-style counts and average
 * rewards. No backend, no identifiers.
 */
object AdaptiveUi {

    private const val TAG = "AdaptiveUi"
    private const val FILE_NAME = "adaptive_ui.json"

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "AdaptiveUi-io") }
    private val random = Random()

    private var appContext: Context? = null
    private var loaded = false

    /** collectionId -> style -> (n, mean, m2) via Welford. */
    private class Arm(var n: Int = 0, var mean: Double = 0.0, var m2: Double = 0.0) {
        fun update(reward: Double) {
            n++
            val delta = reward - mean
            mean += delta / n
            m2 += delta * (reward - mean)
        }
        fun variance(): Double = if (n < 2) 0.08 else max(0.005, m2 / (n - 1))
    }

    private val arms = HashMap<String, HashMap<LayoutStyle, Arm>>()
    private val forced = HashMap<String, LayoutStyle>()

    /** Live sessions: collectionId -> metrics. */
    private class Session(val style: LayoutStyle, val startedAt: Long) {
        var clicks = 0
        var maxScrollDepth = 0.0
    }
    private val sessions = HashMap<String, Session>()

    // ------------------------------------------------------------------ init

    /** Call once, e.g. in Application.onCreate(). */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (appContext != null) return
            appContext = app
        }
        io.execute { load(app) }
    }

    // --------------------------------------------------------------- choosing

    /**
     * Start a showing of [collectionId]: samples a style (Thompson) and begins
     * collecting engagement. Call [endSession] when the collection leaves the
     * screen. [AdaptiveCollection] does both for you.
     */
    fun beginSession(
        collectionId: String,
        allowed: Set<LayoutStyle> = LayoutStyle.entries.toSet(),
    ): LayoutStyle {
        val style = sample(collectionId, allowed)
        synchronized(lock) {
            sessions[collectionId] = Session(style, System.currentTimeMillis())
        }
        return style
    }

    /** An item in the collection was tapped. */
    fun recordItemClick(collectionId: String) {
        synchronized(lock) { sessions[collectionId]?.let { it.clicks++ } }
    }

    /** How far the user scrolled, 0–1. Keep calling; the max is kept. */
    fun recordScrollDepth(collectionId: String, depth: Double) {
        synchronized(lock) {
            sessions[collectionId]?.let { it.maxScrollDepth = max(it.maxScrollDepth, depth.coerceIn(0.0, 1.0)) }
        }
    }

    /** Ends the showing, converts engagement into a reward, updates the bandit. */
    fun endSession(collectionId: String) {
        val reward: Double
        val style: LayoutStyle
        synchronized(lock) {
            val s = sessions.remove(collectionId) ?: return
            style = s.style
            val viewMs = System.currentTimeMillis() - s.startedAt
            reward = when {
                // Barely looked and didn't touch: this layout bounced them.
                viewMs < 2_000 && s.clicks == 0 -> 0.05
                else -> min(1.0, 0.6 * min(1.0, s.clicks / 3.0) + 0.4 * s.maxScrollDepth)
            }
            arms.getOrPut(collectionId) { HashMap() }
                .getOrPut(style) { Arm() }
                .update(reward)
        }
        save()
        Log.d(TAG, "'$collectionId' $style reward=${(reward * 100).toInt() / 100.0}")
    }

    /**
     * The style the bandit would pick right now, without starting a session —
     * for custom UIs that manage their own lifecycle.
     */
    fun styleFor(
        collectionId: String,
        allowed: Set<LayoutStyle> = LayoutStyle.entries.toSet(),
    ): LayoutStyle = sample(collectionId, allowed)

    /** Pin a style (A/B override, user preference toggle). Null clears the pin. */
    fun force(collectionId: String, style: LayoutStyle?) {
        synchronized(lock) {
            if (style == null) forced.remove(collectionId) else forced[collectionId] = style
        }
        save()
    }

    // --------------------------------------------------------------- reading

    /** Learning state per style for a collection. */
    fun stats(collectionId: String): Map<LayoutStyle, ArmStats> = synchronized(lock) {
        (arms[collectionId] ?: emptyMap()).mapValues { (_, arm) ->
            ArmStats(arm.n, (arm.mean * 1000).toInt() / 1000.0)
        }
    }

    /** Human-readable summary of what the bandit believes for a collection. */
    fun explanation(collectionId: String): String = synchronized(lock) {
        val styleArms = arms[collectionId] ?: return "No data yet for '$collectionId'."
        if (styleArms.isEmpty()) return "No data yet for '$collectionId'."
        val parts = styleArms.entries.sortedByDescending { it.value.mean }.map { (style, arm) ->
            "${style.label}: avg ${(arm.mean * 100).toInt()}% over ${arm.n} showings"
        }
        val leader = styleArms.maxByOrNull { it.value.mean }!!
        "'$collectionId' → currently favors ${leader.key.label}. " + parts.joinToString("; ")
    }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    /** Forget everything learned (all collections, or one). */
    fun reset(collectionId: String? = null) {
        synchronized(lock) {
            if (collectionId == null) { arms.clear(); forced.clear() }
            else { arms.remove(collectionId); forced.remove(collectionId) }
        }
        save()
    }

    // -------------------------------------------------------------- sampling

    /**
     * Thompson sampling with a Gaussian posterior over each arm's mean reward.
     * Unseen arms get an optimistic prior so every style is tried early on.
     */
    private fun sample(collectionId: String, allowed: Set<LayoutStyle>): LayoutStyle {
        require(allowed.isNotEmpty()) { "allowed styles must not be empty" }
        synchronized(lock) {
            forced[collectionId]?.let { if (it in allowed) return it }
            val styleArms = arms[collectionId] ?: HashMap()
            var best: LayoutStyle = allowed.first()
            var bestDraw = Double.NEGATIVE_INFINITY
            for (style in allowed) {
                val arm = styleArms[style]
                val draw = if (arm == null || arm.n == 0) {
                    // Optimistic prior: mean 0.55, wide spread → guaranteed exploration.
                    0.55 + random.nextGaussian() * 0.25
                } else {
                    arm.mean + random.nextGaussian() * sqrt(arm.variance() / arm.n + 1e-4)
                }
                if (draw > bestDraw) { bestDraw = draw; best = style }
            }
            return best
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
                val collections = root.optJSONObject("collections") ?: JSONObject()
                for (id in collections.keys()) {
                    val styles = collections.getJSONObject(id)
                    val map = HashMap<LayoutStyle, Arm>()
                    for (styleName in styles.keys()) {
                        runCatching {
                            val o = styles.getJSONObject(styleName)
                            map[LayoutStyle.valueOf(styleName)] = Arm(
                                n = o.getInt("n"),
                                mean = o.getDouble("mean"),
                                m2 = o.getDouble("m2"),
                            )
                        }
                    }
                    arms[id] = map
                }
                val pins = root.optJSONObject("forced") ?: JSONObject()
                for (id in pins.keys()) {
                    runCatching { forced[id] = LayoutStyle.valueOf(pins.getString(id)) }
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
        val collections = JSONObject()
        for ((id, styleArms) in arms) {
            val styles = JSONObject()
            for ((style, arm) in styleArms) {
                styles.put(
                    style.name,
                    JSONObject().put("n", arm.n).put("mean", arm.mean).put("m2", arm.m2)
                )
            }
            collections.put(id, styles)
        }
        val pins = JSONObject()
        for ((id, style) in forced) pins.put(id, style.name)
        return JSONObject().put("collections", collections).put("forced", pins)
    }
}
