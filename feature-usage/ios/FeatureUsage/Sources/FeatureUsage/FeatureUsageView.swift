#if canImport(SwiftUI)
import SwiftUI
import Combine

/// Ready-made screen showing everything the SDK has recorded, in three tabs:
/// Features (list + charts), Timeline (recent events) and Insights (generated
/// findings). Drop it into a debug menu:
///
///     NavigationLink("Feature usage") { FeatureUsageView() }
///
/// Follows the host app's tint and light/dark mode automatically and
/// live-updates while visible. Tap any feature for a detail view.
@available(iOS 15, macOS 13, *)
public struct FeatureUsageView: View {
    private enum ScreenTab: String, CaseIterable {
        case features = "Features"
        case timeline = "Timeline"
        case insights = "Insights"
    }

    private enum SortMode: String, CaseIterable {
        case mostUsed = "Top"
        case recent = "Recent"
        case trending = "Trend"
        case name = "A–Z"
    }

    @State private var stats: [FeatureStat] = []
    @State private var now = Date()
    @State private var screenTab: ScreenTab = .features
    @State private var query = ""
    @State private var sortMode: SortMode = .mostUsed
    @State private var activityRange = 30
    @State private var confirmingReset = false
    @State private var selected: FeatureStat?

    public init() {}

    private var visibleStats: [FeatureStat] {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let filtered = trimmed.isEmpty
            ? stats
            : stats.filter { $0.name.localizedCaseInsensitiveContains(trimmed) }
        switch sortMode {
        case .mostUsed:
            return filtered
        case .recent:
            return filtered.sorted { $0.lastUsedAt > $1.lastUsedAt }
        case .trending:
            return filtered.sorted {
                let a = $0.count(lastDays: 7, from: now)
                let b = $1.count(lastDays: 7, from: now)
                return a != b ? a > b : $0.total > $1.total
            }
        case .name:
            return filtered.sorted { $0.name.lowercased() < $1.name.lowercased() }
        }
    }

    // stats is already most-used first, so rank by position there.
    private var ranks: [String: Int] {
        Dictionary(uniqueKeysWithValues: stats.enumerated().map { ($0.element.name, $0.offset + 1) })
    }

    private var totalEvents: Int { stats.reduce(0) { $0 + $1.total } }
    private var eventsLast7Days: Int { stats.reduce(0) { $0 + $1.count(lastDays: 7, from: now) } }
    private var eventsToday: Int { stats.reduce(0) { $0 + $1.count(lastDays: 1, from: now) } }
    private var maxTotal: Int { stats.map(\.total).max() ?? 1 }

    /// Sum of every feature's change over the last 7 days vs the 7 before; nil without a baseline.
    private var overallTrend7: Int? {
        let previousEnd = now.addingTimeInterval(-7 * 86_400)
        let previous = stats.reduce(0) { $0 + $1.count(lastDays: 7, from: previousEnd) }
        guard previous > 0 else { return nil }
        return Int((Double(eventsLast7Days - previous) * 100.0 / Double(previous)).rounded())
    }

    /// App-wide events per day for the selected activity range.
    private var activityCounts: [Int] {
        let perFeature = stats.map { $0.dailyCounts(lastDays: activityRange, from: now) }
        return (0..<activityRange).map { day in perFeature.reduce(0) { $0 + $1[day] } }
    }

    private var eventGroups: [(String, [UsageEvent])] {
        var order: [String] = []
        var groups: [String: [UsageEvent]] = [:]
        for event in FeatureUsage.recentEvents(limit: 200) {
            let key = FeatureUsage.dayKey(for: event.at)
            if groups[key] == nil { order.append(key) }
            groups[key, default: []].append(event)
        }
        return order.map { ($0, groups[$0] ?? []) }
    }

