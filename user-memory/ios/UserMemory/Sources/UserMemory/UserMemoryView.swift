#if canImport(SwiftUI)
import SwiftUI

/// Ready-made "what this app knows about you" screen: profile, suggestions,
/// habits, learned choices and explicit preferences, with export and reset.
/// Drop it into a settings or debug destination:
///
///     NavigationLink("User memory") { UserMemoryView() }
///
/// Follows the host app's tint and light/dark mode automatically.
@available(iOS 15, macOS 13, *)
public struct UserMemoryView: View {
    @State private var profile = UserMemory.profile()
    @State private var recommendations: [Recommendation] = []
    @State private var habits: [Habit] = []
    @State private var learned: [Learned] = []
    @State private var preferences: [Preference] = []
    @State private var confirmingReset = false
    private let now = Date()

    public init() {}

    private var isEmpty: Bool {
        habits.isEmpty && learned.isEmpty && preferences.isEmpty
    }

    public var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                heroCard
                if isEmpty {
                    emptyState
                } else {
                    if !recommendations.isEmpty {
                        sectionHeader("Suggestions")
                        SectionCard(items: recommendations) { SuggestionRow(rec: $0) }
                    }
                    if !habits.isEmpty {
                        sectionHeader("Habits")
                        SectionCard(items: habits) { HabitRow(habit: $0, now: now) }
                    }
                    if !learned.isEmpty {
                        sectionHeader("Learned from choices")
                        SectionCard(items: learned) { LearnedRow(learned: $0) }
                    }
                    if !preferences.isEmpty {
                        sectionHeader("Preferences")
                        SectionCard(items: preferences) { PreferenceRow(pref: $0, now: now) }
                    }
                    Text("Everything above lives in one JSON file inside this app on "
                         + "this device. Nothing is sent anywhere. Share JSON to move it "
                         + "to another device or app; forget everything to start over.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, 4)
                }
            }
            .padding(16)
        }
        .navigationTitle("User memory")
        .confirmationDialog(
            "Forget everything?",
            isPresented: $confirmingReset,
            titleVisibility: .visible
        ) {
            Button("Forget everything", role: .destructive) {
                UserMemory.reset()
                refresh()
            }
        } message: {
            Text("Deletes all preferences, learned choices and habit history on this device.")
        }
        .onAppear(perform: refresh)
    }

    private func refresh() {
        profile = UserMemory.profile()
        recommendations = UserMemory.recommendations()
        habits = UserMemory.habits()
        learned = UserMemory.learned()
        preferences = UserMemory.preferences()
    }

    // MARK: Hero

    private var heroCard: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text("USER MEMORY")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                Spacer()
                menu
            }
            Text(profile.engagement.label)
                .font(.title2.weight(.bold))
            Text(heroSubtitle)
                .font(.caption)
                .foregroundStyle(.secondary)
            if !heroChips.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(heroChips, id: \.self) { chip in
                            Text(chip)
                                .font(.caption.weight(.medium))
                                .padding(.horizontal, 10)
                                .padding(.vertical, 4)
                                .background(Color.accentColor.opacity(0.14), in: Capsule())
                        }
                    }
                }
                .padding(.top, 8)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.accentColor.opacity(0.12), in: RoundedRectangle(cornerRadius: 20))
    }

    private var heroSubtitle: String {
        let known = profile.daysKnown == 1 ? "since today" : "for \(profile.daysKnown) days"
        let days = profile.activeDays28 == 1 ? "day" : "days"
        return "With you \(known) · \(profile.activeDays28) active \(days) in the last 4 weeks"
    }

    private var heroChips: [String] {
        var chips: [String] = []
        if let part = profile.peakPart { chips.append("\(part.label) person") }
        if profile.weekendLeaning { chips.append("Weekend-leaning") }
        if profile.currentStreakDays >= 2 { chips.append("\(profile.currentStreakDays)-day streak") }
        if profile.habitCount > 0 {
            chips.append("\(profile.habitCount) \(profile.habitCount == 1 ? "habit" : "habits")")
        }
        if profile.learnedCount > 0 { chips.append("\(profile.learnedCount) learned") }
        return chips
    }

    private var menu: some View {
        Menu {
            if #available(iOS 16, macOS 13, *) {
                ShareLink(item: UserMemory.exportJSON()) {
                    Label("Share JSON", systemImage: "square.and.arrow.up")
                }
            }
            Button {
                copyToPasteboard(UserMemory.exportJSON())
            } label: {
                Label("Copy JSON", systemImage: "doc.on.doc")
            }
            Button(role: .destructive) {
                confirmingReset = true
            } label: {
                Label("Forget everything", systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
                .foregroundStyle(Color.accentColor)
        }
    }

    private func sectionHeader(_ text: String) -> some View {
        Text(text.uppercased())
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            .padding(.leading, 4)
            .padding(.top, 4)
    }

    private var emptyState: some View {
        VStack(spacing: 8) {
            Image(systemName: "brain")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("Nothing remembered yet")
                .font(.headline)
            Text("Call UserMemory.set(key, value) for explicit preferences, "
                 + "observe(key, choice:) to learn from choices, and record(name) "
                 + "for recurring actions. Everything shows up here.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    private func copyToPasteboard(_ string: String) {
        #if canImport(UIKit)
        UIPasteboard.general.string = string
        #elseif canImport(AppKit)
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(string, forType: .string)
        #endif
    }
}

// MARK: - Shared pieces

@available(iOS 15, macOS 13, *)
private struct SectionCard<Item: Identifiable, Content: View>: View {
    let items: [Item]
    @ViewBuilder let row: (Item) -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                if index > 0 {
                    Divider().opacity(0.5).padding(.vertical, 12)
                }
                row(item)
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.secondary.opacity(0.1), in: RoundedRectangle(cornerRadius: 16))
    }
}

