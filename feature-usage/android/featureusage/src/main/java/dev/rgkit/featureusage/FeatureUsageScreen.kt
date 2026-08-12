package dev.rgkit.featureusage

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class UsageTab(val label: String) {
    FEATURES("Features"),
    TIMELINE("Timeline"),
    INSIGHTS("Insights"),
}

private enum class SortMode(val label: String) {
    MOST_USED("Most used"),
    RECENT("Recent"),
    TRENDING("Trending"),
    NAME("A–Z"),
}

/**
 * Ready-made screen showing everything the SDK has recorded, in three tabs:
 * Features (list + charts), Timeline (recent events) and Insights (generated
 * findings). Drop it into a debug menu or any navigation destination:
 *
 *     FeatureUsageScreen()
 *
 * Follows the host app's MaterialTheme (colors, light/dark) automatically and
 * live-updates while visible. Tap any feature for a detail view.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureUsageScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var stats by remember { mutableStateOf(FeatureUsage.stats()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var refreshTick by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf(UsageTab.FEATURES) }
    var query by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(SortMode.MOST_USED) }
    var confirmingReset by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<FeatureStat?>(null) }

    DisposableEffect(Unit) {
        val listener: () -> Unit = {
            stats = FeatureUsage.stats()
            now = System.currentTimeMillis()
            refreshTick += 1
        }
        FeatureUsage.addChangeListener(listener)
        onDispose { FeatureUsage.removeChangeListener(listener) }
    }

    // stats() is already most-used first, so rank by position there.
    val ranks = remember(stats) { stats.mapIndexed { i, s -> s.name to i + 1 }.toMap() }
    val totalEvents = stats.sumOf { it.total }

    val visible = remember(stats, sortMode, query) {
        val filtered =
            if (query.isBlank()) stats
            else stats.filter { it.name.contains(query.trim(), ignoreCase = true) }
        when (sortMode) {
            SortMode.MOST_USED -> filtered
            SortMode.RECENT -> filtered.sortedByDescending { it.lastUsedAt }
            SortMode.TRENDING -> filtered.sortedWith(
                compareByDescending<FeatureStat> { it.countLastDays(7, now) }
                    .thenByDescending { it.total },
            )
            SortMode.NAME -> filtered.sortedBy { it.name.lowercase() }
        }
    }
    val maxTotal = stats.maxOfOrNull { it.total } ?: 1

    val eventGroups = remember(refreshTick, tab) {
        if (tab != UsageTab.TIMELINE) emptyList()
        else FeatureUsage.recentEvents(200).groupBy { FeatureUsage.dayKey(it.at) }.toList()
    }
    val insightList = remember(refreshTick, tab) {
        if (tab != UsageTab.INSIGHTS) emptyList() else FeatureUsage.insights(now)
    }

    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text("Delete all recorded feature usage?") },
            confirmButton = {
                TextButton(onClick = {
                    FeatureUsage.reset()
                    confirmingReset = false
                }) { Text("Delete everything") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
            },
        )
    }

    selected?.let { stat ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            FeatureDetailSheet(
                stat = stat,
                now = now,
                onDeleted = {
                    FeatureUsage.reset(stat.name)
                    selected = null
                },
            )
        }
    }

    if (stats.isEmpty()) {
        EmptyState(modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                UsageTab.entries.forEach { t ->
                    FilterChip(
                        selected = tab == t,
                        onClick = { tab = t },
                        label = { Text(t.label) },
                    )
                }
            }
        }
        when (tab) {
            UsageTab.FEATURES -> {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile("${stats.size}", "Features", Modifier.weight(1f))
                            StatTile("$totalEvents", "Events", Modifier.weight(1f))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(
                                "${stats.sumOf { it.countLastDays(7, now) }}",
                                "Last 7 days",
                                Modifier.weight(1f),
                                trend = overallTrend(stats, 7, now),
                            )
                            StatTile(
                                "${stats.sumOf { it.countLastDays(1, now) }}",
                                "Today",
                                Modifier.weight(1f),
                            )
                        }
                    }
                }
                item { ActivityCard(stats = stats, now = now) }
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search features") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(12.dp),
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            SortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = sortMode == mode,
                                    onClick = { sortMode = mode },
                                    label = { Text(mode.label) },
                                )
                            }
                        }
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Share JSON") },
                                    onClick = {
                                        menuExpanded = false
                                        share(context, FeatureUsage.exportJson(), "application/json")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Share CSV") },
                                    onClick = {
                                        menuExpanded = false
                                        share(context, FeatureUsage.exportCsv(), "text/csv")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Copy JSON") },
                                    onClick = {
                                        menuExpanded = false
                                        clipboard.setText(AnnotatedString(FeatureUsage.exportJson()))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Import from clipboard") },
                                    onClick = {
                                        menuExpanded = false
                                        clipboard.getText()?.text?.let { FeatureUsage.importJson(it) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Reset all data") },
                                    onClick = {
                                        menuExpanded = false
                                        confirmingReset = true
                                    },
                                )
                            }
                        }
                    }
                }
                if (visible.isEmpty()) {
                    item {
                        Text(
                            "No features match \"${query.trim()}\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(visible, key = { it.name }) { stat ->
                    FeatureRow(
                        stat = stat,
                        rank = ranks[stat.name] ?: 0,
                        maxTotal = maxTotal,
                        totalEvents = totalEvents,
                        now = now,
                        onClick = { selected = stat },
                    )
                }
                item {
                    Text(
                        "Mini bars show the last 7 days, today rightmost. Tap a feature for " +
                            "details. Data lives only on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            UsageTab.TIMELINE -> {
                if (eventGroups.isEmpty()) {
                    item {
                        Text(
                            "No recent events. The last 500 uses show up here as they happen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                eventGroups.forEach { (day, dayEvents) ->
                    item {
                        Text(
                            dayLabel(day, now),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(dayEvents) { event -> TimelineRow(event) }
                }
            }
            UsageTab.INSIGHTS -> {
                if (insightList.isEmpty()) {
                    item {
                        Text(
                            "Not enough data yet — insights appear as usage accumulates.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                }
                items(insightList) { insight -> InsightCard(insight) }
            }
        }
    }
}

/** Sum of every feature's change over the window, as a percentage; null if no baseline. */
private fun overallTrend(stats: List<FeatureStat>, days: Int, now: Long): Int? {
    val current = stats.sumOf { it.countLastDays(days, now) }
    val previous = stats.sumOf { it.countLastDays(days, now - days * FeatureStat.DAY_MILLIS) }
    if (previous == 0) return null
    return ((current - previous) * 100.0 / previous).toInt()
}

