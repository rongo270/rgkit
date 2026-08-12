import XCTest
@testable import UserMemory

final class UserMemoryTests: XCTestCase {
    override func setUp() {
        super.setUp()
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("UserMemoryTests-\(UUID().uuidString)", isDirectory: true)
        UserMemory.configure(directory: directory)
    }

    /// Today at `hour`, shifted by `dayOffset` days (negative = past).
    private func date(dayOffset: Int, hour: Int) -> Date {
        let start = Calendar.current.startOfDay(for: Date())
        return Calendar.current.date(
            byAdding: DateComponents(day: dayOffset, hour: hour), to: start
        )!
    }

    // MARK: Preferences

    func testPreferenceRoundtrip() {
        UserMemory.set("theme", "dark")
        UserMemory.set("haptics", true)
        UserMemory.set("rest_seconds", 90)
        UserMemory.set("weight_step", 2.5)
        UserMemory.set("favorite_exercises", ["squat", "bench"])

        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
        XCTAssertEqual(UserMemory.bool(for: "haptics", default: false), true)
        XCTAssertEqual(UserMemory.int(for: "rest_seconds", default: 0), 90)
        XCTAssertEqual(UserMemory.double(for: "weight_step", default: 0), 2.5)
        XCTAssertEqual(UserMemory.strings(for: "favorite_exercises"), ["squat", "bench"])
        XCTAssertEqual(UserMemory.preferences().count, 5)

        UserMemory.removePreference("theme")
        XCTAssertNil(UserMemory.string(for: "theme"))
        XCTAssertEqual(UserMemory.string(for: "theme", default: "light"), "light")
    }

    // MARK: Behavior learning

    func testLearnedPreference() {
        let now = Date()
        for _ in 0..<4 { UserMemory.observe("export_format", choice: "pdf", at: now) }
        UserMemory.observe("export_format", choice: "png", at: now)

        let learned = UserMemory.preferred("export_format")
        XCTAssertNotNil(learned)
        XCTAssertEqual(learned?.top.value, "pdf")
        XCTAssertEqual(learned?.observations, 5)
        XCTAssertEqual(learned?.top.share ?? 0, 0.8, accuracy: 0.001)
        XCTAssertEqual(learned?.confidence ?? 0, 0.8, accuracy: 0.001)
        XCTAssertEqual(UserMemory.preferredValue("export_format"), "pdf")
        XCTAssertNil(UserMemory.preferred("never_observed"))
    }

    func testRecencyDecayFlipsPreference() {
        let longAgo = Date().addingTimeInterval(-90 * 86_400)
        for _ in 0..<5 { UserMemory.observe("export_format", choice: "png", at: longAgo) }
        let now = Date()
        for _ in 0..<3 { UserMemory.observe("export_format", choice: "pdf", at: now) }

        // 5 png observations decayed over 3 half-lives (~0.6 weight) lose to
        // 3 fresh pdf observations.
        XCTAssertEqual(UserMemory.preferredValue("export_format"), "pdf")
    }

    func testSuggestRanksLearnedFirst() {
        let now = Date()
        for _ in 0..<3 { UserMemory.observe("export_format", choice: "png", at: now) }
        UserMemory.observe("export_format", choice: "txt", at: now)

        let ranked = UserMemory.suggest("export_format", options: ["pdf", "txt", "png", "csv"])
        XCTAssertEqual(ranked, ["png", "txt", "pdf", "csv"])

        // Unknown key: original order untouched.
        XCTAssertEqual(
            UserMemory.suggest("nothing", options: ["a", "b"]),
            ["a", "b"]
        )
    }

    // MARK: Habits

    func testHabitStreakAndTypicalHour() {
        for offset in -9...0 {
            UserMemory.record("workout", at: date(dayOffset: offset, hour: 7), count: 1)
        }
        let now = date(dayOffset: 0, hour: 12)

        let habit = UserMemory.habit("workout")
        XCTAssertNotNil(habit)
        XCTAssertEqual(habit?.total, 10)
        XCTAssertEqual(habit?.currentStreakDays(from: now), 10)
        XCTAssertEqual(habit?.bestStreakDays(), 10)
        XCTAssertEqual(habit?.typicalHour(), 7)
        XCTAssertEqual(habit?.dayPart(), .morning)
        XCTAssertEqual(habit?.activeDays(last: 28, from: now), 10)
        XCTAssertEqual(habit?.isHabit(from: now), true)
        XCTAssertEqual(habit?.doneToday(from: now), true)
    }

    func testRecommendationsHabitDue() {
        // 8 straight days at 7am, ending yesterday; it is now 7am today.
        for offset in -8...(-1) {
            UserMemory.record("workout", at: date(dayOffset: offset, hour: 7), count: 1)
        }
        let recs = UserMemory.recommendations(limit: 5, now: date(dayOffset: 0, hour: 7))
        XCTAssertTrue(recs.contains { $0.kind == .habitDue && $0.subject == "workout" })
    }

    func testRecommendationsStreakAtRisk() {
        // 8-day streak ending yesterday; it is now 8pm and nothing logged today.
        for offset in -8...(-1) {
            UserMemory.record("workout", at: date(dayOffset: offset, hour: 7), count: 1)
        }
        let recs = UserMemory.recommendations(limit: 5, now: date(dayOffset: 0, hour: 20))
        XCTAssertTrue(recs.contains { $0.kind == .streakAtRisk && $0.subject == "workout" })
    }

