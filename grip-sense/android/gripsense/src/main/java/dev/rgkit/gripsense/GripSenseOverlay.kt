package dev.rgkit.gripsense

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/**
 * Debug overlay: the tap heatmap plus the reach-zone model for the current
 * handedness. Put it in a Box above a screen (debug builds only):
 *
 * ```kotlin
 * Box {
 *     MyScreen()
 *     if (BuildConfig.DEBUG && showGrip) GripHeatmapOverlay()
 * }
 * ```
 *
 * Red-tinted cells are hard-to-reach for the detected grip; the brighter a
 * cell, the more taps land there. Bright red = frequently used AND painful.
 */
@Composable
fun GripHeatmapOverlay(
    modifier: Modifier = Modifier,
    maxAlpha: Float = 0.45f,
) {
    val grid = GripSense.heatmap()
    val (hand, _) = GripSense.handedness()
    Canvas(modifier.fillMaxSize()) {
        val rows = grid.size
        val cols = if (rows > 0) grid[0].size else 0
        if (rows == 0 || cols == 0) return@Canvas
        var peak = 1
        for (row in grid) for (v in row) if (v > peak) peak = v
        val cellW = size.width / cols
        val cellH = size.height / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = (c + 0.5) / cols
                val y = (r + 0.5) / rows
                val zone = GripSense.zoneFor(x, y, hand)
                val intensity = grid[r][c].toFloat() / peak
                val base = when (zone) {
                    ReachZone.EASY -> Color(0xFF2ECC71)
                    ReachZone.STRETCH -> Color(0xFFF1C40F)
                    ReachZone.HARD -> Color(0xFFE74C3C)
                }
                // Zone tint is faint; taps raise the alpha so hotspots pop.
                val alpha = (0.08f + intensity * maxAlpha).coerceAtMost(0.85f)
                drawRect(
                    color = base.copy(alpha = alpha),
                    topLeft = Offset(c * cellW, r * cellH),
                    size = Size(cellW + 0.5f, cellH + 0.5f),
                )
            }
        }
    }
}
