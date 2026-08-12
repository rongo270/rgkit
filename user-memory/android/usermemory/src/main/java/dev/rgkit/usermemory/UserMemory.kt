package dev.rgkit.usermemory

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Rough part of the day, used for "morning person" style insights. */
enum class DayPart(val label: String) {
    MORNING("Morning"),
    AFTERNOON("Afternoon"),
    EVENING("Evening"),
    NIGHT("Night");

    companion object {
        fun of(hour: Int): DayPart = when (hour) {
            in 5..11 -> MORNING
            in 12..16 -> AFTERNOON
            in 17..21 -> EVENING
            else -> NIGHT
        }
    }
}

/** How engaged this user is, derived from active days in the last 28. */
enum class Engagement(val label: String) {
    NEW("Just met"),
    CASUAL("Casual user"),
    REGULAR("Regular user"),
    POWER("Power user"),
}

/** An explicitly stored preference (`set(...)`). Value is String, Boolean, Long, Double or List<String>. */
data class Preference(
    val key: String,
    val value: Any,
    val updatedAt: Long,
) {
    /** Human-readable value for display. */
    val displayValue: String
        get() = when (value) {
            is List<*> -> value.joinToString(", ")
            else -> value.toString()
        }
}

/** One option of a learned choice, with its recency-weighted share. */
data class LearnedChoice(
    val value: String,
    /** This option's share of the total decayed weight, 0..1. */
    val share: Double,
    /** Raw number of times this option was observed. */
    val count: Int,
    val lastAt: Long,
)

/**
 * What the SDK believes about one choice key after watching the user
 * (e.g. key "export_format" learned toward "pdf").
 */
data class Learned(
    val key: String,
    /** All observed options, strongest first. Never empty. */
    val choices: List<LearnedChoice>,
    /** Total observations across all options. */
    val observations: Int,
    /**
     * 0..1 — how sure the SDK is that [top] really is the user's preference.
     * Combines the top option's share with how many observations back it.
     */
    val confidence: Double,
) {
    val top: LearnedChoice get() = choices.first()
}

/**
 * A recurring action the user takes (`record(...)`). Immutable snapshot —
 * safe to hold on any thread and to render from Compose.
 */
