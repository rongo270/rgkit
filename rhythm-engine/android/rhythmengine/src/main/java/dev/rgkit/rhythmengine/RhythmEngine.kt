package dev.rgkit.rhythmengine

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/** A candidate engagement window with its learned score. */
data class EngageWindow(
    /** Start of the window (epoch millis, on the hour). */
    val startAt: Long,
    val hourOfDay: Int,
    /** 0–1 relative to this user's best hour. */
    val score: Double,
)

/**
 * RhythmEngine — learns *when* this user lives inside your app, and turns it
 * into decisions:
 *
 *  - [bestTimeToEngage] — the smartest moment to send that notification
 *  - [nextExpectedOpenAt] — when they'll probably come back on their own
 *  - [expectedSessionMinutes] — how long a session started now would last
 *    (don't start a 10-minute flow in a 90-second window)
 *  - [churnRisk] — how unusual the current silence is, 0–1
 *
 * One `init()` — opens and session lengths are tracked automatically. All
 * learning is a small on-device JSON file with exponential decay, so the
 * rhythm follows the user as their life changes.
 */
object RhythmEngine {

    private const val TAG = "RhythmEngine"
    private const val FILE_NAME = "rhythm_engine.json"
    private const val GAP_SAMPLES = 200
    private const val LEN_SAMPLES_PER_HOUR = 30
    private const val DECAY_PER_DAY = 0.985

