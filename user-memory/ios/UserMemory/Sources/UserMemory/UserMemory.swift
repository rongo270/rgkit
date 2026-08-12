import Foundation

/// Rough part of the day, used for "morning person" style insights.
public enum DayPart: String, Sendable, CaseIterable {
    case morning, afternoon, evening, night

    public var label: String {
        switch self {
        case .morning: return "Morning"
        case .afternoon: return "Afternoon"
        case .evening: return "Evening"
        case .night: return "Night"
        }
    }

    public static func of(hour: Int) -> DayPart {
        switch hour {
        case 5...11: return .morning
        case 12...16: return .afternoon
        case 17...21: return .evening
        default: return .night
        }
    }
}

/// How engaged this user is, derived from active days in the last 28.
public enum Engagement: String, Sendable {
    case new, casual, regular, power

    public var label: String {
        switch self {
        case .new: return "Just met"
        case .casual: return "Casual user"
        case .regular: return "Regular user"
        case .power: return "Power user"
        }
    }
}

/// The value of an explicitly stored preference.
public enum PreferenceValue: Equatable, Sendable {
    case string(String)
    case bool(Bool)
    case int(Int)
    case double(Double)
    case strings([String])

    /// Human-readable value for display.
    public var displayValue: String {
        switch self {
        case .string(let value): return value
        case .bool(let value): return value ? "true" : "false"
        case .int(let value): return String(value)
        case .double(let value): return String(value)
        case .strings(let value): return value.joined(separator: ", ")
        }
    }
}

/// An explicitly stored preference (`set(...)`).
public struct Preference: Identifiable, Sendable {
    public var id: String { key }
    public let key: String
    public let value: PreferenceValue
    public let updatedAt: Date
}

/// One option of a learned choice, with its recency-weighted share.
public struct LearnedChoice: Equatable, Sendable {
    public let value: String
    /// This option's share of the total decayed weight, 0..1.
    public let share: Double
    /// Raw number of times this option was observed.
    public let count: Int
    public let lastAt: Date
}

/// What the SDK believes about one choice key after watching the user
/// (e.g. key "export_format" learned toward "pdf").
public struct Learned: Identifiable, Sendable {
    public var id: String { key }
    public let key: String
    /// All observed options, strongest first. Never empty.
    public let choices: [LearnedChoice]
    /// Total observations across all options.
    public let observations: Int
    /// 0..1 — how sure the SDK is that `top` really is the user's preference.
    public let confidence: Double

    public var top: LearnedChoice { choices[0] }
}

/// A recurring action the user takes (`record(...)`). Immutable snapshot.
public struct Habit: Identifiable, Sendable {
    public var id: String { name }

    public let name: String
    public let total: Int
    public let firstAt: Date
    public let lastAt: Date
    /// Per-day counts keyed by local date "yyyy-MM-dd". Pruned to the last 365 days.
    public let daily: [String: Int]
    /// Lifetime counts per hour of day (0–23). Never pruned.
    public let hourly: [Int: Int]

    /// Days with at least one occurrence within the last `days` days.
    public func activeDays(last days: Int, from reference: Date = Date()) -> Int {
        dailyCounts(lastDays: days, from: reference).filter { $0 > 0 }.count
    }

    /// Average occurrence-days per week over the last 28 days.
    public func perWeek(from reference: Date = Date()) -> Double {
        Double(activeDays(last: 28, from: reference)) / 4.0
    }

    /// 0..1 — how established this habit is. 1.0 means done (nearly) every day
    /// for the last four weeks; ~0.3 means about twice a week.
    public func strength(from reference: Date = Date()) -> Double {
        min(1.0, Double(activeDays(last: 28, from: reference)) / 21.0)
    }

    /// True when this qualifies as a real habit: regular and recent.
    public func isHabit(from reference: Date = Date()) -> Bool {
        strength(from: reference) >= 0.3
            && reference.timeIntervalSince(lastAt) <= 14 * 86_400
    }

    /// True when the previous month was habit-like but activity has since halved.
    public func isFading(from reference: Date = Date()) -> Bool {
        let previous = activeDays(last: 28, from: reference.addingTimeInterval(-28 * 86_400))
        return previous >= 6 && activeDays(last: 28, from: reference) <= previous / 2
    }

    /// True when the habit occurred at least once today.
    public func doneToday(from reference: Date = Date()) -> Bool {
        daily[UserMemory.dayKey(for: reference)] != nil
    }

