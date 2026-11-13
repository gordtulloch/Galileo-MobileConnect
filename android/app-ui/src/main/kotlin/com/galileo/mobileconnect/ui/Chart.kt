package com.galileo.mobileconnect.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.galileo.mobileconnect.ui.theme.LocalTheme
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/** One line or set of points on a [Chart]. Told apart by dash as well as colour: Night has only the one hue. */
class ChartSeries(
    val points: List<Pair<Double, Double>>,
    val color: Color,
    val dashed: Boolean = false,
    val scatter: Boolean = false,
)

/** Tick values that land on round numbers, between [low] and [high]. Unit-tested on the JVM. */
fun niceTicks(low: Double, high: Double, target: Int = 4): List<Double> {
    if (!(high > low)) return listOf(low)
    val rough = (high - low) / target
    val magnitude = 10.0.pow(floor(log10(rough)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
    val first = ceil(low / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= high + step * 1e-9 }.map { if (abs(it) < step * 1e-9) 0.0 else it }.toList()
}

fun tickLabel(value: Double, step: Double): String =
    if (step >= 1 || value == Math.rint(value)) value.toLong().toString() else "%.${max(1, ceil(-log10(step)).toInt())}f".format(value)

/** The chart the guide trace and the V-curve are drawn with: lines or points, themed axes, no library (docs/android/SDD.md 3.7). */
@Composable
fun Chart(
    series: List<ChartSeries>,
    description: String,
    modifier: Modifier = Modifier,
    yRange: ClosedFloatingPointRange<Double>? = null,
    height: Int = 200,
    tag: String? = null,
) {
    val theme = LocalTheme.current
    val measurer = rememberTextMeasurer()
    val all = series.flatMap { it.points }
    val xs = all.map { it.first }
    val ys = all.map { it.second }
    val xLow = xs.minOrNull() ?: 0.0
    val xHigh = (xs.maxOrNull() ?: 1.0).let { if (it > xLow) it else xLow + 1 }
    val yLow = yRange?.start ?: ((ys.minOrNull() ?: 0.0) - 0.05 * ((ys.maxOrNull() ?: 1.0) - (ys.minOrNull() ?: 0.0)))
    val yHigh = (yRange?.endInclusive ?: ((ys.maxOrNull() ?: 1.0) + 0.05 * ((ys.maxOrNull() ?: 1.0) - (ys.minOrNull() ?: 0.0)))).let { if (it > yLow) it else yLow + 1 }
    Canvas(
        modifier.fillMaxWidth().height(height.dp).let { if (tag != null) it.testTag(tag) else it }
            .semantics { contentDescription = description },
    ) {
        val label = TextStyle(color = theme.textSecondary, fontSize = 11.sp)
        val left = 44.dp.toPx()
        val bottom = 20.dp.toPx()
        val plot = Offset(left, 4.dp.toPx())
        val width = size.width - left - 6.dp.toPx()
        val plotHeight = size.height - bottom - plot.y
        fun x(v: Double) = plot.x + ((v - xLow) / (xHigh - xLow)).toFloat() * width
        fun y(v: Double) = plot.y + (1f - ((v - yLow) / (yHigh - yLow)).toFloat()) * plotHeight

        val yTicks = niceTicks(yLow, yHigh)
        val ySpan = if (yTicks.size > 1) yTicks[1] - yTicks[0] else 1.0
        for (tick in yTicks) {
            drawLine(theme.border, Offset(plot.x, y(tick)), Offset(plot.x + width, y(tick)), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(tickLabel(tick, ySpan), label)
            drawText(text, topLeft = Offset(plot.x - text.size.width - 4.dp.toPx(), y(tick) - text.size.height / 2f))
        }
        val xTicks = niceTicks(xLow, xHigh, 3)
        val xSpan = if (xTicks.size > 1) xTicks[1] - xTicks[0] else 1.0
        for (tick in xTicks) {
            drawLine(theme.border, Offset(x(tick), plot.y), Offset(x(tick), plot.y + plotHeight), strokeWidth = 1.dp.toPx())
            val text = measurer.measure(tickLabel(tick, xSpan), label)
            drawText(text, topLeft = Offset(x(tick) - text.size.width / 2f, plot.y + plotHeight + 3.dp.toPx()))
        }
        for (s in series) {
            if (s.scatter) {
                s.points.forEach { (px, py) -> drawCircle(s.color, radius = 3.dp.toPx(), center = Offset(x(px), y(py))) }
            } else {
                for (i in 1 until s.points.size) {
                    val a = s.points[i - 1]
                    val b = s.points[i]
                    drawLine(
                        s.color, Offset(x(a.first), y(a.second)), Offset(x(b.first), y(b.second)), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = if (s.dashed) PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null,
                    )
                }
            }
        }
    }
}
