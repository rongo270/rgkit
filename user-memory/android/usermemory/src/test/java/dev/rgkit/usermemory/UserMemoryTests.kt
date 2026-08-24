package dev.rgkit.usermemory

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * Drives the time-parameterised internals ([UserMemory.observe],
 * [UserMemory.record], [UserMemory.preferred], [UserMemory.profile],
 * [UserMemory.recommendations]) so decay, streaks and habit maths can be
 * exercised over months in one run. No Context, so nothing is written to disk.
 */
class UserMemoryTests {

    /** Monday 2026-03-02 in local time; every timestamp below hangs off it. */
    private fun at(dayOffset: Int, hour: Int = 9, minute: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.MARCH, 2, hour, minute, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.add(Calendar.DAY_OF_YEAR, dayOffset)
        return cal.timeInMillis
    }

    @Before
    fun setUp() = UserMemory.reset()

    @After
    fun tearDown() = UserMemory.reset()

    /** Records [name] once a day at [hour] for each of the given day offsets. */
    private fun recordDays(name: String, days: Iterable<Int>, hour: Int = 9) {
        for (d in days) UserMemory.record(name, at(d, hour), 1)
    }

    // ------------------------------------------------------------ preferences

    @Test
    fun everyPreferenceTypeComesBackAsItWentIn() {
        UserMemory.set("theme", "dark")
        UserMemory.set("haptics", true)
        UserMemory.set("goal", 30)
        UserMemory.set("streak_target", 100L)
        UserMemory.set("volume", 0.8)
        UserMemory.set("tags", listOf("work", "home"))

        assertEquals("dark", UserMemory.getString("theme"))
        assertTrue(UserMemory.getBoolean("haptics", false))
        assertEquals(30, UserMemory.getInt("goal", 0))
        assertEquals(100, UserMemory.getInt("streak_target", 0))
        assertEquals(0.8, UserMemory.getDouble("volume", 0.0), 1e-9)
        assertEquals(listOf("work", "home"), UserMemory.getStrings("tags"))
    }

    @Test
    fun anUnknownKeyGivesBackTheDefault() {
        assertNull(UserMemory.getString("theme"))
        assertEquals("light", UserMemory.getString("theme", "light"))
        assertTrue(UserMemory.getBoolean("haptics", true))
        assertEquals(7, UserMemory.getInt("goal", 7))
        assertEquals(1.0, UserMemory.getDouble("volume", 1.0), 1e-9)
        assertTrue(UserMemory.getStrings("tags").isEmpty())
    }

    @Test
    fun readingAPreferenceAsTheWrongTypeFallsBackRatherThanThrowing() {
        UserMemory.set("theme", "dark")
        assertEquals(5, UserMemory.getInt("theme", 5))
        assertFalse(UserMemory.getBoolean("theme", false))
        assertTrue(UserMemory.getStrings("theme").isEmpty())
    }

    @Test
    fun anIntegerPreferenceCanBeReadAsADouble() {
        UserMemory.set("goal", 30)
        assertEquals(30.0, UserMemory.getDouble("goal", 0.0), 1e-9)
    }

    @Test
    fun keysAreTrimmedAndBlankOnesIgnored() {
        UserMemory.set("  theme  ", "dark")
        UserMemory.set("   ", "nonsense")

        assertEquals("dark", UserMemory.getString("theme"))
        assertEquals(1, UserMemory.preferences().size)
    }

    @Test
    fun settingAKeyAgainReplacesIt() {
        UserMemory.set("theme", "dark")
        UserMemory.set("theme", "light")

        assertEquals("light", UserMemory.getString("theme"))
        assertEquals(1, UserMemory.preferences().size)
    }

    @Test
    fun preferencesAreListedAlphabeticallyAndReadable() {
        UserMemory.set("theme", "dark")
        UserMemory.set("Alarms", listOf("07:00", "22:00"))
        UserMemory.set("haptics", true)

        val prefs = UserMemory.preferences()
        assertEquals(listOf("Alarms", "haptics", "theme"), prefs.map { it.key })
        assertEquals("07:00, 22:00", prefs.first().displayValue)
        assertEquals("true", prefs[1].displayValue)
    }

