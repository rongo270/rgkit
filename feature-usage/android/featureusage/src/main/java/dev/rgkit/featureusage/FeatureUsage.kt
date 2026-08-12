package dev.rgkit.featureusage

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Usage statistics for a single feature. Immutable snapshot — safe to hold
 * on any thread and to render from Compose.
 */
data class FeatureStat(
    val name: String,
    val total: Int,
    val firstUsedAt: Long,
    val lastUsedAt: Long,
    /** Per-day counts keyed by local date "yyyy-MM-dd". Pruned to the last 365 days. */
    val daily: Map<String, Int>,
    /** Lifetime counts per hour of day (0–23). Never pruned. */
    val hourly: Map<Int, Int> = emptyMap(),
    /** Total time recorded via begin()/end(), in milliseconds. */
    val totalDurationMs: Long = 0,
    /** Number of begin()/end() sessions that contributed to [totalDurationMs]. */
    val timedSessions: Int = 0,
) {
    /** Number of distinct days with at least one use (within the retention window). */
    val activeDays: Int get() = daily.size

    /** Average begin()/end() session length in milliseconds, or 0 without timed data. */
    val averageSessionMs: Long
        get() = if (timedSessions > 0) totalDurationMs / timedSessions else 0

    /** Total uses within the last [days] days (including today). */
    fun countLastDays(days: Int, from: Long = System.currentTimeMillis()): Int =
        dailyCounts(days, from).sum()

    /** Counts for each of the last [days] days, oldest first — ready for a sparkline. */
    fun dailyCounts(days: Int, from: Long = System.currentTimeMillis()): List<Int> =
        (days - 1 downTo 0).map { offset ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            cal.add(Calendar.DAY_OF_YEAR, -offset)
            daily[FeatureUsage.dayKey(cal.timeInMillis)] ?: 0
        }

    /** True when the feature has not been used for [days] days or more. */
    fun isStale(days: Int = 30, from: Long = System.currentTimeMillis()): Boolean =
        from - lastUsedAt >= days * DAY_MILLIS

    /**
     * Change of the last [days] days vs the [days] before them, as a rounded
     * percentage (25 = up 25%, -50 = halved). Null when the previous window had
     * no uses, so there is nothing to compare against.
     */
    fun trendPercent(days: Int = 7, from: Long = System.currentTimeMillis()): Int? {
        val current = countLastDays(days, from)
        val previous = countLastDays(days, from - days * DAY_MILLIS)
        if (previous == 0) return null
        return ((current - previous) * 100.0 / previous).roundToInt()
    }

    /** Average uses per active day (days with at least one use). */
    fun averagePerActiveDay(): Double =
        if (daily.isEmpty()) 0.0 else daily.values.sum().toDouble() / daily.size

    /** Lifetime counts for each hour of day, index 0 = midnight–1am … index 23. */
    fun hourlyCounts(): List<Int> = (0..23).map { hourly[it] ?: 0 }

    /** Counts summed by day of week, Monday first (index 0 = Mon … 6 = Sun). */
    fun weekdayCounts(): List<Int> {
        val counts = IntArray(7)
        for ((key, value) in daily) {
            val cal = parseDayKey(key) ?: continue
            counts[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += value
        }
        return counts.toList()
    }

    /**
     * Consecutive days with at least one use, counting back from today.
     * A day with no use yet today does not break the streak until tomorrow.
     */
    fun currentStreakDays(from: Long = System.currentTimeMillis()): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        if (FeatureUsage.dayKey(cal.timeInMillis) !in daily) cal.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        while (FeatureUsage.dayKey(cal.timeInMillis) in daily) {
            streak += 1
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
    }

    /** Longest run of consecutive days with use (within the retention window). */
    fun bestStreakDays(): Int {
        if (daily.isEmpty()) return 0
        val keys = daily.keys.sorted()
        var best = 1
        var run = 1
        for (i in 1 until keys.size) {
            val prev = parseDayKey(keys[i - 1])
            if (prev == null) {
                run = 1
                continue
            }
            prev.add(Calendar.DAY_OF_YEAR, 1)
            run = if (FeatureUsage.dayKey(prev.timeInMillis) == keys[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    private fun parseDayKey(key: String): Calendar? {
        val parts = key.split("-")
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return Calendar.getInstance().apply {
            clear()
            set(year, month - 1, day)
        }
    }

    internal companion object {
        const val DAY_MILLIS = 86_400_000L
    }
}

/** One recorded use, for the recent-events timeline. */
data class UsageEvent(val name: String, val at: Long)

/** An automatically generated finding about recorded usage. */
data class UsageInsight(
    val kind: Kind,
    val title: String,
    val detail: String,
    /** The feature the insight is about, when it concerns a single feature. */
    val feature: String? = null,
) {
    enum class Kind { RISING, FALLING, STALE, STREAK, PEAK_HOUR, PEAK_DAY, CONCENTRATION, NEW }
}

/**
 * Local-first feature usage tracking. No backend, no network, no account —
 * everything is stored in small JSON files inside the app's sandbox.
 *
 * Initialize once in Application.onCreate():
 *
 *     FeatureUsage.init(this)
 *
 * then record uses anywhere:
 *
 *     FeatureUsage.track("export_pdf")
 *     FeatureUsage.begin("editor"); FeatureUsage.end("editor")  // timed session
 *
 * Show [FeatureUsageScreen] anywhere (e.g. a debug menu) to see the numbers.
 */
object FeatureUsage {
    private const val TAG = "FeatureUsage"
    private const val RETENTION_DAYS = 365
    private const val EVENT_CAP = 500
    private const val MAX_SESSION_MILLIS = 6 * 60 * 60 * 1000L

    private val lock = Any()
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FeatureUsage-save").apply { isDaemon = true }
    }
    private var file: File? = null
    private var eventsFile: File? = null
    private val features = LinkedHashMap<String, MutableStat>()
    private val events = ArrayDeque<UsageEvent>()
    private val pendingStarts = HashMap<String, Long>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** Set false to turn all recording into a no-op (e.g. in release builds). */
    @Volatile
    @JvmStatic
    var enabled: Boolean = true

    private class MutableStat(
        val name: String,
        var total: Int,
        var firstUsedAt: Long,
        var lastUsedAt: Long,
        val daily: MutableMap<String, Int>,
        val hourly: MutableMap<Int, Int> = mutableMapOf(),
        var totalDurationMs: Long = 0,
        var timedSessions: Int = 0,
    ) {
        fun record(at: Long, count: Int = 1, durationMs: Long = 0) {
            total += count
            if (at > lastUsedAt) lastUsedAt = at
            if (at < firstUsedAt) firstUsedAt = at
            val key = dayKey(at)
            daily[key] = (daily[key] ?: 0) + count
            val hour = Calendar.getInstance()
                .apply { timeInMillis = at }
                .get(Calendar.HOUR_OF_DAY)
            hourly[hour] = (hourly[hour] ?: 0) + count
            if (durationMs > 0) {
                totalDurationMs += durationMs
                timedSessions += 1
            }
        }

        fun prune(keepingDays: Int, from: Long) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            cal.add(Calendar.DAY_OF_YEAR, -(keepingDays - 1))
            val cutoffKey = dayKey(cal.timeInMillis)
            // Keys are zero-padded yyyy-MM-dd, so string order is date order.
            daily.keys.retainAll { it >= cutoffKey }
        }

        fun snapshot() = FeatureStat(
            name, total, firstUsedAt, lastUsedAt, daily.toMap(), hourly.toMap(),
            totalDurationMs, timedSessions,
        )
    }

    // MARK: Public API — recording

    /** Call once from Application.onCreate(). Idempotent. */
    @JvmStatic
    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = context.applicationContext.filesDir
            file = File(dir, "feature_usage.json")
            eventsFile = File(dir, "feature_usage_events.json")
            load()
            loadEvents()
        }
    }

    /**
     * Records one use of a feature and returns the new total (1 means first
     * use ever — handy for one-time tips). Safe to call from any thread; disk
     * writes happen on a background thread, so it is cheap enough for every
     * tap. Empty names are ignored.
     */
    @JvmStatic
    fun track(name: String): Int = track(name, System.currentTimeMillis(), 1)

    /** Records [count] uses at once (e.g. "imported 12 photos"). Returns the new total. */
    @JvmStatic
    fun track(name: String, count: Int): Int {
        if (count <= 0) return count(name)
        return track(name, System.currentTimeMillis(), count)
    }

    /**
     * Starts a timed session for a feature. Pair with [end]; the elapsed time
     * accumulates into the feature's time-spent stats. Calling begin() again
     * before end() restarts the clock.
     */
    @JvmStatic
    fun begin(name: String) {
        val key = name.trim()
        if (key.isEmpty() || !enabled) return
        synchronized(lock) { pendingStarts[key] = System.currentTimeMillis() }
    }

    /**
     * Ends a timed session started with [begin] and records one use with the
     * elapsed duration (capped at 6h). Without a matching begin() it just
     * records a plain use. Returns the new total.
     */
    @JvmStatic
    fun end(name: String): Int {
        val key = name.trim()
        if (key.isEmpty() || !enabled) return count(name)
        val now = System.currentTimeMillis()
        var duration = 0L
        synchronized(lock) {
            pendingStarts.remove(key)?.let {
                duration = (now - it).coerceIn(0, MAX_SESSION_MILLIS)
            }
        }
        return track(key, now, 1, duration)
    }

    // MARK: Public API — reading

    /** All features, most used first. */
    @JvmStatic
    fun stats(): List<FeatureStat> = synchronized(lock) {
        features.values
            .map { it.snapshot() }
            .sortedWith(compareByDescending<FeatureStat> { it.total }.thenBy { it.name })
    }

    /** Stats for one feature, or null if it was never tracked. */
    @JvmStatic
    fun stat(name: String): FeatureStat? = synchronized(lock) {
        features[name.trim()]?.snapshot()
    }

    /** Total recorded uses of one feature. */
    @JvmStatic
    fun count(name: String): Int = stat(name)?.total ?: 0

    /** The most recent recorded events, newest first. Kept for the last 500 uses. */
    @JvmStatic
    fun recentEvents(limit: Int = 100): List<UsageEvent> = synchronized(lock) {
        events.takeLast(limit).reversed()
    }

    /**
     * Automatically generated findings: trending features, streaks, stale
     * features, peak hour/day, and more. Empty until there is enough data.
     */
    @JvmStatic
    fun insights(now: Long = System.currentTimeMillis()): List<UsageInsight> {
        val all = stats()
        if (all.isEmpty()) return emptyList()
        val result = mutableListOf<UsageInsight>()

        val trends = all.mapNotNull { s -> s.trendPercent(7, now)?.let { s to it } }
        trends.filter { it.second > 0 && it.first.countLastDays(7, now) >= 3 }
            .maxByOrNull { it.second }
            ?.let { (s, p) ->
                result += UsageInsight(
                    UsageInsight.Kind.RISING,
                    "\"${s.name}\" is trending up",
                    "+$p% vs the previous week (${s.countLastDays(7, now)} uses in 7 days)",
                    s.name,
                )
            }
        trends.filter {
            it.second < 0 && it.first.countLastDays(7, now - 7 * FeatureStat.DAY_MILLIS) >= 3
        }
            .minByOrNull { it.second }
            ?.let { (s, p) ->
                result += UsageInsight(
                    UsageInsight.Kind.FALLING,
                    "\"${s.name}\" is dropping",
                    "$p% vs the previous week",
                    s.name,
                )
            }

        val streakLeader = all.maxByOrNull { it.currentStreakDays(now) }
        if (streakLeader != null && streakLeader.currentStreakDays(now) >= 3) {
            result += UsageInsight(
                UsageInsight.Kind.STREAK,
                "\"${streakLeader.name}\" is on a streak",
                "Used ${streakLeader.currentStreakDays(now)} days in a row",
                streakLeader.name,
            )
        }

        val stale = all.filter { it.isStale(30, now) }
        if (stale.isNotEmpty()) {
            val names = stale.take(5).joinToString(", ") { it.name } +
                if (stale.size > 5) ", …" else ""
            result += UsageInsight(
                UsageInsight.Kind.STALE,
                "${stale.size} feature${if (stale.size == 1) "" else "s"} unused for 30+ days",
                names,
            )
        }

        val hourTotals = IntArray(24)
        all.forEach { s -> s.hourlyCounts().forEachIndexed { h, c -> hourTotals[h] += c } }
        val hourSum = hourTotals.sum()
        if (hourSum >= 20) {
            val peak = hourTotals.indices.maxByOrNull { hourTotals[it] } ?: 0
            result += UsageInsight(
                UsageInsight.Kind.PEAK_HOUR,
                String.format(Locale.US, "Peak hour: %02d:00–%02d:00", peak, (peak + 1) % 24),
                "${hourTotals[peak] * 100 / hourSum}% of all recorded uses",
            )
        }

        val dayTotals = IntArray(7)
        all.forEach { s -> s.weekdayCounts().forEachIndexed { d, c -> dayTotals[d] += c } }
        val daySum = dayTotals.sum()
        if (daySum >= 20) {
            val peak = dayTotals.indices.maxByOrNull { dayTotals[it] } ?: 0
            val names = listOf(
                "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
            )
            result += UsageInsight(
                UsageInsight.Kind.PEAK_DAY,
                "Busiest day: ${names[peak]}",
                "${dayTotals[peak] * 100 / daySum}% of all recorded uses",
            )
        }

        if (all.size >= 4) {
            val total = all.sumOf { it.total }
            val top3 = all.take(3).sumOf { it.total }
            if (total > 0 && top3 * 100 / total >= 60) {
                result += UsageInsight(
                    UsageInsight.Kind.CONCENTRATION,
                    "Usage is concentrated",
                    "Top 3 features account for ${top3 * 100 / total}% of all usage",
                )
            }
        }

        val fresh = all.filter { now - it.firstUsedAt < 7 * FeatureStat.DAY_MILLIS }
        if (fresh.isNotEmpty()) {
            result += UsageInsight(
                UsageInsight.Kind.NEW,
                "New this week",
                fresh.take(5).joinToString(", ") { it.name } + if (fresh.size > 5) ", …" else "",
            )
        }
        return result
    }

    // MARK: Public API — listeners

    /** Registers a callback invoked after any recorded change (any thread). */
    @JvmStatic
    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Removes a callback added with [addChangeListener]. */
    @JvmStatic
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    // MARK: Public API — export / import / wipe

    /** Full data as pretty-printed JSON (includes per-day and per-hour history). */
    @JvmStatic
    fun exportJson(): String {
        val iso = isoFormatter()
        val array = JSONArray()
        for (stat in stats()) {
            val obj = JSONObject()
            obj.put("name", stat.name)
            obj.put("total", stat.total)
            obj.put("firstUsedAt", iso.format(Date(stat.firstUsedAt)))
            obj.put("lastUsedAt", iso.format(Date(stat.lastUsedAt)))
            obj.put("daily", JSONObject(stat.daily as Map<*, *>))
            obj.put("hourly", JSONObject(stat.hourly.mapKeys { it.key.toString() } as Map<*, *>))
            obj.put("totalDurationMs", stat.totalDurationMs)
            obj.put("timedSessions", stat.timedSessions)
            array.put(obj)
        }
        return array.toString(2)
    }

    /** Summary as CSV: one row per feature. */
    @JvmStatic
    fun exportCsv(): String {
        val iso = isoFormatter()
        val now = System.currentTimeMillis()
        val lines = mutableListOf(
            "feature,total,first_used,last_used,last_7_days,last_30_days,trend_7d_pct,active_days",
        )
        for (stat in stats()) {
            val name = if ("," in stat.name) "\"${stat.name}\"" else stat.name
            lines += listOf(
                name,
                stat.total.toString(),
                iso.format(Date(stat.firstUsedAt)),
                iso.format(Date(stat.lastUsedAt)),
                stat.countLastDays(7, now).toString(),
                stat.countLastDays(30, now).toString(),
                stat.trendPercent(7, now)?.toString() ?: "",
                stat.activeDays.toString(),
            ).joinToString(",")
        }
        return lines.joinToString("\n")
    }

    /**
     * Merges an [exportJson] payload (from this or another device) into the
     * local data: totals, daily/hourly buckets and durations are summed,
     * first/last-used widened. Returns the number of features merged, or 0 if
     * the payload could not be parsed.
     */
    @JvmStatic
    fun importJson(json: String): Int {
        var merged = 0
        synchronized(lock) {
            if (file == null) return 0
            try {
                val array = JSONArray(json)
                val now = System.currentTimeMillis()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val name = obj.optString("name").trim()
                    if (name.isEmpty()) continue
                    val first = parseTimestamp(obj.opt("firstUsedAt"))
                    val last = parseTimestamp(obj.opt("lastUsedAt"))
                    val existing = features[name]
                    val stat = existing ?: MutableStat(
                        name, 0, first ?: last ?: now, last ?: first ?: now, mutableMapOf(),
                    ).also { features[name] = it }
                    if (existing != null) {
                        if (first != null && first < stat.firstUsedAt) stat.firstUsedAt = first
                        if (last != null && last > stat.lastUsedAt) stat.lastUsedAt = last
                    }
                    stat.total += obj.optInt("total", 0)
                    obj.optJSONObject("daily")?.let { d ->
                        for (k in d.keys()) stat.daily[k] = (stat.daily[k] ?: 0) + d.getInt(k)
                    }
                    obj.optJSONObject("hourly")?.let { h ->
                        for (k in h.keys()) {
                            val hour = k.toIntOrNull() ?: continue
                            stat.hourly[hour] = (stat.hourly[hour] ?: 0) + h.getInt(k)
                        }
                    }
                    stat.totalDurationMs += obj.optLong("totalDurationMs", 0)
                    stat.timedSessions += obj.optInt("timedSessions", 0)
                    stat.prune(RETENTION_DAYS, now)
                    merged += 1
                }
                if (merged > 0) scheduleSave()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import usage data", e)
                return 0
            }
        }
        if (merged > 0) notifyListeners()
        return merged
    }

    /** Deletes all recorded usage (including the event timeline). */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            features.clear()
            events.clear()
            pendingStarts.clear()
            scheduleSave()
            scheduleEventsSave()
        }
        notifyListeners()
    }

    /** Deletes recorded usage for one feature. */
    @JvmStatic
    fun reset(name: String) {
        val key = name.trim()
        synchronized(lock) {
            features.remove(key)
            events.removeAll { it.name == key }
            pendingStarts.remove(key)
            scheduleSave()
            scheduleEventsSave()
        }
        notifyListeners()
    }

    // MARK: Internals

    internal fun track(name: String, at: Long, count: Int = 1, durationMs: Long = 0): Int {
        val key = name.trim()
        if (key.isEmpty() || !enabled) return count(key)
        val total: Int
        synchronized(lock) {
            if (file == null) {
                Log.w(TAG, "track(\"$key\") ignored — call FeatureUsage.init(context) first")
                return 0
            }
            val stat = features.getOrPut(key) {
                MutableStat(key, 0, at, at, mutableMapOf())
            }
            stat.record(at, count, durationMs)
            stat.prune(RETENTION_DAYS, at)
            events.addLast(UsageEvent(key, at))
            while (events.size > EVENT_CAP) events.removeFirst()
            scheduleSave()
            scheduleEventsSave()
            total = stat.total
        }
        notifyListeners()
        return total
    }

    /** Local calendar day as zero-padded "yyyy-MM-dd". Thread-safe. */
    internal fun dayKey(timeMillis: Long): String {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )
    }

    /** Points storage at [directory] and reloads — used by unit tests. */
    internal fun initForTest(directory: File) {
        synchronized(lock) {
            directory.mkdirs()
            file = File(directory, "feature_usage.json")
            eventsFile = File(directory, "feature_usage_events.json")
            features.clear()
            events.clear()
            pendingStarts.clear()
            enabled = true
            load()
            loadEvents()
        }
    }

    /** Blocks until all scheduled disk writes have completed — used by unit tests. */
    internal fun awaitWrites() {
        saveExecutor.submit { }.get()
    }

    private fun notifyListeners() {
        for (listener in listeners) {
            runCatching { listener() }
        }
    }

    private fun parseTimestamp(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> runCatching { isoFormatter().parse(value)?.time }.getOrNull()
        else -> null
    }

    private fun isoFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

    /** Must be called while holding [lock]. */
    private fun scheduleSave() {
        val target = file ?: return
        val json = toStorageJson()
        saveExecutor.execute { writeAtomically(target, json) }
    }

    /** Must be called while holding [lock]. */
    private fun scheduleEventsSave() {
        val target = eventsFile ?: return
        val array = JSONArray()
        for (event in events) {
            array.put(JSONObject().put("n", event.name).put("t", event.at))
        }
        val json = array.toString()
        saveExecutor.execute { writeAtomically(target, json) }
    }

    private fun writeAtomically(target: File, json: String) {
        try {
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(target)) {
                target.writeText(json)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save usage data", e)
        }
    }

    /** Must be called while holding [lock]. */
    private fun toStorageJson(): String {
        val array = JSONArray()
        for (stat in features.values) {
            val obj = JSONObject()
            obj.put("name", stat.name)
            obj.put("total", stat.total)
            obj.put("firstUsedAt", stat.firstUsedAt)
            obj.put("lastUsedAt", stat.lastUsedAt)
            obj.put("daily", JSONObject(stat.daily as Map<*, *>))
            obj.put("hourly", JSONObject(stat.hourly.mapKeys { it.key.toString() } as Map<*, *>))
            obj.put("totalDurationMs", stat.totalDurationMs)
            obj.put("timedSessions", stat.timedSessions)
            array.put(obj)
        }
        return array.toString()
    }

    /** Must be called while holding [lock]. */
    private fun load() {
        val target = file ?: return
        if (!target.exists()) return
        try {
            val array = JSONArray(target.readText())
            val now = System.currentTimeMillis()
            features.clear()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val daily = mutableMapOf<String, Int>()
                val dailyObj = obj.optJSONObject("daily") ?: JSONObject()
                for (key in dailyObj.keys()) {
                    daily[key] = dailyObj.getInt(key)
                }
                // "hourly" and duration fields are absent in files written by
                // older versions.
                val hourly = mutableMapOf<Int, Int>()
                val hourlyObj = obj.optJSONObject("hourly") ?: JSONObject()
                for (key in hourlyObj.keys()) {
                    val hour = key.toIntOrNull() ?: continue
                    hourly[hour] = hourlyObj.getInt(key)
                }
                val stat = MutableStat(
                    name = obj.getString("name"),
                    total = obj.getInt("total"),
                    firstUsedAt = obj.getLong("firstUsedAt"),
                    lastUsedAt = obj.getLong("lastUsedAt"),
                    daily = daily,
                    hourly = hourly,
                    totalDurationMs = obj.optLong("totalDurationMs", 0),
                    timedSessions = obj.optInt("timedSessions", 0),
                )
                stat.prune(RETENTION_DAYS, now)
                features[stat.name] = stat
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load usage data, starting fresh", e)
            features.clear()
        }
    }

    /** Must be called while holding [lock]. */
    private fun loadEvents() {
        val target = eventsFile ?: return
        events.clear()
        if (!target.exists()) return
        try {
            val array = JSONArray(target.readText())
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                events.addLast(UsageEvent(obj.getString("n"), obj.getLong("t")))
            }
            while (events.size > EVENT_CAP) events.removeFirst()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load event log, starting fresh", e)
            events.clear()
        }
    }
}