private fun dayLabel(dayKey: String, now: Long): String {
    if (dayKey == FeatureUsage.dayKey(now)) return "Today"
    if (dayKey == FeatureUsage.dayKey(now - FeatureStat.DAY_MILLIS)) return "Yesterday"
    val parts = dayKey.split("-").mapNotNull { it.toIntOrNull() }
    if (parts.size != 3) return dayKey
    val cal = Calendar.getInstance().apply {
        clear()
        set(parts[0], parts[1] - 1, parts[2])
    }
    return java.text.SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(cal.time)
}

private fun formatDuration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3_600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
        s < 86_400 -> "${s / 3_600}h ${(s % 3_600) / 60}m"
        else -> "${s / 86_400}d ${(s % 86_400) / 3_600}h"
    }
}

@Composable
private fun TimelineRow(event: UsageEvent) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.at)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            event.name,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InsightCard(insight: UsageInsight) {
    val kindLabel = when (insight.kind) {
        UsageInsight.Kind.RISING -> "TRENDING UP"
        UsageInsight.Kind.FALLING -> "TRENDING DOWN"
        UsageInsight.Kind.STALE -> "STALE"
        UsageInsight.Kind.STREAK -> "STREAK"
        UsageInsight.Kind.PEAK_HOUR -> "PEAK HOUR"
        UsageInsight.Kind.PEAK_DAY -> "PEAK DAY"
        UsageInsight.Kind.CONCENTRATION -> "FOCUS"
        UsageInsight.Kind.NEW -> "NEW"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TagBadge(kindLabel)
        Text(
            insight.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            insight.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    trend: Int? = null,
) {
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TrendLabel(trend)
        }
    }
}

