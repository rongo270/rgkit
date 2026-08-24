package dev.rgkit.perceivedspeed

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Feeds frames, input latencies and stalls straight into the accounting, with
 * no Choreographer and no Activity. `SystemClock.uptimeMillis()` returns 0 on
 * the JVM, so a screen's "resumed at" is 0 and the frame timestamps passed in
 * below are also the time-to-interactive.
 */
class PerceivedSpeedTests {

    @Before
    fun setUp() = PerceivedSpeed.reset()

    @After
    fun tearDown() = PerceivedSpeed.reset()

    /** [count] frames that all hit the budget, timestamped from [fromMs]. */
    private fun goodFrames(count: Int, fromMs: Long = 16) {
        var t = fromMs
        repeat(count) {
            PerceivedSpeed.observeFrame(12.0, t)
            t += 16
        }
    }

    private fun report(screen: String = "Home"): ScreenSpeed =
        requireNotNull(PerceivedSpeed.screenReport(screen)) { "no report for $screen" }

    // -------------------------------------------------------------- counting

    @Test
    fun aSmoothScreenFeelsPerfect() {
        PerceivedSpeed.screen("Home")
        goodFrames(60)

        val r = report()
        assertEquals(60L, r.framesObserved)
        assertEquals(0.0, r.jankPercent, 1e-9)
        assertEquals(0L, r.frozenFrames)
        assertEquals(100, r.feltScore)
    }

    @Test
    fun framesSlowerThanTwoBudgetsAreJank() {
        PerceivedSpeed.screen("Home")
        goodFrames(9)
        PerceivedSpeed.observeFrame(40.0, 200)

        val r = report()
        assertEquals(10L, r.framesObserved)
        assertEquals(10.0, r.jankPercent, 1e-9)
        assertEquals(0L, r.frozenFrames)
    }

    @Test
    fun aFrameOnTheBudgetLineIsNotJank() {
        PerceivedSpeed.screen("Home")
        repeat(10) { PerceivedSpeed.observeFrame(33.0, 100) }

        assertEquals(0.0, report().jankPercent, 1e-9)
    }

    @Test
    fun framesOverTwoThirdsOfASecondAreFrozen() {
        PerceivedSpeed.screen("Home")
        goodFrames(8)
        PerceivedSpeed.observeFrame(800.0, 1_000)
        PerceivedSpeed.observeFrame(40.0, 1_100)

        val r = report()
        assertEquals(1L, r.frozenFrames)
        assertEquals(20.0, r.jankPercent, 1e-9) // a frozen frame is janky too
    }

    @Test
    fun jankPercentIsRoundedToOneDecimal() {
        PerceivedSpeed.screen("Home")
        goodFrames(2)
        PerceivedSpeed.observeFrame(50.0, 100)

        assertEquals(33.3, report().jankPercent, 1e-9)
    }

    @Test
    fun framesAreAttributedToTheScreenTheyWereDrawnOn() {
        PerceivedSpeed.screen("Home")
        goodFrames(10)
        PerceivedSpeed.screen("Settings")
        goodFrames(4)
        PerceivedSpeed.observeFrame(60.0, 500)

        assertEquals(10L, report("Home").framesObserved)
        assertEquals(5L, report("Settings").framesObserved)
        assertEquals(0.0, report("Home").jankPercent, 1e-9)
        assertEquals(20.0, report("Settings").jankPercent, 1e-9)
    }

    @Test
    fun aScreenNeverSeenHasNoReport() {
        assertNull(PerceivedSpeed.screenReport("NeverOpened"))
    }

    // ------------------------------------------------------------------- TTI

    @Test
    fun timeToInteractiveIsTheFirstRunOfFiveGoodFrames() {
        PerceivedSpeed.screen("Home")
        // Four good frames, then a stutter resets the run.
        repeat(4) { PerceivedSpeed.observeFrame(10.0, 100) }
        PerceivedSpeed.observeFrame(120.0, 300)
        repeat(5) { PerceivedSpeed.observeFrame(10.0, 450) }

        assertEquals(450L, report().ttiMedianMs)
    }

