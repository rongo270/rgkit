package dev.rgkit.usermemory

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Ready-made "what this app knows about you" screen: profile, suggestions,
 * habits, learned choices and explicit preferences, with export and reset.
 * Drop it into a settings or debug destination:
 *
 *     UserMemoryScreen()
 *
 * Follows the host app's MaterialTheme (colors, light/dark) automatically.
 */
@Composable
fun UserMemoryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val now = remember { System.currentTimeMillis() }

    // refreshKey bumps after reset/import so every snapshot below recomputes.
    var refreshKey by remember { mutableIntStateOf(0) }
    val profile = remember(refreshKey) { UserMemory.profile() }
    val recommendations = remember(refreshKey) { UserMemory.recommendations() }
    val habits = remember(refreshKey) { UserMemory.habits() }
    val learned = remember(refreshKey) { UserMemory.learned() }
    val preferences = remember(refreshKey) { UserMemory.preferences() }

    var menuExpanded by remember { mutableStateOf(false) }
    var confirmingReset by remember { mutableStateOf(false) }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Forget everything?") },
            text = { Text("Deletes all preferences, learned choices and habit history on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    UserMemory.reset()
                    refreshKey += 1
                    confirmingReset = false
                }) { Text("Forget everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
            },
        )
    }

    val isEmpty = habits.isEmpty() && learned.isEmpty() && preferences.isEmpty()

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            HeroCard(
                profile = profile,
                now = now,
                menu = {
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More actions",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Share JSON") },
                                onClick = {
                                    menuExpanded = false
                                    share(context, UserMemory.exportJson())
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Copy JSON") },
                                onClick = {
                                    menuExpanded = false
                                    clipboard.setText(AnnotatedString(UserMemory.exportJson()))
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Forget everything") },
                                onClick = {
                                    menuExpanded = false
                                    confirmingReset = true
                                },
                            )
                        }
                    }
                },
            )
        }

        if (isEmpty) {
            item { EmptyState() }
            return@LazyColumn
        }

        if (recommendations.isNotEmpty()) {
            item { SectionHeader("Suggestions") }
            item {
                SectionCard {
                    recommendations.forEachIndexed { index, rec ->
                        if (index > 0) SectionDivider()
                        SuggestionRow(rec)
                    }
                }
            }
        }

        if (habits.isNotEmpty()) {
            item { SectionHeader("Habits") }
            item {
                SectionCard {
                    habits.forEachIndexed { index, habit ->
                        if (index > 0) SectionDivider()
                        HabitRow(habit, now)
                    }
                }
            }
        }

        if (learned.isNotEmpty()) {
            item { SectionHeader("Learned from choices") }
            item {
                SectionCard {
                    learned.forEachIndexed { index, entry ->
                        if (index > 0) SectionDivider()
                        LearnedRow(entry)
                    }
                }
            }
        }

        if (preferences.isNotEmpty()) {
            item { SectionHeader("Preferences") }
            item {
                SectionCard {
                    preferences.forEachIndexed { index, pref ->
                        if (index > 0) SectionDivider()
                        PreferenceRow(pref, now)
                    }
                }
            }
        }

        item {
            Text(
                "Everything above lives in one JSON file inside this app on this " +
                    "device. Nothing is sent anywhere. Share JSON to move it to " +
                    "another device or app; forget everything to start over.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// MARK: Hero

@Composable
private fun HeroCard(
    profile: UserProfile,
    now: Long,
    menu: @Composable () -> Unit,
) {
    val onHero = MaterialTheme.colorScheme.onPrimaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "USER MEMORY",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = onHero.copy(alpha = 0.7f),
                modifier = Modifier.weight(1f),
            )
            menu()
        }
        Text(
            profile.engagement.label,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = onHero,
        )
        Text(
            heroSubtitle(profile),
            style = MaterialTheme.typography.bodySmall,
            color = onHero.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = 2.dp, end = 12.dp),
        )
        val chips = heroChips(profile)
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 12.dp, end = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { chip ->
                    Text(
                        chip,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = onHero,
                        modifier = Modifier
                            .background(onHero.copy(alpha = 0.1f), RoundedCornerShape(50))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

private fun heroSubtitle(profile: UserProfile): String {
    val known = if (profile.daysKnown == 1) "since today" else "for ${profile.daysKnown} days"
    return "With you $known · ${profile.activeDays28} active " +
        (if (profile.activeDays28 == 1) "day" else "days") + " in the last 4 weeks"
}

private fun heroChips(profile: UserProfile): List<String> {
    val chips = mutableListOf<String>()
    profile.peakPart?.let { chips += "${it.label} person" }
    if (profile.weekendLeaning) chips += "Weekend-leaning"
    if (profile.currentStreakDays >= 2) chips += "${profile.currentStreakDays}-day streak"
    if (profile.habitCount > 0) {
        chips += "${profile.habitCount} " + if (profile.habitCount == 1) "habit" else "habits"
    }
    if (profile.learnedCount > 0) chips += "${profile.learnedCount} learned"
    return chips
}

// MARK: Suggestions

@Composable
private fun SuggestionRow(rec: Recommendation) {
    val icon: ImageVector = when (rec.kind) {
        RecommendationKind.HABIT_DUE -> Icons.Default.Notifications
        RecommendationKind.STREAK_AT_RISK -> Icons.Default.Warning
        RecommendationKind.FADING_HABIT -> Icons.Default.Refresh
        RecommendationKind.LEARNED_DEFAULT -> Icons.Default.Star
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier
            .weight(1f)
            .padding(start = 12.dp)) {
            Text(
                rec.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rec.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

// MARK: Habits

@Composable
private fun HabitRow(habit: Habit, now: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                habit.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            when {
                habit.isFading(now) -> TagBadge(
                    "FADING",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 8.dp),
                )
                habit.isHabit(now) -> TagBadge(
                    "HABIT",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
                now - habit.firstAt < 14 * UserMemory.DAY_MILLIS -> TagBadge(
                    "FORMING",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                perWeekLabel(habit.perWeek(now)),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DotRow(
                done = habit.dailyCounts(14, now).map { it > 0 },
                modifier = Modifier.weight(1f),
            )
            Text(
                "14 days",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HourStrip(counts = habit.hourlyCounts())
        Text(
            habitCaption(habit, now),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun perWeekLabel(perWeek: Double): String {
    val rounded = (perWeek * 10).roundToInt() / 10.0
    return if (rounded == rounded.toInt().toDouble()) {
        "~${rounded.toInt()}×/wk"
    } else {
        "~${String.format(Locale.US, "%.1f", rounded)}×/wk"
    }
}

private fun habitCaption(habit: Habit, now: Long): String {
    val parts = mutableListOf<String>()
    habit.typicalHour()?.let { parts += "usually around ${UserMemory.hourLabel(it)}" }
    val weekdays = habit.typicalWeekdays()
    val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    when {
        weekdays.isEmpty() -> Unit
        weekdays.all { it >= 5 } -> parts += "mostly weekends"
        weekdays.size in 1..3 -> parts += "mostly " + weekdays.joinToString(", ") { dayNames[it] }
    }
    val streak = habit.currentStreakDays(now)
    if (streak >= 2) parts += "$streak-day streak"
    parts += "${habit.total}× total"
    return parts.joinToString(" · ")
}

/** One dot per day, oldest first; filled = active, today (rightmost) full-strength. */
@Composable
private fun DotRow(done: List<Boolean>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        done.forEachIndexed { index, active ->
            val color = when {
                active && index == done.lastIndex -> MaterialTheme.colorScheme.primary
                active -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            }
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(color, CircleShape),
            )
        }
    }
}

/**
 * 24 cells, midnight to 11pm, darker = more activity in that hour —
 * a single-hue sequential ramp on the theme's primary color.
 */
@Composable
private fun HourStrip(counts: List<Int>) {
    val maxCount = maxOf(counts.maxOrNull() ?: 0, 1)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            counts.forEach { count ->
                val alpha = if (count == 0) 0.08f else 0.25f + 0.75f * count / maxCount
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            listOf("12am", "6am", "12pm", "6pm", "11pm").forEachIndexed { index, label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (index < 4) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// MARK: Learned choices

@Composable
private fun LearnedRow(learned: Learned) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                learned.key,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                learned.top.value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConfidenceBar(fraction = learned.confidence.toFloat(), modifier = Modifier.weight(1f))
            Text(
                "${(learned.confidence * 100).roundToInt()}% sure",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            learned.choices.joinToString(" · ") {
                "${it.value} ${(it.share * 100).roundToInt()}%"
            } + " — ${learned.observations} " +
                if (learned.observations == 1) "observation" else "observations",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConfidenceBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(6.dp)
            .background(
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                RoundedCornerShape(3.dp),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp)),
        )
    }
}

// MARK: Preferences

@Composable
private fun PreferenceRow(pref: Preference, now: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                pref.key,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "Updated " + DateUtils.getRelativeTimeSpanString(
                    pref.updatedAt, now, DateUtils.MINUTE_IN_MILLIS,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            pref.displayValue,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

// MARK: Shared pieces

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
    )
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(14.dp),
        content = content,
    )
}

@Composable
private fun SectionDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
}

@Composable
private fun TagBadge(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    val background =
        if (tint == Color.Unspecified) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
        } else {
            tint.copy(alpha = 0.12f)
        }
    val textColor =
        if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = textColor,
        modifier = modifier
            .background(background, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing remembered yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Call UserMemory.set(key, value) for explicit preferences, " +
                "observe(key, choice) to learn from choices, and record(name) " +
                "for recurring actions. Everything shows up here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

private fun share(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "User memory export"))
}
