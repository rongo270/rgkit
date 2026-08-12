package dev.rgkit.flowlearning

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.min

/** The kinds of discoveries the miner can make. */
enum class InsightType(val label: String) {
    ORDERING("Ordering pattern"),
    DROP_OFF("Drop-off point"),
    LOOP("Confusion loop"),
    FUNNEL_LEAK("Funnel leak"),
    COMMON_PATH("Common journey"),
    ENTRY_POINT("Entry point"),
}

/** One mined behavioral insight, ready to read (or show in a debug screen). */
data class Insight(
    val type: InsightType,
    /** Short headline, e.g. "92% visit 'price' before 'reviews'". */
    val title: String,
    /** The numbers behind it. */
    val detail: String,
    /** What to do about it — the "AI UX consultant" part. */
    val recommendation: String,
    /** 0–1: how strong/actionable the pattern is. */
    val strength: Double,
    /** How many sessions support it. */
    val sampleSessions: Int,
)

data class FlowConfig(
    /** A gap this long between events starts a new session. */
    val sessionGapMs: Long = 90_000,
    /** Patterns need at least this many supporting sessions to be reported. */
    val minSupport: Int = 12,
    /** Keep at most this many sessions (oldest dropped). */
    val maxSessions: Int = 400,
)

/**
 * FlowLearning — instead of "Button A clicked, Button B clicked", it discovers
 * *behavioral patterns*:
 *
 *  - "92% of sessions visit 'price' before 'reviews' → surface pricing higher"
 *  - "21% of sessions end at 'cart' → the checkout entry is leaking"
 *  - "Users bounce 'product' → 'shipping' → 'product' → shipping info is
 *     missing on the product screen"
 *
 * Feed it `track("screen_or_action")` events; sessions are cut automatically
 * (time gaps + app background). Call [insights] whenever you want the current
 * findings. Everything is mined and stored on-device.
 */
object FlowLearning {

    private const val TAG = "FlowLearning"
    private const val FILE_NAME = "flow_learning.json"