    private val lock = Any()
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "RhythmEngine-io") }

    private var appContext: Context? = null
    private var loaded = false

    // ---- learned state ----
    private val hourOfWeek = DoubleArray(168)              // decayed open counts
    private val gaps = ArrayDeque<Long>()                  // between session starts (ms)
    private val lengthsByHour = Array(24) { ArrayDeque<Long>() } // session ms per hour-of-day
    private val dailyOpens = HashMap<String, Int>()        // dayKey -> opens
    private var lastOpenAt = 0L
    private var lastDecayDay = ""
    private var totalOpens = 0

    // ---- session tracking ----
    private var startedActivities = 0
    private var sessionStartedAt = 0L

    // ------------------------------------------------------------------ init

    /** Call once in Application.onCreate(). */
    fun init(application: Application) {
        synchronized(lock) {
            if (appContext != null) return
            appContext = application
        }
        io.execute { load(application) }
        application.registerActivityLifecycleCallbacks(object :
            Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(lock) {
                    if (startedActivities == 0) onAppOpen()
                    startedActivities++
                }
            }
            override fun onActivityStopped(activity: Activity) {
                synchronized(lock) {
                    startedActivities--
                    if (startedActivities <= 0) { startedActivities = 0; onAppClose() }
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    // --------------------------------------------------------------- queries

    /**
     * Best hours to engage within the next [withinHours], best first.
     * Scores are relative to this user's own peak hour (1.0 = their best).
     * Empty until ~10 opens have been observed.
     */
    fun bestTimeToEngage(withinHours: Int = 24, top: Int = 3, now: Long = System.currentTimeMillis()): List<EngageWindow> {
        synchronized(lock) {
            if (totalOpens < 10) return emptyList()
            // Smooth with neighbours so a 7:58pm-often user scores 8pm too.
            fun smoothed(index: Int): Double =
                hourOfWeek[index] * 0.7 +
                    hourOfWeek[(index + 167) % 168] * 0.15 +
                    hourOfWeek[(index + 1) % 168] * 0.15

            // Normalised against the best *smoothed* hour, so this user's own
            // peak scores the documented 1.0. Dividing by the raw peak instead
            // capped a lone spike at 0.7, since smoothing gives 30% of every
            // hour's weight to its neighbours.
            val peak = (0 until 168).maxOf { smoothed(it) }
            if (peak <= 0.0) return emptyList()
            val cal = Calendar.getInstance()
            val windows = ArrayList<EngageWindow>()
            for (offset in 0 until withinHours) {
                cal.timeInMillis = now + offset * 3_600_000L
                cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                windows += EngageWindow(
                    startAt = cal.timeInMillis,
                    hourOfDay = cal.get(Calendar.HOUR_OF_DAY),
                    score = ((smoothed(hourOfWeekIndex(cal)) / peak) * 100).toInt() / 100.0,
                )
            }
            return windows.sortedByDescending { it.score }.take(top)
        }
    }

    /** Estimated next self-initiated open (last open + median gap). Null early on. */
    fun nextExpectedOpenAt(): Long? = synchronized(lock) {
        if (gaps.size < 5 || lastOpenAt == 0L) return null
        lastOpenAt + median(gaps.toList())
    }

    /** Expected session length (minutes) for a session starting around [at]. */
    fun expectedSessionMinutes(at: Long = System.currentTimeMillis()): Double? {
        synchronized(lock) {
            val cal = Calendar.getInstance().apply { timeInMillis = at }
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            // Pool ±1 hour so sparse hours still answer.
            val pooled = ArrayList<Long>()
            for (h in intArrayOf((hour + 23) % 24, hour, (hour + 1) % 24)) {
                pooled.addAll(lengthsByHour[h])
            }
            if (pooled.size < 5) {
                val all = lengthsByHour.flatMap { it }
                if (all.size < 5) return null
                return (median(all) / 6000) / 10.0
            }
            return (median(pooled) / 6000) / 10.0
        }
    }

    /**
     * 0–1: how worrying the current silence is.
     * ~0.0–0.4 normal rhythm · 0.5+ unusually quiet · 0.75+ probably churning.
     * Blends the current gap's percentile against this user's own history with
     * the week-over-week open trend.
     */
    fun churnRisk(now: Long = System.currentTimeMillis()): Double {
        synchronized(lock) {
            if (gaps.size < 8 || lastOpenAt == 0L) return 0.0
            val currentGap = now - lastOpenAt
            val sorted = gaps.toList().sorted()
            val below = sorted.count { it < currentGap }
            val percentile = below.toDouble() / sorted.size

            val last7 = opensInWindow(now, 7)
            val prev7 = opensInWindow(now - 7L * 86_400_000, 7)
            val decline = if (prev7 == 0) 0.0 else {
                max(0.0, (prev7 - last7).toDouble() / prev7)
            }
            return ((percentile * 0.65 + decline * 0.35) * 100).toInt() / 100.0
        }
    }

    /** True when the current gap exceeds 90% of this user's historical gaps. */
    fun isUnusuallyQuiet(now: Long = System.currentTimeMillis()): Boolean {
        synchronized(lock) {
            if (gaps.size < 8 || lastOpenAt == 0L) return false
            val sorted = gaps.toList().sorted()
            val p90 = sorted[min(sorted.size - 1, (sorted.size * 9) / 10)]
            return now - lastOpenAt > p90
        }
    }

    /** Percent change of opens, last 7 days vs the 7 before. Null without baseline. */
    fun engagementTrend(now: Long = System.currentTimeMillis()): Int? {
        synchronized(lock) {
            val last7 = opensInWindow(now, 7)
            val prev7 = opensInWindow(now - 7L * 86_400_000, 7)
            if (prev7 == 0) return null
            return (last7 - prev7) * 100 / prev7
        }
    }

    /** 7×24 open-intensity matrix (Mon..Sun × hour), normalized 0–1 — heatmap-ready. */
    fun weeklyPattern(): Array<DoubleArray> = synchronized(lock) {
        val peak = max(1e-9, hourOfWeek.max())
        Array(7) { day ->
            DoubleArray(24) { hour ->
                ((hourOfWeek[day * 24 + hour] / peak) * 100).toInt() / 100.0
            }
        }
    }

    fun totalOpens(): Int = synchronized(lock) { totalOpens }

    fun exportJson(): String = synchronized(lock) { toJson().toString(2) }

    fun reset() {
        synchronized(lock) {
            hourOfWeek.fill(0.0); gaps.clear(); dailyOpens.clear()
            for (dq in lengthsByHour) dq.clear()
            lastOpenAt = 0; totalOpens = 0
            lastDecayDay = ""; sessionStartedAt = 0
        }
        save()
    }

    // ------------------------------------------------------------- recording

    /**
     * Called with the lock held. Records an app open at [now]. The clock is a
     * parameter so unit tests can lay down a history in one pass that real
     * usage would take weeks to build.
     */
    internal fun onAppOpen(now: Long = System.currentTimeMillis()) {
        // Ignore rotations / instant relaunches.
        if (lastOpenAt > 0 && now - lastOpenAt < 30_000) {
            sessionStartedAt = if (sessionStartedAt == 0L) now else sessionStartedAt
            return
        }
        decayIfNewDay(now)
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        hourOfWeek[hourOfWeekIndex(cal)] += 1.0
        if (lastOpenAt > 0) {
            gaps.addLast(now - lastOpenAt)
            while (gaps.size > GAP_SAMPLES) gaps.removeFirst()
        }
        lastOpenAt = now
        sessionStartedAt = now
        totalOpens++
        val day = dayKey(now)
        dailyOpens[day] = (dailyOpens[day] ?: 0) + 1
        // Prune day counts beyond 30 days.
        if (dailyOpens.size > 40) {
            val cutoff = dayKey(now - 30L * 86_400_000)
            dailyOpens.keys.removeAll { it < cutoff }
        }
        save()
    }

    /** Called with the lock held. [now] is injectable for the same reason as [onAppOpen]. */
    internal fun onAppClose(now: Long = System.currentTimeMillis()) {
        if (sessionStartedAt == 0L) return
        val length = now - sessionStartedAt
        sessionStartedAt = 0
        if (length < 1_000) return
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)
        val dq = lengthsByHour[hour]
        dq.addLast(length)
        while (dq.size > LEN_SAMPLES_PER_HOUR) dq.removeFirst()
        save()
    }

    private fun decayIfNewDay(now: Long) {
        val today = dayKey(now)
        if (lastDecayDay == today) return
        if (lastDecayDay.isNotEmpty()) {
            // Days elapsed approximated by 1+ (cheap and monotone) — decay once per new day seen.
            for (i in hourOfWeek.indices) hourOfWeek[i] *= DECAY_PER_DAY
        }
        lastDecayDay = today
    }

    private fun opensInWindow(endExclusive: Long, days: Int): Int {
        var count = 0
        for (d in 0 until days) {
            count += dailyOpens[dayKey(endExclusive - d.toLong() * 86_400_000)] ?: 0
        }
        return count
    }

    // --------------------------------------------------------------- helpers

    private fun hourOfWeekIndex(cal: Calendar): Int {
        val day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
        return day * 24 + cal.get(Calendar.HOUR_OF_DAY)
    }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun dayKey(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time))

    // ------------------------------------------------------------ persistence

    private fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
            try {
                val file = File(context.filesDir, FILE_NAME)
                if (!file.exists()) return
                val root = JSONObject(file.readText())
                val how = root.optJSONArray("hourOfWeek") ?: JSONArray()
                for (i in 0 until min(168, how.length())) hourOfWeek[i] = how.getDouble(i)
                val g = root.optJSONArray("gaps") ?: JSONArray()
                for (i in 0 until g.length()) gaps.addLast(g.getLong(i))
                val lens = root.optJSONArray("lengthsByHour") ?: JSONArray()
                for (h in 0 until min(24, lens.length())) {
                    val arr = lens.getJSONArray(h)
                    for (i in 0 until arr.length()) lengthsByHour[h].addLast(arr.getLong(i))
                }
                val days = root.optJSONObject("dailyOpens") ?: JSONObject()
                for (k in days.keys()) dailyOpens[k] = days.getInt(k)
                lastOpenAt = root.optLong("lastOpenAt", 0)
                lastDecayDay = root.optString("lastDecayDay", "")
                totalOpens = root.optInt("totalOpens", 0)
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
        val how = JSONArray()
        for (v in hourOfWeek) how.put((v * 1000).toInt() / 1000.0)
        val g = JSONArray()
        for (v in gaps) g.put(v)
        val lens = JSONArray()
        for (dq in lengthsByHour) {
            val arr = JSONArray()
            for (v in dq) arr.put(v)
            lens.put(arr)
        }
        val days = JSONObject()
        for ((k, v) in dailyOpens) days.put(k, v)
        return JSONObject()
            .put("hourOfWeek", how)
            .put("gaps", g)
            .put("lengthsByHour", lens)
            .put("dailyOpens", days)
            .put("lastOpenAt", lastOpenAt)
            .put("lastDecayDay", lastDecayDay)
            .put("totalOpens", totalOpens)
    }
}
