package dev.rgkit.adaptiveui

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Random

/**
 * Drives the bandit with a seeded [java.util.Random] and a hand-cranked clock,
 * so both the reward maths and the exploration/exploitation behaviour are
 * repeatable. No Context, so nothing is persisted.
 */
class AdaptiveUiTests {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        AdaptiveUi.random = Random(20260302)
        AdaptiveUi.clock = { now }
        AdaptiveUi.reset()
    }

    @After
    fun tearDown() {
        AdaptiveUi.reset()
        AdaptiveUi.random = Random()
        AdaptiveUi.clock = { System.currentTimeMillis() }
    }

    /** One showing: dwell [dwellMs], tap [clicks] items, scroll to [depth]. */
    private fun showing(
        collectionId: String = "feed",
        dwellMs: Long = 10_000,
        clicks: Int = 0,
        depth: Double = 0.0,
    ): LayoutStyle {
        val style = AdaptiveUi.beginSession(collectionId)
        repeat(clicks) { AdaptiveUi.recordItemClick(collectionId) }
        if (depth > 0) AdaptiveUi.recordScrollDepth(collectionId, depth)
        now += dwellMs
        AdaptiveUi.endSession(collectionId)
        return style
    }

    private fun meanFor(style: LayoutStyle, collectionId: String = "feed"): Double =
        AdaptiveUi.stats(collectionId).getValue(style).meanReward

    // ------------------------------------------------------------- the reward

    @Test
    fun aGlanceWithNoInteractionScoresAlmostNothing() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(dwellMs = 1_500)

        assertEquals(0.05, meanFor(LayoutStyle.GRID), 1e-9)
    }

    @Test
    fun aShortVisitThatEndedInATapIsNotABounce() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(dwellMs = 1_500, clicks = 1)

        // One of three taps: 0.6 × 1/3, reported to three decimals.
        assertEquals(0.2, meanFor(LayoutStyle.GRID), 1e-9)
    }

    @Test
    fun tapsAreMostOfTheReward() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 3)

        assertEquals(0.6, meanFor(LayoutStyle.GRID), 1e-9)
    }

    @Test
    fun tappingMoreThanThreeTimesDoesNotScoreExtra() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 12)

        assertEquals(0.6, meanFor(LayoutStyle.GRID), 1e-9)
    }

    @Test
    fun scrollDepthCarriesTheRest() {
        AdaptiveUi.force("feed", LayoutStyle.LIST)
        showing(depth = 1.0)

        assertEquals(0.4, meanFor(LayoutStyle.LIST), 1e-9)
    }

    @Test
    fun aFullyEngagedShowingScoresOne() {
        AdaptiveUi.force("feed", LayoutStyle.CARDS)
        showing(clicks = 3, depth = 1.0)

        assertEquals(1.0, meanFor(LayoutStyle.CARDS), 1e-9)
    }

    @Test
    fun theDeepestScrollOfTheShowingIsTheOneThatCounts() {
        AdaptiveUi.force("feed", LayoutStyle.LIST)
        AdaptiveUi.beginSession("feed")
        AdaptiveUi.recordScrollDepth("feed", 0.9)
        AdaptiveUi.recordScrollDepth("feed", 0.2) // scrolled back up
        now += 10_000
        AdaptiveUi.endSession("feed")

        assertEquals(0.36, meanFor(LayoutStyle.LIST), 1e-9)
    }

    @Test
    fun scrollDepthIsClampedToSomethingSane() {
        AdaptiveUi.force("feed", LayoutStyle.LIST)
        AdaptiveUi.beginSession("feed")
        AdaptiveUi.recordScrollDepth("feed", 4.0)
        AdaptiveUi.recordScrollDepth("feed", -3.0)
        now += 10_000
        AdaptiveUi.endSession("feed")

        assertEquals(0.4, meanFor(LayoutStyle.LIST), 1e-9)
    }

    @Test
    fun rewardsAreAveragedAcrossShowings() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 3, depth = 1.0)  // 1.0
        showing(dwellMs = 1_000)          // 0.05

        assertEquals(2, AdaptiveUi.stats("feed").getValue(LayoutStyle.GRID).sessions)
        assertEquals(0.525, meanFor(LayoutStyle.GRID), 1e-9)
    }

    @Test
    fun engagementReportedOutsideAShowingIsIgnored() {
        AdaptiveUi.recordItemClick("feed")
        AdaptiveUi.recordScrollDepth("feed", 1.0)
        AdaptiveUi.endSession("feed")

        assertTrue(AdaptiveUi.stats("feed").isEmpty())
    }

    @Test
    fun endingAShowingTwiceOnlyCountsOnce() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 3)
        AdaptiveUi.endSession("feed")

        assertEquals(1, AdaptiveUi.stats("feed").getValue(LayoutStyle.GRID).sessions)
    }

    @Test
    fun collectionsLearnSeparately() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        AdaptiveUi.force("search", LayoutStyle.LIST)
        showing("feed", clicks = 3, depth = 1.0)
        showing("search", dwellMs = 500)

        assertEquals(1.0, meanFor(LayoutStyle.GRID, "feed"), 1e-9)
        assertEquals(0.05, meanFor(LayoutStyle.LIST, "search"), 1e-9)
        assertTrue(AdaptiveUi.stats("feed").keys == setOf(LayoutStyle.GRID))
    }

    // -------------------------------------------------------------- pinning

    @Test
    fun aPinnedStyleIsAlwaysTheOneShown() {
        AdaptiveUi.force("feed", LayoutStyle.CAROUSEL)

        repeat(20) { assertEquals(LayoutStyle.CAROUSEL, AdaptiveUi.styleFor("feed")) }
    }

    @Test
    fun clearingThePinHandsTheChoiceBackToTheBandit() {
        AdaptiveUi.force("feed", LayoutStyle.CAROUSEL)
        AdaptiveUi.force("feed", null)

        val drawn = (1..40).map { AdaptiveUi.styleFor("feed") }.toSet()
        assertTrue("expected exploration, got $drawn", drawn.size > 1)
    }

    @Test
    fun aPinOutsideTheAllowedStylesIsIgnored() {
        AdaptiveUi.force("feed", LayoutStyle.CAROUSEL)
        val allowed = setOf(LayoutStyle.GRID, LayoutStyle.LIST)

        repeat(20) { assertTrue(AdaptiveUi.styleFor("feed", allowed) in allowed) }
    }

    @Test
    fun onlyAllowedStylesAreEverChosen() {
        val allowed = setOf(LayoutStyle.LIST)
        repeat(20) { assertEquals(LayoutStyle.LIST, AdaptiveUi.beginSession("feed", allowed)) }
    }

    // -------------------------------------------------------------- learning

    @Test
    fun everyStyleIsTriedWhileNothingIsKnown() {
        val seen = (1..200).map { AdaptiveUi.styleFor("feed") }.toSet()
        assertEquals(LayoutStyle.entries.toSet(), seen)
    }

    @Test
    fun theStyleThisUserEngagesWithWins() {
        // Teach it: CARDS is loved, everything else is bounced.
        for (style in LayoutStyle.entries) {
            AdaptiveUi.force("feed", style)
            repeat(15) {
                if (style == LayoutStyle.CARDS) showing(clicks = 3, depth = 1.0) else showing(dwellMs = 500)
            }
        }
        AdaptiveUi.force("feed", null)

        val picks = (1..200).map { AdaptiveUi.styleFor("feed") }
        val cardShare = picks.count { it == LayoutStyle.CARDS } / 200.0
        assertTrue("CARDS was picked only ${(cardShare * 100).toInt()}% of the time", cardShare > 0.9)
    }

    @Test
    fun aStyleThatStopsWorkingLosesItsLead() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        repeat(10) { showing(clicks = 3, depth = 1.0) }
        assertEquals(1.0, meanFor(LayoutStyle.GRID), 1e-9)

        repeat(30) { showing(dwellMs = 500) }
        assertTrue("mean should have fallen: ${meanFor(LayoutStyle.GRID)}", meanFor(LayoutStyle.GRID) < 0.3)
    }

    // ---------------------------------------------------- reporting / export

    @Test
    fun statsRoundTheAveragesForDisplay() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 1)               // 0.2
        showing(clicks = 1, depth = 0.5)  // 0.4

        val stats = AdaptiveUi.stats("feed").getValue(LayoutStyle.GRID)
        assertEquals(2, stats.sessions)
        assertEquals(0.3, stats.meanReward, 1e-9)
    }

    @Test
    fun theExplanationNamesTheLeader() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        repeat(3) { showing(clicks = 3, depth = 1.0) }
        AdaptiveUi.force("feed", LayoutStyle.LIST)
        repeat(3) { showing(dwellMs = 500) }

        val explanation = AdaptiveUi.explanation("feed")
        assertTrue(explanation, explanation.contains("currently favors Grid"))
        assertTrue(explanation, explanation.contains("Grid: avg 100% over 3 showings"))
        assertTrue(explanation, explanation.contains("List: avg 5% over 3 showings"))
    }

    @Test
    fun anUntouchedCollectionSaysSoRatherThanGuessing() {
        assertEquals("No data yet for 'feed'.", AdaptiveUi.explanation("feed"))
        assertTrue(AdaptiveUi.stats("feed").isEmpty())
    }

    @Test
    fun exportCarriesTheArmsAndThePins() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing(clicks = 3, depth = 1.0)

        val json = JSONObject(AdaptiveUi.exportJson())
        val arm = json.getJSONObject("collections").getJSONObject("feed").getJSONObject("GRID")
        assertEquals(1, arm.getInt("n"))
        assertEquals(1.0, arm.getDouble("mean"), 1e-9)
        assertEquals("GRID", json.getJSONObject("forced").getString("feed"))
    }

    // ----------------------------------------------------------------- reset

    @Test
    fun oneCollectionCanBeForgottenOnItsOwn() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        AdaptiveUi.force("search", LayoutStyle.LIST)
        showing("feed", clicks = 3)
        showing("search", clicks = 3)

        AdaptiveUi.reset("feed")

        assertTrue(AdaptiveUi.stats("feed").isEmpty())
        assertEquals(1, AdaptiveUi.stats("search").getValue(LayoutStyle.LIST).sessions)
        // The pin went with it.
        val drawn = (1..40).map { AdaptiveUi.styleFor("feed") }.toSet()
        assertTrue("expected exploration, got $drawn", drawn.size > 1)
        assertEquals(LayoutStyle.LIST, AdaptiveUi.styleFor("search"))
    }

    @Test
    fun everythingCanBeForgottenAtOnce() {
        AdaptiveUi.force("feed", LayoutStyle.GRID)
        showing("feed", clicks = 3)
        AdaptiveUi.reset()

        assertTrue(AdaptiveUi.stats("feed").isEmpty())
        assertEquals("No data yet for 'feed'.", AdaptiveUi.explanation("feed"))
        assertNotEquals("", AdaptiveUi.exportJson())
    }
}
