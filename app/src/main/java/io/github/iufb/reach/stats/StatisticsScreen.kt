package io.github.iufb.reach.stats

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.ui.components.DelayedSkeleton
import io.github.iufb.reach.ui.components.GroupedRow
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.groupedHeader
import io.github.iufb.reach.ui.components.groupedItems
import io.github.iufb.reach.ui.format.AppLocale
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Статистика канала в стиле Telegram iOS: период, сводка плитками в карточке, графики карточками
 * с подзаголовком, недавние публикации списком.
 */
@Composable
fun StatisticsScreen(onBack: () -> Unit, onOpenPost: (Long) -> Unit, viewModel: StatisticsViewModel = hiltViewModel()) {
    val dark = ReachTheme.colors.isDark
    LaunchedEffect(viewModel) { viewModel.load(dark) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = ReachTheme.colors
    val period = (state as? StatsState.Ready)?.let { stringResource(R.string.stats_period, formatDate(it.periodStart), formatDate(it.periodEnd)) }
    val postsTitle = stringResource(R.string.stats_recent_posts)
    ListScreen(
        title = stringResource(R.string.stats_title),
        onBack = onBack,
        loading = state == StatsState.Loading,
    ) {
        when (val s = state) {
            StatsState.Loading -> Unit
            is StatsState.Unavailable -> item(key = "unavailable") {
                Text(
                    stringResource(R.string.stats_unavailable),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = colors.secondaryLabel,
                )
            }
            is StatsState.Ready -> {
                period?.let { groupedHeader(it, key = "period") }
                item(key = "metrics") {
                    GroupedRow(first = true, last = true) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            s.metrics.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { MetricTile(it, Modifier.weight(1f)) }
                                    if (row.size == 1) Box(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                s.graphs.forEach { graph ->
                    item(key = "graph-${graph.title}") { GraphCard(graph) }
                }
                if (s.posts.isNotEmpty()) {
                    groupedHeader(postsTitle)
                    groupedItems(s.posts, key = { it.messageId }) { post -> PostRow(post) { onOpenPost(post.messageId) } }
                }
            }
        }
    }
}

/** Плитка сводки: число крупно, изменение зелёным / красным, подпись. */
@Composable
private fun MetricTile(metric: Metric, modifier: Modifier) {
    val colors = ReachTheme.colors
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                if (metric.isPercent) "${formatNumber(metric.value)}%" else formatNumber(metric.value),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.label,
            )
            if (!metric.isPercent && metric.value != metric.previous) {
                val diff = metric.value - metric.previous
                Text(
                    " ${if (diff >= 0) "+" else "−"}${formatNumber(abs(diff))} (${formatNumber(abs(metric.growthPercent))}%)",
                    modifier = Modifier.padding(bottom = 3.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (diff >= 0) colors.positive else colors.destructive,
                    maxLines = 1,
                )
            }
        }
        Text(stringResource(metric.label), style = MaterialTheme.typography.bodySmall, color = colors.secondaryLabel)
    }
}

/** Заглушка графика той же высоты: линии сетки и блок под кривую. */
@Composable
internal fun GraphSkeleton() {
    val colors = ReachTheme.colors
    DelayedSkeleton(Modifier.fillMaxWidth().height(GRAPH_HEIGHT)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            repeat(SKELETON_GRID_LINES) { HorizontalDivider(thickness = 0.5.dp, color = colors.separator) }
        }
        SkeletonBox(
            Modifier.fillMaxWidth().height(GRAPH_HEIGHT * 0.6f).align(Alignment.BottomCenter).padding(bottom = 1.dp),
            RoundedCornerShape(8.dp),
        )
    }
}

/** График карточкой с подзаголовком над ней. */
@Composable
private fun GraphCard(item: GraphItem) {
    val colors = ReachTheme.colors
    GroupedSection(header = stringResource(item.title)) {
        Box(Modifier.fillMaxWidth().padding(12.dp)) {
            when (val g = item.state) {
                GraphState.Loading -> GraphSkeleton()
                is GraphState.Ready -> ChartView(g.chart)
                is GraphState.Error -> Text(
                    stringResource(R.string.stats_graph_error),
                    modifier = Modifier.padding(vertical = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryLabel,
                )
            }
        }
    }
}

@Composable
private fun PostRow(post: PostStats, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            post.body?.let { bodySummary(it).replace('\n', ' ') } ?: stringResource(R.string.stats_post),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            color = colors.label,
        )
        Text(
            listOf(
                pluralStringResource(R.plurals.views, post.views, post.views),
                pluralStringResource(R.plurals.stats_shares, post.shares, post.shares),
                pluralStringResource(R.plurals.stats_reactions, post.reactions, post.reactions),
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = colors.secondaryLabel,
        )
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.roundToLong().toDouble()) "%,d".format(AppLocale, value.roundToLong()) else "%.1f".format(AppLocale, value)

private fun formatDate(unix: Int): String = Dates.date(unix.toLong())

private val GRAPH_HEIGHT = 180.dp
private const val SKELETON_GRID_LINES = 5
