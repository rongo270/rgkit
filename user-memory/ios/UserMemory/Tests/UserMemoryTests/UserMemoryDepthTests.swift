import XCTest
@testable import UserMemory

/// Fills in the corners around `UserMemoryTests`: preference typing, the
/// habit maths (streaks, usual hour, weekday leaning, fading) and the profile
/// tiers, driven through the date-parameterised internals so nothing depends
/// on what time the suite happens to run.
final class UserMemoryDepthTests: XCTestCase {

    override func setUp() {
        super.setUp()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("UserMemoryDepth-\(UUID().uuidString)", isDirectory: true)
        UserMemory.configure(directory: directory)
    }

    /// Today at `hour`, shifted by `dayOffset` days (negative = past).
    private func date(dayOffset: Int, hour: Int = 9) -> Date {
        let start = Calendar.current.startOfDay(for: Date())
        return Calendar.current.date(
            byAdding: DateComponents(day: dayOffset, hour: hour), to: start
        )!
    }

    private func record(_ name: String, days: [Int], hour: Int = 9) {
        for day in days { UserMemory.record(name, at: date(dayOffset: day, hour: hour), count: 1) }
    }

    /// Day offsets in the last four weeks that fall on a Saturday or Sunday.
    private func recentWeekendOffsets(count: Int) -> [Int] {
        let calendar = Calendar.current
        return (-28...0).filter { offset in
            let weekday = calendar.component(.weekday, from: date(dayOffset: offset))
            return weekday == 1 || weekday == 7
        }.suffix(count)
    }