    public var body: some View {
        List {
            if stats.isEmpty {
                emptyState
            } else {
                Section {
                    Picker("Tab", selection: $screenTab) {
                        ForEach(ScreenTab.allCases, id: \.self) { Text($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    .listRowSeparator(.hidden)
                }
                switch screenTab {
                case .features: featureContent
                case .timeline: timelineContent
                case .insights: insightContent
                }
            }
        }
        .navigationTitle("Feature usage")
        .toolbar {
            Menu {
                if #available(iOS 16, macOS 13, *) {
                    ShareLink(item: FeatureUsage.exportJSON()) { Label("Share JSON", systemImage: "square.and.arrow.up") }
                    ShareLink(item: FeatureUsage.exportCSV()) { Label("Share CSV", systemImage: "tablecells") }
                }
                Button {
                    copyToPasteboard(FeatureUsage.exportJSON())
                } label: {
                    Label("Copy JSON", systemImage: "doc.on.doc")
                }
                Button {
                    copyToPasteboard(FeatureUsage.exportCSV())
                } label: {
                    Label("Copy CSV", systemImage: "doc.plaintext")
                }
                Button {
                    if let pasted = pasteboardString() { FeatureUsage.importJSON(pasted) }
                } label: {
                    Label("Import from clipboard", systemImage: "square.and.arrow.down")
                }
                Button(role: .destructive) {
                    confirmingReset = true
                } label: {
                    Label("Reset all data", systemImage: "trash")
                }
            } label: {
                Image(systemName: "ellipsis.circle")
            }
        }
        .confirmationDialog(
            "Delete all recorded feature usage?",
            isPresented: $confirmingReset,
            titleVisibility: .visible
        ) {
            Button("Delete everything", role: .destructive) { FeatureUsage.reset() }
        }
        .sheet(item: $selected) { stat in
            FeatureDetailView(stat: stat, now: now) {
                FeatureUsage.reset(stat.name)
                selected = nil
            }
        }
        .refreshable { refresh() }
        .onAppear { refresh() }
        .onReceive(
            NotificationCenter.default
                .publisher(for: FeatureUsage.didChangeNotification)
                .receive(on: DispatchQueue.main)
        ) { _ in refresh() }
    }

    private func refresh() {
        stats = FeatureUsage.stats()
        now = Date()
    }

    // MARK: Features tab

    @ViewBuilder
    private var featureContent: some View {
        Section {
            summaryTiles
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                .listRowSeparator(.hidden)
            activityCard
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 12, trailing: 16))
                .listRowSeparator(.hidden)
        }
        Section {
            searchField
                .listRowSeparator(.hidden)
            Picker("Sort", selection: $sortMode) {
                ForEach(SortMode.allCases, id: \.self) { Text($0.rawValue) }
            }
            .pickerStyle(.segmented)
            .listRowSeparator(.hidden)

            if visibleStats.isEmpty {
                Text("No features match \"\(query.trimmingCharacters(in: .whitespaces))\"")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .listRowSeparator(.hidden)
            }
            ForEach(visibleStats) { stat in
                FeatureRow(
                    stat: stat,
                    rank: ranks[stat.name] ?? 0,
                    maxTotal: maxTotal,
                    totalEvents: totalEvents,
                    now: now
                )
                .contentShape(Rectangle())
                .onTapGesture { selected = stat }
            }
        } footer: {
            Text("Mini bars show the last 7 days, today rightmost. Tap a feature for details. Data lives only on this device.")
        }
    }