    var config: FlowConfig = FlowConfig()

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "FlowLearning-io") }

    private var appContext: Context? = null
    private var loaded = false

    // Step names are dictionary-coded to keep the file small.
    private val names = ArrayList<String>()          // id -> name
    private val ids = HashMap<String, Int>()         // name -> id
    private val sessions = ArrayList<IntArray>()     // closed sessions
    private val current = ArrayList<Int>()           // open session
    private var lastEventAt = 0L
    private val funnels = LinkedHashMap<String, List<String>>()

    // ------------------------------------------------------------------ init

    /**
     * Call once in Application.onCreate(). Passing the Application also closes
     * sessions when the app goes to background.
     */
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
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityStopped(activity: Activity) {
                started--
                if (started <= 0) { started = 0; closeSession() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /**
     * Record a step: a screen shown or a meaningful action taken.
     * Use stable snake_case names ("product_detail", "add_to_cart").
     */
    fun track(step: String) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (lastEventAt > 0 && now - lastEventAt > config.sessionGapMs) closeSessionLocked()
            lastEventAt = now
            val id = ids.getOrPut(step) { names.add(step); names.size - 1 }
            // Collapse immediate repeats (recompositions, tab re-taps).
            if (current.lastOrNull() != id) current.add(id)
        }
        save()
    }

    /** Declare a funnel to get per-step conversion + leak insights. */
    fun defineFunnel(name: String, steps: List<String>) {
        synchronized(lock) { funnels[name] = steps }
        save()
    }

    // --------------------------------------------------------------- reading

    /** Mine everything and return the current findings, strongest first. */
    fun insights(): List<Insight> {
        val closed: List<IntArray>
        val dict: List<String>
        val funnelDefs: Map<String, List<String>>
        synchronized(lock) {
            closed = ArrayList(sessions).also { list ->
                if (current.size >= 2) list.add(current.toIntArray())
            }
            dict = ArrayList(names)
            funnelDefs = LinkedHashMap(funnels)
        }
        if (closed.size < 5) return emptyList()

        val results = ArrayList<Insight>()
        results += mineOrdering(closed, dict)
        results += mineDropOffs(closed, dict)
        results += mineLoops(closed, dict)
        results += mineFunnels(closed, dict, funnelDefs)
        results += mineCommonPaths(closed, dict)
        results += mineEntryPoints(closed, dict)
        return results.sortedByDescending { it.strength }
    }

    /** Where users go next from [step], as (nextStep, probability), best first. */
    fun transitionsFrom(step: String): List<Pair<String, Double>> {
        val closed: List<IntArray>
        val dict: List<String>
        val id: Int
        synchronized(lock) {
            id = ids[step] ?: return emptyList()
            closed = ArrayList(sessions)
            dict = ArrayList(names)
        }
        val counts = HashMap<Int, Int>()
        var total = 0
        for (s in closed) {
            for (i in 0 until s.size - 1) {
                if (s[i] == id) { counts[s[i + 1]] = (counts[s[i + 1]] ?: 0) + 1; total++ }
            }
        }
        if (total == 0) return emptyList()
        return counts.entries.sortedByDescending { it.value }
            .map { dict[it.key] to (it.value * 1000 / total) / 1000.0 }
    }

    /** The most frequent exact journeys of [length] steps. */
    fun commonPaths(length: Int = 3, top: Int = 10): List<Pair<List<String>, Int>> {
        val closed: List<IntArray>
        val dict: List<String>
        synchronized(lock) { closed = ArrayList(sessions); dict = ArrayList(names) }
        val counts = HashMap<List<Int>, Int>()
        for (s in closed) {
            for (i in 0..s.size - length) {
                val gram = s.slice(i until i + length)
                counts[gram] = (counts[gram] ?: 0) + 1
            }
        }
        return counts.entries.sortedByDescending { it.value }.take(top)
            .map { entry -> entry.key.map { dict[it] } to entry.value }
    }

    /** Number of recorded sessions (closed). */
    fun sessionCount(): Int = synchronized(lock) { sessions.size }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) {
            names.clear(); ids.clear(); sessions.clear(); current.clear(); lastEventAt = 0
        }
        save()
    }

    // ---------------------------------------------------------------- miners

    private fun frequentSteps(closed: List<IntArray>, cap: Int = 40): List<Int> {
        val support = HashMap<Int, Int>() // step -> sessions containing it
        for (s in closed) for (id in s.toHashSet()) support[id] = (support[id] ?: 0) + 1
        return support.entries.asSequence()
            .filter { it.value >= config.minSupport }
            .sortedByDescending { it.value }
            .take(cap)
            .map { it.key }
            .toList()
    }

    private fun mineOrdering(closed: List<IntArray>, dict: List<String>): List<Insight> {
        val steps = frequentSteps(closed)
        val out = ArrayList<Insight>()
        for (i in steps.indices) for (j in i + 1 until steps.size) {
            val a = steps[i]; val b = steps[j]
            var both = 0; var aFirst = 0
            for (s in closed) {
                val ia = s.indexOfFirst { it == a }
                val ib = s.indexOfFirst { it == b }
                if (ia >= 0 && ib >= 0) {
                    both++
                    if (ia < ib) aFirst++
                }
            }
            if (both < config.minSupport) continue
            val pct = aFirst * 100 / both
            val (first, second, share) = if (pct >= 50) {
                Triple(dict[a], dict[b], pct)
            } else Triple(dict[b], dict[a], 100 - pct)
            if (share < 80) continue
            out += Insight(
                type = InsightType.ORDERING,
                title = "$share% visit '$first' before '$second'",
                detail = "Of $both sessions that saw both, $share% opened '$first' first.",
                recommendation = "Users want '$first' information before '$second' — " +
                    "surface '$first' earlier, or embed its key info into '$second'.",
                strength = min(1.0, share / 100.0 * (both.toDouble() / (both + 20))),
                sampleSessions = both,
            )
        }
        return out.sortedByDescending { it.strength }.take(5)
    }

    private fun mineDropOffs(closed: List<IntArray>, dict: List<String>): List<Insight> {
        val enders = HashMap<Int, Int>()
        var total = 0
        for (s in closed) {
            if (s.size < 2) continue
            enders[s.last()] = (enders[s.last()] ?: 0) + 1
            total++
        }
        if (total < config.minSupport) return emptyList()
        return enders.entries.asSequence()
            .filter { it.value >= config.minSupport && it.value * 100 / total >= 20 }
            .sortedByDescending { it.value }
            .take(3)
            .map { (id, count) ->
                val pct = count * 100 / total
                Insight(
                    type = InsightType.DROP_OFF,
                    title = "$pct% of sessions end at '${dict[id]}'",
                    detail = "$count of $total sessions had '${dict[id]}' as their last step.",
                    recommendation = "'${dict[id]}' is where journeys die. If it isn't a natural " +
                        "endpoint, check what users expect next there — the CTA may be invisible or broken.",
                    strength = min(1.0, pct / 100.0 + 0.2),
                    sampleSessions = count,
                )
            }.toList()
    }

    private fun mineLoops(closed: List<IntArray>, dict: List<String>): List<Insight> {
        // A→B→A: went in, found nothing, came straight back.
        val loops = HashMap<Pair<Int, Int>, Int>()
        val visits = HashMap<Pair<Int, Int>, Int>()
        for (s in closed) {
            for (i in 0 until s.size - 1) {
                val key = s[i] to s[i + 1]
                visits[key] = (visits[key] ?: 0) + 1
                if (i + 2 < s.size && s[i + 2] == s[i]) loops[key] = (loops[key] ?: 0) + 1
            }
        }
        return loops.entries.asSequence()
            .filter { (key, count) -> count >= config.minSupport / 2 && (visits[key] ?: 0) > 0 &&
                count * 100 / visits[key]!! >= 35 }
            .sortedByDescending { it.value }
            .take(3)
            .map { (key, count) ->
                val (a, b) = key
                val rate = count * 100 / visits[key]!!
                Insight(
                    type = InsightType.LOOP,
                    title = "'${dict[a]}' → '${dict[b]}' → straight back ($rate%)",
                    detail = "$count of ${visits[key]} '${dict[a]}'→'${dict[b]}' visits returned " +
                        "immediately to '${dict[a]}'.",
                    recommendation = "Users enter '${dict[b]}' expecting something they don't find. " +
                        "Either the link over-promises or the content is buried — fix '${dict[b]}' " +
                        "or preview its content on '${dict[a]}'.",
                    strength = min(1.0, rate / 100.0 + 0.1),
                    sampleSessions = count,
                )
            }.toList()
    }

    private fun mineFunnels(
        closed: List<IntArray>,
        dict: List<String>,
        funnelDefs: Map<String, List<String>>,
    ): List<Insight> {
        val out = ArrayList<Insight>()
        val nameToId = HashMap<String, Int>()
        dict.forEachIndexed { id, name -> nameToId[name] = id }
        for ((funnelName, stepNames) in funnelDefs) {
            val stepIds = stepNames.map { nameToId[it] ?: -1 }
            if (stepIds.any { it < 0 } || stepIds.size < 2) continue
            // reached[i] = sessions that reached step i in order.
            val reached = IntArray(stepIds.size)
            for (s in closed) {
                var from = 0
                for ((stepIndex, stepId) in stepIds.withIndex()) {
                    var found = -1
                    for (k in from until s.size) if (s[k] == stepId) { found = k; break }
                    if (found < 0) break
                    reached[stepIndex]++
                    from = found + 1
                }
            }
            if (reached[0] < config.minSupport) continue
            var worstDrop = 0
            var worstIndex = -1
            for (i in 0 until stepIds.size - 1) {
                if (reached[i] == 0) break
                val drop = (reached[i] - reached[i + 1]) * 100 / reached[i]
                if (drop > worstDrop) { worstDrop = drop; worstIndex = i }
            }
            if (worstIndex < 0 || worstDrop < 30) continue
            out += Insight(
                type = InsightType.FUNNEL_LEAK,
                title = "'$funnelName' leaks $worstDrop% at '${stepNames[worstIndex]}' → '${stepNames[worstIndex + 1]}'",
                detail = "Reached per step: " + stepNames.indices
                    .joinToString(" → ") { "'${stepNames[it]}' ${reached[it]}" },
                recommendation = "The '$funnelName' funnel loses most users between " +
                    "'${stepNames[worstIndex]}' and '${stepNames[worstIndex + 1]}'. " +
                    "Shorten, reassure, or remove friction at exactly that hop.",
                strength = min(1.0, worstDrop / 100.0 + 0.25),
                sampleSessions = reached[0],
            )
        }
        return out
    }

    private fun mineCommonPaths(closed: List<IntArray>, dict: List<String>): List<Insight> {
        val counts = HashMap<List<Int>, Int>()
        for (s in closed) {
            for (i in 0..s.size - 3) {
                val gram = s.slice(i until i + 3)
                counts[gram] = (counts[gram] ?: 0) + 1
            }
        }
        val best = counts.entries.maxByOrNull { it.value } ?: return emptyList()
        if (best.value < config.minSupport) return emptyList()
        val path = best.key.joinToString(" → ") { "'${dict[it]}'" }
        return listOf(
            Insight(
                type = InsightType.COMMON_PATH,
                title = "Most common journey: $path",
                detail = "Seen ${best.value} times across ${closed.size} sessions.",
                recommendation = "This is the highway through your app — make each hop on it " +
                    "one tap, and consider a shortcut from the first step to the last.",
                strength = min(1.0, best.value.toDouble() / closed.size),
                sampleSessions = best.value,
            )
        )
    }

    private fun mineEntryPoints(closed: List<IntArray>, dict: List<String>): List<Insight> {
        val firsts = HashMap<Int, Int>()
        for (s in closed) if (s.isNotEmpty()) firsts[s[0]] = (firsts[s[0]] ?: 0) + 1
        val total = closed.size
        val best = firsts.entries.maxByOrNull { it.value } ?: return emptyList()
        val pct = best.value * 100 / total
        if (best.value < config.minSupport || pct < 40) return emptyList()
        return listOf(
            Insight(
                type = InsightType.ENTRY_POINT,
                title = "$pct% of sessions start at '${dict[best.key]}'",
                detail = "${best.value} of $total sessions began there.",
                recommendation = "'${dict[best.key]}' is your real front door — put the most " +
                    "valuable next action on it, not a splash of everything.",
                strength = min(1.0, pct / 100.0 * 0.8),
                sampleSessions = best.value,
            )
        )
    }

    // ------------------------------------------------------------- sessions

    private fun closeSession() {
        synchronized(lock) { closeSessionLocked() }
        save()
    }

    private fun closeSessionLocked() {
        if (current.size >= 2) {
            sessions.add(current.toIntArray())
            while (sessions.size > config.maxSessions) sessions.removeAt(0)
        }
        current.clear()
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
                val dict = root.optJSONArray("names") ?: JSONArray()
                for (i in 0 until dict.length()) {
                    names.add(dict.getString(i))
                    ids[dict.getString(i)] = i
                }
                val sess = root.optJSONArray("sessions") ?: JSONArray()
                for (i in 0 until sess.length()) {
                    val arr = sess.getJSONArray(i)
                    val s = IntArray(arr.length()) { k -> arr.getInt(k) }
                    if (s.all { it in names.indices }) sessions.add(s)
                }
                val f = root.optJSONObject("funnels") ?: JSONObject()
                for (key in f.keys()) {
                    val arr = f.getJSONArray(key)
                    funnels[key] = List(arr.length()) { k -> arr.getString(k) }
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
        val dict = JSONArray()
        for (name in names) dict.put(name)
        val sess = JSONArray()
        for (s in sessions) {
            val arr = JSONArray()
            for (id in s) arr.put(id)
            sess.put(arr)
        }
        val f = JSONObject()
        for ((key, steps) in funnels) {
            val arr = JSONArray()
            for (step in steps) arr.put(step)
            f.put(key, arr)
        }
        return JSONObject().put("names", dict).put("sessions", sess).put("funnels", f)
    }
}