    @Test
    fun removingAPreferenceForgetsIt() {
        UserMemory.set("theme", "dark")
        UserMemory.remove("theme")

        assertNull(UserMemory.getString("theme"))
        assertTrue(UserMemory.preferences().isEmpty())
    }

    // --------------------------------------------------------------- learning

    @Test
    fun oneObservationIsAlreadyAPreferenceJustNotAConfidentOne() {
        UserMemory.observe("export_format", "pdf", at(0))

        val learned = UserMemory.preferred("export_format", at(0))!!
        assertEquals("pdf", learned.top.value)
        assertEquals(1.0, learned.top.share, 1e-9)
        assertEquals(1, learned.observations)
        assertEquals(0.2, learned.confidence, 1e-9) // 1 of the 5 needed
    }

    @Test
    fun theOptionChosenMostOftenLeads() {
        repeat(7) { UserMemory.observe("export_format", "pdf", at(0)) }
        repeat(3) { UserMemory.observe("export_format", "png", at(0)) }

        val learned = UserMemory.preferred("export_format", at(0))!!
        assertEquals("pdf", learned.top.value)
        assertEquals(0.7, learned.top.share, 1e-9)
        assertEquals(10, learned.observations)
        assertEquals(0.7, learned.confidence, 1e-9)
        assertEquals(listOf("pdf", "png"), learned.choices.map { it.value })
        assertEquals(3, learned.choices[1].count)
    }

    @Test
    fun aChangeOfHabitOverturnsTheOldFavourite() {
        // Ten PNG exports two months ago, five PDFs today: half-life is 30 days,
        // so the old pile has decayed to a quarter of its weight.
        repeat(10) { UserMemory.observe("export_format", "png", at(-60)) }
        repeat(5) { UserMemory.observe("export_format", "pdf", at(0)) }

        val learned = UserMemory.preferred("export_format", at(0))!!
        assertEquals("pdf", learned.top.value)
        assertEquals(0.67, learned.top.share, 0.01)
        assertEquals(15, learned.observations) // raw counts are not decayed
    }

    @Test
    fun confidenceGrowsWithEvidence() {
        val key = "export_format"
        UserMemory.observe(key, "pdf", at(0))
        assertEquals(0.2, UserMemory.preferred(key, at(0))!!.confidence, 1e-9)

        repeat(4) { UserMemory.observe(key, "pdf", at(0)) }
        assertEquals(1.0, UserMemory.preferred(key, at(0))!!.confidence, 1e-9)

        // Past five observations the cap holds it at the leader's share.
        repeat(20) { UserMemory.observe(key, "pdf", at(0)) }
        assertEquals(1.0, UserMemory.preferred(key, at(0))!!.confidence, 1e-9)
    }

    @Test
    fun aKeyNobodyHasChosenIsNotLearned() {
        assertNull(UserMemory.preferred("export_format", at(0)))
        assertNull(UserMemory.preferredValue("export_format"))
    }

    @Test
    fun blankKeysAndChoicesAreIgnored() {
        UserMemory.observe("", "pdf", at(0))
        UserMemory.observe("export_format", "   ", at(0))

        assertNull(UserMemory.preferred("export_format", at(0)))
        assertTrue(UserMemory.learned().isEmpty())
    }

    @Test
    fun suggestPutsWhatTheUserActuallyPicksFirst() {
        repeat(5) { UserMemory.observe("export_format", "png", at(0)) }
        repeat(2) { UserMemory.observe("export_format", "pdf", at(0)) }

        assertEquals(
            listOf("png", "pdf", "txt", "csv"),
            UserMemory.suggest("export_format", listOf("txt", "pdf", "csv", "png")),
        )
    }

    @Test
    fun suggestLeavesTheOrderAloneWhenNothingIsLearned() {
        val options = listOf("txt", "pdf", "csv")
        assertEquals(options, UserMemory.suggest("export_format", options))
    }