    private var summaryTiles: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                StatTile(value: "\(stats.count)", label: "Features")
                StatTile(value: "\(totalEvents)", label: "Events")
            }
            HStack(spacing: 8) {
                StatTile(value: "\(eventsLast7Days)", label: "Last 7 days", trend: overallTrend7)
                StatTile(value: "\(eventsToday)", label: "Today")
            }
        }
    }

    private var activityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Activity · \(activityCounts.reduce(0, +)) events")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                HStack(spacing: 8) {
                    ForEach([7, 30, 90], id: \.self) { range in
                        Button {
                            activityRange = range
                        } label: {
                            Text("\(range)d")
                                .font(.caption.weight(activityRange == range ? .semibold : .regular))
                                .foregroundStyle(activityRange == range ? Color.accentColor : Color.secondary)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            BarChartView(counts: activityCounts, height: 56, emphasizeLast: true)
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }

    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(.secondary)
            TextField("Search features", text: $query)
            if !query.isEmpty {
                Button {
                    query = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
    }

    // MARK: Timeline tab

    @ViewBuilder
    private var timelineContent: some View {
        let groups = eventGroups
        if groups.isEmpty {
            Section {
                Text("No recent events. The last 500 uses show up here as they happen.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .listRowSeparator(.hidden)
            }
        }
        ForEach(groups, id: \.0) { day, events in
            Section(dayLabel(day)) {
                ForEach(Array(events.enumerated()), id: \.offset) { _, event in
                    HStack {
                        Text(event.at.formatted(date: .omitted, time: .shortened))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospacedDigit()
                            .frame(width: 64, alignment: .leading)
                        Text(event.name)
                            .font(.subheadline)
                            .lineLimit(1)
                    }
                }
            }
        }
    }

    private func dayLabel(_ dayKey: String) -> String {
        if dayKey == FeatureUsage.dayKey(for: now) { return "Today" }
        if dayKey == FeatureUsage.dayKey(for: now.addingTimeInterval(-86_400)) { return "Yesterday" }
        guard let date = FeatureUsage.date(fromDayKey: dayKey) else { return dayKey }
        let formatter = DateFormatter()
        formatter.dateFormat = "EEE, MMM d"
        return formatter.string(from: date)
    }

    // MARK: Insights tab

    @ViewBuilder
    private var insightContent: some View {
        let list = FeatureUsage.insights(from: now)
        if list.isEmpty {
            Section {
                Text("Not enough data yet — insights appear as usage accumulates.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .listRowSeparator(.hidden)
            }
        }
        Section {
            ForEach(list) { insight in
                InsightCard(insight: insight)
                    .listRowSeparator(.hidden)
            }
        }
    }

    // MARK: Misc

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "chart.bar.xaxis")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("No usage recorded yet")
                .font(.headline)
            Text("Call FeatureUsage.track(\"feature_name\") where a feature is used. Everything tracked shows up here, with charts, a timeline and insights.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 32)
        .listRowSeparator(.hidden)
    }

    private func copyToPasteboard(_ string: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = string
        #elseif canImport(AppKit)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(string, forType: .string)
        #endif
    }

    private func pasteboardString() -> String? {
        #if canImport(UIKit)
        return UIPasteboard.general.string
        #elseif canImport(AppKit)
        return NSPasteboard.general.string(forType: .string)
        #else
        return nil
        #endif
    }
}

private func formatDurationMs(_ ms: Int) -> String {
    let s = ms / 1000
    switch s {
    case ..<60: return "\(s)s"
    case ..<3_600: return "\(s / 60)m \(String(format: "%02d", s % 60))s"
    case ..<86_400: return "\(s / 3_600)h \((s % 3_600) / 60)m"
    default: return "\(s / 86_400)d \((s % 86_400) / 3_600)h"
    }
}

@available(iOS 15, macOS 13, *)
private struct InsightCard: View {
    let insight: UsageInsight

    private var kindLabel: String {
        switch insight.kind {
        case .rising: return "TRENDING UP"
        case .falling: return "TRENDING DOWN"
        case .stale: return "STALE"
        case .streak: return "STREAK"
        case .peakHour: return "PEAK HOUR"
        case .peakDay: return "PEAK DAY"
        case .concentration: return "FOCUS"
        case .new: return "NEW"
        }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            TagBadge(text: kindLabel)
            Text(insight.title)
                .font(.subheadline.weight(.semibold))
            Text(insight.detail)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

@available(iOS 15, macOS 13, *)
private struct StatTile: View {
    let value: String
    let label: String
    var trend: Int?

    var body: some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title2.weight(.semibold))
                .monospacedDigit()
            HStack(spacing: 4) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                TrendLabel(percent: trend)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// ▲/▼ plus percentage — glyph carries the direction, color reinforces it.
@available(iOS 15, macOS 13, *)
private struct TrendLabel: View {
    let percent: Int?

    var body: some View {
        if let percent {
            Text("\(symbol(percent)) \(abs(percent))%")
                .font(.caption2.weight(.semibold))
                .foregroundStyle(color(percent))
                .monospacedDigit()
        }
    }

    private func symbol(_ percent: Int) -> String {
        percent > 0 ? "▲" : percent < 0 ? "▼" : "•"
    }

    private func color(_ percent: Int) -> Color {
        percent > 0 ? .green : percent < 0 ? .red : .secondary
    }
}

@available(iOS 15, macOS 13, *)
private struct TagBadge: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 5)
            .padding(.vertical, 1)
            .background(Color.secondary.opacity(0.15), in: Capsule())
    }
}

@available(iOS 15, macOS 13, *)
private struct FeatureRow: View {
    let stat: FeatureStat
    let rank: Int
    let maxTotal: Int
    let totalEvents: Int
    let now: Date

    private var stale: Bool { stat.isStale(days: 30, from: now) }
    private var isNew: Bool { now.timeIntervalSince(stat.firstUsedAt) < 7 * 86_400 }
    private var sharePercent: Int { totalEvents > 0 ? stat.total * 100 / totalEvents : 0 }

    private var footnote: String {
        var text = "Last used \(stat.lastUsedAt.formatted(.relative(presentation: .named)))"
        text += " · \(stat.activeDays) active \(stat.activeDays == 1 ? "day" : "days")"
        if stat.totalDurationMs > 0 { text += " · \(formatDurationMs(stat.totalDurationMs))" }
        return text
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text("#\(rank)")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                Text(stat.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                if isNew {
                    TagBadge(text: "NEW")
                }
                if stale {
                    TagBadge(text: "UNUSED 30d+")
                }
                Spacer()
                TrendLabel(percent: stat.trendPercent(days: 7, from: now))
                Text("\(stat.total)")
                    .font(.subheadline.weight(.semibold))
                    .monospacedDigit()
            }
            HStack(spacing: 12) {
                UsageBar(fraction: maxTotal > 0 ? Double(stat.total) / Double(maxTotal) : 0)
                Text("\(sharePercent)%")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
                Sparkline(counts: stat.dailyCounts(lastDays: 7, from: now))
            }
            Text(footnote)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
        .opacity(stale ? 0.65 : 1)
    }
}

/// Detail view for one feature: charts, streaks, and per-feature actions.
@available(iOS 15, macOS 13, *)
private struct FeatureDetailView: View {
    let stat: FeatureStat
    let now: Date
    let onDeleted: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var confirmingDelete = false

    var body: some View {
        NavigationView {
            List {
                Section {
                    statGrid
                        .listRowInsets(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
                        .listRowSeparator(.hidden)
                }
                Section {
                    chartCard(title: "Last 30 days") {
                        BarChartView(counts: stat.dailyCounts(lastDays: 30, from: now), height: 64, emphasizeLast: true)
                    }
                    chartCard(title: "Last 12 weeks") {
                        CalendarHeatmap(stat: stat, now: now)
                    }
                    chartCard(title: "By hour of day") {
                        BarChartView(counts: stat.hourlyCounts(), height: 48)
                        HStack {
                            ForEach(Array(["0", "6", "12", "18", "23"].enumerated()), id: \.offset) { index, hour in
                                Text(hour)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                if index < 4 { Spacer() }
                            }
                        }
                    }
                    chartCard(title: "By day of week") {
                        BarChartView(counts: stat.weekdayCounts(), height: 48)
                        HStack {
                            ForEach(Array(["M", "T", "W", "T", "F", "S", "S"].enumerated()), id: \.offset) { _, day in
                                Text(day)
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }
                .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 16))
                .listRowSeparator(.hidden)
                Section {
                    detailLine("First used", stat.firstUsedAt.formatted(.relative(presentation: .named)))
                    detailLine("Last used", stat.lastUsedAt.formatted(.relative(presentation: .named)))
                }
                Section {
                    Button(role: .destructive) {
                        confirmingDelete = true
                    } label: {
                        Text("Delete this feature's data")
                    }
                }
            }
            .navigationTitle(stat.name)
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .confirmationDialog(
                "Delete recorded usage for \"\(stat.name)\"?",
                isPresented: $confirmingDelete,
                titleVisibility: .visible
            ) {
                Button("Delete", role: .destructive) { onDeleted() }
            }
        }
    }

    private var statGrid: some View {
        VStack(spacing: 8) {
            HStack(spacing: 8) {
                StatCell(label: "Total", value: "\(stat.total)")
                StatCell(label: "Today", value: "\(stat.count(lastDays: 1, from: now))")
            }
            HStack(spacing: 8) {
                StatCell(
                    label: "Last 7 days",
                    value: "\(stat.count(lastDays: 7, from: now))",
                    trend: stat.trendPercent(days: 7, from: now)
                )
                StatCell(label: "Last 30 days", value: "\(stat.count(lastDays: 30, from: now))")
            }
            HStack(spacing: 8) {
                StatCell(label: "Active days", value: "\(stat.activeDays)")
                StatCell(label: "Avg / active day", value: String(format: "%.1f", stat.averagePerActiveDay()))
            }
            HStack(spacing: 8) {
                StatCell(label: "Streak", value: "\(stat.currentStreakDays(from: now))d")
                StatCell(label: "Best streak", value: "\(stat.bestStreakDays())d")
            }
            if stat.timedSessions > 0 {
                HStack(spacing: 8) {
                    StatCell(label: "Time spent", value: formatDurationMs(stat.totalDurationMs))
                    StatCell(label: "Avg session", value: formatDurationMs(stat.averageSessionMs))
                }
            }
        }
    }

    @ViewBuilder
    private func chartCard(title: String, @ViewBuilder content: () -> some View) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            content()
        }
        .padding(12)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }

    private func detailLine(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .font(.caption)
        }
    }
}