@available(iOS 15, macOS 13, *)
private struct TagBadge: View {
    let text: String
    var tint: Color = .secondary

    var body: some View {
        Text(text)
            .font(.caption2.weight(.semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(tint.opacity(0.12), in: RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Suggestions

@available(iOS 15, macOS 13, *)
private struct SuggestionRow: View {
    let rec: Recommendation

    private var icon: String {
        switch rec.kind {
        case .habitDue: return "bell"
        case .streakAtRisk: return "exclamationmark.triangle"
        case .fadingHabit: return "arrow.counterclockwise"
        case .learnedDefault: return "star"
        }
    }

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 16, weight: .medium))
                .foregroundStyle(Color.accentColor)
                .frame(width: 38, height: 38)
                .background(Color.accentColor.opacity(0.12), in: Circle())
            VStack(alignment: .leading, spacing: 1) {
                Text(rec.title)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(2)
                Text(rec.detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
        }
    }
}

// MARK: - Habits

@available(iOS 15, macOS 13, *)
private struct HabitRow: View {
    let habit: Habit
    let now: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                Text(habit.name)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                badge
                Spacer()
                Text(perWeekLabel(habit.perWeek(from: now)))
                    .font(.caption.weight(.semibold))
                    .monospacedDigit()
            }
            HStack(spacing: 12) {
                DotRow(done: habit.dailyCounts(lastDays: 14, from: now).map { $0 > 0 })
                Spacer()
                Text("14 days")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            HourStrip(counts: habit.hourlyCounts())
            Text(caption)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    @ViewBuilder
    private var badge: some View {
        if habit.isFading(from: now) {
            TagBadge(text: "FADING", tint: .red)
        } else if habit.isHabit(from: now) {
            TagBadge(text: "HABIT", tint: .accentColor)
        } else if now.timeIntervalSince(habit.firstAt) < 14 * 86_400 {
            TagBadge(text: "FORMING")
        }
    }

    private var caption: String {
        var parts: [String] = []
        if let hour = habit.typicalHour() {
            parts.append("usually around \(UserMemory.hourLabel(hour))")
        }
        let dayNames = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        let weekdays = habit.typicalWeekdays()
        if !weekdays.isEmpty {
            if weekdays.allSatisfy({ $0 >= 5 }) {
                parts.append("mostly weekends")
            } else if weekdays.count <= 3 {
                parts.append("mostly " + weekdays.map { dayNames[$0] }.joined(separator: ", "))
            }
        }
        let streak = habit.currentStreakDays(from: now)
        if streak >= 2 { parts.append("\(streak)-day streak") }
        parts.append("\(habit.total)× total")
        return parts.joined(separator: " · ")
    }

    private func perWeekLabel(_ perWeek: Double) -> String {
        let rounded = (perWeek * 10).rounded() / 10
        if rounded == rounded.rounded() {
            return "~\(Int(rounded))×/wk"
        }
        return String(format: "~%.1f×/wk", rounded)
    }
}

/// One dot per day, oldest first; filled = active, today (rightmost) full-strength.
@available(iOS 15, macOS 13, *)
private struct DotRow: View {
    let done: [Bool]

    var body: some View {
        HStack(spacing: 4) {
            ForEach(done.indices, id: \.self) { index in
                Circle()
                    .fill(color(at: index))
                    .frame(width: 9, height: 9)
            }
        }
        .accessibilityLabel(
            "Last \(done.count) days: \(done.filter { $0 }.count) active"
        )
    }

    private func color(at index: Int) -> Color {
        if done[index] {
            return index == done.count - 1
                ? Color.accentColor
                : Color.accentColor.opacity(0.55)
        }
        return Color.primary.opacity(0.1)
    }
}

/// 24 cells, midnight to 11pm, darker = more activity in that hour —
/// a single-hue sequential ramp on the app's accent color.
@available(iOS 15, macOS 13, *)
private struct HourStrip: View {
    let counts: [Int]

    var body: some View {
        let maxCount = max(counts.max() ?? 0, 1)
        VStack(spacing: 3) {
            HStack(spacing: 2) {
                ForEach(counts.indices, id: \.self) { index in
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color.accentColor.opacity(
                            counts[index] == 0
                                ? 0.08
                                : 0.25 + 0.75 * Double(counts[index]) / Double(maxCount)
                        ))
                        .frame(maxWidth: .infinity)
                        .frame(height: 10)
                }
            }
            HStack {
                ForEach(Array(["12am", "6am", "12pm", "6pm", "11pm"].enumerated()),
                        id: \.offset) { index, label in
                    Text(label)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    if index < 4 { Spacer() }
                }
            }
        }
        .accessibilityLabel("Activity by hour of day")
    }
}

// MARK: - Learned choices

@available(iOS 15, macOS 13, *)
private struct LearnedRow: View {
    let learned: Learned

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(learned.key)
                    .font(.subheadline.weight(.semibold))
                    .lineLimit(1)
                Spacer()
                Text(learned.top.value)
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Color.accentColor)
            }
            HStack(spacing: 12) {
                ConfidenceBar(fraction: learned.confidence)
                Text("\(Int((learned.confidence * 100).rounded()))% sure")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .monospacedDigit()
            }
            Text(alternativesLine)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    private var alternativesLine: String {
        let options = learned.choices
            .map { "\($0.value) \(Int(($0.share * 100).rounded()))%" }
            .joined(separator: " · ")
        let word = learned.observations == 1 ? "observation" : "observations"
        return "\(options) — \(learned.observations) \(word)"
    }
}

@available(iOS 15, macOS 13, *)
private struct ConfidenceBar: View {
    let fraction: Double

    var body: some View {
        GeometryReader { proxy in
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.primary.opacity(0.08))
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.accentColor)
                    .frame(width: max(6, proxy.size.width * min(max(fraction, 0), 1)))
            }
        }
        .frame(height: 6)
    }
}

// MARK: - Preferences

@available(iOS 15, macOS 13, *)
private struct PreferenceRow: View {
    let pref: Preference
    let now: Date

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 1) {
                Text(pref.key)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                Text("Updated \(pref.updatedAt.formatted(.relative(presentation: .named)))")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            Text(pref.value.displayValue)
                .font(.subheadline.weight(.semibold))
                .multilineTextAlignment(.trailing)
                .lineLimit(2)
        }
    }
}
#endif
