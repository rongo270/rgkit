package dev.rgkit.gripsense

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Feeds normalized taps straight into [GripSense.recordTap] with the
 * alternation clock hand-cranked. No Activity, no window callback — just the
 * grid, the votes and the reach model.
 */
class GripSenseTests {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        GripSense.clock = { now }
        GripSense.reset()
    }

    @After
    fun tearDown() {
        GripSense.reset()
        GripSense.clock = { System.currentTimeMillis() }
    }

    /** Taps a point [times], leaving a full second between taps unless told otherwise. */
    private fun tap(x: Double, y: Double, times: Int = 1, gapMs: Long = 1_000) {
        repeat(times) {
            GripSense.recordTap(x, y)
            now += gapMs
        }
    }

    // ------------------------------------------------------------ reach model

    @Test
    fun theBottomOfTheHoldingSideIsTheEasyZone() {
        assertEquals(ReachZone.EASY, GripSense.zoneFor(0.85, 0.90, Handedness.RIGHT_THUMB))
        assertEquals(ReachZone.EASY, GripSense.zoneFor(0.15, 0.90, Handedness.LEFT_THUMB))
    }

    @Test
    fun theOppositeTopCornerIsTheHardZone() {
        assertEquals(ReachZone.HARD, GripSense.zoneFor(0.10, 0.10, Handedness.RIGHT_THUMB))
        assertEquals(ReachZone.HARD, GripSense.zoneFor(0.90, 0.10, Handedness.LEFT_THUMB))
    }

    @Test
    fun theMiddleOfTheScreenIsAStretch() {
        assertEquals(ReachZone.STRETCH, GripSense.zoneFor(0.50, 0.50, Handedness.RIGHT_THUMB))
    }

    @Test
    fun theLeftThumbModelIsTheMirrorOfTheRight() {
        for (y in listOf(0.1, 0.4, 0.7, 0.95)) {
            for (x in listOf(0.05, 0.3, 0.5, 0.7, 0.95)) {
                assertEquals(
                    "mirror mismatch at ($x, $y)",
                    GripSense.zoneFor(x, y, Handedness.RIGHT_THUMB),
                    GripSense.zoneFor(1.0 - x, y, Handedness.LEFT_THUMB),
                )
            }
        }
    }

    @Test
    fun anUnknownGripIsJudgedWithTheRightThumbModel() {
        for (x in listOf(0.1, 0.5, 0.9)) {
            assertEquals(
                GripSense.zoneFor(x, 0.8, Handedness.RIGHT_THUMB),
                GripSense.zoneFor(x, 0.8, Handedness.UNKNOWN),
            )
        }
    }

    // ------------------------------------------------------------ handedness

    @Test
    fun aHandfulOfTapsIsNotEnoughToCallIt() {
        tap(0.80, 0.90, times = 20)
        assertEquals(Handedness.UNKNOWN to 0.0, GripSense.handedness())
    }

    @Test
    fun tapsHuggingTheBottomRightMeanARightThumb() {
        tap(0.80, 0.90, times = 30)

        val (hand, confidence) = GripSense.handedness()
        assertEquals(Handedness.RIGHT_THUMB, hand)
        assertEquals(0.95, confidence, 1e-9) // capped
    }

    @Test
    fun tapsHuggingTheBottomLeftMeanALeftThumb() {
        tap(0.20, 0.90, times = 30)

        val (hand, confidence) = GripSense.handedness()
        assertEquals(Handedness.LEFT_THUMB, hand)
        assertEquals(0.95, confidence, 1e-9)
    }

    @Test
    fun tapsInTheMiddleOfTheBottomEdgeVoteForNobody() {
        // 0.38..0.62 is the no-man's-land between the two thumb arcs.
        tap(0.50, 0.90, times = 40)
        assertEquals(Handedness.UNKNOWN to 0.0, GripSense.handedness())
    }

    @Test
    fun anEvenSplitIsNoVerdictRatherThanAGuess() {
        tap(0.80, 0.90, times = 15)
        tap(0.20, 0.90, times = 15)

        val (hand, confidence) = GripSense.handedness()
        assertEquals(Handedness.UNKNOWN, hand)
        assertEquals(0.4, confidence, 1e-9)
    }

    @Test
    fun fastAlternationAcrossTheScreenMeansTwoThumbs() {
        // Left, right, left… 120 ms apart: no single thumb covers that.
        repeat(15) {
            tap(0.15, 0.90, gapMs = 120)
            tap(0.85, 0.90, gapMs = 120)
        }

        val (hand, confidence) = GripSense.handedness()
        assertEquals(Handedness.TWO_HANDED, hand)
        assertTrue("confidence $confidence", confidence in 0.4..0.9)
    }

    @Test
    fun theSameAlternationAtReadingSpeedIsNotTwoThumbs() {
        repeat(15) {
            tap(0.15, 0.90, gapMs = 900)
            tap(0.85, 0.90, gapMs = 900)
        }
        assertEquals(Handedness.UNKNOWN, GripSense.handedness().first)
    }

    @Test
    fun aClearMajorityStillWinsWithSomeStrayTaps() {
        tap(0.80, 0.90, times = 24)
        tap(0.20, 0.90, times = 6)

        val (hand, confidence) = GripSense.handedness()
        assertEquals(Handedness.RIGHT_THUMB, hand)
        assertEquals(0.8, confidence, 1e-9) // 24 of 30
    }

    @Test
    fun swipeCurvatureIsAWeakerVoteThanTaps() {
        tap(0.50, 0.90, times = 30) // no thumb-arc votes at all
        repeat(24) { GripSense.recordSwipeBow(-0.02) } // bows left -> right thumb

        assertEquals(Handedness.RIGHT_THUMB, GripSense.handedness().first)
    }

    @Test
    fun aStraightSwipeVotesForNeitherHand() {
        tap(0.50, 0.90, times = 30)
        repeat(24) { GripSense.recordSwipeBow(0.001) }

        assertEquals(Handedness.UNKNOWN, GripSense.handedness().first)
    }

    // ----------------------------------------------------------------- strain

    @Test
    fun strainIsZeroBeforeAnyTaps() {
        assertEquals(0.0, GripSense.stretchTapShare(), 1e-9)
        assertTrue(GripSense.hardestHotspots().isEmpty())
    }

    @Test
    fun tapsThatAllLandInThumbReachHaveNoStrain() {
        tap(0.80, 0.90, times = 30)
        assertEquals(0.0, GripSense.stretchTapShare(), 1e-9)
    }

    @Test
    fun strainIsTheShareOfTapsInTheHardZone() {
        tap(0.80, 0.90, times = 30)  // easy for the right thumb
        tap(0.05, 0.05, times = 10)  // far corner

        assertEquals(0.25, GripSense.stretchTapShare(), 1e-9)
    }

    @Test
    fun hotspotsListOnlyTheAwkwardCells() {
        tap(0.80, 0.90, times = 30)  // easy — never a hotspot
        tap(0.05, 0.05, times = 10)  // hard
        tap(0.50, 0.50, times = 12)  // stretch

        val spots = GripSense.hardestHotspots()
        assertEquals(2, spots.size)
        // Hard cells count double, so 10 hard taps outrank 12 stretch taps.
        assertEquals(ReachZone.HARD, spots.first().zone)
        assertEquals(10, spots.first().taps)
        assertEquals(ReachZone.STRETCH, spots.last().zone)
        assertTrue(spots.none { it.zone == ReachZone.EASY })
    }

    @Test
    fun hotspotsAreCappedAtTheRequestedCount() {
        tap(0.80, 0.90, times = 30)
        for (i in 0 until 6) tap(0.05 + i * 0.1, 0.05, times = 3)

        assertEquals(2, GripSense.hardestHotspots(top = 2).size)
    }

    @Test
    fun hotspotsAreReportedAtTheirCellCentre() {
        tap(0.80, 0.90, times = 30)
        tap(0.02, 0.02, times = 5)

        val spot = GripSense.hardestHotspots().single()
        assertEquals(0.5 / 12, spot.x, 1e-9)
        assertEquals(0.5 / 20, spot.y, 1e-9)
    }

    // ---------------------------------------------------------------- heatmap

    @Test
    fun theHeatmapIsATwelveByTwentyGridOfCounts() {
        val grid = GripSense.heatmap()
        assertEquals(20, grid.size)
        for (row in grid) assertEquals(12, row.size)

        tap(0.0, 0.0, times = 3)
        assertEquals(3, GripSense.heatmap()[0][0])
    }

    @Test
    fun tapsOnTheVeryEdgeStayInsideTheGrid() {
        tap(1.0, 1.0)
        tap(1.5, -0.2) // out of range input must not blow up the grid

        val grid = GripSense.heatmap()
        assertEquals(1, grid[19][11])
        assertEquals(1, grid[0][11])
    }

    @Test
    fun theHeatmapIsACopyNotTheLiveGrid() {
        tap(0.5, 0.5)
        val grid = GripSense.heatmap()
        grid[10][6] = 999

        assertEquals(1, GripSense.heatmap()[10][6])
    }

    // ----------------------------------------------------------------- report

    @Test
    fun theReportSpellsOutWhatToDoAboutARightThumb() {
        tap(0.80, 0.90, times = 30)

        val report = GripSense.report()
        assertEquals(Handedness.RIGHT_THUMB, report.handedness)
        assertEquals(30, report.totalTaps)
        assertTrue(report.advice, report.advice.contains("bottom-right"))
    }

    @Test
    fun theReportCallsOutRealStrain() {
        tap(0.80, 0.90, times = 30)
        tap(0.05, 0.05, times = 10)

        val report = GripSense.report()
        assertEquals(0.25, report.stretchTapShare, 1e-9)
        assertTrue(report.advice, report.advice.contains("25% of taps strain the thumb"))
        assertEquals(GripSense.hardestHotspots(), report.hotspots)
    }

    @Test
    fun theReportStaysHonestBeforeItKnowsAnything() {
        val report = GripSense.report()
        assertEquals(Handedness.UNKNOWN, report.handedness)
        assertEquals(0.0, report.confidence, 1e-9)
        assertEquals(0, report.totalTaps)
        assertTrue(report.advice, report.advice.contains("Not enough data"))
    }

    @Test
    fun lowStrainIsSaidToBeLow() {
        tap(0.80, 0.90, times = 90)
        tap(0.05, 0.05, times = 5) // ~5%

        val advice = GripSense.report().advice
        assertTrue(advice, advice.contains("Strain is low"))
    }

    // -------------------------------------------------------- export / reset

    @Test
    fun exportCarriesTheGridAndTheVotes() {
        tap(0.80, 0.90, times = 30)

        val json = JSONObject(GripSense.exportJson())
        assertEquals(30, json.getInt("totalTaps"))
        assertEquals(30.0, json.getDouble("rightVotes"), 1e-9)
        assertEquals(0.0, json.getDouble("leftVotes"), 1e-9)
        assertEquals(20, json.getJSONArray("grid").length())
        assertEquals(12, json.getJSONArray("grid").getJSONArray(0).length())
    }

    @Test
    fun resetForgetsTheGripEntirely() {
        tap(0.80, 0.90, times = 40)
        GripSense.reset()

        assertEquals(Handedness.UNKNOWN to 0.0, GripSense.handedness())
        assertEquals(0, GripSense.report().totalTaps)
        assertEquals(0.0, GripSense.stretchTapShare(), 1e-9)
        assertTrue(GripSense.heatmap().all { row -> row.all { it == 0 } })
    }
}
