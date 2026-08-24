package dev.rgkit.contextmoments

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drives [ContextMoments.decide] — the pure half of a sampling round — with
 * hand-written [SignalState]s, so the fusion table, the UNKNOWN floor and the
 * stability debounce are all exercised without a sensor or a Context.
 */
class ContextMomentsTests {

    private val at = 1_700_000_000_000L

    @Before
    fun setUp() {
        ContextMoments.config = MomentsConfig()
        ContextMoments.reset()
    }

    @After
    fun tearDown() {
        ContextMoments.reset()
        ContextMoments.config = MomentsConfig()
    }

    /** A deliberately featureless baseline; each test turns on only what it means. */
    private fun signals(
        motion: MotionState = MotionState.UNKNOWN,
        motionEnergy: Double = 0.0,
        screenBrightness: Double = 0.5,
        charging: Boolean = false,
        batteryPercent: Int = 60,
        ringerSilentOrVibrate: Boolean = false,
        dndOn: Boolean = false,
        wiredHeadset: Boolean = false,
        bluetoothAudio: Boolean = false,
        onWifi: Boolean = true,
        hourOfDay: Double = 12.0,
        weekday: Boolean = true,
        minutesSinceLongSleepWake: Long = -1,
        calendarBusy: Boolean? = null,
        ambientDb: Double? = null,
    ) = SignalState(
        motion, motionEnergy, screenBrightness, charging, batteryPercent,
        ringerSilentOrVibrate, dndOn, wiredHeadset, bluetoothAudio, onWifi,
        hourOfDay, weekday, minutesSinceLongSleepWake, calendarBusy, ambientDb,
    )

    private fun decide(s: SignalState) = ContextMoments.decide(s, at)

    // ------------------------------------------------------- the moment table