/** App-wide events per day, with a 7d / 30d / 90d range switch. */
@Composable
private fun ActivityCard(stats: List<FeatureStat>, now: Long) {
    var range by remember { mutableStateOf(30) }
    val counts = remember(stats, range, now) {
        val perFeature = stats.map { it.dailyCounts(range, now) }
        (0 until range).map { day -> perFeature.sumOf { it[day] } }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Activity · ${counts.sum()} events",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(7, 30, 90).forEach { r ->
                    Text(
                        "${r}d",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (range == r) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (range == r) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable { range = r }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
        }
        BarChart(counts = counts, height = 56.dp, emphasizeLast = true)
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No usage recorded yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Call FeatureUsage.track(\"feature_name\") where a feature is used. " +
                "Everything tracked shows up here, with charts, a timeline and insights.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun FeatureRow(
    stat: FeatureStat,
    rank: Int,
    maxTotal: Int,
    totalEvents: Int,
    now: Long,
    onClick: () -> Unit,
) {
    val stale = stat.isStale(30, now)
    val isNew = now - stat.firstUsedAt < 7 * FeatureStat.DAY_MILLIS
    val sharePercent = if (totalEvents > 0) stat.total * 100 / totalEvents else 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (stale) 0.65f else 1f)
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "#$rank",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 6.dp),
                )
                Text(
                    stat.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isNew) TagBadge("NEW", Modifier.padding(start = 6.dp))
                if (stale) TagBadge("UNUSED 30d+", Modifier.padding(start = 6.dp))
            }
            TrendLabel(stat.trendPercent(7, now), Modifier.padding(start = 8.dp))
            Text(
                "${stat.total}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            UsageBar(
                fraction = if (maxTotal > 0) stat.total.toFloat() / maxTotal else 0f,
                modifier = Modifier.weight(1f),
            )
            Text(
                "$sharePercent%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Sparkline(counts = stat.dailyCounts(7, now))
        }
        Text(
            buildString {
                append("Last used ")
                append(
                    DateUtils.getRelativeTimeSpanString(
                        stat.lastUsedAt, now, DateUtils.MINUTE_IN_MILLIS,
                    ),
                )
                append(" · ${stat.activeDays} active ")
                append(if (stat.activeDays == 1) "day" else "days")
                if (stat.totalDurationMs > 0) append(" · ${formatDuration(stat.totalDurationMs)}")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Detail view for one feature: charts, streaks, and per-feature actions. */
@Composable
private fun FeatureDetailSheet(
    stat: FeatureStat,
    now: Long,
    onDeleted: () -> Unit,
) {
    var confirmingDelete by remember { mutableStateOf(false) }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete recorded usage for \"${stat.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDeleted()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Cancel") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stat.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (stat.isStale(30, now)) TagBadge("UNUSED 30d+")
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell("Total", "${stat.total}", Modifier.weight(1f))
                StatCell("Today", "${stat.countLastDays(1, now)}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell(
                    "Last 7 days",
                    "${stat.countLastDays(7, now)}",
                    Modifier.weight(1f),
                    trend = stat.trendPercent(7, now),
                )
                StatCell("Last 30 days", "${stat.countLastDays(30, now)}", Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell("Active days", "${stat.activeDays}", Modifier.weight(1f))
                StatCell(
                    "Avg / active day",
                    String.format(Locale.US, "%.1f", stat.averagePerActiveDay()),
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCell("Streak", "${stat.currentStreakDays(now)}d", Modifier.weight(1f))
                StatCell("Best streak", "${stat.bestStreakDays()}d", Modifier.weight(1f))
            }
            if (stat.timedSessions > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCell("Time spent", formatDuration(stat.totalDurationMs), Modifier.weight(1f))
                    StatCell("Avg session", formatDuration(stat.averageSessionMs), Modifier.weight(1f))
                }
            }
        }

        ChartSection("Last 30 days") {
            BarChart(counts = stat.dailyCounts(30, now), height = 64.dp, emphasizeLast = true)
        }

        ChartSection("Last 12 weeks") {
            CalendarHeatmap(stat = stat, now = now)
        }

        ChartSection("By hour of day") {
            BarChart(counts = stat.hourlyCounts(), height = 48.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("0", "6", "12", "18", "23").forEachIndexed { index, hour ->
                    Text(
                        hour,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (index < 4) Spacer(Modifier.weight(1f))
                }
            }
        }

        ChartSection("By day of week") {
            BarChart(counts = stat.weekdayCounts(), height = 48.dp)
            Row(modifier = Modifier.fillMaxWidth()) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                    Text(
                        day,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Column {
            DetailLine("First used", DateUtils.getRelativeTimeSpanString(
                stat.firstUsedAt, now, DateUtils.MINUTE_IN_MILLIS,
            ).toString())
            DetailLine("Last used", DateUtils.getRelativeTimeSpanString(
                stat.lastUsedAt, now, DateUtils.MINUTE_IN_MILLIS,
            ).toString())
        }

        TextButton(onClick = { confirmingDelete = true }) {
            Text("Delete this feature's data", color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * 12-week calendar heatmap: columns are weeks, rows Monday–Sunday, cell
 * intensity scaled to the busiest day.
 */
@Composable
private fun CalendarHeatmap(stat: FeatureStat, now: Long) {
    val days = 84
    val counts = stat.dailyCounts(days, now)
    val maxCount = maxOf(counts.maxOrNull() ?: 0, 1)
    val cal = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -(days - 1))
    }
    val pad = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val cols = (pad + days + 6) / 7
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(cols) { col ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(7) { row ->
                    val index = col * 7 + row - pad
                    val color = when {
                        index < 0 || index >= days -> Color.Transparent
                        counts[index] == 0 ->
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        else -> MaterialTheme.colorScheme.primary.copy(
                            alpha = 0.3f + 0.7f * counts[index] / maxCount,
                        )
                    }
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(color, RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartSection(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    trend: Int? = null,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            TrendLabel(trend)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

/** ▲/▼ plus percentage — glyph carries the direction, color reinforces it. */
@Composable
private fun TrendLabel(percent: Int?, modifier: Modifier = Modifier) {
    percent ?: return
    val (symbol, color) = when {
        percent > 0 -> "▲" to MaterialTheme.colorScheme.primary
        percent < 0 -> "▼" to MaterialTheme.colorScheme.error
        else -> "•" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        "$symbol ${abs(percent)}%",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        modifier = modifier,
    )
}

@Composable
private fun TagBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Horizontal bar showing this feature's share of the most-used feature. */
@Composable
private fun UsageBar(fraction: Float, modifier: Modifier = Modifier) {
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

/**
 * Bars scaled to the max count, oldest/first leftmost, baseline-anchored with
 * rounded tops. With [emphasizeLast], the last bar (today) is full-strength and
 * the rest recede.
 */
@Composable
private fun BarChart(
    counts: List<Int>,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    emphasizeLast: Boolean = false,
) {
    val maxCount = maxOf(counts.maxOrNull() ?: 0, 1)
    val spacing = if (counts.size > 45) 1.dp else 2.dp
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        counts.forEachIndexed { index, count ->
            val barHeight =
                if (count == 0) 2.dp else maxOf(4f, height.value * count / maxCount).dp
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(barHeight)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (!emphasizeLast || index == counts.lastIndex) 1f else 0.45f,
                        ),
                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp),
                    ),
            )
        }
    }
}

/** Last 7 days as mini bars, oldest first; today (rightmost) is emphasized. */
@Composable
private fun Sparkline(counts: List<Int>) {
    val maxCount = maxOf(counts.maxOrNull() ?: 0, 1)
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(16.dp),
    ) {
        counts.forEachIndexed { index, count ->
            val height = if (count == 0) 2.dp else maxOf(4f, 16f * count / maxCount).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(height)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(
                            alpha = if (index == counts.lastIndex) 1f else 0.35f,
                        ),
                        RoundedCornerShape(1.5.dp),
                    ),
            )
        }
    }
}

private fun share(context: android.content.Context, text: String, mimeType: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Feature usage export"))
}
