package com.wellmute.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wellmute.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

data class DonutSlice(val value: Float, val color: Color)

/**
 * Ring split into [slices], with [content] in the middle. An empty ring when everything is zero.
 * With [onSliceClick], tapping the ring reports the slice's index in [slices], and tapping
 * anywhere else reports null. The slice at [selectedIndex] is drawn thicker and the rest dimmed.
 */
@Composable
fun DonutChart(
    slices: List<DonutSlice>,
    description: String,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    strokeWidth: Dp = 18.dp,
    selectedIndex: Int? = null,
    onSliceClick: ((Int?) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val total = slices.sumOf { it.value.toDouble() }.toFloat()
    val tapModifier = if (onSliceClick == null || total <= 0f) {
        Modifier
    } else {
        Modifier.pointerInput(slices) {
            detectTapGestures { tap ->
                val stroke = strokeWidth.toPx()
                val radius = (this.size.width - stroke) / 2
                val dx = tap.x - this.size.width / 2
                val dy = tap.y - this.size.height / 2
                // Generous around the ring, so a thin slice is still easy to hit with a finger.
                if (hypot(dx, dy) !in (radius - stroke * 1.5f)..(radius + stroke)) {
                    onSliceClick(null)
                    return@detectTapGestures
                }
                // Same convention as drawArc: 0° at 3 o'clock, clockwise, slices starting at 12.
                var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                if (angle < -90f) angle += 360f
                var start = -90f
                for ((index, slice) in slices.withIndex()) {
                    val sweep = slice.value / total * 360f
                    if (slice.value > 0f && angle < start + sweep) {
                        onSliceClick(index)
                        return@detectTapGestures
                    }
                    start += sweep
                }
                onSliceClick(null)
            }
        }
    }
    Box(modifier.size(size).semantics { contentDescription = description }.then(tapModifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = strokeWidth.toPx()
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2, stroke / 2)
            if (total <= 0f) {
                drawArc(trackColor, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                return@Canvas
            }
            val gap = if (slices.count { it.value > 0f } > 1) GAP_DEGREES else 0f
            var start = -90f
            slices.forEachIndexed { index, slice ->
                if (slice.value <= 0f) return@forEachIndexed
                val sweep = slice.value / total * 360f
                val isSelected = index == selectedIndex
                drawArc(
                    color = if (selectedIndex == null || isSelected) slice.color else slice.color.copy(alpha = slice.color.alpha * 0.35f),
                    startAngle = start + gap / 2,
                    sweepAngle = (sweep - gap).coerceAtLeast(MIN_SWEEP_DEGREES),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(if (isSelected) stroke * 1.5f else stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        content()
    }
}

/**
 * How a bar chart's Y axis is labelled: the tick sizes it may use, smallest first, and how to
 * write a value ("3 h", "30 min", "20").
 */
class ChartAxis(val steps: List<Long>, val format: (Long) -> String) {
    /** The smallest step whose ticks reach [max], like "0, 3 h, 6 h". */
    fun stepFor(max: Long): Long = steps.firstOrNull { it * TICKS >= max }
        // Beyond the list, keep doubling the largest step.
        ?: generateSequence(steps.last()) { it * 2 }.first { it * TICKS >= max }

    companion object {
        const val TICKS = 2
    }
}

/** Screen time: minutes up to an hour, then whole hours. */
@Composable
fun durationAxis(): ChartAxis {
    val resources = LocalResources.current
    return remember(resources) {
        ChartAxis(
            steps = listOf(1, 2, 5, 10, 15, 20, 30, 60, 120, 180, 240, 300, 360, 480, 600, 720).map { it * 60_000L },
        ) { millis ->
            val minutes = millis / 60_000
            when {
                minutes == 0L -> "0"
                minutes % 60 == 0L -> resources.getString(R.string.duration_hours, minutes / 60)
                else -> resources.getString(R.string.duration_minutes, minutes)
            }
        }
    }
}

/** Notifications and opens. */
@Composable
fun countAxis(): ChartAxis = remember {
    ChartAxis(steps = listOf(1L, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)) { it.toString() }
}

/**
 * Vertical bars with a label under each. The bar at [selectedIndex] is highlighted, and
 * [onBarClick], when given, makes the bars tappable. With an [axis], the right edge shows the
 * scale ("0", "3 h", "6 h") with faint lines, and the average line is labelled.
 */
@Composable
fun BarChart(
    values: List<Long>,
    labels: List<String>,
    description: String,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    height: Dp = 140.dp,
    /** Drawn as a dashed line across the bars, when given. */
    average: Long? = null,
    axis: ChartAxis? = null,
    /** Each bar split into parts, bottom first, coloured with [stackColors] (e.g. by app category). */
    stacks: List<List<Long>>? = null,
    stackColors: List<Color> = emptyList(),
    /** With [stacks], the part to pick out: the others are dimmed in every bar (a tapped legend entry). */
    highlightedPart: Int? = null,
    onBarClick: ((Int) -> Unit)? = null,
) {
    val selected = MaterialTheme.colorScheme.primary
    val others = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val baseline = MaterialTheme.colorScheme.outlineVariant
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val averageColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.labelSmall
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val highest = maxOf(values.maxOrNull() ?: 0L, average ?: 0L).coerceAtLeast(1L)
    val step = axis?.stepFor(highest)
    // With an axis the top of the chart is the top tick, so the scale reads true.
    val max = step?.let { it * ChartAxis.TICKS } ?: highest
    val ticks = step?.let { s -> (0..ChartAxis.TICKS).map { it * s } }.orEmpty()
    val tickTexts = if (axis == null) emptyList() else ticks.map(axis.format)
    val averageText = stringResource(R.string.chart_average)
    val showAverage = average != null && average > 0
    // Room on the right for the widest label, so bars and labels never overlap.
    val axisWidth = if (axis == null) 0.dp else with(density) {
        val widest = (tickTexts + if (showAverage) listOf(averageText) else emptyList())
            .maxOf { measurer.measure(it, labelStyle).size.width }
        widest.toDp() + AXIS_GAP
    }

    Column(modifier.semantics { contentDescription = description }) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .then(
                    if (onBarClick == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(values.size, axisWidth) {
                            detectTapGestures { offset ->
                                val barsWidth = size.width - axisWidth.toPx()
                                val index = (offset.x / (barsWidth / values.size)).toInt()
                                if (offset.x < barsWidth && index in values.indices) onBarClick(index)
                            }
                        }
                    },
                ),
        ) {
            if (values.isEmpty()) return@Canvas
            val barsWidth = this.size.width - axisWidth.toPx()
            val chartHeight = this.size.height
            fun yOf(value: Long) = chartHeight - chartHeight * value / max

            ticks.drop(1).forEach { tick ->
                drawLine(grid, Offset(0f, yOf(tick)), Offset(barsWidth, yOf(tick)), strokeWidth = 1.dp.toPx())
            }
            val slot = barsWidth / values.size
            val barWidth = slot * BAR_WIDTH_FRACTION
            val radius = CornerRadius(barWidth / 4, barWidth / 4)
            values.forEachIndexed { index, value ->
                val barHeight = chartHeight * value / max
                if (barHeight <= 0f) return@forEachIndexed
                val highlighted = selectedIndex == null || index == selectedIndex
                val left = index * slot + (slot - barWidth) / 2
                val stack = stacks?.getOrNull(index)
                if (stack.isNullOrEmpty()) {
                    drawRoundRect(
                        color = if (highlighted) selected else others,
                        topLeft = Offset(left, chartHeight - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = radius,
                    )
                    return@forEachIndexed
                }
                // One rounded bar, cut into its parts from the bottom up.
                val outline = Path().apply {
                    addRoundRect(RoundRect(left, chartHeight - barHeight, left + barWidth, chartHeight, radius))
                }
                clipPath(outline) {
                    var bottom = chartHeight
                    stack.forEachIndexed { part, partValue ->
                        val partHeight = chartHeight * partValue / max
                        if (partHeight <= 0f) return@forEachIndexed
                        val color = stackColors.getOrElse(part) { selected }
                        drawRect(
                            color = if (highlighted && (highlightedPart == null || part == highlightedPart)) color else color.copy(alpha = color.alpha * DIMMED_ALPHA),
                            topLeft = Offset(left, bottom - partHeight),
                            size = Size(barWidth, partHeight),
                        )
                        bottom -= partHeight
                    }
                }
            }
            drawLine(baseline, Offset(0f, chartHeight), Offset(barsWidth, chartHeight), strokeWidth = 1.dp.toPx())

            val averageY = if (showAverage) yOf(average!!) else null
            if (averageY != null) {
                drawLine(
                    averageColor,
                    Offset(0f, averageY),
                    Offset(barsWidth, averageY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            }
            if (axis == null) return@Canvas

            // Labels sit centred on their line, kept inside the chart at the top and bottom.
            val x = barsWidth + AXIS_GAP.toPx()
            fun labelTop(y: Float, textHeight: Int) = (y - textHeight / 2f).coerceIn(0f, chartHeight - textHeight)
            val averageLabel = averageY?.let { measurer.measure(averageText, labelStyle.copy(color = averageColor)) }
            val averageTop = if (averageY != null && averageLabel != null) labelTop(averageY, averageLabel.size.height) else null
            ticks.forEachIndexed { i, tick ->
                val text = measurer.measure(tickTexts[i], labelStyle.copy(color = labelColor))
                val top = labelTop(yOf(tick), text.size.height)
                // The average's label wins where the two would overlap.
                val overlaps = averageTop != null && abs(top - averageTop) < text.size.height
                if (!overlaps) drawText(text, topLeft = Offset(x, top))
            }
            if (averageLabel != null && averageTop != null) drawText(averageLabel, topLeft = Offset(x, averageTop))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp, end = axisWidth)) {
            labels.forEach { label ->
                // Unbounded so "29" can spill past a month's narrow bar instead of being cut off.
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                        textAlign = TextAlign.Center,
                        style = labelStyle,
                        color = labelColor,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Colours for the top apps in the day's ring, in order ("Indigo sunset"): the brand indigo for the
 * most used app, then coral, golden yellow and violet, far apart so no two look alike. Lighter in
 * the dark theme. Everything else uses [otherSliceColor].
 */
@Composable
fun sliceColors(): List<Color> =
    if (isSystemInDarkTheme()) {
        listOf(Color(0xFF7986CB), Color(0xFFFF8A65), Color(0xFFFFD54F), Color(0xFFBA68C8))
    } else {
        listOf(Color(0xFF3949AB), Color(0xFFE64A19), Color(0xFFF9A825), Color(0xFF8E24AA))
    }

/** The rest of the apps: a quiet grey. */
@Composable
fun otherSliceColor(): Color = if (isSystemInDarkTheme()) Color(0xFF4A4C57) else Color(0xFFC4C5D0)

private const val GAP_DEGREES = 3f
private const val MIN_SWEEP_DEGREES = 0.5f
private const val BAR_WIDTH_FRACTION = 0.6f
private val AXIS_GAP = 6.dp
private const val DIMMED_ALPHA = 0.35f