    @Test
    fun learnedKeysComeBackMostConfidentFirst() {
        repeat(6) { UserMemory.observe("export_format", "pdf", at(0)) }
        UserMemory.observe("sort_order", "date", at(0))

        val learned = UserMemory.learned()
        assertEquals(listOf("export_format", "sort_order"), learned.map { it.key })
        assertTrue(learned.first().confidence > learned.last().confidence)
    }

    @Test
    fun forgettingAKeyDropsBothWhatWasSetAndWhatWasLearned() {
        UserMemory.set("export_format", "pdf")
        repeat(5) { UserMemory.observe("export_format", "pdf", at(0)) }
        UserMemory.forget("export_format")

        assertNull(UserMemory.getString("export_format"))
        assertNull(UserMemory.preferred("export_format", at(0)))
    }

    // ----------------------------------------------------------------- habits

    @Test
    fun recordingAnActionBuildsItsHistory() {
        recordDays("workout", listOf(-2, -1, 0))

        val habit = UserMemory.habit("workout")!!
        assertEquals("workout", habit.name)
        assertEquals(3, habit.total)
        assertEquals(at(-2), habit.firstAt)
        assertEquals(at(0), habit.lastAt)
        assertEquals(3, habit.daily.size)
    }

    @Test
    fun severalOccurrencesCanBeRecordedAtOnce() {
        UserMemory.record("water", at(0), 5)
        UserMemory.record("water", at(0), 3)

        val habit = UserMemory.habit("water")!!
        assertEquals(8, habit.total)
        assertEquals(1, habit.daily.size) // still one active day
    }

    @Test
    fun nonsenseRecordingsAreIgnored() {
        UserMemory.record("   ", at(0), 1)
        UserMemory.record("workout", 0)

        assertNull(UserMemory.habit("workout"))
        assertTrue(UserMemory.habits().isEmpty())
    }

    @Test
    fun anActionNeverRecordedHasNoHabit() {
        assertNull(UserMemory.habit("never"))
    }

    @Test
    fun dailyCountsReadAsASparkline() {
        UserMemory.record("workout", at(-2), 2)
        UserMemory.record("workout", at(0), 1)

        assertEquals(listOf(2, 0, 1), UserMemory.habit("workout")!!.dailyCounts(3, at(0)))
    }

    @Test
    fun strengthIsHowMuchOfTheLastThreeWeeksItFilled() {
        recordDays("workout", -20..0)

        val habit = UserMemory.habit("workout")!!
        assertEquals(21, habit.activeDays(28, at(0)))
        assertEquals(1.0, habit.strength(at(0)), 1e-9)
        assertEquals(5.25, habit.perWeek(at(0)), 1e-9) // active days per week, not occurrences
        assertTrue(habit.isHabit(at(0)))
    }

    @Test
    fun anOccasionalActionIsNotYetAHabit() {
        recordDays("workout", listOf(-20, -10, -3))

        val habit = UserMemory.habit("workout")!!
        assertEquals(3, habit.activeDays(28, at(0)))
        assertEquals(0.14, habit.strength(at(0)), 0.01)
        assertFalse(habit.isHabit(at(0)))
    }

    @Test
    fun anAbandonedHabitStopsCountingAsOne() {
        recordDays("workout", -40..-20)

        val habit = UserMemory.habit("workout")!!
        assertFalse("last logged 20 days ago", habit.isHabit(at(0)))
    }

    @Test
    fun aHabitThatHasHalvedIsFading() {
        recordDays("workout", -50..-40)  // 11 days in the month before last
        recordDays("workout", listOf(-10, -5))

        val habit = UserMemory.habit("workout")!!
        assertTrue(habit.isFading(at(0)))
    }

    @Test
    fun aSteadyHabitIsNotFading() {
        recordDays("workout", -50..-40)
        recordDays("workout", -20..-10)

        assertFalse(UserMemory.habit("workout")!!.isFading(at(0)))
    }

    @Test
    fun todaysEntryIsVisibleImmediately() {
        recordDays("workout", listOf(-1))
        assertFalse(UserMemory.habit("workout")!!.doneToday(at(0)))

        UserMemory.record("workout", at(0, 20), 1)
        assertTrue(UserMemory.habit("workout")!!.doneToday(at(0, 21)))
    }