data class Habit(
    val name: String,
    val total: Int,
    val firstAt: Long,
    val lastAt: Long,
    /** Per-day counts keyed by local date "yyyy-MM-dd". Pruned to the last 365 days. */
    val daily: Map<String, Int>,
    /** Lifetime counts per hour of day (0–23). Never pruned. */
    val hourly: Map<Int, Int> = emptyMap(),
) {
    /** Days with at least one occurrence within the last [days] days. */
    fun activeDays(days: Int, from: Long = System.currentTimeMillis()): Int =
        dailyCounts(days, from).count { it > 0 }

    /** Average occurrences-days per week over the last 28 days. */
    fun perWeek(from: Long = System.currentTimeMillis()): Double =
        activeDays(28, from) / 4.0

    /**
     * 0..1 — how established this habit is. 1.0 means done (nearly) every day
     * for the last four weeks; ~0.3 means about twice a week.
     */
    fun strength(from: Long = System.currentTimeMillis()): Double =
        min(1.0, activeDays(28, from) / 21.0)

    /** True when this qualifies as a real habit: regular and recent. */
    fun isHabit(from: Long = System.currentTimeMillis()): Boolean =
        strength(from) >= 0.3 && from - lastAt <= 14 * DAY_MILLIS

    /** True when the previous month was habit-like but activity has since halved. */
    fun isFading(from: Long = System.currentTimeMillis()): Boolean {
        val previous = activeDays(28, from - 28 * DAY_MILLIS)
        return previous >= 6 && activeDays(28, from) <= previous / 2
    }

    /** True when the habit occurred at least once today. */
    fun doneToday(from: Long = System.currentTimeMillis()): Boolean =
        UserMemory.dayKey(from) in daily

    /**
     * The hour of day this habit usually happens, or null when there is no
     * clear pattern. "Usual" means at least half of all occurrences fall in a
     * 3-hour window around the returned hour (needs 5+ occurrences).
     */
    fun typicalHour(): Int? {
        val total = hourly.values.sum()
        if (total < 5) return null
        var bestHour = 0
        var bestSum = -1
        for (hour in 0..23) {
            val sum = (hour - 1..hour + 1).sumOf { hourly[(it + 24) % 24] ?: 0 }
            // Tied windows resolve to the hour with the most direct hits.
            if (sum > bestSum || (sum == bestSum && (hourly[hour] ?: 0) > (hourly[bestHour] ?: 0))) {
                bestSum = sum
                bestHour = hour
            }
        }
        return if (bestSum.toDouble() / total >= 0.5) bestHour else null
    }

    /** The part of day this habit usually happens, or null without a clear pattern. */
    fun dayPart(): DayPart? = typicalHour()?.let { DayPart.of(it) }

    /**
     * Weekdays this habit leans toward, Monday=0 … Sunday=6. A day qualifies
     * when it holds a clearly above-average share of active days. Empty when
     * there is not enough data or no leaning.
     */
    fun typicalWeekdays(): List<Int> {
        val counts = weekdayActiveDays()
        val total = counts.sum()
        if (total < 6) return emptyList()
        return (0..6).filter { counts[it] > 0 && counts[it].toDouble() / total >= 0.21 }
    }

    /** Counts for each of the last [days] days, oldest first — ready for a sparkline. */
    fun dailyCounts(days: Int, from: Long = System.currentTimeMillis()): List<Int> =
        (days - 1 downTo 0).map { offset ->
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            cal.add(Calendar.DAY_OF_YEAR, -offset)
            daily[UserMemory.dayKey(cal.timeInMillis)] ?: 0
        }

    /** Lifetime counts for each hour of day, index 0 = midnight–1am … index 23. */
    fun hourlyCounts(): List<Int> = (0..23).map { hourly[it] ?: 0 }

    /**
     * Consecutive days with at least one occurrence, counting back from today.
     * A day with nothing yet today does not break the streak until tomorrow.
     */
    fun currentStreakDays(from: Long = System.currentTimeMillis()): Int =
        UserMemory.streakDays(daily.keys, from)

    /** Longest run of consecutive days (within the retention window). */
    fun bestStreakDays(): Int {
        if (daily.isEmpty()) return 0
        val keys = daily.keys.sorted()
        var best = 1
        var run = 1
        for (i in 1 until keys.size) {
            val prev = UserMemory.parseDayKey(keys[i - 1])
            if (prev == null) {
                run = 1
                continue
            }
            prev.add(Calendar.DAY_OF_YEAR, 1)
            run = if (UserMemory.dayKey(prev.timeInMillis) == keys[i]) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** Number of active days per weekday, Monday first (index 0 = Mon … 6 = Sun). */
    private fun weekdayActiveDays(): IntArray {
        val counts = IntArray(7)
        for (key in daily.keys) {
            val cal = UserMemory.parseDayKey(key) ?: continue
            counts[(cal.get(Calendar.DAY_OF_WEEK) + 5) % 7] += 1
        }
        return counts
    }

    internal companion object {
        const val DAY_MILLIS = UserMemory.DAY_MILLIS
    }
}

/** A snapshot of who this user is, derived from everything remembered so far. */
data class UserProfile(
    /** When the SDK first saw this user. */
    val firstSeenAt: Long,
    /** Whole days since [firstSeenAt], minimum 1. */
    val daysKnown: Int,
    /** Days with any recorded activity in the last 28. */
    val activeDays28: Int,
    /** Total recorded habit events, lifetime. */
    val eventsTotal: Int,
    val engagement: Engagement,
    /** The part of day most activity happens, or null without a clear peak. */
    val peakPart: DayPart?,
    /** True when activity clearly leans toward Saturday/Sunday. */
    val weekendLeaning: Boolean,
    /** Consecutive days (counting back from today) with any activity. */
    val currentStreakDays: Int,
    /** Recorded actions that currently qualify as habits. */
    val habitCount: Int,
    /** Choice keys with at least one observation. */
    val learnedCount: Int,
    /** Explicitly stored preferences. */
    val preferenceCount: Int,
)

enum class RecommendationKind {
    /** A habit that usually happens around now and hasn't yet today. */
    HABIT_DUE,
    /** A streak that will break unless the habit happens today. */
    STREAK_AT_RISK,
    /** A previously regular habit the user is drifting away from. */
    FADING_HABIT,
    /** A learned choice strong enough to preselect as the default. */
    LEARNED_DEFAULT,
}

/** An actionable suggestion derived from memory, strongest first from [UserMemory.recommendations]. */
data class Recommendation(
    val kind: RecommendationKind,
    /** The habit name or choice key this is about. */
    val subject: String,
    val title: String,
    val detail: String,
    /** Ranking score, higher = more relevant right now. */
    val score: Double,
)

/**
 * Universal user memory: persistent preferences, learned choices, habit
 * recognition and smart recommendations. Local-first — no backend, no network,
 * no account; everything lives in one JSON file inside the app's sandbox, and
 * `exportJson()` / `importJson()` move it between apps and platforms (the iOS
 * SDK reads the same format).
 *
 * Initialize once in Application.onCreate():
 *
 *     UserMemory.init(this)
 *
 * then, anywhere:
 *
 *     UserMemory.set("units", "metric")             // explicit preference
 *     UserMemory.observe("export_format", "pdf")    // learn from a choice
 *     UserMemory.record("workout_logged")           // habit signal
 *
 *     UserMemory.preferredValue("export_format")    // → "pdf"
 *     UserMemory.recommendations()                  // → what to surface now
 *
 * Show [UserMemoryScreen] anywhere to see (and manage) everything remembered.
 */
object UserMemory {
    private const val TAG = "UserMemory"
    private const val RETENTION_DAYS = 365
    private const val SCHEMA_VERSION = 1

    /** Half-life of a choice observation: after 30 days it counts half as much. */
    private const val CHOICE_HALF_LIFE_DAYS = 30.0

    internal const val DAY_MILLIS = 86_400_000L

    private val lock = Any()
    private val saveExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "UserMemory-save").apply { isDaemon = true }
    }
    private var file: File? = null
    private var since: Long = 0L
    private val prefs = LinkedHashMap<String, Pref>()
    private val signals = LinkedHashMap<String, LinkedHashMap<String, Signal>>()
    private val events = LinkedHashMap<String, MutableEvent>()

    private class Pref(var value: Any, var updatedAt: Long)

    private class Signal(var weight: Double, var count: Int, var lastAt: Long)

    private class MutableEvent(
        val name: String,
        var total: Int,
        var firstAt: Long,
        var lastAt: Long,
        val daily: MutableMap<String, Int>,
        val hourly: MutableMap<Int, Int> = mutableMapOf(),
    ) {
        fun record(at: Long, count: Int) {
            total += count
            if (at > lastAt) lastAt = at
            if (at < firstAt) firstAt = at
            val key = dayKey(at)
            daily[key] = (daily[key] ?: 0) + count
            val hour = Calendar.getInstance()
                .apply { timeInMillis = at }
                .get(Calendar.HOUR_OF_DAY)
            hourly[hour] = (hourly[hour] ?: 0) + count
        }

        fun prune(keepingDays: Int, from: Long) {
            val cal = Calendar.getInstance()
            cal.timeInMillis = from
            cal.add(Calendar.DAY_OF_YEAR, -(keepingDays - 1))
            val cutoffKey = dayKey(cal.timeInMillis)
            // Keys are zero-padded yyyy-MM-dd, so string order is date order.
            daily.keys.retainAll { it >= cutoffKey }
        }

        fun snapshot() = Habit(name, total, firstAt, lastAt, daily.toMap(), hourly.toMap())
    }

    // MARK: Setup

    /** Call once from Application.onCreate(). Idempotent. */
    @JvmStatic
    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            file = File(context.applicationContext.filesDir, "user_memory.json")
            load()
            if (since == 0L) {
                since = System.currentTimeMillis()
                scheduleSave()
            }
        }
    }

    // MARK: Preferences (explicit)

    @JvmStatic
    fun set(key: String, value: String) = putPref(key, value)

    @JvmStatic
    fun set(key: String, value: Boolean) = putPref(key, value)

    @JvmStatic
    fun set(key: String, value: Int) = putPref(key, value.toLong())

    @JvmStatic
    fun set(key: String, value: Long) = putPref(key, value)

    @JvmStatic
    fun set(key: String, value: Double) = putPref(key, value)

    @JvmStatic
    fun set(key: String, value: List<String>) = putPref(key, value.toList())

    @JvmStatic
    @JvmOverloads
    fun getString(key: String, default: String? = null): String? =
        pref(key) as? String ?: default

    @JvmStatic
    fun getBoolean(key: String, default: Boolean): Boolean =
        pref(key) as? Boolean ?: default

    @JvmStatic
    fun getInt(key: String, default: Int): Int =
        (pref(key) as? Long)?.toInt() ?: default

    @JvmStatic
    fun getDouble(key: String, default: Double): Double =
        when (val value = pref(key)) {
            is Double -> value
            is Long -> value.toDouble()
            else -> default
        }

    @JvmStatic
    fun getStrings(key: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return pref(key) as? List<String> ?: emptyList()
    }

    /** Removes one explicit preference. */
    @JvmStatic
    fun remove(key: String) {
        synchronized(lock) {
            if (prefs.remove(key.trim()) != null) scheduleSave()
        }
    }

    /** All explicit preferences, alphabetical. */
    @JvmStatic
    fun preferences(): List<Preference> = synchronized(lock) {
        prefs.map { (key, pref) -> Preference(key, pref.value, pref.updatedAt) }
            .sortedBy { it.key.lowercase() }
    }

    // MARK: Behavior learning (implicit)

    /**
     * Records that the user chose [choice] for [key] — e.g.
     * `observe("export_format", "pdf")` every time an export happens. Recent
     * choices count more than old ones, so the learned preference follows the
     * user when their behavior changes.
     */
    @JvmStatic
    fun observe(key: String, choice: String) =
        observe(key, choice, System.currentTimeMillis())

    /** What the SDK has learned about [key], or null when never observed. */
    @JvmStatic
    fun preferred(key: String): Learned? =
        preferred(key, System.currentTimeMillis())

    /** Shorthand for the strongest learned option's value, or null. */
    @JvmStatic
    fun preferredValue(key: String): String? = preferred(key)?.top?.value

    /**
     * Ranks [options] by what was learned for [key]: observed options come
     * first (strongest first), never-observed ones keep their given order.
     * Ideal for ordering menus, chips or defaulting a picker:
     *
     *     val ordered = UserMemory.suggest("export_format", listOf("pdf", "png", "txt"))
     */
    @JvmStatic
    fun suggest(key: String, options: List<String>): List<String> {
        val learned = preferred(key) ?: return options
        val rank = learned.choices.withIndex().associate { (index, choice) -> choice.value to index }
        return options.sortedWith(compareBy { rank[it] ?: Int.MAX_VALUE })
    }

    /** All learned choice keys with what was learned, most confident first. */
    @JvmStatic
    fun learned(): List<Learned> {
        val now = System.currentTimeMillis()
        val keys = synchronized(lock) { signals.keys.toList() }
        return keys.mapNotNull { preferred(it, now) }
            .sortedWith(compareByDescending<Learned> { it.confidence }.thenBy { it.key })
    }

    // MARK: Habits

    /**
     * Records one occurrence of a recurring action — e.g.
     * `record("workout_logged")`. Safe to call from any thread; disk writes
     * happen on a background thread. Empty names are ignored.
     */
    @JvmStatic
    fun record(name: String) = record(name, System.currentTimeMillis(), 1)

    /** Records [count] occurrences at once. */
    @JvmStatic
    fun record(name: String, count: Int) {
        if (count <= 0) return
        record(name, System.currentTimeMillis(), count)
    }

    /** All recorded actions as habit snapshots, most established first. */
    @JvmStatic
    fun habits(): List<Habit> {
        val now = System.currentTimeMillis()
        return synchronized(lock) { events.values.map { it.snapshot() } }
            .sortedWith(compareByDescending<Habit> { it.strength(now) }.thenByDescending { it.total })
    }

    /** Habit snapshot for one action, or null if it was never recorded. */
    @JvmStatic
    fun habit(name: String): Habit? = synchronized(lock) {
        events[name.trim()]?.snapshot()
    }

    // MARK: Recommendations

    /**
     * What is worth surfacing right now, strongest first: habits due around
     * this hour, streaks at risk, fading habits, and learned choices strong
     * enough to preselect.
     */
    @JvmStatic
    @JvmOverloads
    fun recommendations(limit: Int = 5): List<Recommendation> =
        recommendations(limit, System.currentTimeMillis())

    // MARK: Profile

    /** Who this user is, derived from everything remembered so far. */
    @JvmStatic
    fun profile(): UserProfile = profile(System.currentTimeMillis())

    // MARK: Export / import / forget

    /**
     * Everything remembered, as pretty-printed JSON. The iOS SDK imports the
     * same format, so memory can move between apps, devices and platforms.
     */
    @JvmStatic
    fun exportJson(): String = synchronized(lock) {
        toStorageJsonObject().put("exportedAt", System.currentTimeMillis()).toString(2)
    }

    /**
     * Replaces everything remembered with [json] (as produced by
     * `exportJson()` on either platform). Returns false when the JSON cannot
     * be parsed; memory is left unchanged in that case.
     */
    @JvmStatic
    fun importJson(json: String): Boolean = synchronized(lock) {
        try {
            val obj = JSONObject(json)
            if (obj.optInt("version", SCHEMA_VERSION) > SCHEMA_VERSION) return false
            applyStorageJson(obj)
            scheduleSave()
            true
        } catch (e: Exception) {
            Log.w(TAG, "importJson failed, keeping existing memory", e)
            false
        }
    }

    /** Deletes everything remembered. */
    @JvmStatic
    fun reset() {
        synchronized(lock) {
            prefs.clear()
            signals.clear()
            events.clear()
            since = System.currentTimeMillis()
            scheduleSave()
        }
    }

    /** Forgets one key: its explicit preference and everything learned about it. */
    @JvmStatic
    fun forget(key: String) {
        synchronized(lock) {
            val trimmed = key.trim()
            val removed = prefs.remove(trimmed) != null || signals.remove(trimmed) != null
            if (removed) scheduleSave()
        }
    }

    /** Forgets one recorded action's entire history. */
    @JvmStatic
    fun forgetHabit(name: String) {
        synchronized(lock) {
            if (events.remove(name.trim()) != null) scheduleSave()
        }
    }

    // MARK: Internals (also used by tests and the built-in screen)

    private fun putPref(key: String, value: Any) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        synchronized(lock) {
            warnIfUninitialized("set(\"$trimmed\")")
            val existing = prefs[trimmed]
            if (existing != null) {
                existing.value = value
                existing.updatedAt = System.currentTimeMillis()
            } else {
                prefs[trimmed] = Pref(value, System.currentTimeMillis())
            }
            scheduleSave()
        }
    }

    private fun pref(key: String): Any? = synchronized(lock) { prefs[key.trim()]?.value }

    internal fun observe(key: String, choice: String, at: Long) {
        val trimmedKey = key.trim()
        val trimmedChoice = choice.trim()
        if (trimmedKey.isEmpty() || trimmedChoice.isEmpty()) return
        synchronized(lock) {
            warnIfUninitialized("observe(\"$trimmedKey\")")
            val options = signals.getOrPut(trimmedKey) { LinkedHashMap() }
            val signal = options.getOrPut(trimmedChoice) { Signal(0.0, 0, at) }
            signal.weight = decayed(signal.weight, signal.lastAt, at) + 1.0
            signal.count += 1
            signal.lastAt = at
            scheduleSave()
        }
    }

    internal fun preferred(key: String, now: Long): Learned? {
        val choices: List<LearnedChoice>
        val observations: Int
        synchronized(lock) {
            val options = signals[key.trim()] ?: return null
            if (options.isEmpty()) return null
            val weights = options.map { (value, signal) ->
                Triple(value, decayed(signal.weight, signal.lastAt, now), signal)
            }
            val total = weights.sumOf { it.second }
            if (total <= 0.0) return null
            choices = weights
                .map { (value, weight, signal) ->
                    LearnedChoice(value, weight / total, signal.count, signal.lastAt)
                }
                .sortedWith(compareByDescending<LearnedChoice> { it.share }.thenBy { it.value })
            observations = options.values.sumOf { it.count }
        }
        val confidence = choices.first().share * min(1.0, observations / 5.0)
        return Learned(key.trim(), choices, observations, confidence)
    }

    internal fun record(name: String, at: Long, count: Int) {
        val key = name.trim()
        if (key.isEmpty()) return
        synchronized(lock) {
            warnIfUninitialized("record(\"$key\")")
            val event = events.getOrPut(key) { MutableEvent(key, 0, at, at, mutableMapOf()) }
            event.record(at, count)
            event.prune(RETENTION_DAYS, at)
            scheduleSave()
        }
    }

    internal fun recommendations(limit: Int, now: Long): List<Recommendation> {
        val recs = mutableListOf<Recommendation>()
        val hour = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.HOUR_OF_DAY)

        for (habit in habits()) {
            val strength = habit.strength(now)
            if (habit.isFading(now)) {
                val previous = habit.activeDays(28, now - 28 * DAY_MILLIS)
                val current = habit.activeDays(28, now)
                recs += Recommendation(
                    RecommendationKind.FADING_HABIT,
                    habit.name,
                    "“${habit.name}” is fading",
                    "Active $previous days the month before, $current in the last 28. " +
                        "Worth resurfacing?",
                    0.35,
                )
            }
            if (strength < 0.3 || habit.doneToday(now) || now - habit.lastAt > 14 * DAY_MILLIS) {
                continue
            }
            val typical = habit.typicalHour()
            if (typical != null && hourDistance(hour, typical) <= 1) {
                recs += Recommendation(
                    RecommendationKind.HABIT_DUE,
                    habit.name,
                    "“${habit.name}” usually happens about now",
                    "Most often around ${hourLabel(typical)} — nothing logged yet today.",
                    0.7 + 0.2 * strength,
                )
            }
            val streak = habit.currentStreakDays(now)
            val pastUsualTime = if (typical != null) hour > typical else hour >= 18
            if (streak >= 3 && pastUsualTime) {
                recs += Recommendation(
                    RecommendationKind.STREAK_AT_RISK,
                    habit.name,
                    "$streak-day “${habit.name}” streak on the line",
                    "Nothing logged today yet — one more keeps it alive.",
                    0.9 + min(0.1, streak / 100.0),
                )
            }
        }

        val keys = synchronized(lock) { signals.keys.toList() }
        for (key in keys) {
            val learned = preferred(key, now) ?: continue
            if (learned.confidence < 0.55) continue
            recs += Recommendation(
                RecommendationKind.LEARNED_DEFAULT,
                key,
                "Default $key to “${learned.top.value}”",
                "Chosen ${(learned.top.share * 100).roundToInt()}% of the time " +
                    "across ${learned.observations} choices.",
                0.3 + 0.5 * learned.confidence,
            )
        }

        return recs.sortedByDescending { it.score }.take(limit)
    }

    internal fun profile(now: Long): UserProfile {
        val snapshots: List<Habit>
        val firstSeen: Long
        val learnedCount: Int
        val preferenceCount: Int
        synchronized(lock) {
            snapshots = events.values.map { it.snapshot() }
            firstSeen = if (since != 0L) since else now
            learnedCount = signals.count { it.value.isNotEmpty() }
            preferenceCount = prefs.size
        }

        // One combined activity calendar across every recorded action.
        val unionDaily = mutableMapOf<String, Int>()
        val unionHourly = mutableMapOf<Int, Int>()
        for (habit in snapshots) {
            for ((day, count) in habit.daily) {
                unionDaily[day] = (unionDaily[day] ?: 0) + count
            }
            for ((hourOfDay, count) in habit.hourly) {
                unionHourly[hourOfDay] = (unionHourly[hourOfDay] ?: 0) + count
            }
        }
        val union = Habit("", snapshots.sumOf { it.total }, firstSeen, now, unionDaily, unionHourly)

        val daysKnown = ((now - firstSeen) / DAY_MILLIS).toInt() + 1
        val activeDays28 = union.activeDays(28, now)
        val engagement = when {
            daysKnown < 7 -> Engagement.NEW
            activeDays28 >= 20 -> Engagement.POWER
            activeDays28 >= 8 -> Engagement.REGULAR
            else -> Engagement.CASUAL
        }

        val hourlyTotal = unionHourly.values.sum()
        val peakPart = if (hourlyTotal >= 10) {
            val byPart = unionHourly.entries.groupBy({ DayPart.of(it.key) }, { it.value })
                .mapValues { it.value.sum() }
            byPart.maxByOrNull { it.value }
                ?.takeIf { it.value.toDouble() / hourlyTotal >= 0.4 }
                ?.key
        } else {
            null
        }

        val activeDayKeys = unionDaily.keys
        val weekendDays = activeDayKeys.count { key ->
            val cal = parseDayKey(key) ?: return@count false
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            dow == Calendar.SATURDAY || dow == Calendar.SUNDAY
        }
        val weekendLeaning =
            activeDayKeys.size >= 6 && weekendDays.toDouble() / activeDayKeys.size >= 0.5

        return UserProfile(
            firstSeenAt = firstSeen,
            daysKnown = daysKnown,
            activeDays28 = activeDays28,
            eventsTotal = union.total,
            engagement = engagement,
            peakPart = peakPart,
            weekendLeaning = weekendLeaning,
            currentStreakDays = streakDays(unionDaily.keys, now),
            habitCount = snapshots.count { it.isHabit(now) },
            learnedCount = learnedCount,
            preferenceCount = preferenceCount,
        )
    }

    /** "7am" / "12pm" style label for an hour of day. */
    @JvmStatic
    fun hourLabel(hour: Int): String = when {
        hour == 0 -> "12am"
        hour < 12 -> "${hour}am"
        hour == 12 -> "12pm"
        else -> "${hour - 12}pm"
    }

    private fun decayed(weight: Double, from: Long, to: Long): Double {
        if (to <= from) return weight
        val days = (to - from).toDouble() / DAY_MILLIS
        return weight * 0.5.pow(days / CHOICE_HALF_LIFE_DAYS)
    }

    private fun hourDistance(a: Int, b: Int): Int {
        val diff = abs(a - b)
        return min(diff, 24 - diff)
    }

    /** Consecutive day keys present in [keys], counting back from [from]'s day. */
    internal fun streakDays(keys: Set<String>, from: Long): Int {
        val cal = Calendar.getInstance()
        cal.timeInMillis = from
        if (dayKey(cal.timeInMillis) !in keys) cal.add(Calendar.DAY_OF_YEAR, -1)
        var streak = 0
        while (dayKey(cal.timeInMillis) in keys) {
            streak += 1
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        return streak
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

    internal fun parseDayKey(key: String): Calendar? {
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

    /** Must be called while holding [lock]. */
    private fun warnIfUninitialized(call: String) {
        if (file == null) {
            Log.w(TAG, "$call before UserMemory.init(context) — data will not persist")
        }
    }

    /** Must be called while holding [lock]. */
    private fun scheduleSave() {
        val target = file ?: return
        val json = toStorageJsonObject().toString()
        saveExecutor.execute {
            try {
                val tmp = File(target.parentFile, target.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save memory", e)
            }
        }
    }

    /** Must be called while holding [lock]. */
    private fun toStorageJsonObject(): JSONObject {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        root.put("since", since)

        val prefsObj = JSONObject()
        for ((key, pref) in prefs) {
            val entry = JSONObject()
            when (val value = pref.value) {
                is String -> entry.put("t", "s").put("v", value)
                is Boolean -> entry.put("t", "b").put("v", value)
                is Long -> entry.put("t", "i").put("v", value)
                is Double -> entry.put("t", "d").put("v", value)
                is List<*> -> entry.put("t", "l").put("v", JSONArray(value))
                else -> continue
            }
            entry.put("at", pref.updatedAt)
            prefsObj.put(key, entry)
        }
        root.put("prefs", prefsObj)

        val signalsObj = JSONObject()
        for ((key, options) in signals) {
            val optionsObj = JSONObject()
            for ((choice, signal) in options) {
                optionsObj.put(
                    choice,
                    JSONObject()
                        .put("w", signal.weight)
                        .put("n", signal.count)
                        .put("at", signal.lastAt),
                )
            }
            signalsObj.put(key, optionsObj)
        }
        root.put("signals", signalsObj)

        val eventsArr = JSONArray()
        for (event in events.values) {
            eventsArr.put(
                JSONObject()
                    .put("name", event.name)
                    .put("total", event.total)
                    .put("firstAt", event.firstAt)
                    .put("lastAt", event.lastAt)
                    .put("daily", JSONObject(event.daily as Map<*, *>))
                    .put("hourly", JSONObject(event.hourly.mapKeys { it.key.toString() } as Map<*, *>)),
            )
        }
        root.put("events", eventsArr)
        return root
    }

    /** Must be called while holding [lock]. Throws on malformed input. */
    private fun applyStorageJson(root: JSONObject) {
        val newPrefs = LinkedHashMap<String, Pref>()
        val prefsObj = root.optJSONObject("prefs") ?: JSONObject()
        for (key in prefsObj.keys()) {
            val entry = prefsObj.getJSONObject(key)
            val at = entry.optLong("at", System.currentTimeMillis())
            val value: Any = when (entry.optString("t", "s")) {
                "b" -> entry.getBoolean("v")
                "i" -> entry.getLong("v")
                "d" -> entry.getDouble("v")
                "l" -> {
                    val arr = entry.getJSONArray("v")
                    (0 until arr.length()).map { arr.getString(it) }
                }
                else -> entry.getString("v")
            }
            newPrefs[key] = Pref(value, at)
        }

        val newSignals = LinkedHashMap<String, LinkedHashMap<String, Signal>>()
        val signalsObj = root.optJSONObject("signals") ?: JSONObject()
        for (key in signalsObj.keys()) {
            val optionsObj = signalsObj.getJSONObject(key)
            val options = LinkedHashMap<String, Signal>()
            for (choice in optionsObj.keys()) {
                val entry = optionsObj.getJSONObject(choice)
                options[choice] = Signal(
                    entry.getDouble("w"),
                    entry.getInt("n"),
                    entry.getLong("at"),
                )
            }
            if (options.isNotEmpty()) newSignals[key] = options
        }

        val newEvents = LinkedHashMap<String, MutableEvent>()
        val eventsArr = root.optJSONArray("events") ?: JSONArray()
        val now = System.currentTimeMillis()
        for (i in 0 until eventsArr.length()) {
            val obj = eventsArr.getJSONObject(i)
            val daily = mutableMapOf<String, Int>()
            val dailyObj = obj.optJSONObject("daily") ?: JSONObject()
            for (key in dailyObj.keys()) {
                daily[key] = dailyObj.getInt(key)
            }
            val hourly = mutableMapOf<Int, Int>()
            val hourlyObj = obj.optJSONObject("hourly") ?: JSONObject()
            for (key in hourlyObj.keys()) {
                val hour = key.toIntOrNull() ?: continue
                hourly[hour] = hourlyObj.getInt(key)
            }
            val event = MutableEvent(
                name = obj.getString("name"),
                total = obj.getInt("total"),
                firstAt = obj.getLong("firstAt"),
                lastAt = obj.getLong("lastAt"),
                daily = daily,
                hourly = hourly,
            )
            event.prune(RETENTION_DAYS, now)
            newEvents[event.name] = event
        }

        prefs.clear()
        prefs.putAll(newPrefs)
        signals.clear()
        signals.putAll(newSignals)
        events.clear()
        events.putAll(newEvents)
        since = root.optLong("since", since.takeIf { it != 0L } ?: now)
    }

    /** Must be called while holding [lock]. */
    private fun load() {
        val target = file ?: return
        if (!target.exists()) return
        try {
            applyStorageJson(JSONObject(target.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load memory, starting fresh", e)
            prefs.clear()
            signals.clear()
            events.clear()
        }
    }
}