    func testRecommendationsFadingHabit() {
        // Regular the month before last, almost nothing since.
        for offset in -45...(-34) {
            UserMemory.record("meditate", at: date(dayOffset: offset, hour: 21), count: 1)
        }
        UserMemory.record("meditate", at: date(dayOffset: -5, hour: 21), count: 1)

        let now = date(dayOffset: 0, hour: 12)
        XCTAssertEqual(UserMemory.habit("meditate")?.isFading(from: now), true)
        let recs = UserMemory.recommendations(limit: 5, now: now)
        XCTAssertTrue(recs.contains { $0.kind == .fadingHabit && $0.subject == "meditate" })
    }

    func testRecommendationsLearnedDefault() {
        let now = Date()
        for _ in 0..<8 { UserMemory.observe("export_format", choice: "pdf", at: now) }
        let recs = UserMemory.recommendations(limit: 5, now: now)
        XCTAssertTrue(recs.contains { $0.kind == .learnedDefault && $0.subject == "export_format" })
    }

    // MARK: Profile

    func testProfile() {
        for offset in -9...0 {
            UserMemory.record("workout", at: date(dayOffset: offset, hour: 8), count: 1)
        }
        let profile = UserMemory.profile(now: date(dayOffset: 0, hour: 12))

        XCTAssertEqual(profile.engagement, .new) // known < 7 days
        XCTAssertEqual(profile.activeDays28, 10)
        XCTAssertEqual(profile.eventsTotal, 10)
        XCTAssertEqual(profile.peakPart, .morning)
        XCTAssertEqual(profile.currentStreakDays, 10)
        XCTAssertEqual(profile.habitCount, 1)
        XCTAssertEqual(profile.preferenceCount, 0)
    }

    // MARK: Export / import / persistence

    func testExportImportRoundtrip() {
        UserMemory.set("theme", "dark")
        UserMemory.observe("export_format", choice: "pdf", at: Date())
        UserMemory.record("workout", at: date(dayOffset: 0, hour: 7), count: 2)

        let json = UserMemory.exportJSON()
        UserMemory.reset()
        XCTAssertNil(UserMemory.string(for: "theme"))

        XCTAssertTrue(UserMemory.importJSON(json))
        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
        XCTAssertEqual(UserMemory.preferredValue("export_format"), "pdf")
        XCTAssertEqual(UserMemory.habit("workout")?.total, 2)
    }

    func testImportAndroidSchema() {
        // Hand-built JSON in the exact shape the Android SDK writes.
        let nowMillis = Int64(Date().timeIntervalSince1970 * 1000)
        let today = UserMemory.dayKey(for: Date())
        let json = """
        {"version":1,"since":\(nowMillis - 86_400_000),
         "prefs":{"theme":{"t":"s","v":"dark","at":\(nowMillis)},
                  "rest_seconds":{"t":"i","v":90,"at":\(nowMillis)}},
         "signals":{"export_format":{"pdf":{"w":3.0,"n":3,"at":\(nowMillis)}}},
         "events":[{"name":"workout","total":2,"firstAt":\(nowMillis - 86_400_000),
                    "lastAt":\(nowMillis),"daily":{"\(today)":2},"hourly":{"7":2}}]}
        """
        XCTAssertTrue(UserMemory.importJSON(json))
        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
        XCTAssertEqual(UserMemory.int(for: "rest_seconds", default: 0), 90)
        XCTAssertEqual(UserMemory.preferredValue("export_format"), "pdf")
        XCTAssertEqual(UserMemory.habit("workout")?.doneToday(), true)
    }

    func testImportRejectsGarbage() {
        UserMemory.set("theme", "dark")
        XCTAssertFalse(UserMemory.importJSON("not json at all"))
        XCTAssertFalse(UserMemory.importJSON("{\"version\":99}"))
        // Failed imports leave memory untouched.
        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
    }

    func testPersistenceAcrossReload() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("UserMemoryTests-persist-\(UUID().uuidString)", isDirectory: true)
        UserMemory.configure(directory: directory)
        UserMemory.set("theme", "dark")
        UserMemory.record("workout", at: date(dayOffset: 0, hour: 7), count: 1)

        // Fresh in-memory state pointed at the same directory reloads from disk.
        UserMemory.configure(directory: directory)
        XCTAssertEqual(UserMemory.string(for: "theme"), "dark")
        XCTAssertEqual(UserMemory.habit("workout")?.total, 1)
    }

    // MARK: Forget / reset

    func testForget() {
        UserMemory.set("theme", "dark")
        UserMemory.observe("theme", choice: "dark", at: Date())
        UserMemory.record("workout", at: Date(), count: 1)

        UserMemory.forget("theme")
        XCTAssertNil(UserMemory.string(for: "theme"))
        XCTAssertNil(UserMemory.preferred("theme"))
        XCTAssertNotNil(UserMemory.habit("workout"))

        UserMemory.forgetHabit("workout")
        XCTAssertNil(UserMemory.habit("workout"))
    }

    func testReset() {
        UserMemory.set("theme", "dark")
        UserMemory.observe("export_format", choice: "pdf", at: Date())
        UserMemory.record("workout", at: Date(), count: 1)

        UserMemory.reset()
        XCTAssertTrue(UserMemory.preferences().isEmpty)
        XCTAssertTrue(UserMemory.learned().isEmpty)
        XCTAssertTrue(UserMemory.habits().isEmpty)
    }
}