    /// The hour of day this habit usually happens, or nil when there is no
    /// clear pattern. "Usual" means at least half of all occurrences fall in a
    /// 3-hour window around the returned hour (needs 5+ occurrences).
    public func typicalHour() -> Int? {
        let total = hourly.values.reduce(0, +)
        guard total >= 5 else { return nil }
        var bestHour = 0
        var bestSum = -1
        for hour in 0...23 {
            let sum = (hour - 1...hour + 1).reduce(0) { $0 + (hourly[($1 + 24) % 24] ?? 0) }
            // Tied windows resolve to the hour with the most direct hits.
            if sum > bestSum || (sum == bestSum && (hourly[hour] ?? 0) > (hourly[bestHour] ?? 0)) {
                bestSum = sum
                bestHour = hour
            }
        }
        return Double(bestSum) / Double(total) >= 0.5 ? bestHour : nil
    }

    /// The part of day this habit usually happens, or nil without a clear pattern.
    public func dayPart() -> DayPart? {
        typicalHour().map { DayPart.of(hour: $0) }
    }

    /// Weekdays this habit leans toward, Monday=0 … Sunday=6. A day qualifies
    /// when it holds a clearly above-average share of active days. Empty when
    /// there is not enough data or no leaning.
    public func typicalWeekdays() -> [Int] {
        var counts = [Int](repeating: 0, count: 7)
        for key in daily.keys {
            guard let date = UserMemory.parseDayKey(key) else { continue }
            let weekday = Calendar.current.component(.weekday, from: date)
            counts[(weekday + 5) % 7] += 1
        }
        let total = counts.reduce(0, +)
        guard total >= 6 else { return [] }
        return (0...6).filter { counts[$0] > 0 && Double(counts[$0]) / Double(total) >= 0.21 }
    }

    /// Counts for each of the last `days` days, oldest first — ready for a sparkline.
    public func dailyCounts(lastDays days: Int, from reference: Date = Date()) -> [Int] {
        let calendar = Calendar.current
        return (0..<days).reversed().map { offset in
            guard let day = calendar.date(byAdding: .day, value: -offset, to: reference) else {
                return 0
            }
            return daily[UserMemory.dayKey(for: day)] ?? 0
        }
    }

    /// Lifetime counts for each hour of day, index 0 = midnight–1am … index 23.
    public func hourlyCounts() -> [Int] {
        (0...23).map { hourly[$0] ?? 0 }
    }

    /// Consecutive days with at least one occurrence, counting back from today.
    /// A day with nothing yet today does not break the streak until tomorrow.
    public func currentStreakDays(from reference: Date = Date()) -> Int {
        UserMemory.streakDays(keys: Set(daily.keys), from: reference)
    }

    /// Longest run of consecutive days (within the retention window).
    public func bestStreakDays() -> Int {
        guard !daily.isEmpty else { return 0 }
        let keys = daily.keys.sorted()
        var best = 1
        var run = 1
        for index in 1..<keys.count {
            guard let previous = UserMemory.parseDayKey(keys[index - 1]),
                  let next = Calendar.current.date(byAdding: .day, value: 1, to: previous)
            else {
                run = 1
                continue
            }
            run = UserMemory.dayKey(for: next) == keys[index] ? run + 1 : 1
            best = max(best, run)
        }
        return best
    }
}

/// A snapshot of who this user is, derived from everything remembered so far.
public struct UserProfile: Sendable {
    /// When the SDK first saw this user.
    public let firstSeenAt: Date
    /// Whole days since `firstSeenAt`, minimum 1.
    public let daysKnown: Int
    /// Days with any recorded activity in the last 28.
    public let activeDays28: Int
    /// Total recorded habit events, lifetime.
    public let eventsTotal: Int
    public let engagement: Engagement
    /// The part of day most activity happens, or nil without a clear peak.
    public let peakPart: DayPart?
    /// True when activity clearly leans toward Saturday/Sunday.
    public let weekendLeaning: Bool
    /// Consecutive days (counting back from today) with any activity.
    public let currentStreakDays: Int
    /// Recorded actions that currently qualify as habits.
    public let habitCount: Int
    /// Choice keys with at least one observation.
    public let learnedCount: Int
    /// Explicitly stored preferences.
    public let preferenceCount: Int
}

