import Foundation

/// Usage statistics for a single feature.
public struct FeatureStat: Codable, Equatable, Identifiable, Sendable {
    public var id: String { name }

    public let name: String
    public private(set) var total: Int
    public private(set) var firstUsedAt: Date
    public private(set) var lastUsedAt: Date
    /// Per-day counts keyed by local date "yyyy-MM-dd". Pruned to the last 365 days.
    public private(set) var daily: [String: Int]
    /// Lifetime counts per hour of day (0–23). Never pruned.
    public private(set) var hourly: [Int: Int]
    /// Total time recorded via begin()/end(), in milliseconds.
    public private(set) var totalDurationMs: Int
    /// Number of begin()/end() sessions that contributed to `totalDurationMs`.
    public private(set) var timedSessions: Int

    init(name: String, at date: Date, count: Int = 1, durationMs: Int = 0) {
        self.name = name
        self.total = 0
        self.firstUsedAt = date
        self.lastUsedAt = date
        self.daily = [:]
        self.hourly = [:]
        self.totalDurationMs = 0
        self.timedSessions = 0
        record(at: date, count: count, durationMs: durationMs)
    }

    mutating func record(at date: Date, count: Int = 1, durationMs: Int = 0) {
        total += count
        lastUsedAt = date
        if date < firstUsedAt { firstUsedAt = date }
        let key = FeatureUsage.dayKey(for: date)
        daily[key, default: 0] += count
        let hour = Calendar.current.component(.hour, from: date)
        hourly[hour, default: 0] += count
        if durationMs > 0 {
            totalDurationMs += durationMs
            timedSessions += 1
        }
    }

    mutating func prune(keeping days: Int, from reference: Date) {
        guard let cutoff = Calendar.current.date(byAdding: .day, value: -(days - 1), to: reference) else { return }
        let cutoffKey = FeatureUsage.dayKey(for: cutoff)
        daily = daily.filter { $0.key >= cutoffKey }
    }

    /// Sums another snapshot of the same feature into this one (used by import).
    mutating func merge(_ other: FeatureStat) {
        total += other.total
        if other.firstUsedAt < firstUsedAt { firstUsedAt = other.firstUsedAt }
        if other.lastUsedAt > lastUsedAt { lastUsedAt = other.lastUsedAt }
        for (key, value) in other.daily { daily[key, default: 0] += value }
        for (key, value) in other.hourly { hourly[key, default: 0] += value }
        totalDurationMs += other.totalDurationMs
        timedSessions += other.timedSessions
    }

    /// Number of distinct days with at least one use (within the retention window).
    public var activeDays: Int { daily.count }

    /// Average begin()/end() session length in milliseconds, or 0 without timed data.
    public var averageSessionMs: Int {
        timedSessions > 0 ? totalDurationMs / timedSessions : 0
    }

    /// Total uses within the last `days` days (including today).
    public func count(lastDays days: Int, from reference: Date = Date()) -> Int {
        dailyCounts(lastDays: days, from: reference).reduce(0, +)
    }

    /// Counts for each of the last `days` days, oldest first — ready for a sparkline.
    public func dailyCounts(lastDays days: Int, from reference: Date = Date()) -> [Int] {
        let calendar = Calendar.current
        return (0..<days).reversed().map { offset in
            guard let day = calendar.date(byAdding: .day, value: -offset, to: reference) else { return 0 }
            return daily[FeatureUsage.dayKey(for: day)] ?? 0
        }
    }

    /// True when the feature has not been used for `days` days or more.
    public func isStale(days: Int = 30, from reference: Date = Date()) -> Bool {
        reference.timeIntervalSince(lastUsedAt) >= TimeInterval(days) * 86_400
    }

    /// Change of the last `days` days vs the `days` before them, as a rounded
    /// percentage (25 = up 25%, -50 = halved). Nil when the previous window had
    /// no uses, so there is nothing to compare against.
    public func trendPercent(days: Int = 7, from reference: Date = Date()) -> Int? {
        let current = count(lastDays: days, from: reference)
        let previousEnd = reference.addingTimeInterval(-TimeInterval(days) * 86_400)
        let previous = count(lastDays: days, from: previousEnd)
        guard previous > 0 else { return nil }
        return Int((Double(current - previous) * 100.0 / Double(previous)).rounded())
    }

    /// Average uses per active day (days with at least one use).
    public func averagePerActiveDay() -> Double {
        daily.isEmpty ? 0 : Double(daily.values.reduce(0, +)) / Double(daily.count)
    }

