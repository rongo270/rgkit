package dev.rgkit.adaptiveui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ElevatedCard
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Drop-in adaptive collection: renders [items] as a grid, list, cards or
 * carousel — whichever this user engages with most, learned automatically by
 * [AdaptiveUi]'s bandit. Write one item composable; it receives the chosen
 * style so it can adjust its own internals (e.g. image size).
 *
 * ```kotlin
 * AdaptiveCollection(id = "products", items = products) { product, style ->
 *     ProductTile(product, compact = style == LayoutStyle.LIST)
 * }
 * ```
 *
 * Engagement (item taps, scroll depth, dwell) is observed without consuming
 * any touch events, and the session closes automatically when the collection
 * leaves composition.
 */
@Composable
fun <T> AdaptiveCollection(
    id: String,
    items: List<T>,
    modifier: Modifier = Modifier,
    allowed: Set<LayoutStyle> = LayoutStyle.entries.toSet(),
    contentPadding: PaddingValues = PaddingValues(12.dp),
    itemContent: @Composable (item: T, style: LayoutStyle) -> Unit,
) {
    val style = remember(id) { AdaptiveUi.beginSession(id, allowed) }
    DisposableEffect(id) {
        onDispose { AdaptiveUi.endSession(id) }
    }

    when (style) {
        LayoutStyle.GRID -> {
            val state = rememberLazyGridState()
            LaunchedEffect(id) {
                snapshotFlow { state.layoutInfo }.collect { info ->
                    val total = info.totalItemsCount
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                    if (total > 0 && last >= 0) {
                        AdaptiveUi.recordScrollDepth(id, (last + 1).toDouble() / total)
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items.size) { index ->
                    EngagementProbe(id) { itemContent(items[index], style) }
                }
            }
        }

        LayoutStyle.LIST -> {
            val state = rememberLazyListState()
            ListDepthReporter(id, state)
            LazyColumn(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    EngagementProbe(id) { itemContent(item, style) }
                }
            }
        }

        LayoutStyle.CARDS -> {
            val state = rememberLazyListState()
            ListDepthReporter(id, state)
            LazyColumn(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    EngagementProbe(id) {
                        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.padding(4.dp)) { itemContent(item, style) }
                        }
                    }
                }
            }
        }

        LayoutStyle.CAROUSEL -> {
            val state = rememberLazyListState()
            ListDepthReporter(id, state)
            LazyRow(
                state = state,
                modifier = modifier,
                contentPadding = contentPadding,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(items) { _, item ->
                    Box(Modifier.fillParentMaxWidth(0.82f)) {
                        EngagementProbe(id) { itemContent(item, style) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ListDepthReporter(id: String, state: androidx.compose.foundation.lazy.LazyListState) {
    LaunchedEffect(id) {
        snapshotFlow { state.layoutInfo }.collect { info ->
            val total = info.totalItemsCount
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (total > 0 && last >= 0) {
                AdaptiveUi.recordScrollDepth(id, (last + 1).toDouble() / total)
            }
        }
    }
}

/** Counts taps on an item (Initial pass, nothing consumed) as engagement. */
@Composable
private fun EngagementProbe(id: String, content: @Composable () -> Unit) {
    Box(
        Modifier.pointerInput(id) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                var isTap = true
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                        isTap = false
                    }
                    if (!change.pressed) {
                        if (isTap) AdaptiveUi.recordItemClick(id)
                        break
                    }
                }
            }
        }
    ) { content() }
}
