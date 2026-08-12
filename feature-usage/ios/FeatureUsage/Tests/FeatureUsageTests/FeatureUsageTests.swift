import XCTest
@testable import FeatureUsage

final class FeatureUsageTests: XCTestCase {
    var tempDirectory: URL!

    override func setUp() {
        super.setUp()
        tempDirectory = FileManager.default.temporaryDirectory
            .appendingPathComponent("FeatureUsageTests-\(UUID().uuidString)", isDirectory: true)
        FeatureUsage.configure(directory: tempDirectory)
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: tempDirectory)
        super.tearDown()
    }

    func testTrackIncrementsCount() {
        FeatureUsage.track("export_pdf")
        FeatureUsage.track("export_pdf")
        FeatureUsage.track("share")
        XCTAssertEqual(FeatureUsage.count(for: "export_pdf"), 2)
        XCTAssertEqual(FeatureUsage.count(for: "share"), 1)
        XCTAssertEqual(FeatureUsage.count(for: "never_used"), 0)
    }

    func testNamesAreTrimmedAndEmptyIgnored() {
        FeatureUsage.track("  export_pdf  ")
        FeatureUsage.track("")
        FeatureUsage.track("   ")
        XCTAssertEqual(FeatureUsage.count(for: "export_pdf"), 1)
        XCTAssertEqual(FeatureUsage.stats().count, 1)
    }

    func testStatsSortedByTotalDescending() {
        FeatureUsage.track("a_rare")
        for _ in 0..<5 { FeatureUsage.track("popular") }
        for _ in 0..<3 { FeatureUsage.track("middle") }
        XCTAssertEqual(FeatureUsage.stats().map(\.name), ["popular", "middle", "a_rare"])
    }

    func testPersistenceRoundTrip() {
        for _ in 0..<4 { FeatureUsage.track("export_pdf") }
        // Re-configuring to the same directory drops in-memory state and reloads from disk.
        FeatureUsage.configure(directory: tempDirectory)
        XCTAssertEqual(FeatureUsage.count(for: "export_pdf"), 4)
        XCTAssertEqual(FeatureUsage.stat("export_pdf")?.daily.values.reduce(0, +), 4)
    }

    func testDailyBucketsAndSparkline() {
        let calendar = Calendar.current
        let now = Date()
        let twoDaysAgo = calendar.date(byAdding: .day, value: -2, to: now)!
        FeatureUsage.track("share", at: twoDaysAgo)
        FeatureUsage.track("share", at: twoDaysAgo)
        FeatureUsage.track("share", at: now)

        let stat = FeatureUsage.stat("share")!
        XCTAssertEqual(stat.count(lastDays: 7, from: now), 3)
        XCTAssertEqual(stat.count(lastDays: 1, from: now), 1)
        let sparkline = stat.dailyCounts(lastDays: 7, from: now)
        XCTAssertEqual(sparkline.count, 7)
        XCTAssertEqual(sparkline[6], 1) // today, newest last
        XCTAssertEqual(sparkline[4], 2) // two days ago
    }

    func testStaleDetection() {
        let now = Date()
        let fortyDaysAgo = now.addingTimeInterval(-40 * 86_400)
        FeatureUsage.track("old_feature", at: fortyDaysAgo)
        FeatureUsage.track("fresh_feature", at: now)
        XCTAssertTrue(FeatureUsage.stat("old_feature")!.isStale(days: 30, from: now))
        XCTAssertFalse(FeatureUsage.stat("fresh_feature")!.isStale(days: 30, from: now))
    }

    func testFirstAndLastUsedAt() {
        let now = Date()
        let earlier = now.addingTimeInterval(-3_600)
        FeatureUsage.track("share", at: earlier)
        FeatureUsage.track("share", at: now)
        let stat = FeatureUsage.stat("share")!
        XCTAssertEqual(stat.firstUsedAt.timeIntervalSince1970, earlier.timeIntervalSince1970, accuracy: 1)
        XCTAssertEqual(stat.lastUsedAt.timeIntervalSince1970, now.timeIntervalSince1970, accuracy: 1)
    }

    func testExportJSONContainsFeatures() {
        FeatureUsage.track("export_pdf")
        let json = FeatureUsage.exportJSON()
        XCTAssertTrue(json.contains("\"export_pdf\""))
        XCTAssertTrue(json.contains("\"total\" : 1"))
    }

    func testExportCSV() {
        FeatureUsage.track("export_pdf")
        FeatureUsage.track("export_pdf")
        let csv = FeatureUsage.exportCSV()
        let lines = csv.split(separator: "\n")
        XCTAssertEqual(
            lines[0],
            "feature,total,first_used,last_used,last_7_days,last_30_days,trend_7d_pct,active_days"
        )
        XCTAssertTrue(lines[1].hasPrefix("export_pdf,2,"))
        XCTAssertTrue(lines[1].hasSuffix(",1")) // one active day
    }

    func testReset() {
        FeatureUsage.track("a")
        FeatureUsage.track("b")
        FeatureUsage.reset("a")
        XCTAssertEqual(FeatureUsage.count(for: "a"), 0)
        XCTAssertEqual(FeatureUsage.count(for: "b"), 1)
        FeatureUsage.reset()
        XCTAssertTrue(FeatureUsage.stats().isEmpty)
        // Reset must also survive a reload.
        FeatureUsage.configure(directory: tempDirectory)
        XCTAssertTrue(FeatureUsage.stats().isEmpty)
    }

    func testOldDailyBucketsArePruned() {
        let now = Date()
        let twoYearsAgo = now.addingTimeInterval(-730 * 86_400)
        FeatureUsage.track("ancient", at: twoYearsAgo)
        FeatureUsage.track("ancient", at: now)
        let stat = FeatureUsage.stat("ancient")!
        XCTAssertEqual(stat.total, 2) // total is preserved
        XCTAssertEqual(stat.daily.count, 1) // but the old daily bucket is gone
        XCTAssertEqual(stat.firstUsedAt.timeIntervalSince1970, twoYearsAgo.timeIntervalSince1970, accuracy: 1)
    }

    func testConcurrentTrackingIsSafe() {
        let group = DispatchGroup()
        for _ in 0..<100 {
            DispatchQueue.global().async(group: group) {
                FeatureUsage.track("hammered")
            }
        }
        group.wait()
        XCTAssertEqual(FeatureUsage.count(for: "hammered"), 100)
    }

    func testTrackWithCount() {
        FeatureUsage.track("import_photos", count: 12)
        FeatureUsage.track("import_photos", count: 3)
        FeatureUsage.track("import_photos", count: 0)   // ignored
        FeatureUsage.track("import_photos", count: -5)  // ignored
        let stat = FeatureUsage.stat("import_photos")!
        XCTAssertEqual(stat.total, 15)
        XCTAssertEqual(stat.count(lastDays: 1), 15)
        XCTAssertEqual(stat.hourlyCounts().reduce(0, +), 15)
    }

    func testHourlyHistogram() {
        let calendar = Calendar.current
        let nineAM = calendar.date(bySettingHour: 9, minute: 30, second: 0, of: Date())!
        FeatureUsage.track("morning_thing", at: nineAM)
        FeatureUsage.track("morning_thing", at: nineAM)
        let counts = FeatureUsage.stat("morning_thing")!.hourlyCounts()
        XCTAssertEqual(counts.count, 24)
        XCTAssertEqual(counts[9], 2)
        XCTAssertEqual(counts.reduce(0, +), 2)
    }

    func testHourlySurvivesReload() {
        let calendar = Calendar.current
        let nineAM = calendar.date(bySettingHour: 9, minute: 0, second: 0, of: Date())!
        FeatureUsage.track("morning_thing", at: nineAM)
        FeatureUsage.configure(directory: tempDirectory)
        XCTAssertEqual(FeatureUsage.stat("morning_thing")!.hourlyCounts()[9], 1)
    }

    func testDecodesLegacyFileWithoutHourly() throws {
        // A file written by the previous SDK version has no "hourly" key.
        let legacy = """
        [{"name":"old_one","total":7,"firstUsedAt":"2026-07-01T10:00:00Z",
          "lastUsedAt":"2026-07-19T10:00:00Z","daily":{"2026-07-19":7}}]
        """
        try FileManager.default.createDirectory(at: tempDirectory, withIntermediateDirectories: true)
        try legacy.data(using: .utf8)!.write(to: tempDirectory.appendingPathComponent("usage.json"))
        FeatureUsage.configure(directory: tempDirectory)
        let stat = FeatureUsage.stat("old_one")
        XCTAssertEqual(stat?.total, 7)
        XCTAssertEqual(stat?.hourlyCounts(), [Int](repeating: 0, count: 24))
    }

    func testTrendPercent() {
        let now = Date()
        // Previous week: 4 uses; this week: 6 uses → +50%.
        let tenDaysAgo = now.addingTimeInterval(-10 * 86_400)
        for _ in 0..<4 { FeatureUsage.track("trending", at: tenDaysAgo) }
        for _ in 0..<6 { FeatureUsage.track("trending", at: now) }
        XCTAssertEqual(FeatureUsage.stat("trending")!.trendPercent(days: 7, from: now), 50)
        // No previous-window uses → nil, not a percentage.
        FeatureUsage.track("brand_new", at: now)
        XCTAssertNil(FeatureUsage.stat("brand_new")!.trendPercent(days: 7, from: now))
    }

    func testStreaks() {
        let calendar = Calendar.current
        let now = Date()
        // 3-day run ending today, plus an older single day → current 3, best 3.
        for offset in 0...2 {
            FeatureUsage.track("daily_habit", at: calendar.date(byAdding: .day, value: -offset, to: now)!)
        }
        FeatureUsage.track("daily_habit", at: calendar.date(byAdding: .day, value: -10, to: now)!)
        let stat = FeatureUsage.stat("daily_habit")!
        XCTAssertEqual(stat.currentStreakDays(from: now), 3)
        XCTAssertEqual(stat.bestStreakDays(), 3)
    }

    func testCurrentStreakToleratesQuietToday() {
        let calendar = Calendar.current
        let now = Date()
        // Used yesterday and the day before, but not yet today → streak is alive at 2.
        FeatureUsage.track("daily_habit", at: calendar.date(byAdding: .day, value: -1, to: now)!)
        FeatureUsage.track("daily_habit", at: calendar.date(byAdding: .day, value: -2, to: now)!)
        XCTAssertEqual(FeatureUsage.stat("daily_habit")!.currentStreakDays(from: now), 2)
    }

    func testActiveDaysAndAverage() {
        let calendar = Calendar.current
        let now = Date()
        FeatureUsage.track("sometimes", at: now)
        FeatureUsage.track("sometimes", at: now)
        FeatureUsage.track("sometimes", at: calendar.date(byAdding: .day, value: -3, to: now)!)
        let stat = FeatureUsage.stat("sometimes")!
        XCTAssertEqual(stat.activeDays, 2)
        XCTAssertEqual(stat.averagePerActiveDay(), 1.5, accuracy: 0.001)
    }

    func testWeekdayCountsSumMatchesDaily() {
        let calendar = Calendar.current
        let now = Date()
        for offset in 0..<10 {
            FeatureUsage.track("spread", at: calendar.date(byAdding: .day, value: -offset, to: now)!)
        }
        let stat = FeatureUsage.stat("spread")!
        let weekdays = stat.weekdayCounts()
        XCTAssertEqual(weekdays.count, 7)
        XCTAssertEqual(weekdays.reduce(0, +), stat.daily.values.reduce(0, +))
    }

    func testExportJSONIncludesHourly() {
        FeatureUsage.track("export_pdf")
        let json = FeatureUsage.exportJSON()
        XCTAssertTrue(json.contains("\"hourly\""))
        XCTAssertTrue(json.contains("\"totalDurationMs\""))
    }

    func testTrackReturnsNewTotal() {
        XCTAssertEqual(FeatureUsage.track("export_pdf"), 1)
        XCTAssertEqual(FeatureUsage.track("export_pdf"), 2)
        XCTAssertEqual(FeatureUsage.track("bulk", count: 5), 5)
    }

    func testBeginEndRecordsDuration() {
        FeatureUsage.begin("editor")
        Thread.sleep(forTimeInterval: 0.05)
        FeatureUsage.end("editor")
        let stat = FeatureUsage.stat("editor")!
        XCTAssertEqual(stat.total, 1)
        XCTAssertEqual(stat.timedSessions, 1)
        XCTAssertGreaterThanOrEqual(stat.totalDurationMs, 10)
        XCTAssertGreaterThanOrEqual(stat.averageSessionMs, 10)
    }

    func testEndWithoutBeginIsPlainUse() {
        FeatureUsage.end("editor")
        let stat = FeatureUsage.stat("editor")!
        XCTAssertEqual(stat.total, 1)
        XCTAssertEqual(stat.timedSessions, 0)
        XCTAssertEqual(stat.totalDurationMs, 0)
    }

    func testDurationsSurviveReload() {
        FeatureUsage.begin("editor")
        Thread.sleep(forTimeInterval: 0.02)
        FeatureUsage.end("editor")
        FeatureUsage.configure(directory: tempDirectory)
        XCTAssertGreaterThan(FeatureUsage.stat("editor")!.totalDurationMs, 0)
    }

    func testRecentEventsNewestFirstAndLimited() {
        let now = Date()
        FeatureUsage.track("a", at: now.addingTimeInterval(-3))
        FeatureUsage.track("b", at: now.addingTimeInterval(-2))
        FeatureUsage.track("c", at: now.addingTimeInterval(-1))
        XCTAssertEqual(FeatureUsage.recentEvents(limit: 10).map(\.name), ["c", "b", "a"])
        XCTAssertEqual(FeatureUsage.recentEvents(limit: 2).count, 2)
    }

    func testEventsSurviveReload() {
        FeatureUsage.track("persisted_event")
        FeatureUsage.configure(directory: tempDirectory)
        XCTAssertEqual(FeatureUsage.recentEvents(limit: 10).first?.name, "persisted_event")
    }

    func testResetFeatureRemovesItsEvents() {
        FeatureUsage.track("keep")
        FeatureUsage.track("drop")
        FeatureUsage.reset("drop")
        XCTAssertEqual(FeatureUsage.recentEvents(limit: 10).map(\.name), ["keep"])
    }

    func testDisabledIsNoOp() {
        FeatureUsage.enabled = false
        FeatureUsage.track("nope")
        FeatureUsage.begin("nope")
        FeatureUsage.end("nope")
        FeatureUsage.enabled = true
        XCTAssertEqual(FeatureUsage.count(for: "nope"), 0)
        XCTAssertTrue(FeatureUsage.recentEvents(limit: 10).isEmpty)
    }

    func testChangeNotificationFires() {
        let expectation = expectation(
            forNotification: FeatureUsage.didChangeNotification, object: nil
        )
        FeatureUsage.track("observed")
        wait(for: [expectation], timeout: 1)
    }

    func testImportMergesIntoExistingData() {
        FeatureUsage.track("export_pdf")
        let todayKey = FeatureUsage.dayKey(for: Date())
        let merged = FeatureUsage.importJSON(
            """
            [{"name":"export_pdf","total":5,
              "firstUsedAt":"2026-01-01T10:00:00Z","lastUsedAt":"2026-01-02T10:00:00Z",
              "daily":{"\(todayKey)":5},"hourly":{"9":5}},
             {"name":"from_other_device","total":2,
              "firstUsedAt":"2026-06-01T10:00:00Z","lastUsedAt":"2026-06-01T10:00:00Z",
              "daily":{},"hourly":{}}]
            """
        )
        XCTAssertEqual(merged, 2)
        let stat = FeatureUsage.stat("export_pdf")!
        XCTAssertEqual(stat.total, 6)
        XCTAssertEqual(stat.count(lastDays: 1), 6)
        XCTAssertLessThan(stat.firstUsedAt, Date().addingTimeInterval(-100 * 86_400)) // widened to January
        XCTAssertEqual(FeatureUsage.count(for: "from_other_device"), 2)
    }

    func testImportOfExportRestoresTotals() {
        for _ in 0..<3 { FeatureUsage.track("a") }
        FeatureUsage.track("b")
        let export = FeatureUsage.exportJSON()
        FeatureUsage.reset()
        XCTAssertEqual(FeatureUsage.importJSON(export), 2)
        XCTAssertEqual(FeatureUsage.count(for: "a"), 3)
        XCTAssertEqual(FeatureUsage.count(for: "b"), 1)
    }

    func testImportRejectsGarbage() {
        XCTAssertEqual(FeatureUsage.importJSON("not json at all"), 0)
        XCTAssertTrue(FeatureUsage.stats().isEmpty)
    }

    func testInsightsSurfaceRisersAndStale() {
        XCTAssertTrue(FeatureUsage.insights().isEmpty) // no data yet
        let now = Date()
        for _ in 0..<3 { FeatureUsage.track("riser", at: now.addingTimeInterval(-8 * 86_400)) }
        for _ in 0..<9 { FeatureUsage.track("riser", at: now) }
        FeatureUsage.track("dead_feature", at: now.addingTimeInterval(-40 * 86_400))
        let insights = FeatureUsage.insights(from: now)
        XCTAssertTrue(insights.contains { $0.kind == .rising && $0.feature == "riser" })
        XCTAssertTrue(insights.contains { $0.kind == .stale })
    }
}
