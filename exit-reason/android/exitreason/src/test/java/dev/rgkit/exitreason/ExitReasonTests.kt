package dev.rgkit.exitreason

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ExitReason.decide] directly. Without an Application the environment
 * probes fall back to "no call, screen on, battery unknown", so these exercise
 * the session-fact half of the precedence ladder.
 */
class ExitReasonTests {

    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        ExitReason.config = ExitConfig()
    }

    @After
    fun tearDown() {
        ExitReason.config = ExitConfig()
    }

    /** Named args so each test reads as the session it describes. */
    private fun decide(
        sessionMs: Long = 60_000,
        idleMs: Long = 0,
        interactions: Int = 20,
        screen: String? = "HomeActivity",
        completed: Boolean = false,
        frustrated: Boolean = false,
        rageTaps: Int = 0,
        rageBacks: Int = 0,
    ): ExitReport = ExitReason.decide(
        now, sessionMs, idleMs, interactions, screen,
        completed, frustrated, rageTaps, rageBacks
    )

    @Test
    fun completedTaskBeatsEveryOtherSessionSignal() {
        // Also looks like a rage quit and a quick bounce; completion wins.
        val r = decide(sessionMs = 3_000, interactions = 1, completed = true, rageTaps = 8)
        assertEquals(ExitReasonType.TASK_COMPLETED, r.reason)
        assertEquals(0.9, r.confidence, 1e-9)
    }

    @Test
    fun tapBurstInAShortSessionIsARageQuit() {
        val r = decide(sessionMs = 60_000, rageTaps = 4)
        assertEquals(ExitReasonType.RAGE_QUIT, r.reason)
        assertEquals(0.7, r.confidence, 1e-9)
    }

    @Test
    fun aBiggerBurstRaisesRageQuitConfidence() {
        assertEquals(0.85, decide(sessionMs = 60_000, rageTaps = 6).confidence, 1e-9)
        assertEquals(0.85, decide(sessionMs = 60_000, rageBacks = 3).confidence, 1e-9)
    }

    @Test
    fun rageIsNotInferredFromALongSession() {
        // Same burst, but four minutes in — no longer reads as frustration.
        val r = decide(sessionMs = 5 * 60_000, rageTaps = 8)
        assertEquals(ExitReasonType.SWITCHED_AWAY, r.reason)
    }

    @Test
    fun shortSessionWithBarelyAnyInteractionIsAQuickBounce() {
        val r = decide(sessionMs = 8_000, interactions = 2)
        assertEquals(ExitReasonType.QUICK_BOUNCE, r.reason)
        assertEquals(0.8, r.confidence, 1e-9)
    }

    @Test
    fun aShortSessionThatWasActuallyUsedIsNotABounce() {
        val r = decide(sessionMs = 8_000, interactions = 3)
        assertEquals(ExitReasonType.SWITCHED_AWAY, r.reason)
    }

    @Test
    fun goingIdleBeforeLeavingReadsAsLostInterest() {
        val r = decide(sessionMs = 5 * 60_000, idleMs = 45_000)
        assertEquals(ExitReasonType.LOST_INTEREST, r.reason)
    }

    @Test
    fun anActiveSessionThatEndsIsAnAppSwitch() {
        val r = decide(sessionMs = 5 * 60_000, idleMs = 1_000)
        assertEquals(ExitReasonType.SWITCHED_AWAY, r.reason)
        assertEquals(0.5, r.confidence, 1e-9)
    }

    @Test
    fun configThresholdsAreRespected() {
        ExitReason.config = ExitConfig(quickBounceMs = 30_000, boredIdleMs = 10_000)
        assertEquals(ExitReasonType.QUICK_BOUNCE, decide(sessionMs = 25_000, interactions = 1).reason)
        assertEquals(
            ExitReasonType.LOST_INTEREST,
            decide(sessionMs = 5 * 60_000, idleMs = 12_000).reason,
        )
    }

    @Test
    fun everyReportCarriesItsEvidence() {
        val r = decide(sessionMs = 90_000, idleMs = 3_000, interactions = 12)
        assertEquals(90L, r.details["session_s"]?.toLong())
        assertEquals(3L, r.details["idle_before_exit_s"]?.toLong())
        assertEquals(12, r.details["interactions"]?.toInt())
        assertTrue(r.details.containsKey("why"))
        // No Application, so the environment probes stay at their defaults.
        assertEquals("none", r.details["call_state"])
        assertEquals("true", r.details["screen_on"])
    }

    @Test
    fun reportEchoesTheSessionItDescribes() {
        val r = decide(sessionMs = 42_000, interactions = 7, screen = "CheckoutActivity")
        assertEquals(now, r.at)
        assertEquals(42_000L, r.sessionMs)
        assertEquals("CheckoutActivity", r.lastScreen)
        assertEquals(7, r.interactionCount)
    }

    @Test
    fun confidenceIsAlwaysARoundedProbability() {
        val all = listOf(
            decide(completed = true),
            decide(sessionMs = 60_000, rageTaps = 5),
            decide(sessionMs = 8_000, interactions = 1),
            decide(sessionMs = 5 * 60_000, idleMs = 60_000),
            decide(),
        )
        for (r in all) {
            assertTrue("confidence out of range: ${r.confidence}", r.confidence in 0.0..1.0)
            assertEquals(r.confidence, (r.confidence * 100).toInt() / 100.0, 1e-9)
        }
    }
}
