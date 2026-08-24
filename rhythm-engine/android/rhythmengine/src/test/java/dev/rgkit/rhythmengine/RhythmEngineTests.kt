package dev.rgkit.rhythmengine

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.json.JSONObject
import java.util.Calendar

/**
 * Drives [RhythmEngine.onAppOpen] / [RhythmEngine.onAppClose] with an injected
 * clock. With no Application the persistence layer no-ops, so everything here
 * exercises the learned-state maths on its own.
 */
class RhythmEngineTests {

    /** Monday 2026-03-02, local time — every timestamp below is relative to it. */
    private fun at(dayOffset: Int, hour: Int, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.MARCH, 2, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    @Before
    fun setUp() = RhythmEngine.reset()

    @After
    fun tearDown() = RhythmEngine.reset()

    /** One open per day at [hour], [days] days running, starting at day 0. */
    private fun openDaily(days: Int, hour: Int) {
        for (d in 0 until days) RhythmEngine.onAppOpen(at(d, hour))
    }

    // ------------------------------------------------------------ open counts

    @Test
    fun everyDistinctOpenIsCounted() {
        openDaily(days = 5, hour = 9)
        assertEquals(5, RhythmEngine.totalOpens())
    }

    @Test
    fun aRelaunchWithinThirtySecondsIsTheSameSession() {
        // A rotation or an instant return must not look like a second open.
        RhythmEngine.onAppOpen(at(0, 9))
        RhythmEngine.onAppOpen(at(0, 9, 0) + 20_000)
        assertEquals(1, RhythmEngine.totalOpens())

        RhythmEngine.onAppOpen(at(0, 9, 0) + 31_000)
        assertEquals(2, RhythmEngine.totalOpens())
    }

    // ------------------------------------------------------ bestTimeToEngage

    @Test
    fun engageWindowsStayEmptyUntilThereIsEnoughHistory() {
        openDaily(days = 9, hour = 20)
        assertTrue(RhythmEngine.bestTimeToEngage(now = at(9, 12)).isEmpty())
    }

    @Test
    fun theHabitualHourWinsOnceHistoryExists() {
        // Twelve Mondays at 20:00 — one clear peak in the week grid.
        for (week in 0 until 12) RhythmEngine.onAppOpen(at(week * 7, 20))

        val best = RhythmEngine.bestTimeToEngage(top = 3, now = at(84, 12))
        assertEquals(3, best.size)
        assertEquals(20, best.first().hourOfDay)
        // The window starts on the hour, never mid-hour.
        val cal = Calendar.getInstance().apply { timeInMillis = best.first().startAt }
        assertEquals(0, cal.get(Calendar.MINUTE))
        assertEquals(0, cal.get(Calendar.SECOND))
    }

    @Test
    fun neighbouringHoursInheritSomeOfThePeak() {
        // Smoothing is 0.7 self + 0.15 each side, so 19:00 and 21:00 score
        // without a single open of their own — and the peak itself tops out
        // at 0.7 when its neighbours are empty.
        for (week in 0 until 12) RhythmEngine.onAppOpen(at(week * 7, 20))

        val byHour = RhythmEngine.bestTimeToEngage(withinHours = 24, top = 24, now = at(84, 12))
            .associateBy { it.hourOfDay }
        assertEquals(0.7, byHour.getValue(20).score, 1e-9)
        assertEquals(0.15, byHour.getValue(19).score, 1e-9)
        assertEquals(0.15, byHour.getValue(21).score, 1e-9)
        assertEquals(0.0, byHour.getValue(3).score, 1e-9)
    }

    @Test
    fun windowsComeBackBestFirstAndScoredWithinRange() {
        for (week in 0 until 12) RhythmEngine.onAppOpen(at(week * 7, 20))

        val windows = RhythmEngine.bestTimeToEngage(withinHours = 24, top = 24, now = at(84, 12))
        assertEquals(windows.sortedByDescending { it.score }, windows)
        for (w in windows) {
            assertTrue("score out of range: ${w.score}", w.score in 0.0..1.0)
            assertEquals(w.score, (w.score * 100).toInt() / 100.0, 1e-9)
        }
    }

    // ----------------------------------------------------- next expected open

    @Test
    fun nextOpenIsUnknownUntilFiveGapsAreObserved() {
        openDaily(days = 5, hour = 9) // 5 opens -> only 4 gaps
        assertNull(RhythmEngine.nextExpectedOpenAt())
    }

    @Test
    fun nextOpenIsTheLastOpenPlusTheMedianGap() {
        // Five 1-day gaps and one 3-day gap: the median stays a day.
        openDaily(days = 6, hour = 9)
        RhythmEngine.onAppOpen(at(8, 9))

        val expected = at(8, 9) + 86_400_000L
        assertEquals(expected, RhythmEngine.nextExpectedOpenAt())
    }

    // ------------------------------------------------------- session lengths

    @Test
    fun sessionLengthIsUnknownWithoutEnoughSamples() {
        for (d in 0 until 4) {
            RhythmEngine.onAppOpen(at(d, 14))
            RhythmEngine.onAppClose(at(d, 14) + 300_000)
        }
        assertNull(RhythmEngine.expectedSessionMinutes(at(9, 14)))
    }

    @Test
    fun sessionLengthIsTheMedianForThatTimeOfDay() {
        // Five 5-minute afternoon sessions, and much longer evening ones —
        // asking about the afternoon must not inherit the evening's median.
        for (d in 0 until 5) {
            RhythmEngine.onAppOpen(at(d, 14))
            RhythmEngine.onAppClose(at(d, 14) + 300_000)
        }
        for (d in 0 until 5) {
            RhythmEngine.onAppOpen(at(d, 21))
            RhythmEngine.onAppClose(at(d, 21) + 1_800_000)
        }
        assertEquals(5.0, RhythmEngine.expectedSessionMinutes(at(9, 14))!!, 1e-9)
        assertEquals(30.0, RhythmEngine.expectedSessionMinutes(at(9, 21))!!, 1e-9)
    }

    @Test
    fun anAdjacentHourIsPooledIn() {
        // Nothing recorded at 15:00 itself; the 14:00 samples answer for it.
        for (d in 0 until 5) {
            RhythmEngine.onAppOpen(at(d, 14))
            RhythmEngine.onAppClose(at(d, 14) + 300_000)
        }
        assertEquals(5.0, RhythmEngine.expectedSessionMinutes(at(9, 15))!!, 1e-9)
    }

    @Test
    fun aGlanceUnderASecondIsNotASession() {
        for (d in 0 until 5) {
            RhythmEngine.onAppOpen(at(d, 14))
            RhythmEngine.onAppClose(at(d, 14) + 900)
        }
        assertNull(RhythmEngine.expectedSessionMinutes(at(9, 14)))
    }

    @Test
    fun closingWithoutAnOpenIsIgnored() {
        RhythmEngine.onAppClose(at(0, 14))
        assertNull(RhythmEngine.expectedSessionMinutes(at(0, 14)))
    }

    // ------------------------------------------------------------ churn risk

    @Test
    fun churnRiskIsZeroWithoutEnoughRhythmToJudge() {
        openDaily(days = 6, hour = 9)
        assertEquals(0.0, RhythmEngine.churnRisk(at(20, 9)), 1e-9)
    }

    @Test
    fun aSilenceLongerThanEveryPastGapReadsAsChurn() {
        openDaily(days = 10, hour = 9) // 9 daily gaps
        val onTime = RhythmEngine.churnRisk(at(9, 21))     // half a day later
        val longGone = RhythmEngine.churnRisk(at(40, 9))   // a month later

        assertTrue("expected a quiet month to score higher: $onTime vs $longGone", longGone > onTime)
        assertTrue(longGone >= 0.65)
        for (r in listOf(onTime, longGone)) {
            assertTrue("risk out of range: $r", r in 0.0..1.0)
            assertEquals(r, (r * 100).toInt() / 100.0, 1e-9)
        }
    }

    @Test
    fun quietIsMeasuredAgainstThisUsersOwnGaps() {
        openDaily(days = 10, hour = 9)
        assertFalse(RhythmEngine.isUnusuallyQuiet(at(9, 15)))
        assertTrue(RhythmEngine.isUnusuallyQuiet(at(14, 9)))
    }

    @Test
    fun quietNeedsAHistoryToCompareAgainst() {
        openDaily(days = 4, hour = 9)
        assertFalse(RhythmEngine.isUnusuallyQuiet(at(60, 9)))
    }

    // -------------------------------------------------------------- trending

    @Test
    fun trendNeedsABaselineWeek() {
        openDaily(days = 3, hour = 9)
        assertNull(RhythmEngine.engagementTrend(at(3, 9)))
    }

    @Test
    fun trendIsThePercentChangeAgainstThePreviousWeek() {
        // Week one: every day. Week two: every other day.
        for (d in 0 until 7) RhythmEngine.onAppOpen(at(d, 9))
        for (d in intArrayOf(7, 9, 11)) RhythmEngine.onAppOpen(at(d, 9))

        // "now" sits on day 13, so last7 = days 7..13 (3 opens), prev7 = days 0..6 (7).
        assertEquals(-57, RhythmEngine.engagementTrend(at(13, 9))!!.toInt())
    }

    // --------------------------------------------------------- weekly pattern

    @Test
    fun weeklyPatternIsANormalisedSevenByTwentyFourGrid() {
        for (week in 0 until 4) RhythmEngine.onAppOpen(at(week * 7, 20))     // Mondays
        for (week in 0 until 4) RhythmEngine.onAppOpen(at(week * 7 + 2, 8))  // Wednesdays

        val grid = RhythmEngine.weeklyPattern()
        assertEquals(7, grid.size)
        for (row in grid) assertEquals(24, row.size)
        // Monday is index 0 and carries the peak.
        assertEquals(1.0, grid[0][20], 1e-9)
        assertTrue(grid[2][8] > 0.0 && grid[2][8] <= 1.0)
        assertEquals(0.0, grid[5][3], 1e-9)
    }

    @Test
    fun anEmptyPatternIsAllZerosRatherThanNaN() {
        for (row in RhythmEngine.weeklyPattern()) {
            for (v in row) assertEquals(0.0, v, 1e-9)
        }
    }

    // ------------------------------------------------------------------ decay

    @Test
    fun olderHabitsFadeAsNewDaysArrive() {
        // Same hour-of-week hit twice a week apart: the older hit has been
        // decayed once per intervening day, so the newer one weighs more.
        RhythmEngine.onAppOpen(at(0, 20))
        val fresh = RhythmEngine.weeklyPattern()[0][20]
        assertEquals(1.0, fresh, 1e-9) // normalised against itself

        for (d in 1 until 7) RhythmEngine.onAppOpen(at(d, 11))
        RhythmEngine.onAppOpen(at(7, 20))

        // Monday 20:00 now holds 0.985^6 + 1 of "weight" vs Tuesday 11:00's
        // single decayed hit, so it must still lead.
        val grid = RhythmEngine.weeklyPattern()
        assertTrue(grid[0][20] > grid[1][11])
    }

    // ------------------------------------------------------- export and reset

    @Test
    fun exportCarriesTheLearnedState() {
        openDaily(days = 3, hour = 9)
        RhythmEngine.onAppOpen(at(3, 9))
        RhythmEngine.onAppClose(at(3, 9) + 600_000)

        val json = JSONObject(RhythmEngine.exportJson())
        assertEquals(4, json.getInt("totalOpens"))
        assertEquals(at(3, 9), json.getLong("lastOpenAt"))
        assertEquals(168, json.getJSONArray("hourOfWeek").length())
        assertEquals(3, json.getJSONArray("gaps").length())
        assertEquals(24, json.getJSONArray("lengthsByHour").length())
        assertEquals(4, json.getJSONObject("dailyOpens").length())
    }

    @Test
    fun resetClearsEverythingItLearned() {
        openDaily(days = 12, hour = 20)
        RhythmEngine.onAppClose(at(11, 21))
        RhythmEngine.reset()

        assertEquals(0, RhythmEngine.totalOpens())
        assertNull(RhythmEngine.nextExpectedOpenAt())
        assertNull(RhythmEngine.expectedSessionMinutes(at(11, 21)))
        assertEquals(0.0, RhythmEngine.churnRisk(at(20, 9)), 1e-9)
        assertTrue(RhythmEngine.bestTimeToEngage(now = at(12, 12)).isEmpty())
        assertNotNull(RhythmEngine.exportJson())
    }
}