    @Test
    fun aStreakSurvivesADayThatIsNotOverYet() {
        recordDays("workout", -4..-1)

        val habit = UserMemory.habit("workout")!!
        assertEquals(4, habit.currentStreakDays(at(0)))
        assertEquals(4, habit.bestStreakDays())
    }

    @Test
    fun aMissedDayEndsTheStreak() {
        recordDays("workout", listOf(-9, -8, -7, -6, -5))
        recordDays("workout", listOf(-1, 0))

        val habit = UserMemory.habit("workout")!!
        assertEquals(2, habit.currentStreakDays(at(0)))
        assertEquals(5, habit.bestStreakDays())
    }

    @Test
    fun aHabitWithNoHistoryHasNoStreak() {
        UserMemory.record("workout", at(-30), 1)

        val habit = UserMemory.habit("workout")!!
        assertEquals(0, habit.currentStreakDays(at(0)))
        assertEquals(1, habit.bestStreakDays())
    }

    @Test
    fun theUsualHourEmergesFromEnoughOccurrences() {
        recordDays("workout", -3..0, hour = 7)
        assertNull("four is not enough", UserMemory.habit("workout")!!.typicalHour())

        UserMemory.record("workout", at(-4, 8), 1)
        val habit = UserMemory.habit("workout")!!
        assertEquals(7, habit.typicalHour())
        assertEquals(DayPart.MORNING, habit.dayPart())
    }

    @Test
    fun anActionScatteredAcrossTheDayHasNoUsualHour() {
        for (hour in listOf(1, 6, 11, 16, 21, 23)) UserMemory.record("check", at(0, hour), 1)

        val habit = UserMemory.habit("check")!!
        assertNull(habit.typicalHour())
        assertNull(habit.dayPart())
    }

    @Test
    fun hourlyCountsCoverTheWholeDay() {
        UserMemory.record("workout", at(0, 7), 2)
        UserMemory.record("workout", at(-1, 19), 1)

        val counts = UserMemory.habit("workout")!!.hourlyCounts()
        assertEquals(24, counts.size)
        assertEquals(2, counts[7])
        assertEquals(1, counts[19])
        assertEquals(0, counts[0])
    }

    @Test
    fun aWeekendActionShowsUpAsAWeekendLeaning() {
        // 2026-03-02 is a Monday, so +5 and +6 are Saturday and Sunday.
        recordDays("long_run", listOf(5, 6, 12, 13, 19, 20))

        val weekdays = UserMemory.habit("long_run")!!.typicalWeekdays()
        assertEquals(listOf(5, 6), weekdays) // Saturday, Sunday
    }

    @Test
    fun anEverydayActionLeansTowardNoParticularDay() {
        recordDays("workout", 0..13)
        assertTrue(UserMemory.habit("workout")!!.typicalWeekdays().isEmpty())
    }

    @Test
    fun habitsAreListedMostEstablishedFirst() {
        recordDays("daily_thing", -13..0)
        recordDays("rare_thing", listOf(-1))

        assertEquals(listOf("daily_thing", "rare_thing"), UserMemory.habits().map { it.name })
    }

    @Test
    fun forgettingAHabitWipesItsHistory() {
        recordDays("workout", -5..0)
        UserMemory.forgetHabit("workout")

        assertNull(UserMemory.habit("workout"))
        assertTrue(UserMemory.habits().isEmpty())
    }

    // -------------------------------------------------------- recommendations

    @Test
    fun aHabitDueAboutNowIsSurfaced() {
        recordDays("workout", -21..-1, hour = 9)

        val recs = UserMemory.recommendations(5, at(0, 9))
        val due = recs.single { it.kind == RecommendationKind.HABIT_DUE }
        assertEquals("workout", due.subject)
        assertTrue(due.title, due.title.contains("usually happens about now"))
        assertTrue(due.detail, due.detail.contains("9am"))
        assertEquals(0.9, due.score, 1e-9)
    }