    @Test
    fun onlyTheFirstSettleOfAScreenVisitIsTimed() {
        PerceivedSpeed.screen("Home")
        repeat(5) { PerceivedSpeed.observeFrame(10.0, 200) }
        repeat(5) { PerceivedSpeed.observeFrame(10.0, 9_000) }

        assertEquals(200L, report().ttiMedianMs)
    }

    @Test
    fun timeToInteractiveIsMedianedAcrossVisits() {
        for (tti in listOf(200L, 900L, 500L)) {
            PerceivedSpeed.screen("Home")
            repeat(5) { PerceivedSpeed.observeFrame(10.0, tti) }
        }
        assertEquals(500L, report().ttiMedianMs)
    }

    @Test
    fun anAbsurdSettleTimeIsNotRecorded() {
        PerceivedSpeed.screen("Home")
        repeat(5) { PerceivedSpeed.observeFrame(10.0, 45_000) }

        assertEquals(0L, report().ttiMedianMs)
    }

    // --------------------------------------------------------- input latency

    @Test
    fun inputLatencyIsReportedAtTheNinetyFifthPercentile() {
        PerceivedSpeed.screen("Home")
        goodFrames(5)
        repeat(19) { PerceivedSpeed.addLatencySample(30) }
        PerceivedSpeed.addLatencySample(300)

        assertEquals(300L, report().inputLatencyP95Ms)
    }

    @Test
    fun impossibleLatenciesAreDropped() {
        PerceivedSpeed.screen("Home")
        goodFrames(5)
        PerceivedSpeed.addLatencySample(-5)
        PerceivedSpeed.addLatencySample(9_000)

        assertEquals(0L, report().inputLatencyP95Ms)
    }

    // ---------------------------------------------------------------- stalls

    @Test
    fun aStallIsLoggedAgainstTheCurrentScreenWithItsStack() {
        PerceivedSpeed.screen("Home")
        goodFrames(5)
        PerceivedSpeed.recordStall(1_500, listOf("Db.query(Db.kt:42)", "Repo.load(Repo.kt:9)"))

        assertEquals(1, report().stalls)
        val stall = PerceivedSpeed.recentStalls().single()
        assertEquals("Home", stall.screen)
        assertEquals(1_500L, stall.durationMs)
        assertEquals("Db.query(Db.kt:42)", stall.topFrames.first())
    }

    @Test
    fun stallsComeBackNewestFirstAndCanBeLimited() {
        PerceivedSpeed.screen("Home")
        for (ms in listOf(1_100L, 1_200L, 1_300L)) PerceivedSpeed.recordStall(ms, emptyList())

        assertEquals(
            listOf(1_300L, 1_200L, 1_100L),
            PerceivedSpeed.recentStalls().map { it.durationMs },
        )
        assertEquals(listOf(1_300L), PerceivedSpeed.recentStalls(limit = 1).map { it.durationMs })
    }

    @Test
    fun theStallLogIsCappedAtFifty() {
        PerceivedSpeed.screen("Home")
        repeat(60) { PerceivedSpeed.recordStall(1_000L + it, emptyList()) }

        val stalls = PerceivedSpeed.recentStalls(limit = 100)
        assertEquals(50, stalls.size)
        assertEquals(1_059L, stalls.first().durationMs) // the oldest ten fell off
    }

    // ------------------------------------------------------------ felt score

    @Test
    fun stutterCostsTheScore() {
        PerceivedSpeed.screen("Home")
        goodFrames(9)
        PerceivedSpeed.observeFrame(40.0, 100)

        // 10% jank -> 12 points.
        assertEquals(88, report().feltScore)
    }

    @Test
    fun laggyTapsCostTheScore() {
        PerceivedSpeed.screen("Home")
        goodFrames(20)
        repeat(20) { PerceivedSpeed.addLatencySample(140) }

        // p95 140 ms -> (140 - 60) / 8 = 10 points.
        assertEquals(90, report().feltScore)
    }

    @Test
    fun aSlowSettleCostsTheScore() {
        PerceivedSpeed.screen("Home")
        repeat(5) { PerceivedSpeed.observeFrame(10.0, 1_600) }

        // TTI 1600 ms -> (1600 - 700) / 150 = 6 points.
        assertEquals(94, report().feltScore)
    }

