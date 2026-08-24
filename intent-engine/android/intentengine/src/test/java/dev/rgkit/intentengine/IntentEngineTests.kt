package dev.rgkit.intentengine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives the detectors through the public event API with [IntentEngine.clock]
 * swapped for a hand-cranked one. With no Application, persistence no-ops and
 * the main-thread Handler never runs, so signals are read back out of
 * [IntentEngine.recentSignals] rather than through a listener.
 */
class IntentEngineTests {

    private var now = 1_700_000_000_000L

    @Before
    fun setUp() {
        IntentEngine.clock = { now }
        IntentEngine.config = IntentConfig()
        IntentEngine.reset()
    }

    @After
    fun tearDown() {
        IntentEngine.reset()
        IntentEngine.config = IntentConfig()
        IntentEngine.clock = { System.currentTimeMillis() }
    }

    private fun advance(ms: Long) { now += ms }

    private fun signals() = IntentEngine.recentSignals()
    private fun types() = signals().map { it.type }
    private fun only(): IntentSignal {
        val all = signals()
        assertEquals("expected exactly one signal, got ${all.map { it.type }}", 1, all.size)
        return all.first()
    }

    // ------------------------------------------------------------- rage taps

    @Test
    fun threeFastTapsOnTheSameSpotAreARageTap() {
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(120) }

