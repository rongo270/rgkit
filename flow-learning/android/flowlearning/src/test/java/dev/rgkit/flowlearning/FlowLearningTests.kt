package dev.rgkit.flowlearning

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Feeds whole sessions through [FlowLearning.track] with the session clock
 * hand-cranked, then checks what the miners make of them. No Context, so
 * persistence no-ops.
 */
class FlowLearningTests {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        FlowLearning.clock = { now }
        FlowLearning.config = FlowConfig()
        FlowLearning.reset()
    }

    @After
    fun tearDown() {
        FlowLearning.reset()
        FlowLearning.config = FlowConfig()
        FlowLearning.clock = { System.currentTimeMillis() }
    }

    /** One complete session: the steps, then the app goes to background. */
    private fun session(vararg steps: String) {
        for (step in steps) {
            FlowLearning.track(step)
            now += 5_000
        }
        FlowLearning.closeSession()
    }

    private fun insightsOf(type: InsightType) = FlowLearning.insights().filter { it.type == type }

    // ------------------------------------------------------- session cutting

    @Test
    fun aSessionIsWhatHappensBeforeTheAppGoesAway() {
        session("home", "feed")
        session("home", "settings")
        assertEquals(2, FlowLearning.sessionCount())
    }

    @Test
    fun aLongPauseStartsANewSession() {
        FlowLearning.track("home")
        now += 5_000
        FlowLearning.track("feed")
        now += 91_000 // past the 90 s gap
        FlowLearning.track("home")
        FlowLearning.track("profile")

        assertEquals(1, FlowLearning.sessionCount()) // the first one closed itself
        FlowLearning.closeSession()
        assertEquals(2, FlowLearning.sessionCount())
    }

    @Test
    fun theSessionGapIsConfigurable() {
        FlowLearning.config = FlowConfig(sessionGapMs = 10_000)
        FlowLearning.track("home")
        now += 11_000
        FlowLearning.track("feed")
        FlowLearning.track("cart")
        FlowLearning.closeSession()

        // "home" alone was too short to keep; the second session survived.
        assertEquals(1, FlowLearning.sessionCount())
    }

    @Test
    fun aSingleStepIsNotAJourney() {
        session("home")
        assertEquals(0, FlowLearning.sessionCount())
    }

    @Test
    fun immediateRepeatsAreCollapsed() {
        // Recompositions and tab re-taps must not look like navigation.
        FlowLearning.track("home")
        FlowLearning.track("home")
        FlowLearning.track("home")
        FlowLearning.track("feed")
        FlowLearning.closeSession()

        assertEquals(listOf(listOf("home", "feed")), FlowLearning.commonPaths(length = 2).map { it.first })
    }

    @Test
    fun onlyTheNewestSessionsAreKept() {
        FlowLearning.config = FlowConfig(maxSessions = 3)
        for (i in 1..5) session("home", "step_$i")

        assertEquals(3, FlowLearning.sessionCount())
        val kept = FlowLearning.commonPaths(length = 2, top = 5).map { it.first.last() }
        assertEquals(listOf("step_3", "step_4", "step_5"), kept.sorted())
    }

    @Test
    fun resetForgetsEverySession() {
        repeat(6) { session("home", "feed") }
        FlowLearning.reset()

        assertEquals(0, FlowLearning.sessionCount())
        assertTrue(FlowLearning.insights().isEmpty())
        assertTrue(FlowLearning.commonPaths().isEmpty())
        assertTrue(FlowLearning.transitionsFrom("home").isEmpty())
    }

    // ---------------------------------------------------------- mining gates

    @Test
    fun nothingIsMinedFromAHandfulOfSessions() {
        repeat(4) { session("price", "reviews") }
        assertTrue(FlowLearning.insights().isEmpty())
    }

    @Test
    fun patternsNeedEnoughSupportingSessions() {
        // Ten sessions clears the "5 sessions" floor but not minSupport = 12.
        repeat(10) { session("price", "reviews") }
        assertTrue(insightsOf(InsightType.ORDERING).isEmpty())

        FlowLearning.config = FlowConfig(minSupport = 8)
        assertTrue(insightsOf(InsightType.ORDERING).isNotEmpty())
    }

    // --------------------------------------------------------------- miners

    @Test
    fun aConsistentOrderBecomesAnOrderingInsight() {
        repeat(12) { session("price", "reviews") }

        val ordering = insightsOf(InsightType.ORDERING).single()
        assertEquals("100% visit 'price' before 'reviews'", ordering.title)
        assertEquals(12, ordering.sampleSessions)
        assertTrue(ordering.detail, ordering.detail.contains("Of 12 sessions"))
        assertTrue(ordering.recommendation, ordering.recommendation.contains("surface 'price' earlier"))
    }

    @Test
    fun aCoinFlipOrderIsNotAPattern() {
        repeat(12) { session("price", "reviews") }
        repeat(12) { session("reviews", "price") }

        assertTrue(insightsOf(InsightType.ORDERING).isEmpty())
    }

    @Test
    fun theOrderingInsightNamesWhicheverStepComesFirst() {
        // Reported from the majority's point of view, whichever way round it is.
        repeat(12) { session("reviews", "price") }

        assertEquals(
            "100% visit 'reviews' before 'price'",
            insightsOf(InsightType.ORDERING).single().title,
        )
    }

    @Test
    fun aScreenMostSessionsDieOnBecomesADropOff() {
        repeat(12) { session("home", "cart") }

        val drop = insightsOf(InsightType.DROP_OFF).single()
        assertEquals("100% of sessions end at 'cart'", drop.title)
        assertEquals(12, drop.sampleSessions)
        assertTrue(drop.recommendation, drop.recommendation.contains("journeys die"))
    }

    @Test
    fun aScatterOfEndingsIsNoDropOff() {
        for (i in 1..12) session("home", "end_$i")
        assertTrue(insightsOf(InsightType.DROP_OFF).isEmpty())
    }

    @Test
    fun bouncingStraightBackBecomesAConfusionLoop() {
        repeat(12) { session("product", "shipping", "product", "checkout") }

        val loop = insightsOf(InsightType.LOOP).single()
        assertEquals("'product' → 'shipping' → straight back (100%)", loop.title)
        assertEquals(12, loop.sampleSessions)
        assertTrue(loop.recommendation, loop.recommendation.contains("expecting something they don't find"))
    }

    @Test
    fun movingOnRatherThanBackIsNoLoop() {
        repeat(12) { session("product", "shipping", "checkout") }
        assertTrue(insightsOf(InsightType.LOOP).isEmpty())
    }

    @Test
    fun aFunnelReportsItsWorstHop() {
        FlowLearning.defineFunnel("checkout", listOf("f_cart", "f_address", "f_pay"))
        repeat(9) { session("f_cart", "f_address") }
        repeat(3) { session("f_cart", "f_address", "f_pay") }

        val leak = insightsOf(InsightType.FUNNEL_LEAK).single()
        assertEquals("'checkout' leaks 75% at 'f_address' → 'f_pay'", leak.title)
        assertEquals(12, leak.sampleSessions)
        assertTrue(leak.detail, leak.detail.contains("'f_cart' 12"))
        assertTrue(leak.detail, leak.detail.contains("'f_pay' 3"))
    }

    @Test
    fun aFunnelPeopleCompleteIsNotReported() {
        FlowLearning.defineFunnel("onboarding", listOf("o_start", "o_name", "o_done"))
        repeat(12) { session("o_start", "o_name", "o_done") }

        assertTrue(insightsOf(InsightType.FUNNEL_LEAK).isEmpty())
    }

    @Test
    fun aFunnelWithStepsNobodyHasTakenIsSkipped() {
        FlowLearning.defineFunnel("ghost", listOf("g_one", "g_two"))
        repeat(12) { session("home", "feed") }

        assertTrue(insightsOf(InsightType.FUNNEL_LEAK).isEmpty())
    }

    @Test
    fun theBusiestThreeStepRunBecomesTheCommonJourney() {
        repeat(12) { session("home", "search", "result") }

        val path = insightsOf(InsightType.COMMON_PATH).single()
        assertEquals("Most common journey: 'home' → 'search' → 'result'", path.title)
        assertEquals(12, path.sampleSessions)
        assertEquals(1.0, path.strength, 1e-9)
    }

    @Test
    fun theScreenMostJourneysStartOnBecomesTheEntryPoint() {
        repeat(12) { session("home", "feed") }

        val entry = insightsOf(InsightType.ENTRY_POINT).single()
        assertEquals("100% of sessions start at 'home'", entry.title)
        assertTrue(entry.recommendation, entry.recommendation.contains("front door"))
    }

    @Test
    fun aSpreadOfEntryPointsIsNotAFrontDoor() {
        for (i in 1..12) session("start_$i", "home")
        assertTrue(insightsOf(InsightType.ENTRY_POINT).isEmpty())
    }

    @Test
    fun insightsComeBackStrongestFirst() {
        repeat(12) { session("home", "search", "result") }
        repeat(12) { session("product", "shipping", "product") }

        val all = FlowLearning.insights()
        assertTrue(all.size > 1)
        assertEquals(all.sortedByDescending { it.strength }, all)
        for (i in all) {
            assertTrue("strength ${i.strength}", i.strength in 0.0..1.0)
            assertTrue(i.title.isNotBlank())
            assertTrue(i.recommendation.isNotBlank())
            assertTrue(i.sampleSessions > 0)
        }
    }

    @Test
    fun theOpenSessionIsMinedToo() {
        repeat(11) { session("price", "reviews") }
        // A twelfth journey still in progress tips it over minSupport.
        FlowLearning.track("price")
        FlowLearning.track("reviews")

        assertEquals(11, FlowLearning.sessionCount())
        assertEquals(12, insightsOf(InsightType.ORDERING).single().sampleSessions)
    }

    // ------------------------------------------------------------ transitions

    @Test
    fun transitionsAreProbabilitiesOverWhatHappenedNext() {
        repeat(8) { session("home", "feed") }
        repeat(2) { session("home", "settings") }

        assertEquals(
            listOf("feed" to 0.8, "settings" to 0.2),
            FlowLearning.transitionsFrom("home"),
        )
    }

    @Test
    fun aStepNobodyHasReachedHasNoTransitions() {
        repeat(6) { session("home", "feed") }
        assertTrue(FlowLearning.transitionsFrom("never_tracked").isEmpty())
        assertTrue(FlowLearning.transitionsFrom("feed").isEmpty()) // always the last step
    }

    @Test
    fun commonPathsCanBeAskedForAnyLength() {
        repeat(6) { session("home", "search", "result", "buy") }

        val pairs = FlowLearning.commonPaths(length = 2, top = 2)
        assertEquals(2, pairs.size)
        assertEquals(6, pairs.first().second)
        assertEquals(2, pairs.first().first.size)

        val quads = FlowLearning.commonPaths(length = 4, top = 5)
        assertEquals(listOf("home", "search", "result", "buy"), quads.single().first)
    }

    // ---------------------------------------------------------------- export

    @Test
    fun exportCarriesTheDictionaryAndTheSessions() {
        FlowLearning.defineFunnel("checkout", listOf("home", "feed"))
        repeat(2) { session("home", "feed") }

        val json = JSONObject(FlowLearning.exportJson())
        val names = json.getJSONArray("names")
        assertEquals(2, names.length())
        assertEquals("home", names.getString(0))
        assertEquals(2, json.getJSONArray("sessions").length())
        // Sessions are stored as dictionary ids, never raw strings.
        assertEquals(0, json.getJSONArray("sessions").getJSONArray(0).getInt(0))
        assertEquals(listOf("home", "feed").size, json.getJSONObject("funnels").getJSONArray("checkout").length())
    }

    @Test
    fun stepNamesAreDictionaryCodedOnce() {
        repeat(5) { session("home", "feed") }

        val names = JSONObject(FlowLearning.exportJson()).getJSONArray("names")
        assertEquals(2, names.length())
        assertNull(FlowLearning.transitionsFrom("home").find { it.first == "home" })
    }
}