    @Test
    fun aHabitAlreadyDoneTodayIsNotNagged() {
        recordDays("workout", -21..0, hour = 9)

        val recs = UserMemory.recommendations(5, at(0, 9))
        assertTrue(recs.none { it.kind == RecommendationKind.HABIT_DUE })
    }

    @Test
    fun aStreakStillUnloggedLateInTheDayIsFlagged() {
        recordDays("workout", -21..-1, hour = 9)

        val recs = UserMemory.recommendations(5, at(0, 20))
        val risk = recs.single { it.kind == RecommendationKind.STREAK_AT_RISK }
        assertEquals("workout", risk.subject)
        assertTrue(risk.title, risk.title.contains("21-day"))
        assertTrue("streaks outrank everything: ${risk.score}", risk.score >= 0.9)
        assertEquals(recs.first(), risk)
    }

    @Test
    fun aFadingHabitIsWorthResurfacing() {
        recordDays("meditate", -50..-40)

        val recs = UserMemory.recommendations(5, at(0, 12))
        val fading = recs.single { it.kind == RecommendationKind.FADING_HABIT }
        assertEquals("meditate", fading.subject)
        assertTrue(fading.detail, fading.detail.contains("11 days the month before"))
    }

    @Test
    fun aStronglyLearnedChoiceBecomesASuggestedDefault() {
        repeat(6) { UserMemory.observe("export_format", "pdf", at(0)) }

        val rec = UserMemory.recommendations(5, at(0, 12))
            .single { it.kind == RecommendationKind.LEARNED_DEFAULT }
        assertEquals("export_format", rec.subject)
        assertTrue(rec.title, rec.title.contains("“pdf”"))
        assertTrue(rec.detail, rec.detail.contains("100% of the time"))
    }

    @Test
    fun aWeaklyLearnedChoiceIsNotPushed() {
        UserMemory.observe("export_format", "pdf", at(0))
        UserMemory.observe("export_format", "png", at(0))

        assertTrue(UserMemory.recommendations(5, at(0, 12)).isEmpty())
    }

    @Test
    fun recommendationsAreRankedAndCapped() {
        recordDays("workout", -21..-1, hour = 9)
        recordDays("meditate", -50..-40)
        repeat(6) { UserMemory.observe("export_format", "pdf", at(0)) }

        val recs = UserMemory.recommendations(2, at(0, 20))
        assertEquals(2, recs.size)
        assertEquals(recs.sortedByDescending { it.score }, recs)
    }

    @Test
    fun aFreshUserIsRecommendedNothing() {
        assertTrue(UserMemory.recommendations(5, at(0, 12)).isEmpty())
    }

    // ---------------------------------------------------------------- profile

    /** Seeds "first seen" so engagement tiers can be reached. */
    private fun knownSince(dayOffset: Int) {
        UserMemory.importJson(JSONObject().put("version", 1).put("since", at(dayOffset)).toString())
    }

    @Test
    fun aUserWeJustMetIsNotJudgedYet() {
        knownSince(-3)
        recordDays("workout", -3..0)

        val profile = UserMemory.profile(at(0))
        assertEquals(Engagement.NEW, profile.engagement)
        assertEquals(4, profile.daysKnown)
        assertEquals(4, profile.eventsTotal)
    }

    @Test
    fun engagementFollowsActiveDays() {
        knownSince(-60)
        recordDays("workout", -5..-1)
        assertEquals(Engagement.CASUAL, UserMemory.profile(at(0)).engagement)

        recordDays("workout", -15..-6)
        assertEquals(Engagement.REGULAR, UserMemory.profile(at(0)).engagement)

        recordDays("workout", -25..-16)
        assertEquals(Engagement.POWER, UserMemory.profile(at(0)).engagement)
    }

