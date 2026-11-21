package io.github.iufb.reach.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import io.github.iufb.reach.ui.format.Dates

/** Серия графика: тип отрисовки, подпись, цвет и значения по [Chart.x]. */
data class Series(val kind: Kind, val name: String, val color: Color, val values: List<Double>) {
    enum class Kind { Line, Bar }
}

/** Разобранный Telegram-чарт (ADR-0006). [x] — миллисекунды. */
data class Chart(val x: List<Long>, val series: List<Series>, val stacked: Boolean, val percentage: Boolean)

/** `jsonData` из `StatisticalGraphData`; `null` — формат не распознан или данных нет. */
fun parseChart(json: String): Chart? = runCatching {
    val root = JSONObject(json)
    val columns = root.getJSONArray("columns")
    val types = root.optJSONObject("types") ?: JSONObject()
    val names = root.optJSONObject("names") ?: JSONObject()
    val colors = root.optJSONObject("colors") ?: JSONObject()
    var x = emptyList<Long>()
    val series = mutableListOf<Series>()
    for (i in 0 until columns.length()) {
        val column = columns.getJSONArray(i)
        val key = column.getString(0)
        val values = (1 until column.length()).map { column.getDouble(it) }
        when (types.optString(key)) {
            "x" -> x = values.map { it.toLong() }
            else -> series += Series(
                kind = if (types.optString(key) == "bar") Series.Kind.Bar else Series.Kind.Line,
                name = names.optString(key, key),
                color = parseColor(colors.optString(key)),
                values = values,
            )
        }
    }
    if (x.isEmpty() || series.isEmpty()) null else Chart(x, series, root.optBoolean("stacked"), root.optBoolean("percentage"))
}.getOrNull()

private fun parseColor(value: String): Color {
    // Цвет бывает «#RRGGBB» или «name#RRGGBB»
    val hex = value.substringAfterLast('#', "").takeIf { it.length == 6 } ?: return Color(0xFF3497ED)
    return Color(0xFF000000 or hex.toLong(16))
}

/** График с легендой и датами по краям. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartView(chart: Chart, modifier: Modifier = Modifier) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val n = chart.x.size
            if (n == 0) return@Canvas
            val bars = chart.series.filter { it.kind == Series.Kind.Bar }
            val lines = chart.series.filter { it.kind == Series.Kind.Line }
            val stackedMax = if (chart.stacked && bars.isNotEmpty()) {
                (0 until n).maxOf { i -> bars.sumOf { it.values.getOrElse(i) { 0.0 } } }
            } else {
                0.0
            }
            val max = maxOf(stackedMax, chart.series.maxOf { s -> s.values.maxOrNull() ?: 0.0 }).takeIf { it > 0 } ?: 1.0
            for (step in 0..GRID_LINES) {
                val y = size.height * step / GRID_LINES
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            if (bars.isNotEmpty()) {
                val slot = size.width / n
                val width = (slot * BAR_FILL).coerceAtLeast(1f)
                for (i in 0 until n) {
                    var base = 0.0
                    bars.forEachIndexed { k, s ->
                        val v = s.values.getOrElse(i) { 0.0 }
                        val top = if (chart.stacked) base + v else v
                        val height = (top - if (chart.stacked) base else 0.0) / max * size.height
                        val left = i * slot + if (chart.stacked) 0f else width * k / bars.size
                        val w = if (chart.stacked) width else width / bars.size
                        drawRect(s.color, Offset(left, (size.height - top / max * size.height).toFloat()), Size(w, height.toFloat()))
                        base = top
                    }
                }
            }
            for (s in lines) {
                val path = Path()
                s.values.forEachIndexed { i, v ->
                    val px = if (n == 1) size.width / 2 else size.width * i / (n - 1)
                    val py = (size.height - v / max * size.height).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, s.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatDate(chart.x.first()), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            Text(formatDate(chart.x.last()), style = MaterialTheme.typography.labelSmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            chart.series.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(s.color, CircleShape))
                    Box(Modifier.width(4.dp))
                    Text(s.name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun formatDate(millis: Long): String = Dates.chartDay(millis)

private const val GRID_LINES = 4
private const val BAR_FILL = 0.8f