        val s = only()
        assertEquals(IntentType.RAGE_TAP, s.type)
        assertEquals("save", s.target)
        assertEquals(0.6, s.confidence, 1e-9)
        assertTrue(s.evidence, s.evidence.contains("3 taps"))
    }

    @Test
    fun tapsSpreadAcrossTheScreenAreNotRage() {
        IntentEngine.onTap(10f, 10f, "a"); advance(100)
        IntentEngine.onTap(600f, 900f, "b"); advance(100)
        IntentEngine.onTap(50f, 1200f, "c")

        assertTrue(types().toString(), IntentType.RAGE_TAP !in types())
    }

    @Test
    fun tapsSpreadOverTimeAreNotRage() {
        // Same spot, but outside the 700 ms burst window.
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(800) }

        assertTrue(types().toString(), IntentType.RAGE_TAP !in types())
    }

    @Test
    fun aRageBurstIsCountedOnceNotOncePerExtraTap() {
        repeat(6) { IntentEngine.onTap(100f, 200f, "save"); advance(80) }

        assertEquals(listOf(IntentType.RAGE_TAP), types())
    }

    @Test
    fun theRageWindowIsConfigurable() {
        IntentEngine.config = IntentConfig(rageTapCount = 5)
        // Untargeted taps, so only the rage detector is in play here.
        repeat(4) { IntentEngine.onTap(100f, 200f); advance(80) }
        assertTrue(signals().isEmpty())

        IntentEngine.onTap(100f, 200f)
        assertEquals(IntentType.RAGE_TAP, only().type)
    }

    // --------------------------------------------------------- repeated taps

    @Test
    fun retappingTheSameTargetAfterABeatIsARepeatedTap() {
        // A second apart: too slow for rage, well inside the 2.5 s repeat window.
        IntentEngine.onTap(40f, 40f, "submit"); advance(1_000)
        IntentEngine.onTap(40f, 40f, "submit"); advance(1_000)
        IntentEngine.onTap(40f, 40f, "submit")

        val s = only()
        assertEquals(IntentType.REPEATED_TAP, s.type)
        assertEquals("submit", s.target)
        assertEquals(0.55, s.confidence, 1e-9)
    }

    @Test
    fun repeatedTapNeedsATargetToBeAboutSomething() {
        IntentEngine.onTap(40f, 40f, null); advance(1_000)
        IntentEngine.onTap(40f, 40f, null); advance(1_000)
        IntentEngine.onTap(40f, 40f, null)

        assertTrue(signals().isEmpty())
    }

    @Test
    fun tappingDifferentTargetsIsJustUsingTheApp() {
        IntentEngine.onTap(40f, 40f, "one"); advance(1_000)
        IntentEngine.onTap(40f, 40f, "two"); advance(1_000)
        IntentEngine.onTap(40f, 40f, "three")

        assertTrue(signals().isEmpty())
    }

    // ----------------------------------------------------------- double back

    @Test
    fun twoQuickBackPressesAreADoubleBack() {
        IntentEngine.onBackPressed(); advance(300)
        IntentEngine.onBackPressed()

        val s = only()
        assertEquals(IntentType.DOUBLE_BACK, s.type)
        assertEquals(0.7, s.confidence, 1e-9)
        assertNull(s.target)
    }

    @Test
    fun backPressesFurtherApartAreNormalNavigation() {
        IntentEngine.onBackPressed(); advance(2_000)
        IntentEngine.onBackPressed()

        assertTrue(signals().isEmpty())
    }

    // ----------------------------------------------------------- fast scroll

    @Test
    fun sustainedFastScrollingReadsAsScanning() {
        repeat(4) { IntentEngine.onScroll(2_000f); advance(300) }

        val s = only()
        assertEquals(IntentType.FAST_SCROLL_SCAN, s.type)
        assertTrue(s.confidence in 0.55..0.9)
        assertTrue(s.evidence, s.evidence.contains("px/s"))
    }

    @Test
    fun readingSpeedScrollingIsNotScanning() {
        repeat(6) { IntentEngine.onScroll(150f); advance(300) }

        assertTrue(types().toString(), IntentType.FAST_SCROLL_SCAN !in types())
    }

    @Test
    fun aFlickTooBriefToJudgeIsIgnored() {
        // Fast, but over well under the 800 ms minimum duration.
        repeat(4) { IntentEngine.onScroll(2_000f); advance(50) }

        assertTrue(signals().isEmpty())
    }

    // ------------------------------------------------------- type and delete

    /** Types [typed] characters onto [field], then deletes [deleted] of them. */
    private fun typeThenDelete(field: String, from: Int, typed: Int, deleted: Int): Int {
        IntentEngine.onTextChanged(field, from + typed)
        advance(500)
        IntentEngine.onTextChanged(field, from + typed - deleted)
        advance(500)
        return from + typed - deleted
    }

    @Test
    fun threeRoundsOfTypingAndDeletingIsALoop() {
        var len = 0
        len = typeThenDelete("email", len, typed = 5, deleted = 2)
        len = typeThenDelete("email", len, typed = 5, deleted = 2)
        assertTrue("fired too early: ${types()}", signals().isEmpty())

        typeThenDelete("email", len, typed = 5, deleted = 2)

        val s = only()
        assertEquals(IntentType.TYPE_DELETE_LOOP, s.type)
        assertEquals("email", s.target)
        assertEquals(0.7, s.confidence, 1e-9)
    }

    @Test
    fun roundsAreCountedPerFieldNotAcrossTheForm() {
        var a = 0; var b = 0
        repeat(2) { a = typeThenDelete("email", a, 5, 2) }
        repeat(2) { b = typeThenDelete("phone", b, 5, 2) }

        assertTrue("fields should not pool: ${types()}", signals().isEmpty())
    }

    @Test
    fun asingleCorrectionIsNotALoop() {
        typeThenDelete("email", 0, typed = 8, deleted = 3)
        assertTrue(signals().isEmpty())
    }

    @Test
    fun roundsFallOutOfTheWindow() {
        var len = 0
        len = typeThenDelete("email", len, 5, 2)
        len = typeThenDelete("email", len, 5, 2)
        advance(31_000) // the first two bursts age out
        typeThenDelete("email", len, 5, 2)

        assertTrue("stale bursts should not count: ${types()}", signals().isEmpty())
    }

    // ----------------------------------------------------------------- drags

    @Test
    fun aDragOnSomethingUndraggableIsReportedAsIs() {
        IntentEngine.onDragAttempt("photo_card")

        val s = only()
        assertEquals(IntentType.DRAG_ATTEMPT, s.type)
        assertEquals("photo_card", s.target)
        assertEquals(0.7, s.confidence, 1e-9)
    }

    // --------------------------------------------------------------- zigzag

    @Test
    fun bouncingBetweenTwoScreensIsZigzagNavigation() {
        // A round trip is counted from the tail back, so it takes five visits
        // (List→Detail→List→Detail→List) to register two of them.
        for (screen in listOf("List", "Detail", "List", "Detail", "List")) {
            IntentEngine.screenChanged(screen)
            advance(2_000)
        }

        val s = only()
        assertEquals(IntentType.ZIGZAG_NAVIGATION, s.type)
        assertEquals("Detail<->List", s.target)
        assertEquals(0.6, s.confidence, 1e-9)
        assertTrue(s.evidence, s.evidence.contains("2 times"))
    }

    @Test
    fun movingForwardThroughAFlowIsNotZigzag() {
        for (screen in listOf("Cart", "Address", "Payment", "Confirm")) {
            IntentEngine.screenChanged(screen)
            advance(2_000)
        }

        assertTrue(signals().isEmpty())
    }

    @Test
    fun visitsOlderThanTheZigzagWindowAreForgotten() {
        IntentEngine.screenChanged("List"); advance(30_000)
        IntentEngine.screenChanged("Detail"); advance(2_000)
        IntentEngine.screenChanged("List"); advance(2_000)
        IntentEngine.screenChanged("Detail"); advance(2_000)
        IntentEngine.screenChanged("List")

        assertTrue("only 4 fresh visits remain: ${types()}", signals().isEmpty())
    }

    // -------------------------------------------------------------- plumbing

    @Test
    fun signalsAreStampedWithTheScreenTheyHappenedOn() {
        IntentEngine.screenChanged("CheckoutActivity")
        advance(1_000)
        repeat(3) { IntentEngine.onTap(100f, 200f, "pay"); advance(100) }

        assertEquals("CheckoutActivity", only().screen)
    }

    @Test
    fun theSameSignalIsNotRepeatedDuringItsCooldown() {
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        advance(1_000)
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        assertEquals(1, signals().size)

        advance(6_000) // past the 5 s cooldown
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        assertEquals(2, signals().size)
    }

    @Test
    fun lowConfidenceSignalsCanBeSuppressedEntirely() {
        IntentEngine.config = IntentConfig(minConfidence = 0.8)
        IntentEngine.onDragAttempt("card")           // 0.7 — below the floor
        repeat(3) { IntentEngine.onTap(1f, 1f, "x"); advance(100) } // 0.6 — below too

        assertTrue(types().toString(), signals().isEmpty())
    }

    @Test
    fun signalsComeBackNewestFirst() {
        IntentEngine.onDragAttempt("a"); advance(1_000)
        IntentEngine.onBackPressed(); advance(200); IntentEngine.onBackPressed()

        assertEquals(listOf(IntentType.DOUBLE_BACK, IntentType.DRAG_ATTEMPT), types())
        assertTrue(signals()[0].at >= signals()[1].at)
    }

    @Test
    fun countsAreKeptPerTypeAndPerDay() {
        IntentEngine.onDragAttempt("a"); advance(6_000)
        IntentEngine.onDragAttempt("b"); advance(6_000)
        IntentEngine.onBackPressed(); advance(200); IntentEngine.onBackPressed()

        assertEquals(2, IntentEngine.stats()[IntentType.DRAG_ATTEMPT])
        assertEquals(1, IntentEngine.stats()[IntentType.DOUBLE_BACK])
        assertEquals(IntentEngine.stats(), IntentEngine.todayCounts())
    }

    // ---------------------------------------------------------- frustration

    @Test
    fun frustrationIsZeroWhenNothingWentWrong() {
        assertEquals(0, IntentEngine.frustrationScore())
    }

    @Test
    fun frustrationAddsUpTheWeightedSignals() {
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) } // rage 0.6 * 22
        assertEquals(13, IntentEngine.frustrationScore())

        advance(1_000)
        IntentEngine.onBackPressed(); advance(200); IntentEngine.onBackPressed() // 0.7 * 12
        assertEquals(21, IntentEngine.frustrationScore())
    }

    @Test
    fun frustrationOnlyCountsTheRecentWindow() {
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        assertTrue(IntentEngine.frustrationScore() > 0)

        advance(11 * 60_000)
        assertEquals(0, IntentEngine.frustrationScore())
        // Still on the record, just no longer "how it feels right now".
        assertEquals(1, IntentEngine.stats()[IntentType.RAGE_TAP])
    }

    @Test
    fun frustrationNeverExceedsAHundred() {
        // Twenty rage bursts, each past the previous cooldown.
        repeat(20) {
            repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
            advance(6_000)
        }
        assertEquals(100, IntentEngine.frustrationScore(windowMs = 10 * 60_000))
    }

    // ------------------------------------------------------- export / reset

    @Test
    fun exportCarriesCountsAndRecentSignals() {
        IntentEngine.screenChanged("Home")
        IntentEngine.onDragAttempt("card")

        val json = JSONObject(IntentEngine.exportJson())
        assertEquals(1, json.getJSONObject("totals").getInt("DRAG_ATTEMPT"))
        assertEquals(1, json.getJSONArray("recent").length())
        val first = json.getJSONArray("recent").getJSONObject(0)
        assertEquals("DRAG_ATTEMPT", first.getString("type"))
        assertEquals("card", first.getString("target"))
        assertEquals("Home", first.getString("screen"))
    }

    @Test
    fun resetForgetsEverything() {
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        IntentEngine.reset()

        assertTrue(signals().isEmpty())
        assertTrue(IntentEngine.stats().isEmpty())
        assertEquals(0, IntentEngine.frustrationScore())
    }

    @Test
    fun detectorStateIsClearedByResetToo() {
        // Two taps of a burst, then a reset: the third tap must not complete it.
        repeat(2) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        IntentEngine.reset()
        IntentEngine.onTap(100f, 200f, "save")

        assertTrue(signals().isEmpty())
    }

    // -------------------------------------------------------- signal content

    @Test
    fun everyTypeCarriesAMeaningAndASuggestion() {
        for (type in IntentType.entries) {
            assertTrue(type.name, type.meaning.isNotBlank())
            assertTrue(type.name, type.suggestion.isNotBlank())
        }
    }

    @Test
    fun confidenceIsAlwaysARoundedProbability() {
        IntentEngine.onDragAttempt("a"); advance(6_000)
        repeat(3) { IntentEngine.onTap(100f, 200f, "save"); advance(100) }
        advance(6_000)
        IntentEngine.onBackPressed(); advance(200); IntentEngine.onBackPressed()

        for (s in signals()) {
            assertTrue("out of range: ${s.confidence}", s.confidence in 0.0..1.0)
            assertEquals(s.confidence, (s.confidence * 100).toInt() / 100.0, 1e-9)
            assertTrue(s.evidence.isNotBlank())
        }
    }
}