public enum RecommendationKind: String, Sendable {
    /// A habit that usually happens around now and hasn't yet today.
    case habitDue
    /// A streak that will break unless the habit happens today.
    case streakAtRisk
    /// A previously regular habit the user is drifting away from.
    case fadingHabit
    /// A learned choice strong enough to preselect as the default.
    case learnedDefault
}

/// An actionable suggestion derived from memory, strongest first from
/// `UserMemory.recommendations()`.
public struct Recommendation: Identifiable, Sendable {
    public var id: String { "\(kind.rawValue)-\(subject)" }
    public let kind: RecommendationKind
    /// The habit name or choice key this is about.
    public let subject: String
    public let title: String
    public let detail: String
    /// Ranking score, higher = more relevant right now.
    public let score: Double
}

/// Universal user memory: persistent preferences, learned choices, habit
/// recognition and smart recommendations. Local-first — no backend, no network,
/// no account; everything lives in one JSON file inside the app's sandbox, and
/// `exportJSON()` / `importJSON(_:)` move it between apps and platforms (the
/// Android SDK reads the same format).
///
///     UserMemory.set("units", "metric")             // explicit preference
///     UserMemory.observe("export_format", choice: "pdf")  // learn from a choice
///     UserMemory.record("workout_logged")           // habit signal
///
///     UserMemory.preferredValue("export_format")    // → "pdf"
///     UserMemory.recommendations()                  // → what to surface now
///
/// Show `UserMemoryView()` anywhere to see (and manage) everything remembered.
public enum UserMemory {
    private static let queue = DispatchQueue(label: "dev.rgkit.usermemory")
    private static let retentionDays = 365
    private static let schemaVersion = 1
    /// Half-life of a choice observation: after 30 days it counts half as much.
    private static let choiceHalfLifeDays = 30.0

    private struct Pref {
        var value: PreferenceValue
        var updatedAt: Date
    }

    private struct Signal {
        var weight: Double
        var count: Int
        var lastAt: Date
    }

    private struct Event {
        let name: String
        var total: Int
        var firstAt: Date
        var lastAt: Date
        var daily: [String: Int]
        var hourly: [Int: Int]

        mutating func record(at date: Date, count: Int) {
            total += count
            if date > lastAt { lastAt = date }
            if date < firstAt { firstAt = date }
            daily[dayKey(for: date), default: 0] += count
            let hour = Calendar.current.component(.hour, from: date)
            hourly[hour, default: 0] += count
        }

        mutating func prune(keeping days: Int, from reference: Date) {
            guard let cutoff = Calendar.current.date(
                byAdding: .day, value: -(days - 1), to: reference
            ) else { return }
            let cutoffKey = dayKey(for: cutoff)
            // Keys are zero-padded yyyy-MM-dd, so string order is date order.
            daily = daily.filter { $0.key >= cutoffKey }
        }

        var snapshot: Habit {
            Habit(name: name, total: total, firstAt: firstAt, lastAt: lastAt,
                  daily: daily, hourly: hourly)
        }
    }

    private static var loaded = false
    private static var storageDirectory: URL?
    private static var since = Date()
    private static var prefs: [String: Pref] = [:]
    private static var signals: [String: [String: Signal]] = [:]
    private static var events: [String: Event] = [:]

