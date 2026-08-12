package dev.rgkit.featureusage

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FeatureUsageTests {
    private lateinit var tempDir: File
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "fu-test-${UUID.randomUUID()}")
        FeatureUsage.initForTest(tempDir)
    }

    @After
    fun tearDown() {
        FeatureUsage.awaitWrites()
        tempDir.deleteRecursively()
    }

    private fun daysAgo(days: Int): Long = now - days * FeatureStat.DAY_MILLIS

    @Test
    fun trackIncrementsCountAndReturnsTotal() {
        assertEquals(1, FeatureUsage.track("export_pdf"))
        assertEquals(2, FeatureUsage.track("export_pdf"))
        assertEquals(1, FeatureUsage.track("share"))
        assertEquals(2, FeatureUsage.count("export_pdf"))
        assertEquals(1, FeatureUsage.count("share"))
        assertEquals(0, FeatureUsage.count("never_used"))
    }

    @Test
    fun namesAreTrimmedAndEmptyIgnored() {
        FeatureUsage.track("  export_pdf  ")
        FeatureUsage.track("")
        FeatureUsage.track("   ")
        assertEquals(1, FeatureUsage.count("export_pdf"))
        assertEquals(1, FeatureUsage.stats().size)
    }

    @Test
    fun statsSortedByTotalDescending() {
        FeatureUsage.track("a_rare")
        repeat(5) { FeatureUsage.track("popular") }
        repeat(3) { FeatureUsage.track("middle") }
        assertEquals(listOf("popular", "middle", "a_rare"), FeatureUsage.stats().map { it.name })
    }

    @Test
    fun persistenceRoundTrip() {
        repeat(4) { FeatureUsage.track("export_pdf") }
        FeatureUsage.awaitWrites()
        // Re-initializing to the same directory drops in-memory state and reloads.
        FeatureUsage.initForTest(tempDir)
        assertEquals(4, FeatureUsage.count("export_pdf"))
        assertEquals(4, FeatureUsage.stat("export_pdf")!!.daily.values.sum())
    }

    @Test
    fun dailyBucketsAndSparkline() {
        FeatureUsage.track("share", daysAgo(2))
        FeatureUsage.track("share", daysAgo(2))
        FeatureUsage.track("share", now)

        val stat = FeatureUsage.stat("share")!!
        assertEquals(3, stat.countLastDays(7, now))
        assertEquals(1, stat.countLastDays(1, now))
        val sparkline = stat.dailyCounts(7, now)
        assertEquals(7, sparkline.size)
        assertEquals(1, sparkline[6]) // today, newest last
        assertEquals(2, sparkline[4]) // two days ago
    }

    @Test
    fun staleDetection() {
        FeatureUsage.track("old_feature", daysAgo(40))
        FeatureUsage.track("fresh_feature", now)
        assertTrue(FeatureUsage.stat("old_feature")!!.isStale(30, now))
        assertFalse(FeatureUsage.stat("fresh_feature")!!.isStale(30, now))
    }

    @Test
    fun firstAndLastUsedAt() {
        val earlier = now - 3_600_000
        FeatureUsage.track("share", earlier)
        FeatureUsage.track("share", now)
        val stat = FeatureUsage.stat("share")!!
        assertEquals(earlier, stat.firstUsedAt)
        assertEquals(now, stat.lastUsedAt)
    }

    @Test
    fun exportJsonContainsFeaturesAndHourly() {
        FeatureUsage.track("export_pdf")
        val json = FeatureUsage.exportJson()
        assertTrue(json.contains("\"export_pdf\""))
        assertTrue(json.contains("\"hourly\""))
        assertTrue(json.contains("\"totalDurationMs\""))
    }

    @Test
    fun exportCsv() {
        FeatureUsage.track("export_pdf")
        FeatureUsage.track("export_pdf")
        val lines = FeatureUsage.exportCsv().split("\n")
        assertEquals(
            "feature,total,first_used,last_used,last_7_days,last_30_days,trend_7d_pct,active_days",
            lines[0],
        )
        assertTrue(lines[1].startsWith("export_pdf,2,"))
        assertTrue(lines[1].endsWith(",1")) // one active day
    }

    @Test
    fun reset() {
        FeatureUsage.track("a")
        FeatureUsage.track("b")
        FeatureUsage.reset("a")
        assertEquals(0, FeatureUsage.count("a"))
        assertEquals(1, FeatureUsage.count("b"))
        FeatureUsage.reset()
        assertTrue(FeatureUsage.stats().isEmpty())
        // Reset must also survive a reload.
        FeatureUsage.awaitWrites()
        FeatureUsage.initForTest(tempDir)
        assertTrue(FeatureUsage.stats().isEmpty())
    }

    @Test
    fun oldDailyBucketsArePruned() {
        FeatureUsage.track("ancient", daysAgo(730))
        FeatureUsage.track("ancient", now)
        val stat = FeatureUsage.stat("ancient")!!
        assertEquals(2, stat.total) // total is preserved
        assertEquals(1, stat.daily.size) // but the old daily bucket is gone
        assertEquals(daysAgo(730), stat.firstUsedAt)
    }

    @Test
    fun concurrentTrackingIsSafe() {
        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(100)
        repeat(100) {
            executor.execute {
                FeatureUsage.track("hammered")
                latch.countDown()
            }
        }
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(100, FeatureUsage.count("hammered"))
    }

    @Test
    fun trackWithCount() {
        FeatureUsage.track("import_photos", 12)
        FeatureUsage.track("import_photos", 3)
        FeatureUsage.track("import_photos", 0) // ignored
        FeatureUsage.track("import_photos", -5) // ignored
        val stat = FeatureUsage.stat("import_photos")!!
        assertEquals(15, stat.total)
        assertEquals(15, stat.countLastDays(1, now))
        assertEquals(15, stat.hourlyCounts().sum())
    }

    @Test
    fun hourlyHistogram() {
        val nineThirty = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 30)
        }.timeInMillis
        FeatureUsage.track("morning_thing", nineThirty)
        FeatureUsage.track("morning_thing", nineThirty)
        val counts = FeatureUsage.stat("morning_thing")!!.hourlyCounts()
        assertEquals(24, counts.size)
        assertEquals(2, counts[9])
        assertEquals(2, counts.sum())
    }

    @Test
    fun legacyFileWithoutHourlyLoads() {
        val todayKey = FeatureUsage.dayKey(now)
        File(tempDir, "feature_usage.json").writeText(
            """[{"name":"old_one","total":7,"firstUsedAt":1000,"lastUsedAt":$now,
                "daily":{"$todayKey":7}}]""",
        )
        FeatureUsage.initForTest(tempDir)
        val stat = FeatureUsage.stat("old_one")!!
        assertEquals(7, stat.total)
        assertEquals(List(24) { 0 }, stat.hourlyCounts())
        assertEquals(0, stat.timedSessions)
    }

    @Test
    fun trendPercent() {
        // Previous week: 4 uses; this week: 6 uses → +50%.
        repeat(4) { FeatureUsage.track("trending", daysAgo(10)) }
        repeat(6) { FeatureUsage.track("trending", now) }
        assertEquals(50, FeatureUsage.stat("trending")!!.trendPercent(7, now))
        // No previous-window uses → null, not a percentage.
        FeatureUsage.track("brand_new", now)
        assertNull(FeatureUsage.stat("brand_new")!!.trendPercent(7, now))
    }

    @Test
    fun streaks() {
        // 3-day run ending today, plus an older single day → current 3, best 3.
        for (offset in 0..2) FeatureUsage.track("daily_habit", daysAgo(offset))
        FeatureUsage.track("daily_habit", daysAgo(10))
        val stat = FeatureUsage.stat("daily_habit")!!
        assertEquals(3, stat.currentStreakDays(now))
        assertEquals(3, stat.bestStreakDays())
    }

    @Test
    fun currentStreakToleratesQuietToday() {
        // Used yesterday and the day before, but not yet today → streak alive at 2.
        FeatureUsage.track("daily_habit", daysAgo(1))
        FeatureUsage.track("daily_habit", daysAgo(2))
        assertEquals(2, FeatureUsage.stat("daily_habit")!!.currentStreakDays(now))
    }

    @Test
    fun activeDaysAndAverage() {
        FeatureUsage.track("sometimes", now)
        FeatureUsage.track("sometimes", now)
        FeatureUsage.track("sometimes", daysAgo(3))
        val stat = FeatureUsage.stat("sometimes")!!
        assertEquals(2, stat.activeDays)
        assertEquals(1.5, stat.averagePerActiveDay(), 0.001)
    }

    @Test
    fun weekdayCountsSumMatchesDaily() {
        for (offset in 0 until 10) FeatureUsage.track("spread", daysAgo(offset))
        val stat = FeatureUsage.stat("spread")!!
        val weekdays = stat.weekdayCounts()
        assertEquals(7, weekdays.size)
        assertEquals(stat.daily.values.sum(), weekdays.sum())
    }

    @Test
    fun beginEndRecordsDuration() {
        FeatureUsage.begin("editor")
        Thread.sleep(30)
        FeatureUsage.end("editor")
        val stat = FeatureUsage.stat("editor")!!
        assertEquals(1, stat.total)
        assertEquals(1, stat.timedSessions)
        assertTrue(stat.totalDurationMs >= 10)
        assertTrue(stat.averageSessionMs >= 10)
    }

    @Test
    fun endWithoutBeginIsPlainUse() {
        FeatureUsage.end("editor")
        val stat = FeatureUsage.stat("editor")!!
        assertEquals(1, stat.total)
        assertEquals(0, stat.timedSessions)
        assertEquals(0, stat.totalDurationMs)
    }

    @Test
    fun durationsSurviveReload() {
        FeatureUsage.begin("editor")
        Thread.sleep(20)
        FeatureUsage.end("editor")
        FeatureUsage.awaitWrites()
        FeatureUsage.initForTest(tempDir)
        assertTrue(FeatureUsage.stat("editor")!!.totalDurationMs > 0)
    }

    @Test
    fun importMergesIntoExistingData() {
        FeatureUsage.track("export_pdf")
        val todayKey = FeatureUsage.dayKey(now)
        val merged = FeatureUsage.importJson(
            """[{"name":"export_pdf","total":5,
                "firstUsedAt":"2026-01-01T10:00:00Z","lastUsedAt":"2026-01-02T10:00:00Z",
                "daily":{"$todayKey":5},"hourly":{"9":5}},
               {"name":"from_other_device","total":2,
                "firstUsedAt":"2026-06-01T10:00:00Z","lastUsedAt":"2026-06-01T10:00:00Z",
                "daily":{},"hourly":{}}]""",
        )
        assertEquals(2, merged)
        val stat = FeatureUsage.stat("export_pdf")!!
        assertEquals(6, stat.total)
        assertEquals(6, stat.countLastDays(1, now))
        assertTrue(stat.firstUsedAt < now - 100 * FeatureStat.DAY_MILLIS) // widened to January
        assertEquals(2, FeatureUsage.count("from_other_device"))
    }

    @Test
    fun importOfExportRestoresTotals() {
        repeat(3) { FeatureUsage.track("a") }
        FeatureUsage.track("b")
        val export = FeatureUsage.exportJson()
        FeatureUsage.reset()
        assertEquals(2, FeatureUsage.importJson(export))
        assertEquals(3, FeatureUsage.count("a"))
        assertEquals(1, FeatureUsage.count("b"))
    }

    @Test
    fun importRejectsGarbage() {
        assertEquals(0, FeatureUsage.importJson("not json at all"))
        assertTrue(FeatureUsage.stats().isEmpty())
    }

    @Test
    fun recentEventsNewestFirstAndLimited() {
        FeatureUsage.track("a", now - 3_000)
        FeatureUsage.track("b", now - 2_000)
        FeatureUsage.track("c", now - 1_000)
        val events = FeatureUsage.recentEvents(10)
        assertEquals(listOf("c", "b", "a"), events.map { it.name })
        assertEquals(2, FeatureUsage.recentEvents(2).size)
    }

    @Test
    fun eventsSurviveReloadAndAreCapped() {
        repeat(510) { FeatureUsage.track("busy", now - (510 - it) * 1_000L) }
        FeatureUsage.awaitWrites()
        FeatureUsage.initForTest(tempDir)
        assertEquals(500, FeatureUsage.recentEvents(600).size)
        assertEquals(510, FeatureUsage.count("busy")) // totals unaffected by the cap
    }

    @Test
    fun resetFeatureRemovesItsEvents() {
        FeatureUsage.track("keep")
        FeatureUsage.track("drop")
        FeatureUsage.reset("drop")
        assertEquals(listOf("keep"), FeatureUsage.recentEvents(10).map { it.name })
    }

    @Test
    fun disabledIsNoOp() {
        FeatureUsage.enabled = false
        FeatureUsage.track("nope")
        FeatureUsage.begin("nope")
        FeatureUsage.end("nope")
        FeatureUsage.enabled = true
        assertEquals(0, FeatureUsage.count("nope"))
        assertTrue(FeatureUsage.recentEvents(10).isEmpty())
    }

    @Test
    fun changeListenerFires() {
        var fired = 0
        val listener: () -> Unit = { fired += 1 }
        FeatureUsage.addChangeListener(listener)
        FeatureUsage.track("x")
        FeatureUsage.reset()
        FeatureUsage.removeChangeListener(listener)
        FeatureUsage.track("y")
        assertEquals(2, fired)
    }

    @Test
    fun insightsSurfaceRisersAndStale() {
        assertTrue(FeatureUsage.insights(now).isEmpty()) // no data yet
        repeat(3) { FeatureUsage.track("riser", daysAgo(8)) }
        repeat(9) { FeatureUsage.track("riser", now) }
        FeatureUsage.track("dead_feature", daysAgo(40))
        val insights = FeatureUsage.insights(now)
        assertTrue(insights.any { it.kind == UsageInsight.Kind.RISING && it.feature == "riser" })
        assertTrue(insights.any { it.kind == UsageInsight.Kind.STALE })
    }
}