    @Test
    fun theProfileCountsEverythingRemembered() {
        knownSince(-30)
        UserMemory.set("theme", "dark")
        repeat(5) { UserMemory.observe("export_format", "pdf", at(-1)) }
        recordDays("workout", -6..0)
        recordDays("water", -2..0)

        val profile = UserMemory.profile(at(0))
        assertEquals(1, profile.preferenceCount)
        assertEquals(1, profile.learnedCount)
        // Only "workout" is established enough to count as a habit; "water" is
        // three days old.
        assertEquals(1, profile.habitCount)
        assertEquals(10, profile.eventsTotal)
        assertEquals(7, profile.activeDays28)
        assertEquals(7, profile.currentStreakDays)
        assertEquals(at(-30), profile.firstSeenAt)
    }

    @Test
    fun theProfileNamesThePartOfDayTheUserShowsUp() {
        knownSince(-30)
        recordDays("workout", -11..0, hour = 20)

        val profile = UserMemory.profile(at(0))
        assertEquals(DayPart.EVENING, profile.peakPart)
    }

    @Test
    fun aScatteredScheduleHasNoPeakPart() {
        knownSince(-30)
        // Three occurrences in each part of the day: no part owns 40%.
        val hours = listOf(6, 7, 8, 13, 14, 15, 18, 19, 20, 23, 1, 2)
        for ((i, d) in (-11..0).withIndex()) UserMemory.record("check", at(d, hours[i]), 1)

        assertNull(UserMemory.profile(at(0)).peakPart)
    }

    @Test
    fun weekendUsersAreRecognised() {
        knownSince(-30)
        recordDays("long_run", listOf(5, 6, 12, 13, 19, 20))

        assertTrue(UserMemory.profile(at(21)).weekendLeaning)
    }

    @Test
    fun everydayUsersAreNotCalledWeekendUsers() {
        knownSince(-30)
        recordDays("workout", 0..13)

        assertFalse(UserMemory.profile(at(14)).weekendLeaning)
    }

    // -------------------------------------------------- export / import / reset

    @Test
    fun exportAndImportRoundTripEverything() {
        knownSince(-30)
        UserMemory.set("theme", "dark")
        UserMemory.set("tags", listOf("a", "b"))
        repeat(4) { UserMemory.observe("export_format", "pdf", at(-1)) }
        recordDays("workout", -3..0, hour = 7)

        val exported = UserMemory.exportJson()
        UserMemory.reset()
        assertTrue(UserMemory.preferences().isEmpty())

        assertTrue(UserMemory.importJson(exported))
        assertEquals("dark", UserMemory.getString("theme"))
        assertEquals(listOf("a", "b"), UserMemory.getStrings("tags"))
        assertEquals("pdf", UserMemory.preferredValue("export_format"))
        assertEquals(4, UserMemory.preferred("export_format", at(0))!!.observations)
        val habit = UserMemory.habit("workout")!!
        assertEquals(4, habit.total)
        assertEquals(4, habit.dailyCounts(4, at(0)).count { it > 0 })
        assertEquals(4, habit.hourlyCounts()[7])
        assertEquals(at(-30), UserMemory.profile(at(0)).firstSeenAt)
    }

    @Test
    fun theExportSaysWhenItWasMade() {
        UserMemory.set("theme", "dark")
        val json = JSONObject(UserMemory.exportJson())

        assertEquals(1, json.getInt("version"))
        assertTrue(json.has("exportedAt"))
        assertEquals("dark", json.getJSONObject("prefs").getJSONObject("theme").getString("v"))
    }

    @Test
    fun brokenImportsLeaveMemoryUntouched() {
        UserMemory.set("theme", "dark")

        assertFalse(UserMemory.importJson("{not json"))
        assertFalse(UserMemory.importJson(JSONObject().put("version", 99).toString()))
        assertEquals("dark", UserMemory.getString("theme"))
    }

    @Test
    fun resetForgetsEverything() {
        UserMemory.set("theme", "dark")
        repeat(5) { UserMemory.observe("export_format", "pdf", at(0)) }
        recordDays("workout", -3..0)

        UserMemory.reset()

        assertTrue(UserMemory.preferences().isEmpty())
        assertTrue(UserMemory.learned().isEmpty())
        assertTrue(UserMemory.habits().isEmpty())
        assertEquals(0, UserMemory.profile(at(0)).eventsTotal)
    }
}