@available(iOS 15, macOS 13, *)
private struct StatCell: View {
    let label: String
    let value: String
    var trend: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(value)
                    .font(.headline)
                    .monospacedDigit()
                TrendLabel(percent: trend)
            }
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 10))
    }
}

/// 12-week calendar heatmap: columns are weeks, rows Monday–Sunday, cell
/// intensity scaled to the busiest day.
@available(iOS 15, macOS 13, *)
private struct CalendarHeatmap: View {
    let stat: FeatureStat
    let now: Date

    var body: some View {
        let days = 84
        let counts = stat.dailyCounts(lastDays: days, from: now)
        let maxCount = max(counts.max() ?? 0, 1)
        let calendar = Calendar.current
        let start = calendar.date(byAdding: .day, value: -(days - 1), to: now) ?? now
        let pad = (calendar.component(.weekday, from: start) + 5) % 7
        let cols = (pad + days + 6) / 7
        HStack(alignment: .top, spacing: 3) {
            ForEach(0..<cols, id: \.self) { col in
                VStack(spacing: 3) {
                    ForEach(0..<7, id: \.self) { row in
                        let index = col * 7 + row - pad
                        RoundedRectangle(cornerRadius: 2)
                            .fill(cellColor(index: index, counts: counts, maxCount: maxCount, days: days))
                            .frame(width: 10, height: 10)
                    }
                }
            }
        }
        .accessibilityElement()
        .accessibilityLabel("12-week usage heatmap")
    }