    /// Lifetime counts for each hour of day, index 0 = midnight–1am … index 23.
    public func hourlyCounts() -> [Int] {
        (0..<24).map { hourly[$0] ?? 0 }
    }

    /// Counts summed by day of week, Monday first (index 0 = Mon … 6 = Sun).
    public func weekdayCounts() -> [Int] {
        var counts = [Int](repeating: 0, count: 7)
        let calendar = Calendar.current
        for (key, value) in daily {
            guard let date = FeatureUsage.date(fromDayKey: key) else { continue }
            let weekday = calendar.component(.weekday, from: date) // 1 = Sunday
            counts[(weekday + 5) % 7] += value
        }
        return counts
    }

    /// Consecutive days with at least one use, counting back from today.
    /// A day with no use yet today does not break the streak until tomorrow.
    public func currentStreakDays(from reference: Date = Date()) -> Int {
        let calendar = Calendar.current
        var day = reference
        if daily[FeatureUsage.dayKey(for: day)] == nil {
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { return 0 }
            day = previous
        }
        var streak = 0
        while daily[FeatureUsage.dayKey(for: day)] != nil {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    /// Longest run of consecutive days with use (within the retention window).
    public func bestStreakDays() -> Int {
        guard !daily.isEmpty else { return 0 }
        let calendar = Calendar.current
        let keys = daily.keys.sorted()
        var best = 1
        var run = 1
        for i in 1..<keys.count {
            guard let previousDate = FeatureUsage.date(fromDayKey: keys[i - 1]),
                  let next = calendar.date(byAdding: .day, value: 1, to: previousDate)
            else {
                run = 1
                continue
            }
            run = FeatureUsage.dayKey(for: next) == keys[i] ? run + 1 : 1
            best = max(best, run)
        }
        return best
    }

    // MARK: Codable
    // Custom so files written before "hourly"/duration fields existed still
    // decode, and so hourly serializes as a string-keyed object (matching the
    // Android SDK) instead of Swift's flattened-array encoding of Int-keyed
    // dictionaries.

    private enum CodingKeys: String, CodingKey {
        case name, total, firstUsedAt, lastUsedAt, daily, hourly, totalDurationMs, timedSessions
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        name = try container.decode(String.self, forKey: .name)
        total = try container.decode(Int.self, forKey: .total)
        firstUsedAt = try container.decode(Date.self, forKey: .firstUsedAt)
        lastUsedAt = try container.decode(Date.self, forKey: .lastUsedAt)
        daily = try container.decode([String: Int].self, forKey: .daily)
        let stringKeyed = try container.decodeIfPresent([String: Int].self, forKey: .hourly) ?? [:]
        var mapped: [Int: Int] = [:]
        for (key, value) in stringKeyed {
            if let hour = Int(key) { mapped[hour] = value }
        }
        hourly = mapped
        totalDurationMs = try container.decodeIfPresent(Int.self, forKey: .totalDurationMs) ?? 0
        timedSessions = try container.decodeIfPresent(Int.self, forKey: .timedSessions) ?? 0
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(name, forKey: .name)
        try container.encode(total, forKey: .total)
        try container.encode(firstUsedAt, forKey: .firstUsedAt)
        try container.encode(lastUsedAt, forKey: .lastUsedAt)
        try container.encode(daily, forKey: .daily)
        let stringKeyed = Dictionary(uniqueKeysWithValues: hourly.map { (String($0.key), $0.value) })
        try container.encode(stringKeyed, forKey: .hourly)
        try container.encode(totalDurationMs, forKey: .totalDurationMs)
        try container.encode(timedSessions, forKey: .timedSessions)
    }
}

/// One recorded use, for the recent-events timeline.
public struct UsageEvent: Codable, Equatable, Sendable {
    public let name: String
    public let at: Date

    enum CodingKeys: String, CodingKey {
        case name = "n"
        case at = "t"
    }
}

/// An automatically generated finding about recorded usage.
public struct UsageInsight: Identifiable, Sendable {
    public enum Kind: Sendable {
        case rising, falling, stale, streak, peakHour, peakDay, concentration, new
    }

    public let id = UUID()
    public let kind: Kind
    public let title: String
    public let detail: String
    /// The feature the insight is about, when it concerns a single feature.
    public let feature: String?

    init(kind: Kind, title: String, detail: String, feature: String? = nil) {
        self.kind = kind
        self.title = title
        self.detail = detail
        self.feature = feature
    }
}

/// Local-first feature usage tracking. No backend, no network, no account —
/// everything is stored in small JSON files inside the app's sandbox.
///
///     FeatureUsage.track("export_pdf")
///     FeatureUsage.begin("editor"); FeatureUsage.end("editor")  // timed session
///
/// Show `FeatureUsageView()` anywhere (e.g. a debug menu) to see the numbers.
public enum FeatureUsage {
    private static let queue = DispatchQueue(label: "dev.rgkit.featureusage")
    private static var features: [String: FeatureStat] = [:]
    private static var events: [UsageEvent] = []
    private static var pendingStarts: [String: Date] = [:]
    private static var loaded = false
    private static var storageDirectory: URL?
    private static let retentionDays = 365
    private static let eventCap = 500
    private static let maxSessionSeconds: TimeInterval = 6 * 60 * 60

    /// Posted after any recorded change (on the thread that made it).
    public static let didChangeNotification = Notification.Name("dev.rgkit.featureusage.didChange")

    /// Set false to turn all recording into a no-op (e.g. in release builds).
    public static var enabled = true

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    // MARK: - Public API — recording

    /// Records one use of a feature and returns the new total (1 means first
    /// use ever — handy for one-time tips). Safe to call from any thread;
    /// cheap enough to call on every tap. Empty names are ignored.
    @discardableResult
    public static func track(_ name: String) -> Int {
        track(name, at: Date(), count: 1)
    }

    /// Records `count` uses at once (e.g. "imported 12 photos"). Returns the new total.
    @discardableResult
    public static func track(_ name: String, count: Int) -> Int {
        guard count > 0 else { return self.count(for: name) }
        return track(name, at: Date(), count: count)
    }

    /// Starts a timed session for a feature. Pair with `end(_:)`; the elapsed
    /// time accumulates into the feature's time-spent stats. Calling begin()
    /// again before end() restarts the clock.
    public static func begin(_ name: String) {
        let key = normalize(name)
        guard !key.isEmpty, enabled else { return }
        queue.sync { pendingStarts[key] = Date() }
    }

    /// Ends a timed session started with `begin(_:)` and records one use with
    /// the elapsed duration (capped at 6h). Without a matching begin() it just
    /// records a plain use. Returns the new total.
    @discardableResult
    public static func end(_ name: String) -> Int {
        let key = normalize(name)
        guard !key.isEmpty, enabled else { return count(for: name) }
        let now = Date()
        var durationMs = 0
        queue.sync {
            if let start = pendingStarts.removeValue(forKey: key) {
                durationMs = Int(min(max(now.timeIntervalSince(start), 0), maxSessionSeconds) * 1000)
            }
        }
        return track(key, at: now, count: 1, durationMs: durationMs)
    }

    /// Optional: change where the JSON files live. Defaults to
    /// Application Support/FeatureUsage. Call before the first `track`.
    public static func configure(directory: URL) {
        queue.sync {
            storageDirectory = directory
            loaded = false
            features = [:]
            events = []
            pendingStarts = [:]
        }
    }

    // MARK: - Public API — reading

    /// All features, most used first.
    public static func stats() -> [FeatureStat] {
        queue.sync {
            loadIfNeeded()
            return features.values.sorted {
                $0.total != $1.total ? $0.total > $1.total : $0.name < $1.name
            }
        }
    }

    /// Stats for one feature, or nil if it was never tracked.
    public static func stat(_ name: String) -> FeatureStat? {
        queue.sync {
            loadIfNeeded()
            return features[normalize(name)]
        }
    }

    /// Total recorded uses of one feature.
    public static func count(for name: String) -> Int {
        stat(name)?.total ?? 0
    }

    /// The most recent recorded events, newest first. Kept for the last 500 uses.
    public static func recentEvents(limit: Int = 100) -> [UsageEvent] {
        queue.sync {
            loadIfNeeded()
            return events.suffix(limit).reversed()
        }
    }

    /// Automatically generated findings: trending features, streaks, stale
    /// features, peak hour/day, and more. Empty until there is enough data.
    public static func insights(from now: Date = Date()) -> [UsageInsight] {
        let all = stats()
        guard !all.isEmpty else { return [] }
        var result: [UsageInsight] = []

        let trends = all.compactMap { s in s.trendPercent(days: 7, from: now).map { (s, $0) } }
        if let (s, p) = trends
            .filter({ $0.1 > 0 && $0.0.count(lastDays: 7, from: now) >= 3 })
            .max(by: { $0.1 < $1.1 }) {
            result.append(UsageInsight(
                kind: .rising,
                title: "\"\(s.name)\" is trending up",
                detail: "+\(p)% vs the previous week (\(s.count(lastDays: 7, from: now)) uses in 7 days)",
                feature: s.name
            ))
        }
        let weekAgo = now.addingTimeInterval(-7 * 86_400)
        if let (s, p) = trends
            .filter({ $0.1 < 0 && $0.0.count(lastDays: 7, from: weekAgo) >= 3 })
            .min(by: { $0.1 < $1.1 }) {
            result.append(UsageInsight(
                kind: .falling,
                title: "\"\(s.name)\" is dropping",
                detail: "\(p)% vs the previous week",
                feature: s.name
            ))
        }

        if let leader = all.max(by: { $0.currentStreakDays(from: now) < $1.currentStreakDays(from: now) }),
           leader.currentStreakDays(from: now) >= 3 {
            result.append(UsageInsight(
                kind: .streak,
                title: "\"\(leader.name)\" is on a streak",
                detail: "Used \(leader.currentStreakDays(from: now)) days in a row",
                feature: leader.name
            ))
        }

        let stale = all.filter { $0.isStale(days: 30, from: now) }
        if !stale.isEmpty {
            let names = stale.prefix(5).map(\.name).joined(separator: ", ")
                + (stale.count > 5 ? ", …" : "")
            result.append(UsageInsight(
                kind: .stale,
                title: "\(stale.count) feature\(stale.count == 1 ? "" : "s") unused for 30+ days",
                detail: names
            ))
        }

        var hourTotals = [Int](repeating: 0, count: 24)
        for s in all {
            for (hour, count) in s.hourlyCounts().enumerated() { hourTotals[hour] += count }
        }
        let hourSum = hourTotals.reduce(0, +)
        if hourSum >= 20, let peak = hourTotals.indices.max(by: { hourTotals[$0] < hourTotals[$1] }) {
            result.append(UsageInsight(
                kind: .peakHour,
                title: String(format: "Peak hour: %02d:00–%02d:00", peak, (peak + 1) % 24),
                detail: "\(hourTotals[peak] * 100 / hourSum)% of all recorded uses"
            ))
        }

        var dayTotals = [Int](repeating: 0, count: 7)
        for s in all {
            for (day, count) in s.weekdayCounts().enumerated() { dayTotals[day] += count }
        }
        let daySum = dayTotals.reduce(0, +)
        if daySum >= 20, let peak = dayTotals.indices.max(by: { dayTotals[$0] < dayTotals[$1] }) {
            let names = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
            result.append(UsageInsight(
                kind: .peakDay,
                title: "Busiest day: \(names[peak])",
                detail: "\(dayTotals[peak] * 100 / daySum)% of all recorded uses"
            ))
        }

        if all.count >= 4 {
            let total = all.reduce(0) { $0 + $1.total }
            let top3 = all.prefix(3).reduce(0) { $0 + $1.total }
            if total > 0 && top3 * 100 / total >= 60 {
                result.append(UsageInsight(
                    kind: .concentration,
                    title: "Usage is concentrated",
                    detail: "Top 3 features account for \(top3 * 100 / total)% of all usage"
                ))
            }
        }

        let fresh = all.filter { now.timeIntervalSince($0.firstUsedAt) < 7 * 86_400 }
        if !fresh.isEmpty {
            result.append(UsageInsight(
                kind: .new,
                title: "New this week",
                detail: fresh.prefix(5).map(\.name).joined(separator: ", ")
                    + (fresh.count > 5 ? ", …" : "")
            ))
        }
        return result
    }

    // MARK: - Public API — export / import / wipe

    /// Full data as pretty-printed JSON (includes per-day and per-hour history).
    public static func exportJSON() -> String {
        let snapshot = stats()
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(snapshot) else { return "[]" }
        return String(data: data, encoding: .utf8) ?? "[]"
    }

    /// Summary as CSV: one row per feature.
    public static func exportCSV() -> String {
        let now = Date()
        var lines = ["feature,total,first_used,last_used,last_7_days,last_30_days,trend_7d_pct,active_days"]
        let iso = ISO8601DateFormatter()
        for stat in stats() {
            let name = stat.name.contains(",") ? "\"\(stat.name)\"" : stat.name
            let trend = stat.trendPercent(days: 7, from: now).map(String.init) ?? ""
            lines.append(
                "\(name),\(stat.total),\(iso.string(from: stat.firstUsedAt)),"
                + "\(iso.string(from: stat.lastUsedAt)),\(stat.count(lastDays: 7, from: now)),"
                + "\(stat.count(lastDays: 30, from: now)),\(trend),\(stat.activeDays)"
            )
        }
        return lines.joined(separator: "\n")
    }

    /// Merges an `exportJSON` payload (from this or another device) into the
    /// local data: totals, daily/hourly buckets and durations are summed,
    /// first/last-used widened. Returns the number of features merged, or 0 if
    /// the payload could not be parsed.
    @discardableResult
    public static func importJSON(_ json: String) -> Int {
        guard let data = json.data(using: .utf8) else { return 0 }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        guard let imported = try? decoder.decode([FeatureStat].self, from: data),
              !imported.isEmpty else { return 0 }
        let now = Date()
        var merged = 0
        queue.sync {
            loadIfNeeded()
            for var stat in imported {
                guard !stat.name.isEmpty else { continue }
                if var existing = features[stat.name] {
                    existing.merge(stat)
                    existing.prune(keeping: retentionDays, from: now)
                    features[stat.name] = existing
                } else {
                    stat.prune(keeping: retentionDays, from: now)
                    features[stat.name] = stat
                }
                merged += 1
            }
            if merged > 0 { save() }
        }
        if merged > 0 { notifyChange() }
        return merged
    }

    /// Deletes all recorded usage (including the event timeline).
    public static func reset() {
        queue.sync {
            loadIfNeeded()
            features = [:]
            events = []
            pendingStarts = [:]
            save()
            saveEvents()
        }
        notifyChange()
    }

    /// Deletes recorded usage for one feature.
    public static func reset(_ name: String) {
        let key = normalize(name)
        queue.sync {
            loadIfNeeded()
            features.removeValue(forKey: key)
            events.removeAll { $0.name == key }
            pendingStarts.removeValue(forKey: key)
            save()
            saveEvents()
        }
        notifyChange()
    }

    // MARK: - Internals

    @discardableResult
    static func track(_ name: String, at date: Date, count: Int = 1, durationMs: Int = 0) -> Int {
        let key = normalize(name)
        guard !key.isEmpty, enabled else { return self.count(for: key) }
        var total = 0
        queue.sync {
            loadIfNeeded()
            if var existing = features[key] {
                existing.record(at: date, count: count, durationMs: durationMs)
                existing.prune(keeping: retentionDays, from: date)
                features[key] = existing
            } else {
                features[key] = FeatureStat(name: key, at: date, count: count, durationMs: durationMs)
            }
            total = features[key]?.total ?? 0
            events.append(UsageEvent(name: key, at: date))
            if events.count > eventCap { events.removeFirst(events.count - eventCap) }
            save()
            saveEvents()
        }
        notifyChange()
        return total
    }

    static func dayKey(for date: Date) -> String {
        dayFormatter.string(from: date)
    }

    static func date(fromDayKey key: String) -> Date? {
        dayFormatter.date(from: key)
    }

    private static func notifyChange() {
        NotificationCenter.default.post(name: didChangeNotification, object: nil)
    }

    private static func normalize(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func directoryURL() -> URL? {
        let directory: URL
        if let storageDirectory {
            directory = storageDirectory
        } else {
            guard let support = FileManager.default.urls(
                for: .applicationSupportDirectory, in: .userDomainMask
            ).first else { return nil }
            directory = support.appendingPathComponent("FeatureUsage", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory
    }

    private static func fileURL() -> URL? {
        directoryURL()?.appendingPathComponent("usage.json")
    }

    private static func eventsFileURL() -> URL? {
        directoryURL()?.appendingPathComponent("events.json")
    }

    private static func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        if let url = fileURL(), let data = try? Data(contentsOf: url) {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .iso8601
            if let stored = try? decoder.decode([FeatureStat].self, from: data) {
                let now = Date()
                for var stat in stored {
                    stat.prune(keeping: retentionDays, from: now)
                    features[stat.name] = stat
                }
            }
        }
        if let url = eventsFileURL(), let data = try? Data(contentsOf: url) {
            let decoder = JSONDecoder()
            decoder.dateDecodingStrategy = .millisecondsSince1970
            events = (try? decoder.decode([UsageEvent].self, from: data)) ?? []
            if events.count > eventCap { events.removeFirst(events.count - eventCap) }
        }
    }

    private static func save() {
        guard let url = fileURL() else { return }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        guard let data = try? encoder.encode(Array(features.values)) else { return }
        try? data.write(to: url, options: .atomic)
    }

    private static func saveEvents() {
        guard let url = eventsFileURL() else { return }
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        guard let data = try? encoder.encode(events) else { return }
        try? data.write(to: url, options: .atomic)
    }
}
