package de.dressler.twizy.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import de.dressler.twizy.core.tuning.MapPoint
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val RPM_STEP = 50L

/** Level as a rounded percentage; [MapPoint.percent] truncates, which shows 56 % for a point set to 57 %. */
fun MapPoint.displayPercent(): Int = (level * 100.0 / MapPoint.FULL_LEVEL).roundToInt()

/** Plot area inside the canvas and the conversions between map values and pixels. */
private class Plot(width: Float, height: Float, density: Density) {
    val left = with(density) { 44.dp.toPx() }
    val top = with(density) { 12.dp.toPx() }
    val right = width - with(density) { 12.dp.toPx() }
    val bottom = height - with(density) { 24.dp.toPx() }

    fun x(rpm: Long) = left + (right - left) * rpm / MapPoint.MAX_RPM
    fun y(level: Long) = bottom - (bottom - top) * level / MapPoint.FULL_LEVEL
    fun yPercent(percent: Int) = bottom - (bottom - top) * percent / 100f
    fun offset(p: MapPoint) = Offset(x(p.rpm), y(p.level))

    /** The point [index] moved to [pos], snapped to 50 rpm / 1 % and kept between its neighbours. */
    fun moved(points: List<MapPoint>, index: Int, pos: Offset): MapPoint {
        val low = if (index == 0) 0L else points[index - 1].rpm
        val high = if (index == points.lastIndex) MapPoint.MAX_RPM else points[index + 1].rpm
        val rpm = ((pos.x - left) / (right - left) * MapPoint.MAX_RPM / RPM_STEP).roundToLong() * RPM_STEP
        val percent = ((bottom - pos.y) / (bottom - top) * 100).roundToInt().coerceIn(0, 100)
        return MapPoint.ofPercent(percent, rpm.coerceIn(low, high))
    }
}

/**
 * A torque/speed map as the controller applies it: linear between the points, the level of
 * the first / last point held below / above them. Points can be dragged; [onMove] receives
 * every intermediate position so the caller can show the value live.
 */
@Composable
fun TorqueSpeedChart(
    points: List<MapPoint>,
    factory: List<MapPoint>,
    kphPerRpm: Double,
    active: Int?,
    enabled: Boolean,
    onActiveChange: (Int?) -> Unit,
    onMove: (Int, MapPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(points)
    val textMeasurer = rememberTextMeasurer()
    val colors = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onSurfaceVariant)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(color = colors.onPrimary, fontWeight = FontWeight.Bold)

    Canvas(
        modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            val touchRadius = 32.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                val plot = Plot(size.width.toFloat(), size.height.toFloat(), this)
                val hits = current.indices
                    .map { it to (plot.offset(current[it]) - down.position).getDistance() }
                    .filter { it.second <= touchRadius }
                    .sortedBy { it.second }
                    .map { it.first }
                if (hits.isEmpty()) return@awaitEachGesture
                down.consume()
                // Points on top of each other: the direction of the first movement decides which one moves.
                val stacked = hits.filter { (plot.offset(current[it]) - plot.offset(current[hits.first()])).getDistance() < 1f }
                var index: Int? = if (stacked.size == 1) hits.first() else null
                onActiveChange(hits.first())
                drag(down.id) { change ->
                    val i = index ?: (if (change.position.x >= down.position.x) stacked.max() else stacked.min()).also { index = it }
                    onActiveChange(i)
                    onMove(i, plot.moved(current, i, change.position))
                    change.consume()
                }
                onActiveChange(null)
            }
        },
    ) {
        val plot = Plot(size.width, size.height, this)
        val grid = colors.outlineVariant
        val gap = 4.dp.toPx()

        for (percent in 0..100 step 25) {
            val y = plot.yPercent(percent)
            drawLine(grid, Offset(plot.left, y), Offset(plot.right, y), strokeWidth = 1.dp.toPx())
            val label = textMeasurer.measure("$percent %", labelStyle)
            drawText(label, topLeft = Offset(plot.left - label.size.width - gap, y - label.size.height / 2))
        }

        val unit = textMeasurer.measure("km/h", labelStyle)
        val unitLeft = plot.right - unit.size.width
        drawText(unit, topLeft = Offset(unitLeft, plot.bottom + gap))
        val maxKph = (MapPoint.MAX_RPM * kphPerRpm).toInt()
        for (kph in 0..maxKph step 10) {
            val x = plot.x((kph / kphPerRpm).roundToLong())
            drawLine(grid, Offset(x, plot.top), Offset(x, plot.bottom), strokeWidth = 1.dp.toPx())
            val label = textMeasurer.measure("$kph", labelStyle)
            val labelLeft = x - label.size.width / 2
            if (labelLeft + label.size.width < unitLeft - gap) drawText(label, topLeft = Offset(labelLeft, plot.bottom + gap))
        }
        drawLine(colors.onSurfaceVariant, Offset(plot.left, plot.top), Offset(plot.left, plot.bottom), strokeWidth = 1.5.dp.toPx())
        drawLine(colors.onSurfaceVariant, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1.5.dp.toPx())

        if (factory != points) drawCurve(plot, factory, colors.outline, 1.5.dp.toPx(), dashedOnly = true)
        drawCurve(plot, points, colors.primary, 3.dp.toPx(), dashedOnly = false)

        points.forEachIndexed { i, p ->
            val center = plot.offset(p)
            drawCircle(colors.primary, radius = (if (i == active) 11 else 8).dp.toPx(), center = center)
            drawCircle(colors.surface, radius = 3.dp.toPx(), center = center)
        }

        if (active != null && active in points.indices) {
            val p = points[active]
            val text = textMeasurer.measure("${p.displayPercent()} %  ·  ${(p.rpm * kphPerRpm).roundToInt()} km/h", valueStyle)
            val pad = 6.dp.toPx()
            val box = Size(text.size.width + 2 * pad, text.size.height + 2 * pad)
            val center = plot.offset(p)
            // Above the finger where possible, so the value stays visible while dragging.
            val above = center.y - 24.dp.toPx() - box.height
            val top = if (above >= 0f) above else center.y + 24.dp.toPx()
            val left = (center.x - box.width / 2).coerceIn(0f, size.width - box.width)
            drawRoundRect(colors.primary, Offset(left, top), box, CornerRadius(pad))
            drawText(text, topLeft = Offset(left + pad, top + pad))
        }
    }
}

/** Solid between the points (interpolated), dashed where the controller holds the end levels. */
private fun DrawScope.drawCurve(plot: Plot, points: List<MapPoint>, color: Color, width: Float, dashedOnly: Boolean) {
    if (points.isEmpty()) return
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    val first = plot.offset(points.first())
    val last = plot.offset(points.last())
    val holdWidth = width * if (dashedOnly) 1f else 0.6f
    drawLine(color, Offset(plot.left, first.y), first, strokeWidth = holdWidth, pathEffect = dash)
    drawLine(color, last, Offset(plot.right, last.y), strokeWidth = holdWidth, pathEffect = dash)
    val path = Path().apply {
        moveTo(first.x, first.y)
        points.drop(1).forEach { lineTo(plot.x(it.rpm), plot.y(it.level)) }
    }
    drawPath(path, color, style = Stroke(width = width, pathEffect = if (dashedOnly) dash else null))
}