    @Test
    fun nightStillnessOnTheChargerIsSleep() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 2.0,
                charging = true, dndOn = true,
            )
        )
        assertEquals(Moment.SLEEPING, snap.moment)
        assertEquals(0.9, snap.scores.getValue(Moment.SLEEPING), 1e-9)
    }

    @Test
    fun theFirstMinutesAfterALongSleepAreJustWokeUp() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 7.0,
                charging = true, minutesSinceLongSleepWake = 3,
            )
        )
        assertEquals(Moment.JUST_WOKE_UP, snap.moment)
        assertEquals(0.95, snap.scores.getValue(Moment.JUST_WOKE_UP), 1e-9)
    }

    @Test
    fun wakingUpWearsOffAfterAQuarterOfAnHour() {
        val stale = decide(
            signals(hourOfDay = 7.0, motion = MotionState.STILL, minutesSinceLongSleepWake = 40)
        )
        assertNotEquals(Moment.JUST_WOKE_UP, stale.moment)
        assertEquals(0.0, stale.scores.getValue(Moment.JUST_WOKE_UP), 1e-9)
    }

    @Test
    fun vehicleMotionInRushHourIsCommuting() {
        val snap = decide(
            signals(
                motion = MotionState.IN_VEHICLE, hourOfDay = 8.0,
                onWifi = false, bluetoothAudio = true,
            )
        )
        assertEquals(Moment.COMMUTING, snap.moment)
        assertEquals(0.95, snap.scores.getValue(Moment.COMMUTING), 1e-9)
    }

    @Test
    fun walkingOffWifiIsWalkingNotAWorkout() {
        val snap = decide(
            signals(motion = MotionState.WALKING, hourOfDay = 13.0, onWifi = false)
        )
        assertEquals(Moment.WALKING, snap.moment)
        assertEquals(0.75, snap.scores.getValue(Moment.WALKING), 1e-9)
        assertTrue(snap.scores.getValue(Moment.WORKING_OUT) < snap.scores.getValue(Moment.WALKING))
    }

    @Test
    fun runningWithHeadphonesIsAWorkout() {
        val snap = decide(
            signals(
                motion = MotionState.RUNNING, hourOfDay = 18.0,
                bluetoothAudio = true, onWifi = false,
            )
        )
        assertEquals(Moment.WORKING_OUT, snap.moment)
        assertEquals(0.8, snap.scores.getValue(Moment.WORKING_OUT), 1e-9)
    }

    @Test
    fun aBusyCalendarSlotOutweighsOrdinaryDeskWork() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 14.0,
                calendarBusy = true, ringerSilentOrVibrate = true,
            )
        )
        assertEquals(Moment.IN_MEETING, snap.moment)
        assertTrue(snap.scores.getValue(Moment.IN_MEETING) > snap.scores.getValue(Moment.WORKING))
    }

    @Test
    fun aQuietWeekdayAfternoonAtTheDeskIsWorking() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 14.0,
                charging = true, calendarBusy = false,
            )
        )
        assertEquals(Moment.WORKING, snap.moment)
        assertEquals(0.65, snap.scores.getValue(Moment.WORKING), 1e-9)
        // Nothing else scores at all, so the margin is the whole score.
        assertEquals(0.81, snap.confidence, 1e-9)
    }

    @Test
    fun aDimLoudEveningAtHomeIsScreenTime() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 21.0,
                screenBrightness = 0.2, charging = true, ambientDb = 60.0,
            )
        )
        assertEquals(Moment.WATCHING_TV, snap.moment)
        assertTrue(snap.scores.getValue(Moment.WATCHING_TV) > snap.scores.getValue(Moment.RELAXING))
    }

    @Test
    fun aQuietBrightEveningAtHomeIsJustRelaxing() {
        // Weekend evening, bright screen, quiet room: none of the TV tells fire.
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 21.0,
                weekday = false, screenBrightness = 0.9,
            )
        )
        assertEquals(Moment.RELAXING, snap.moment)
        assertTrue(snap.scores.getValue(Moment.RELAXING) > snap.scores.getValue(Moment.WATCHING_TV))
    }

    @Test
    fun aWeekendAfternoonOffWifiIsOutAndAbout() {
        val snap = decide(
            signals(
                motion = MotionState.STILL, hourOfDay = 15.0,
                weekday = false, onWifi = false, ambientDb = 65.0,
            )
        )
        assertEquals(Moment.OUT_AND_ABOUT, snap.moment)
        assertEquals(0.4, snap.scores.getValue(Moment.OUT_AND_ABOUT), 1e-9)
    }

    // ------------------------------------------------------ the UNKNOWN floor

    @Test
    fun signalsThatSayNothingStayUnknown() {
        val snap = decide(signals(hourOfDay = 12.0, weekday = false))
        assertEquals(Moment.UNKNOWN, snap.moment)
        assertEquals(0.0, snap.confidence, 1e-9)
    }

    @Test
    fun aWeakLeaderIsNotWorthNaming() {
        // Weekend, still, on wifi: RELAXING leads on 0.35 — exactly the floor,
        // so it is reported; nudge it below and the moment goes UNKNOWN.
        val onTheLine = decide(
            signals(motion = MotionState.STILL, hourOfDay = 15.0, weekday = false)
        )
        assertEquals(Moment.RELAXING, onTheLine.moment)
        assertEquals(0.35, onTheLine.scores.getValue(Moment.RELAXING), 1e-9)

        // Weekend evening off wifi: OUT_AND_ABOUT leads on 0.30, under the floor.
        val below = decide(
            signals(motion = MotionState.STILL, hourOfDay = 21.0, weekday = false, onWifi = false)
        )
        assertTrue(below.scores.values.all { it < 0.35 })
        assertEquals(Moment.UNKNOWN, below.moment)
    }

    // ------------------------------------------------------- shape guarantees

    @Test
    fun everyRealMomentIsScoredEveryRound() {
        val snap = decide(signals(motion = MotionState.STILL, hourOfDay = 10.0))
        val expected = Moment.entries.filter { it != Moment.UNKNOWN }.toSet()
        assertEquals(expected, snap.scores.keys)
    }

    @Test
    fun scoresAndConfidenceAreBoundedAndRounded() {
        val cases = listOf(
            signals(motion = MotionState.STILL, hourOfDay = 2.0, charging = true, dndOn = true),
            signals(motion = MotionState.IN_VEHICLE, hourOfDay = 8.0, onWifi = false),
            signals(motion = MotionState.RUNNING, hourOfDay = 18.0, wiredHeadset = true),
            signals(motion = MotionState.STILL, hourOfDay = 14.0, calendarBusy = true),
            signals(hourOfDay = 12.0, weekday = false),
        )
        for (s in cases) {
            val snap = decide(s)
            assertTrue("confidence ${snap.confidence}", snap.confidence in 0.0..1.0)
            assertEquals(snap.confidence, (snap.confidence * 100).toInt() / 100.0, 1e-9)
            for ((m, v) in snap.scores) {
                assertTrue("$m scored $v", v in 0.0..1.0)
                assertEquals(v, (v * 100).toInt() / 100.0, 1e-9)
            }
        }
    }

    @Test
    fun aClearWinnerIsMoreConfidentThanAContestedOne() {
        val clear = decide(
            signals(motion = MotionState.IN_VEHICLE, hourOfDay = 8.0, onWifi = false, bluetoothAudio = true)
        )
        val contested = decide(
            signals(motion = MotionState.STILL, hourOfDay = 21.0, screenBrightness = 0.2)
        )
        assertTrue(
            "${clear.moment}=${clear.confidence} vs ${contested.moment}=${contested.confidence}",
            clear.confidence > contested.confidence,
        )
    }

    @Test
    fun theSnapshotKeepsTheSignalsItJudged() {
        val s = signals(motion = MotionState.WALKING, hourOfDay = 9.0, batteryPercent = 42)
        val snap = decide(s)
        assertEquals(s, snap.signals)
        assertEquals(at, snap.at)
    }

    // ------------------------------------------------------------- debouncing

    private fun round(moment: Moment) {
        // handleStability only reads .moment/.at/.confidence off the snapshot.
        ContextMoments.handleStability(
            MomentSnapshot(moment, 0.8, emptyMap(), signals(), at)
        )
    }

    @Test
    fun aNewMomentMustHoldForTwoRoundsBeforeItCounts() {
        round(Moment.WALKING)
        assertTrue(ContextMoments.history().isEmpty())

        round(Moment.WALKING)
        assertEquals(listOf(Moment.WALKING), ContextMoments.history().map { it.second })
    }

    @Test
    fun flickeringBetweenMomentsNeverCommits() {
        round(Moment.WALKING); round(Moment.COMMUTING)
        round(Moment.WALKING); round(Moment.COMMUTING)
        assertTrue(ContextMoments.history().toString(), ContextMoments.history().isEmpty())
    }

    @Test
    fun stayingInTheSameMomentIsNotRecordedTwice() {
        repeat(6) { round(Moment.WORKING) }
        assertEquals(1, ContextMoments.history().size)
    }

    @Test
    fun theDebounceCanBeTurnedOff() {
        ContextMoments.config = MomentsConfig(stabilityRounds = 1)
        round(Moment.WORKING_OUT)
        assertEquals(listOf(Moment.WORKING_OUT), ContextMoments.history().map { it.second })
    }

    @Test
    fun historyComesBackNewestFirst() {
        ContextMoments.config = MomentsConfig(stabilityRounds = 1)
        round(Moment.SLEEPING); round(Moment.JUST_WOKE_UP); round(Moment.COMMUTING)

        assertEquals(
            listOf(Moment.COMMUTING, Moment.JUST_WOKE_UP, Moment.SLEEPING),
            ContextMoments.history().map { it.second },
        )
        assertEquals(listOf(Moment.COMMUTING), ContextMoments.history(limit = 1).map { it.second })
    }

    // -------------------------------------------------------- export / reset

    @Test
    fun exportCarriesTheTransitionHistory() {
        ContextMoments.config = MomentsConfig(stabilityRounds = 1)
        round(Moment.WALKING)

        val history = JSONObject(ContextMoments.exportJson()).getJSONArray("history")
        assertEquals(1, history.length())
        assertEquals("WALKING", history.getJSONObject(0).getString("moment"))
        assertEquals(at, history.getJSONObject(0).getLong("at"))
        assertEquals(0.8, history.getJSONObject(0).getDouble("confidence"), 1e-9)
    }

    @Test
    fun resetForgetsHistoryAndAnyPendingCandidate() {
        round(Moment.WALKING) // one round in: pending, not yet committed
        ContextMoments.reset()
        round(Moment.WALKING) // must start counting again from zero

        assertTrue(ContextMoments.history().isEmpty())
    }

    @Test
    fun everyMomentHasAHumanLabel() {
        for (m in Moment.entries) assertTrue(m.name, m.label.isNotBlank())
    }
}