    private func cellColor(index: Int, counts: [Int], maxCount: Int, days: Int) -> Color {
        if index < 0 || index >= days { return .clear }
        if counts[index] == 0 { return Color.secondary.opacity(0.12) }
        return Color.accentColor.opacity(0.3 + 0.7 * Double(counts[index]) / Double(maxCount))
    }
}

/// Horizontal bar showing this feature's share of the most-used feature.
@available(iOS 15, macOS 13, *)
private struct UsageBar: View {
    let fraction: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.secondary.opacity(0.12))
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.accentColor)
                    .frame(width: max(6, proxy.size.width * fraction))
            }
        }
        .frame(height: 6)
    }
}

/// Bars scaled to the max count, oldest/first leftmost, baseline-anchored.
/// With `emphasizeLast`, the last bar (today) is full-strength and the rest recede.
@available(iOS 15, macOS 13, *)
private struct BarChartView: View {
    let counts: [Int]
    var height: CGFloat = 56
    var emphasizeLast = false

    var body: some View {
        let maxCount = max(counts.max() ?? 0, 1)
        HStack(alignment: .bottom, spacing: counts.count > 45 ? 1 : 2) {
            ForEach(counts.indices, id: \.self) { index in
                RoundedRectangle(cornerRadius: 2)
                    .fill(Color.accentColor.opacity(!emphasizeLast || index == counts.count - 1 ? 1 : 0.45))
                    .frame(maxWidth: .infinity)
                    .frame(
                        height: counts[index] == 0
                            ? 2
                            : max(4, height * CGFloat(counts[index]) / CGFloat(maxCount))
                    )
            }
        }
        .frame(height: height, alignment: .bottom)
        .accessibilityElement()
        .accessibilityLabel("Bar chart: \(counts.map(String.init).joined(separator: ", "))")
    }
}

/// Last 7 days as mini bars, oldest first; today (rightmost) is emphasized.
@available(iOS 15, macOS 13, *)
private struct Sparkline: View {
    let counts: [Int]

    var body: some View {
        let maxCount = max(counts.max() ?? 0, 1)
        HStack(alignment: .bottom, spacing: 2) {
            ForEach(counts.indices, id: \.self) { index in
                RoundedRectangle(cornerRadius: 1.5)
                    .fill(Color.accentColor.opacity(index == counts.count - 1 ? 1 : 0.35))
                    .frame(
                        width: 4,
                        height: counts[index] == 0
                            ? 2
                            : max(4, 16 * CGFloat(counts[index]) / CGFloat(maxCount))
                    )
            }
        }
        .frame(height: 16, alignment: .bottom)
        .accessibilityLabel("Last 7 days: \(counts.map(String.init).joined(separator: ", "))")
    }
}
#endif