    /// Seeds "first seen" so the engagement tiers become reachable.
    private func knownSince(dayOffset: Int) {
        let millis = Int(date(dayOffset: dayOffset).timeIntervalSince1970 * 1000)
        XCTAssertTrue(UserMemory.importJSON(#"{"version":1,"since":\#(millis)}"#))
    }

    // MARK: Preferences

    func testUnknownKeysFallBackToDefaults() {
        XCTAssertNil(UserMemory.string(for: "theme"))
        XCTAssertEqual(UserMemory.string(for: "theme", default: "light"), "light")
        XCTAssertTrue(UserMemory.bool(for: "haptics", default: true))
        XCTAssertEqual(UserMemory.int(for: "goal", default: 7), 7)
        XCTAssertEqual(UserMemory.double(for: "volume", default: 1), 1, accuracy: 1e-9)
        XCTAssertTrue(UserMemory.strings(for: "tags").isEmpty)
    }

    func testReadingAPreferenceAsTheWrongTypeFallsBack() {
        UserMemory.set("theme", "dark")

        XCTAssertEqual(UserMemory.int(for: "theme", default: 5), 5)
        XCTAssertFalse(UserMemory.bool(for: "theme", default: false))
        XCTAssertTrue(UserMemory.strings(for: "theme").isEmpty)
    }

    func testAnIntegerPreferenceReadsAsADouble() {
        UserMemory.set("goal", 30)
        XCTAssertEqual(UserMemory.double(for: "goal", default: 0), 30, accuracy: 1e-9)
    }

    func testKeysAreTrimmedAndBlankOnesIgnored() {
        UserMemory.set("  theme  ", "dark")
        UserMemory.set("   ", "nonsense")

        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
        XCTAssertEqual(UserMemory.preferences().count, 1)
    }

    func testSettingAKeyAgainReplacesIt() {
        UserMemory.set("theme", "dark")
        UserMemory.set("theme", "light")

        XCTAssertEqual(UserMemory.string(for: "theme"), "light")
        XCTAssertEqual(UserMemory.preferences().count, 1)
    }

    func testPreferencesAreListedAlphabeticallyAndReadably() {
        UserMemory.set("theme", "dark")
        UserMemory.set("Alarms", ["07:00", "22:00"])
        UserMemory.set("haptics", true)

        let prefs = UserMemory.preferences()
        XCTAssertEqual(prefs.map(\.key), ["Alarms", "haptics", "theme"])
        XCTAssertEqual(prefs[0].value.displayValue, "07:00, 22:00")
        XCTAssertEqual(prefs[1].value.displayValue, "true")
    }

    // MARK: Learning

    func testOneObservationIsAWeakPreference() {
        UserMemory.observe("export_format", choice: "pdf", at: Date())

        let learned = UserMemory.preferred("export_format")
        XCTAssertEqual(learned?.top.value, "pdf")
        XCTAssertEqual(learned?.top.share ?? 0, 1.0, accuracy: 1e-9)
        XCTAssertEqual(learned?.confidence ?? 0, 0.2, accuracy: 1e-9)
    }

    func testBlankObservationsAreIgnored() {
        UserMemory.observe("", choice: "pdf", at: Date())
        UserMemory.observe("export_format", choice: "   ", at: Date())

        XCTAssertNil(UserMemory.preferred("export_format"))
        XCTAssertTrue(UserMemory.learned().isEmpty)
    }

    func testSuggestLeavesUnknownKeysAlone() {
        let options = ["txt", "pdf", "csv"]
        XCTAssertEqual(UserMemory.suggest("export_format", options: options), options)
    }

    func testLearnedKeysComeBackMostConfidentFirst() {
        for _ in 0..<6 { UserMemory.observe("export_format", choice: "pdf", at: Date()) }
        UserMemory.observe("sort_order", choice: "date", at: Date())

        let learned = UserMemory.learned()
        XCTAssertEqual(learned.map(\.key), ["export_format", "sort_order"])
        XCTAssertGreaterThan(learned[0].confidence, learned[1].confidence)
    }

    func testForgettingAKeyDropsBothTheSettingAndTheLearning() {
        UserMemory.set("export_format", "pdf")
        for _ in 0..<5 { UserMemory.observe("export_format", choice: "pdf", at: Date()) }

        UserMemory.forget("export_format")

        XCTAssertNil(UserMemory.string(for: "export_format"))
        XCTAssertNil(UserMemory.preferred("export_format"))
    }

    // MARK: Habits

    func testSeveralOccurrencesCanBeRecordedAtOnce() {
        UserMemory.record("water", at: date(dayOffset: 0), count: 5)
        UserMemory.record("water", at: date(dayOffset: 0), count: 3)

        let habit = UserMemory.habit("water")
        XCTAssertEqual(habit?.total, 8)
        XCTAssertEqual(habit?.daily.count, 1)
    }

    func testNonsenseRecordingsAreIgnored() {
        UserMemory.record("   ")
        UserMemory.record("workout", count: 0)

        XCTAssertNil(UserMemory.habit("workout"))
        XCTAssertTrue(UserMemory.habits().isEmpty)
    }

    func testDailyCountsReadAsASparkline() {
        UserMemory.record("workout", at: date(dayOffset: -2), count: 2)
        UserMemory.record("workout", at: date(dayOffset: 0), count: 1)

        let counts = UserMemory.habit("workout")?.dailyCounts(lastDays: 3, from: date(dayOffset: 0))
        XCTAssertEqual(counts, [2, 0, 1])
    }

    func testStrengthReflectsHowMuchOfTheLastThreeWeeksItFilled() {
        record("workout", days: Array(-20...0))

        let habit = UserMemory.habit("workout")!
        let now = date(dayOffset: 0, hour: 23)
        XCTAssertEqual(habit.activeDays(last: 28, from: now), 21)
        XCTAssertEqual(habit.strength(from: now), 1.0, accuracy: 1e-9)
        XCTAssertEqual(habit.perWeek(from: now), 5.25, accuracy: 1e-9)
        XCTAssertTrue(habit.isHabit(from: now))
    }

    func testAnOccasionalActionIsNotAHabit() {
        record("workout", days: [-20, -10, -3])

        let habit = UserMemory.habit("workout")!
        XCTAssertEqual(habit.activeDays(last: 28, from: date(dayOffset: 0, hour: 23)), 3)
        XCTAssertFalse(habit.isHabit(from: date(dayOffset: 0, hour: 23)))
    }

    func testAnAbandonedHabitStopsCountingAsOne() {
        record("workout", days: Array(-40...(-20)))

        XCTAssertFalse(UserMemory.habit("workout")!.isHabit(from: date(dayOffset: 0, hour: 23)))
    }

    func testAHabitThatHasHalvedIsFading() {
        record("workout", days: Array(-50...(-40)))
        record("workout", days: [-10, -5])

        XCTAssertTrue(UserMemory.habit("workout")!.isFading(from: date(dayOffset: 0, hour: 23)))
    }

    func testASteadyHabitIsNotFading() {
        record("workout", days: Array(-50...(-40)))
        record("workout", days: Array(-20...(-10)))

        XCTAssertFalse(UserMemory.habit("workout")!.isFading(from: date(dayOffset: 0, hour: 23)))
    }

    func testAMissedDayEndsTheStreakButNotTheRecord() {
        record("workout", days: [-9, -8, -7, -6, -5])
        record("workout", days: [-1, 0])

        let habit = UserMemory.habit("workout")!
        XCTAssertEqual(habit.currentStreakDays(from: date(dayOffset: 0, hour: 23)), 2)
        XCTAssertEqual(habit.bestStreakDays(), 5)
    }

    func testAStreakSurvivesADayThatIsNotOverYet() {
        record("workout", days: [-4, -3, -2, -1])

        let habit = UserMemory.habit("workout")!
        XCTAssertEqual(habit.currentStreakDays(from: date(dayOffset: 0, hour: 9)), 4)
    }

    func testAScatteredScheduleHasNoUsualHour() {
        for hour in [1, 6, 11, 16, 21, 23] {
            UserMemory.record("check", at: date(dayOffset: 0, hour: hour), count: 1)
        }

        let habit = UserMemory.habit("check")!
        XCTAssertNil(habit.typicalHour())
        XCTAssertNil(habit.dayPart())
    }

    func testHourlyCountsCoverTheWholeDay() {
        UserMemory.record("workout", at: date(dayOffset: 0, hour: 7), count: 2)
        UserMemory.record("workout", at: date(dayOffset: -1, hour: 19), count: 1)

        let counts = UserMemory.habit("workout")!.hourlyCounts()
        XCTAssertEqual(counts.count, 24)
        XCTAssertEqual(counts[7], 2)
        XCTAssertEqual(counts[19], 1)
        XCTAssertEqual(counts[0], 0)
    }

    func testAWeekendActionLeansTowardTheWeekend() {
        let weekends = recentWeekendOffsets(count: 6)
        XCTAssertEqual(weekends.count, 6)
        record("long_run", days: Array(weekends))

        XCTAssertEqual(UserMemory.habit("long_run")!.typicalWeekdays(), [5, 6])
    }

    func testAnEverydayActionLeansTowardNoDay() {
        record("workout", days: Array(-13...0))
        XCTAssertTrue(UserMemory.habit("workout")!.typicalWeekdays().isEmpty)
    }

    func testHabitsAreListedMostEstablishedFirst() {
        record("daily_thing", days: Array(-13...0))
        record("rare_thing", days: [-1])

        XCTAssertEqual(UserMemory.habits().map(\.name), ["daily_thing", "rare_thing"])
    }

    func testForgettingAHabitWipesItsHistory() {
        record("workout", days: Array(-5...0))
        UserMemory.forgetHabit("workout")

        XCTAssertNil(UserMemory.habit("workout"))
        XCTAssertTrue(UserMemory.habits().isEmpty)
    }

    // MARK: Recommendations

    func testAHabitAlreadyDoneTodayIsNotNagged() {
        record("workout", days: Array(-21...0), hour: 9)

        let recs = UserMemory.recommendations(limit: 5, now: date(dayOffset: 0, hour: 9))
        XCTAssertFalse(recs.contains { $0.kind == .habitDue })
    }

    func testAWeaklyLearnedChoiceIsNotPushed() {
        UserMemory.observe("export_format", choice: "pdf", at: Date())
        UserMemory.observe("export_format", choice: "png", at: Date())

        let recs = UserMemory.recommendations(limit: 5, now: date(dayOffset: 0, hour: 12))
        XCTAssertFalse(recs.contains { $0.kind == .learnedDefault })
    }

    func testRecommendationsAreRankedAndCapped() {
        record("workout", days: Array(-21...(-1)), hour: 9)
        record("meditate", days: Array(-50...(-40)))
        for _ in 0..<6 { UserMemory.observe("export_format", choice: "pdf", at: Date()) }

        let recs = UserMemory.recommendations(limit: 2, now: date(dayOffset: 0, hour: 20))
        XCTAssertEqual(recs.count, 2)
        XCTAssertEqual(recs.map(\.score), recs.map(\.score).sorted(by: >))
    }

    func testAFreshUserIsRecommendedNothing() {
        XCTAssertTrue(UserMemory.recommendations(limit: 5, now: Date()).isEmpty)
    }

    // MARK: Profile

    func testEngagementFollowsActiveDays() {
        knownSince(dayOffset: -60)
        let now = date(dayOffset: 0, hour: 23)

        record("workout", days: Array(-5...(-1)))
        XCTAssertEqual(UserMemory.profile(now: now).engagement, .casual)

        record("workout", days: Array(-15...(-6)))
        XCTAssertEqual(UserMemory.profile(now: now).engagement, .regular)

        record("workout", days: Array(-25...(-16)))
        XCTAssertEqual(UserMemory.profile(now: now).engagement, .power)
    }

    func testAUserWeJustMetIsNotJudgedYet() {
        knownSince(dayOffset: -3)
        record("workout", days: Array(-3...0))

        let profile = UserMemory.profile(now: date(dayOffset: 0, hour: 23))
        XCTAssertEqual(profile.engagement, .new)
        XCTAssertEqual(profile.eventsTotal, 4)
    }

    func testTheProfileCountsEverythingRemembered() {
        knownSince(dayOffset: -30)
        UserMemory.set("theme", "dark")
        for _ in 0..<5 { UserMemory.observe("export_format", choice: "pdf", at: date(dayOffset: -1)) }
        record("workout", days: Array(-6...0))
        record("water", days: Array(-2...0))

        let profile = UserMemory.profile(now: date(dayOffset: 0, hour: 23))
        XCTAssertEqual(profile.preferenceCount, 1)
        XCTAssertEqual(profile.learnedCount, 1)
        XCTAssertEqual(profile.habitCount, 1) // only "workout" is established
        XCTAssertEqual(profile.eventsTotal, 10)
        XCTAssertEqual(profile.activeDays28, 7)
        XCTAssertEqual(profile.currentStreakDays, 7)
    }

    func testAScatteredScheduleHasNoPeakPart() {
        knownSince(dayOffset: -30)
        let hours = [6, 7, 8, 13, 14, 15, 18, 19, 20, 23, 1, 2]
        for (index, day) in (-11...0).enumerated() {
            UserMemory.record("check", at: date(dayOffset: day, hour: hours[index]), count: 1)
        }

        XCTAssertNil(UserMemory.profile(now: date(dayOffset: 0, hour: 23)).peakPart)
    }

    func testAnEveningUserPeaksInTheEvening() {
        knownSince(dayOffset: -30)
        record("workout", days: Array(-11...0), hour: 20)

        XCTAssertEqual(UserMemory.profile(now: date(dayOffset: 0, hour: 23)).peakPart, .evening)
    }

    func testWeekendUsersAreRecognised() {
        knownSince(dayOffset: -30)
        record("long_run", days: Array(recentWeekendOffsets(count: 6)))

        XCTAssertTrue(UserMemory.profile(now: date(dayOffset: 0, hour: 23)).weekendLeaning)
    }

    func testEverydayUsersAreNotCalledWeekendUsers() {
        knownSince(dayOffset: -30)
        record("workout", days: Array(-13...0))

        XCTAssertFalse(UserMemory.profile(now: date(dayOffset: 0, hour: 23)).weekendLeaning)
    }
}
