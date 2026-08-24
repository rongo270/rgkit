package dev.rgkit.discoverycoach

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the coach's frequency rules and feature ranking with the clock in
 * hand, so a month of backoff fits into one test. No Context: persistence
 * no-ops and the listener path (main Handler) is left to instrumentation.
 */
class DiscoveryCoachTests {

    private val day = 86_400_000L
    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        DiscoveryCoach.clock = { now }
        DiscoveryCoach.config = CoachConfig()
        DiscoveryCoach.reset()
    }

    @After
    fun tearDown() {
        DiscoveryCoach.reset()
        DiscoveryCoach.config = CoachConfig()
        DiscoveryCoach.clock = { System.currentTimeMillis() }
    }

    private fun feature(
        id: String,
        priority: Int = 3,
        minSessions: Int = 2,
        prerequisites: List<String> = emptyList(),
    ) = DiscoverableFeature(
        id = id,
        title = id.replace('_', ' '),
        tip = "Try $id",
        priority = priority,
        minSessionsBeforeNudge = minSessions,
        prerequisites = prerequisites,
    )

    /** Simulates [count] app launches, a minute apart. */
    private fun sessions(count: Int) {
        repeat(count) {
            now += 60_000
            DiscoveryCoach.onSessionStart()
        }
    }

    private fun nudge() = DiscoveryCoach.maybeNudge(now)

    // ---------------------------------------------------------- when to nudge

    @Test
    fun aBrandNewUserIsLeftAlone() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        assertNull(nudge())

        sessions(1)
        assertNull(nudge()) // still short of minSessionsBeforeNudge
    }

    @Test
    fun anUndiscoveredFeatureIsTaughtOnceTheUserHasSettledIn() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(2)

        val n = nudge()!!
        assertEquals("swipe_to_archive", n.feature.id)
        assertTrue(n.reason, n.reason.contains("priority 3"))
        assertTrue(n.reason, n.reason.contains("unused after 2 sessions"))
    }

    @Test
    fun aFeatureTheUserAlreadyFoundIsNeverTaught() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(4)
        DiscoveryCoach.used("swipe_to_archive")

        assertNull(nudge())
    }

    @Test
    fun theHighestPriorityUndiscoveredFeatureWins() {
        DiscoveryCoach.register(
            listOf(
                feature("minor_thing", priority = 1),
                feature("the_killer_feature", priority = 5),
                feature("middling", priority = 3),
            )
        )
        sessions(3)

        assertEquals("the_killer_feature", nudge()!!.feature.id)
    }

    @Test
    fun aFeatureWaitsForItsPrerequisite() {
        DiscoveryCoach.register(
            listOf(
                feature("search", priority = 4),
                feature("saved_filters", priority = 5, prerequisites = listOf("search")),
            )
        )
        sessions(3)

        // Filters outrank search, but teaching them first would make no sense.
        assertEquals("search", nudge()!!.feature.id)

        DiscoveryCoach.used("search")
        assertEquals("saved_filters", nudge()!!.feature.id)
    }

    @Test
    fun featuresRegisteredLaterStillHaveToRipen() {
        DiscoveryCoach.register(listOf(feature("old_feature")))
        sessions(5)
        DiscoveryCoach.register(listOf(feature("new_feature", priority = 5)))

        // The new one was registered at session 5, so it is not ripe yet.
        assertEquals("old_feature", nudge()!!.feature.id)

        sessions(2)
        assertEquals("new_feature", nudge()!!.feature.id)
    }

    @Test
    fun registeringAgainDoesNotResetRipeness() {
        val f = feature("swipe_to_archive")
        DiscoveryCoach.register(listOf(f))
        sessions(3)
        DiscoveryCoach.register(listOf(f)) // every launch re-registers

        assertNotNull(nudge())
    }

    // ------------------------------------------------------- how often to nudge

    @Test
    fun onlyOneNudgePerSession() {
        DiscoveryCoach.register(listOf(feature("a", priority = 5), feature("b", priority = 4)))
        sessions(3)

        DiscoveryCoach.nudgeShown(nudge()!!.feature.id)
        now += 5 * day // long past every cooldown
        assertNull("a second nudge in the same session", nudge())

        sessions(1)
        assertNotNull(nudge())
    }

    @Test
    fun nudgesAreSpacedOutWithinTheDay() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false)
        DiscoveryCoach.register(listOf(feature("a", priority = 5), feature("b", priority = 4)))
        sessions(3)

        DiscoveryCoach.nudgeShown("a")
        now += 3 * 3_600_000 // three hours: inside the four-hour gap
        assertNull(nudge())

        now += 2 * 3_600_000
        assertEquals("b", nudge()!!.feature.id)
    }

    @Test
    fun thereIsAHardCapPerDay() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0)
        DiscoveryCoach.register(
            listOf(feature("a", priority = 5), feature("b", priority = 4), feature("c", priority = 3))
        )
        sessions(3)

        DiscoveryCoach.nudgeShown("a")
        DiscoveryCoach.nudgeShown("b")
        assertNull("two a day is the cap", nudge())

        now += day
        // The cap has reset. "a" is top priority and its one-day backoff has
        // just elapsed, so it gets another turn ahead of the untouched "c".
        assertEquals("a", nudge()!!.feature.id)
    }

    @Test
    fun aSecondNudgeForTheSameFeatureBacksOffADay() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0)
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)

        DiscoveryCoach.nudgeShown("swipe_to_archive")
        now += 12 * 3_600_000
        assertNull(nudge())

        now += 13 * 3_600_000 // just over a day since it was shown
        val second = nudge()!!
        assertEquals("swipe_to_archive", second.feature.id)
        assertTrue(second.reason, second.reason.contains("nudged 1× before"))
    }

    @Test
    fun theBackoffGetsLongerEachTime() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0, maxPerDay = 99)
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)

        // Shown three times: the next wait is the third backoff step, 7 days.
        repeat(3) {
            DiscoveryCoach.nudgeShown("swipe_to_archive")
            now += 4 * day
        }
        assertNull("4 days is not enough after the third nudge", nudge())

        now += 4 * day
        assertNotNull(nudge())
    }

    @Test
    fun beingDismissedBuysAMonthOfSilence() {
        DiscoveryCoach.config = CoachConfig(
            onePerSession = false, minGapMs = 0, maxPerDay = 99, dismissLimit = 1,
        )
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)

        DiscoveryCoach.nudgeShown("swipe_to_archive")
        DiscoveryCoach.nudgeDismissed("swipe_to_archive")
        now += 20 * day
        assertNull(nudge())

        now += 11 * day // 31 days since it was last shown
        assertNotNull(nudge())
    }

    @Test
    fun aFeatureDismissedTwiceIsDroppedForGood() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0, maxPerDay = 99)
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)

        repeat(2) {
            DiscoveryCoach.nudgeShown("swipe_to_archive")
            DiscoveryCoach.nudgeDismissed("swipe_to_archive")
            now += 10 * day
        }

        // Two showings and two dismissals sink the score below every other
        // candidate — the month of suppression passes and it still stays quiet.
        assertNull(nudge())
        now += 60 * day
        assertNull(nudge())
    }

    @Test
    fun aDismissedFeatureLosesToAnUntouchedOne() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0, maxPerDay = 99)
        DiscoveryCoach.register(listOf(feature("dismissed", priority = 5), feature("fresh", priority = 4)))
        sessions(3)

        DiscoveryCoach.nudgeShown("dismissed")
        DiscoveryCoach.nudgeDismissed("dismissed")
        now += 2 * day

        assertEquals("fresh", nudge()!!.feature.id)
    }

    @Test
    fun withNothingLeftToTeachNothingIsShown() {
        DiscoveryCoach.register(listOf(feature("a"), feature("b")))
        sessions(3)
        DiscoveryCoach.used("a")
        DiscoveryCoach.used("b")

        assertNull(nudge())
    }

    @Test
    fun rotatingTheScreenIsNotANewSession() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        DiscoveryCoach.onSessionStart()
        now += 5_000
        DiscoveryCoach.onSessionStart()
        now += 5_000
        DiscoveryCoach.onSessionStart()

        assertNull("three launches in ten seconds is one session", nudge())
    }

    // --------------------------------------------------------------- feedback

    @Test
    fun acceptingANudgeCountsAsDiscovery() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)
        DiscoveryCoach.nudgeShown("swipe_to_archive")
        DiscoveryCoach.nudgeAccepted("swipe_to_archive")

        val report = DiscoveryCoach.discoveryReport()
        assertEquals(1, report.discovered)
        assertEquals(100, report.discoveryPercent)
        assertEquals(100, report.nudgeSuccessPercent)
        assertNull(nudge())
    }

    @Test
    fun findingAFeatureWithoutHelpIsNotANudgeSuccess() {
        DiscoveryCoach.register(listOf(feature("a"), feature("b")))
        sessions(3)
        DiscoveryCoach.used("a")            // found it alone
        DiscoveryCoach.nudgeShown("b")      // taught, ignored

        val report = DiscoveryCoach.discoveryReport()
        assertEquals(1, report.discovered)
        assertEquals(50, report.discoveryPercent)
        assertEquals(0, report.nudgeSuccessPercent)
    }

    @Test
    fun featuresNobodyWantsAreCalledOut() {
        DiscoveryCoach.config = CoachConfig(onePerSession = false, minGapMs = 0, maxPerDay = 99)
        DiscoveryCoach.register(listOf(feature("ignored"), feature("loved")))
        sessions(3)
        repeat(3) { DiscoveryCoach.nudgeShown("ignored") }
        DiscoveryCoach.used("loved")

        val dead = DiscoveryCoach.discoveryReport().deadFeatures
        assertEquals(listOf("ignored"), dead.map { it.id })
        assertEquals(3, dead.single().timesNudged)
        assertTrue(dead.single().usedCount == 0)
    }

    @Test
    fun theReportCoversEveryRegisteredFeature() {
        DiscoveryCoach.register(listOf(feature("a"), feature("b"), feature("c")))
        sessions(3)
        DiscoveryCoach.used("a")
        DiscoveryCoach.used("a")
        DiscoveryCoach.nudgeShown("b")
        DiscoveryCoach.nudgeDismissed("b")

        val report = DiscoveryCoach.discoveryReport()
        assertEquals(3, report.registered)
        assertEquals(33, report.discoveryPercent)
        val byId = report.features.associateBy { it.id }
        assertEquals(2, byId.getValue("a").usedCount)
        assertTrue(byId.getValue("a").discovered)
        assertEquals(1, byId.getValue("b").timesNudged)
        assertEquals(1, byId.getValue("b").timesDismissed)
        assertTrue(!byId.getValue("c").discovered)
        assertEquals("a", byId.getValue("a").title)
    }

    @Test
    fun anEmptyCatalogReportsZerosRatherThanDividingByZero() {
        val report = DiscoveryCoach.discoveryReport()
        assertEquals(0, report.registered)
        assertEquals(0, report.discoveryPercent)
        assertEquals(0, report.nudgeSuccessPercent)
        assertTrue(report.features.isEmpty())
        assertTrue(report.deadFeatures.isEmpty())
    }

    // -------------------------------------------------------- export / reset

    @Test
    fun exportCarriesTheCoachingHistory() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)
        DiscoveryCoach.nudgeShown("swipe_to_archive")
        DiscoveryCoach.nudgeDismissed("swipe_to_archive")

        val json = JSONObject(DiscoveryCoach.exportJson())
        assertEquals(3, json.getInt("sessionCount"))
        assertEquals(1, json.getInt("nudgesToday"))
        val state = json.getJSONObject("states").getJSONObject("swipe_to_archive")
        assertEquals(1, state.getInt("shownCount"))
        assertEquals(1, state.getInt("dismissCount"))
        assertEquals(0, state.getInt("usedCount"))
        assertEquals(now, state.getLong("lastShownAt"))
    }

    @Test
    fun resetForgetsTheUserAndTheCatalog() {
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(3)
        DiscoveryCoach.nudgeShown("swipe_to_archive")
        DiscoveryCoach.reset()

        assertEquals(0, DiscoveryCoach.discoveryReport().registered)
        assertNull(nudge())

        // Re-registering starts the relationship over.
        DiscoveryCoach.register(listOf(feature("swipe_to_archive")))
        sessions(2)
        assertNotNull(nudge())
    }
}