    @Test
    fun freezesAndStallsCostTheMost() {
        PerceivedSpeed.screen("Home")
        goodFrames(96)
        repeat(4) { PerceivedSpeed.observeFrame(900.0, 5_000) }
        PerceivedSpeed.recordStall(2_000, emptyList())

        val r = report()
        assertEquals(4L, r.frozenFrames)
        assertEquals(1, r.stalls)
        // 4% jank (4.8) + 4 frozen (16) + 1 stall (6) = 26.8 off.
        assertEquals(73, r.feltScore)
    }

    @Test
    fun eachPenaltyIsCapped() {
        PerceivedSpeed.screen("Home")
        repeat(50) { PerceivedSpeed.observeFrame(2_000.0, 100) }
        repeat(20) { PerceivedSpeed.addLatencySample(5_000) }
        repeat(20) { PerceivedSpeed.recordStall(9_000, emptyList()) }

        // Every term saturates: 35 + 20 + 20 + 25 + 25 = 125 -> floored at 0.
        assertEquals(0, report().feltScore)
    }

    @Test
    fun theWorstScreensComeFirst() {
        PerceivedSpeed.screen("Home")
        goodFrames(50)
        PerceivedSpeed.screen("Feed")
        goodFrames(25)
        repeat(25) { PerceivedSpeed.observeFrame(120.0, 400) }

        val worst = PerceivedSpeed.worstScreens()
        assertEquals(listOf("Feed", "Home"), worst.map { it.screen })
        assertTrue(worst.first().feltScore < worst.last().feltScore)
        assertEquals(1, PerceivedSpeed.worstScreens(top = 1).size)
    }

    @Test
    fun theOverallScoreLeansOnTheScreensPeopleActuallySee() {
        PerceivedSpeed.screen("Home")
        goodFrames(990)                                   // 100, heavily weighted
        PerceivedSpeed.screen("Rare")
        repeat(5) { PerceivedSpeed.observeFrame(2_000.0, 100) }  // dreadful, barely seen

        val overall = PerceivedSpeed.overallScore()
        assertTrue("overall was $overall", overall >= 99)
    }

    @Test
    fun aFreshInstallScoresAHundredRatherThanZero() {
        assertEquals(100, PerceivedSpeed.overallScore())
        assertNull(PerceivedSpeed.coldStartMillis())
        assertTrue(PerceivedSpeed.worstScreens().isEmpty())
        assertTrue(PerceivedSpeed.recentStalls().isEmpty())
    }

    // -------------------------------------------------------- export / reset

    @Test
    fun exportCarriesTheRawSamples() {
        PerceivedSpeed.screen("Home")
        goodFrames(5)
        PerceivedSpeed.observeFrame(900.0, 900)
        PerceivedSpeed.addLatencySample(80)

        val screens = JSONObject(PerceivedSpeed.exportJson()).getJSONObject("screens")
        val home = screens.getJSONObject("Home")
        assertEquals(6, home.getInt("frames"))
        assertEquals(1, home.getInt("janky"))
        assertEquals(1, home.getInt("frozen"))
        assertEquals(1, home.getJSONArray("tti").length())
        assertEquals(1, home.getJSONArray("latency").length())
    }

    @Test
    fun resetForgetsEveryScreen() {
        PerceivedSpeed.screen("Home")
        goodFrames(20)
        PerceivedSpeed.recordStall(1_500, emptyList())
        PerceivedSpeed.reset()

        assertNull(PerceivedSpeed.screenReport("Home"))
        assertTrue(PerceivedSpeed.recentStalls().isEmpty())
        assertEquals(100, PerceivedSpeed.overallScore())
    }

    @Test
    fun framesForAForgottenScreenAreDroppedRatherThanResurrectingIt() {
        PerceivedSpeed.screen("Home")
        goodFrames(5)
        PerceivedSpeed.reset()
        goodFrames(5)

        assertNull(PerceivedSpeed.screenReport("Home"))
    }
}