    private static let dayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.locale = Locale(identifier: "en_US_POSIX")
        return formatter
    }()

    // MARK: - Setup

    /// Optional: change where the JSON file lives. Defaults to
    /// Application Support/UserMemory. Call before anything else (used by tests).
    public static func configure(directory: URL) {
        queue.sync {
            storageDirectory = directory
            loaded = false
            since = Date()
            prefs = [:]
            signals = [:]
            events = [:]
        }
    }

    // MARK: - Preferences (explicit)

    public static func set(_ key: String, _ value: String) { putPref(key, .string(value)) }
    public static func set(_ key: String, _ value: Bool) { putPref(key, .bool(value)) }
    public static func set(_ key: String, _ value: Int) { putPref(key, .int(value)) }
    public static func set(_ key: String, _ value: Double) { putPref(key, .double(value)) }
    public static func set(_ key: String, _ value: [String]) { putPref(key, .strings(value)) }

    public static func string(for key: String, default fallback: String? = nil) -> String? {
        if case .string(let value)? = pref(key) { return value }
        return fallback
    }

    public static func bool(for key: String, default fallback: Bool) -> Bool {
        if case .bool(let value)? = pref(key) { return value }
        return fallback
    }

    public static func int(for key: String, default fallback: Int) -> Int {
        if case .int(let value)? = pref(key) { return value }
        return fallback
    }

    public static func double(for key: String, default fallback: Double) -> Double {
        switch pref(key) {
        case .double(let value)?: return value
        case .int(let value)?: return Double(value)
        default: return fallback
        }
    }

    public static func strings(for key: String) -> [String] {
        if case .strings(let value)? = pref(key) { return value }
        return []
    }

    /// Removes one explicit preference.
    public static func removePreference(_ key: String) {
        queue.sync {
            loadIfNeeded()
            if prefs.removeValue(forKey: normalize(key)) != nil { save() }
        }
    }

    /// All explicit preferences, alphabetical.
    public static func preferences() -> [Preference] {
        queue.sync {
            loadIfNeeded()
            return prefs
                .map { Preference(key: $0.key, value: $0.value.value, updatedAt: $0.value.updatedAt) }
                .sorted { $0.key.lowercased() < $1.key.lowercased() }
        }
    }

    // MARK: - Behavior learning (implicit)

    /// Records that the user chose `choice` for `key` — e.g.
    /// `observe("export_format", choice: "pdf")` every time an export happens.
    /// Recent choices count more than old ones, so the learned preference
    /// follows the user when their behavior changes.
    public static func observe(_ key: String, choice: String) {
        observe(key, choice: choice, at: Date())
    }

    /// What the SDK has learned about `key`, or nil when never observed.
    public static func preferred(_ key: String) -> Learned? {
        queue.sync {
            loadIfNeeded()
            return lockedPreferred(key, now: Date())
        }
    }

    /// Shorthand for the strongest learned option's value, or nil.
    public static func preferredValue(_ key: String) -> String? {
        preferred(key)?.top.value
    }

    /// Ranks `options` by what was learned for `key`: observed options come
    /// first (strongest first), never-observed ones keep their given order.
    /// Ideal for ordering menus, chips or defaulting a picker:
    ///
    ///     let ordered = UserMemory.suggest("export_format", options: ["pdf", "png", "txt"])
    public static func suggest(_ key: String, options: [String]) -> [String] {
        guard let learned = preferred(key) else { return options }
        var rank: [String: Int] = [:]
        for (index, choice) in learned.choices.enumerated() { rank[choice.value] = index }
        return options.enumerated()
            .sorted { lhs, rhs in
                let lhsRank = rank[lhs.element] ?? Int.max
                let rhsRank = rank[rhs.element] ?? Int.max
                return lhsRank != rhsRank ? lhsRank < rhsRank : lhs.offset < rhs.offset
            }
            .map(\.element)
    }

    /// All learned choice keys with what was learned, most confident first.
    public static func learned() -> [Learned] {
        queue.sync {
            loadIfNeeded()
            let now = Date()
            return signals.keys
                .compactMap { lockedPreferred($0, now: now) }
                .sorted {
                    $0.confidence != $1.confidence
                        ? $0.confidence > $1.confidence
                        : $0.key < $1.key
                }
        }
    }

    // MARK: - Habits

    /// Records one occurrence of a recurring action — e.g.
    /// `record("workout_logged")`. Safe to call from any thread. Empty names
    /// are ignored.
    public static func record(_ name: String) {
        record(name, at: Date(), count: 1)
    }

    /// Records `count` occurrences at once.
    public static func record(_ name: String, count: Int) {
        guard count > 0 else { return }
        record(name, at: Date(), count: count)
    }

    /// All recorded actions as habit snapshots, most established first.
    public static func habits() -> [Habit] {
        queue.sync {
            loadIfNeeded()
            return lockedHabits(now: Date())
        }
    }

    /// Habit snapshot for one action, or nil if it was never recorded.
    public static func habit(_ name: String) -> Habit? {
        queue.sync {
            loadIfNeeded()
            return events[normalize(name)]?.snapshot
        }
    }

    // MARK: - Recommendations

    /// What is worth surfacing right now, strongest first: habits due around
    /// this hour, streaks at risk, fading habits, and learned choices strong
    /// enough to preselect.
    public static func recommendations(limit: Int = 5) -> [Recommendation] {
        recommendations(limit: limit, now: Date())
    }

    // MARK: - Profile

    /// Who this user is, derived from everything remembered so far.
    public static func profile() -> UserProfile {
        profile(now: Date())
    }

    // MARK: - Export / import / forget

    /// Everything remembered, as pretty-printed JSON. The Android SDK imports
    /// the same format, so memory can move between apps, devices and platforms.
    public static func exportJSON() -> String {
        queue.sync {
            loadIfNeeded()
            var root = storageObject()
            root["exportedAt"] = millis(from: Date())
            guard let data = try? JSONSerialization.data(
                withJSONObject: root, options: [.prettyPrinted, .sortedKeys]
            ) else { return "{}" }
            return String(data: data, encoding: .utf8) ?? "{}"
        }
    }

    /// Replaces everything remembered with `json` (as produced by
    /// `exportJSON()` on either platform). Returns false when the JSON cannot
    /// be parsed; memory is left unchanged in that case.
    @discardableResult
    public static func importJSON(_ json: String) -> Bool {
        queue.sync {
            loadIfNeeded()
            guard let data = json.data(using: .utf8),
                  let object = try? JSONSerialization.jsonObject(with: data),
                  let root = object as? [String: Any],
                  (root["version"] as? Int ?? schemaVersion) <= schemaVersion,
                  apply(storage: root)
            else { return false }
            save()
            return true
        }
    }

    /// Deletes everything remembered.
    public static func reset() {
        queue.sync {
            loadIfNeeded()
            prefs = [:]
            signals = [:]
            events = [:]
            since = Date()
            save()
        }
    }

    /// Forgets one key: its explicit preference and everything learned about it.
    public static func forget(_ key: String) {
        queue.sync {
            loadIfNeeded()
            let trimmed = normalize(key)
            let removedPref = prefs.removeValue(forKey: trimmed) != nil
            let removedSignal = signals.removeValue(forKey: trimmed) != nil
            if removedPref || removedSignal { save() }
        }
    }

    /// Forgets one recorded action's entire history.
    public static func forgetHabit(_ name: String) {
        queue.sync {
            loadIfNeeded()
            if events.removeValue(forKey: normalize(name)) != nil { save() }
        }
    }

    /// "7am" / "12pm" style label for an hour of day.
    public static func hourLabel(_ hour: Int) -> String {
        switch hour {
        case 0: return "12am"
        case 1..<12: return "\(hour)am"
        case 12: return "12pm"
        default: return "\(hour - 12)pm"
        }
    }

    // MARK: - Internals (also used by tests)

    static func observe(_ key: String, choice: String, at date: Date) {
        let trimmedKey = normalize(key)
        let trimmedChoice = choice.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedKey.isEmpty, !trimmedChoice.isEmpty else { return }
        queue.sync {
            loadIfNeeded()
            var options = signals[trimmedKey] ?? [:]
            var signal = options[trimmedChoice] ?? Signal(weight: 0, count: 0, lastAt: date)
            signal.weight = decayed(signal.weight, from: signal.lastAt, to: date) + 1.0
            signal.count += 1
            signal.lastAt = date
            options[trimmedChoice] = signal
            signals[trimmedKey] = options
            save()
        }
    }

    static func record(_ name: String, at date: Date, count: Int) {
        let key = normalize(name)
        guard !key.isEmpty else { return }
        queue.sync {
            loadIfNeeded()
            var event = events[key] ?? Event(
                name: key, total: 0, firstAt: date, lastAt: date, daily: [:], hourly: [:]
            )
            event.record(at: date, count: count)
            event.prune(keeping: retentionDays, from: date)
            events[key] = event
            save()
        }
    }

    static func recommendations(limit: Int, now: Date) -> [Recommendation] {
        queue.sync {
            loadIfNeeded()
            var recs: [Recommendation] = []
            let hour = Calendar.current.component(.hour, from: now)

            for habit in lockedHabits(now: now) {
                let strength = habit.strength(from: now)
                if habit.isFading(from: now) {
                    let previous = habit.activeDays(
                        last: 28, from: now.addingTimeInterval(-28 * 86_400)
                    )
                    let current = habit.activeDays(last: 28, from: now)
                    recs.append(Recommendation(
                        kind: .fadingHabit,
                        subject: habit.name,
                        title: "“\(habit.name)” is fading",
                        detail: "Active \(previous) days the month before, \(current) in "
                            + "the last 28. Worth resurfacing?",
                        score: 0.35
                    ))
                }
                guard strength >= 0.3,
                      !habit.doneToday(from: now),
                      now.timeIntervalSince(habit.lastAt) <= 14 * 86_400
                else { continue }

                let typical = habit.typicalHour()
                if let typical, hourDistance(hour, typical) <= 1 {
                    recs.append(Recommendation(
                        kind: .habitDue,
                        subject: habit.name,
                        title: "“\(habit.name)” usually happens about now",
                        detail: "Most often around \(hourLabel(typical)) — nothing "
                            + "logged yet today.",
                        score: 0.7 + 0.2 * strength
                    ))
                }
                let streak = habit.currentStreakDays(from: now)
                let pastUsualTime = typical.map { hour > $0 } ?? (hour >= 18)
                if streak >= 3, pastUsualTime {
                    recs.append(Recommendation(
                        kind: .streakAtRisk,
                        subject: habit.name,
                        title: "\(streak)-day “\(habit.name)” streak on the line",
                        detail: "Nothing logged today yet — one more keeps it alive.",
                        score: 0.9 + min(0.1, Double(streak) / 100.0)
                    ))
                }
            }

            for key in signals.keys {
                guard let learned = lockedPreferred(key, now: now),
                      learned.confidence >= 0.55
                else { continue }
                recs.append(Recommendation(
                    kind: .learnedDefault,
                    subject: key,
                    title: "Default \(key) to “\(learned.top.value)”",
                    detail: "Chosen \(Int((learned.top.share * 100).rounded()))% of the "
                        + "time across \(learned.observations) choices.",
                    score: 0.3 + 0.5 * learned.confidence
                ))
            }

            return Array(recs.sorted { $0.score > $1.score }.prefix(limit))
        }
    }

    static func profile(now: Date) -> UserProfile {
        queue.sync {
            loadIfNeeded()
            let snapshots = events.values.map(\.snapshot)

            // One combined activity calendar across every recorded action.
            var unionDaily: [String: Int] = [:]
            var unionHourly: [Int: Int] = [:]
            for habit in snapshots {
                for (day, count) in habit.daily { unionDaily[day, default: 0] += count }
                for (hour, count) in habit.hourly { unionHourly[hour, default: 0] += count }
            }
            let union = Habit(
                name: "", total: snapshots.reduce(0) { $0 + $1.total },
                firstAt: since, lastAt: now, daily: unionDaily, hourly: unionHourly
            )

            let daysKnown = Int(now.timeIntervalSince(since) / 86_400) + 1
            let activeDays28 = union.activeDays(last: 28, from: now)
            let engagement: Engagement
            switch (daysKnown, activeDays28) {
            case (..<7, _): engagement = .new
            case (_, 20...): engagement = .power
            case (_, 8...): engagement = .regular
            default: engagement = .casual
            }

            let hourlyTotal = unionHourly.values.reduce(0, +)
            var peakPart: DayPart?
            if hourlyTotal >= 10 {
                var byPart: [DayPart: Int] = [:]
                for (hour, count) in unionHourly {
                    byPart[DayPart.of(hour: hour), default: 0] += count
                }
                if let best = byPart.max(by: { $0.value < $1.value }),
                   Double(best.value) / Double(hourlyTotal) >= 0.4 {
                    peakPart = best.key
                }
            }

            let activeDayKeys = unionDaily.keys
            let weekendDays = activeDayKeys.filter { key in
                guard let date = parseDayKey(key) else { return false }
                let weekday = Calendar.current.component(.weekday, from: date)
                return weekday == 1 || weekday == 7
            }.count
            let weekendLeaning = activeDayKeys.count >= 6
                && Double(weekendDays) / Double(activeDayKeys.count) >= 0.5

            return UserProfile(
                firstSeenAt: since,
                daysKnown: daysKnown,
                activeDays28: activeDays28,
                eventsTotal: union.total,
                engagement: engagement,
                peakPart: peakPart,
                weekendLeaning: weekendLeaning,
                currentStreakDays: streakDays(keys: Set(unionDaily.keys), from: now),
                habitCount: snapshots.filter { $0.isHabit(from: now) }.count,
                learnedCount: signals.filter { !$0.value.isEmpty }.count,
                preferenceCount: prefs.count
            )
        }
    }

    /// Local calendar day as "yyyy-MM-dd".
    static func dayKey(for date: Date) -> String {
        dayFormatter.string(from: date)
    }

    static func parseDayKey(_ key: String) -> Date? {
        dayFormatter.date(from: key)
    }

    /// Consecutive day keys present in `keys`, counting back from `from`'s day.
    static func streakDays(keys: Set<String>, from reference: Date) -> Int {
        let calendar = Calendar.current
        var day = reference
        if !keys.contains(dayKey(for: day)) {
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else {
                return 0
            }
            day = previous
        }
        var streak = 0
        while keys.contains(dayKey(for: day)) {
            streak += 1
            guard let previous = calendar.date(byAdding: .day, value: -1, to: day) else { break }
            day = previous
        }
        return streak
    }

    // MARK: - Locked helpers (call only from inside queue.sync)

    private static func lockedHabits(now: Date) -> [Habit] {
        events.values.map(\.snapshot).sorted {
            let lhs = $0.strength(from: now)
            let rhs = $1.strength(from: now)
            return lhs != rhs ? lhs > rhs : $0.total > $1.total
        }
    }

    private static func lockedPreferred(_ key: String, now: Date) -> Learned? {
        guard let options = signals[normalize(key)], !options.isEmpty else { return nil }
        let weights = options.map { (value: $0.key, weight: decayed($0.value.weight, from: $0.value.lastAt, to: now), signal: $0.value) }
        let total = weights.reduce(0.0) { $0 + $1.weight }
        guard total > 0 else { return nil }
        let choices = weights
            .map {
                LearnedChoice(
                    value: $0.value, share: $0.weight / total,
                    count: $0.signal.count, lastAt: $0.signal.lastAt
                )
            }
            .sorted { $0.share != $1.share ? $0.share > $1.share : $0.value < $1.value }
        let observations = options.values.reduce(0) { $0 + $1.count }
        let confidence = choices[0].share * min(1.0, Double(observations) / 5.0)
        return Learned(
            key: normalize(key), choices: choices,
            observations: observations, confidence: confidence
        )
    }

    private static func putPref(_ key: String, _ value: PreferenceValue) {
        let trimmed = normalize(key)
        guard !trimmed.isEmpty else { return }
        queue.sync {
            loadIfNeeded()
            prefs[trimmed] = Pref(value: value, updatedAt: Date())
            save()
        }
    }

    private static func pref(_ key: String) -> PreferenceValue? {
        queue.sync {
            loadIfNeeded()
            return prefs[normalize(key)]?.value
        }
    }

    private static func decayed(_ weight: Double, from: Date, to: Date) -> Double {
        let interval = to.timeIntervalSince(from)
        guard interval > 0 else { return weight }
        let days = interval / 86_400
        return weight * pow(0.5, days / choiceHalfLifeDays)
    }

    private static func hourDistance(_ a: Int, _ b: Int) -> Int {
        let diff = abs(a - b)
        return min(diff, 24 - diff)
    }

    private static func normalize(_ name: String) -> String {
        name.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func millis(from date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded())
    }

    private static func date(fromMillis value: Any?) -> Date? {
        guard let number = value as? NSNumber else { return nil }
        return Date(timeIntervalSince1970: number.doubleValue / 1000)
    }

    // MARK: - Storage

    private static func fileURL() -> URL? {
        let directory: URL
        if let storageDirectory {
            directory = storageDirectory
        } else {
            guard let support = FileManager.default.urls(
                for: .applicationSupportDirectory, in: .userDomainMask
            ).first else { return nil }
            directory = support.appendingPathComponent("UserMemory", isDirectory: true)
        }
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("memory.json")
    }

    /// The cross-platform storage schema, epoch-millis timestamps throughout.
    /// Must be called from inside queue.sync.
    private static func storageObject() -> [String: Any] {
        var prefsObj: [String: Any] = [:]
        for (key, pref) in prefs {
            var entry: [String: Any] = ["at": millis(from: pref.updatedAt)]
            switch pref.value {
            case .string(let value):
                entry["t"] = "s"
                entry["v"] = value
            case .bool(let value):
                entry["t"] = "b"
                entry["v"] = value
            case .int(let value):
                entry["t"] = "i"
                entry["v"] = value
            case .double(let value):
                entry["t"] = "d"
                entry["v"] = value
            case .strings(let value):
                entry["t"] = "l"
                entry["v"] = value
            }
            prefsObj[key] = entry
        }

        var signalsObj: [String: Any] = [:]
        for (key, options) in signals {
            var optionsObj: [String: Any] = [:]
            for (choice, signal) in options {
                optionsObj[choice] = [
                    "w": signal.weight,
                    "n": signal.count,
                    "at": millis(from: signal.lastAt),
                ] as [String: Any]
            }
            signalsObj[key] = optionsObj
        }

        let eventsArr: [[String: Any]] = events.values.map { event in
            [
                "name": event.name,
                "total": event.total,
                "firstAt": millis(from: event.firstAt),
                "lastAt": millis(from: event.lastAt),
                "daily": event.daily,
                "hourly": Dictionary(uniqueKeysWithValues: event.hourly.map { (String($0.key), $0.value) }),
            ]
        }

        return [
            "version": schemaVersion,
            "since": millis(from: since),
            "prefs": prefsObj,
            "signals": signalsObj,
            "events": eventsArr,
        ]
    }

    /// Replaces in-memory state from a parsed storage object. Returns false
    /// (leaving state untouched) when the shape is not understood.
    /// Must be called from inside queue.sync.
    private static func apply(storage root: [String: Any]) -> Bool {
        var newPrefs: [String: Pref] = [:]
        for (key, raw) in root["prefs"] as? [String: Any] ?? [:] {
            guard let entry = raw as? [String: Any] else { return false }
            let updatedAt = date(fromMillis: entry["at"]) ?? Date()
            let value: PreferenceValue
            switch entry["t"] as? String ?? "s" {
            case "b":
                guard let v = entry["v"] as? Bool else { return false }
                value = .bool(v)
            case "i":
                guard let v = entry["v"] as? NSNumber else { return false }
                value = .int(v.intValue)
            case "d":
                guard let v = entry["v"] as? NSNumber else { return false }
                value = .double(v.doubleValue)
            case "l":
                guard let v = entry["v"] as? [String] else { return false }
                value = .strings(v)
            default:
                guard let v = entry["v"] as? String else { return false }
                value = .string(v)
            }
            newPrefs[key] = Pref(value: value, updatedAt: updatedAt)
        }

        var newSignals: [String: [String: Signal]] = [:]
        for (key, raw) in root["signals"] as? [String: Any] ?? [:] {
            guard let optionsObj = raw as? [String: Any] else { return false }
            var options: [String: Signal] = [:]
            for (choice, rawSignal) in optionsObj {
                guard let entry = rawSignal as? [String: Any],
                      let weight = entry["w"] as? NSNumber,
                      let count = entry["n"] as? NSNumber,
                      let lastAt = date(fromMillis: entry["at"])
                else { return false }
                options[choice] = Signal(
                    weight: weight.doubleValue, count: count.intValue, lastAt: lastAt
                )
            }
            if !options.isEmpty { newSignals[key] = options }
        }

        var newEvents: [String: Event] = [:]
        let now = Date()
        for raw in root["events"] as? [Any] ?? [] {
            guard let obj = raw as? [String: Any],
                  let name = obj["name"] as? String,
                  let total = obj["total"] as? NSNumber,
                  let firstAt = date(fromMillis: obj["firstAt"]),
                  let lastAt = date(fromMillis: obj["lastAt"])
            else { return false }
            var daily: [String: Int] = [:]
            for (day, count) in obj["daily"] as? [String: Any] ?? [:] {
                guard let count = count as? NSNumber else { return false }
                daily[day] = count.intValue
            }
            var hourly: [Int: Int] = [:]
            for (hourKey, count) in obj["hourly"] as? [String: Any] ?? [:] {
                guard let hour = Int(hourKey), let count = count as? NSNumber else { continue }
                hourly[hour] = count.intValue
            }
            var event = Event(
                name: name, total: total.intValue, firstAt: firstAt, lastAt: lastAt,
                daily: daily, hourly: hourly
            )
            event.prune(keeping: retentionDays, from: now)
            newEvents[name] = event
        }

        prefs = newPrefs
        signals = newSignals
        events = newEvents
        if let storedSince = date(fromMillis: root["since"]) {
            since = storedSince
        }
        return true
    }

    private static func loadIfNeeded() {
        guard !loaded else { return }
        loaded = true
        since = Date()
        guard let url = fileURL(),
              let data = try? Data(contentsOf: url),
              let object = try? JSONSerialization.jsonObject(with: data),
              let root = object as? [String: Any]
        else { return }
        _ = apply(storage: root)
    }

    private static func save() {
        guard let url = fileURL() else { return }
        guard let data = try? JSONSerialization.data(withJSONObject: storageObject()) else {
            return
        }
        try? data.write(to: url, options: .atomic)
    }
}
