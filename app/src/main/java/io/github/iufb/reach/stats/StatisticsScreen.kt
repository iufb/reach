package io.github.iufb.reach.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.ui.theme.reachBarColors
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(onBack: () -> Unit, onOpenPost: (Long) -> Unit, viewModel: StatisticsViewModel = hiltViewModel()) {
    val dark = isSystemInDarkTheme()
    LaunchedEffect(viewModel) { viewModel.load(dark) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.stats_title)) },
            )
        },
    ) { padding ->
        when (val s = state) {
            StatsState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is StatsState.Unavailable -> Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.stats_unavailable), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is StatsState.Ready -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.stats_period, formatDate(s.periodStart), formatDate(s.periodEnd)),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(s.metrics.chunked(2)) { row ->
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { MetricCard(it, Modifier.weight(1f)) }
                        if (row.size == 1) Box(Modifier.weight(1f))
                    }
                }
                items(s.graphs) { graph -> GraphCard(graph) }
                if (s.posts.isNotEmpty()) {
                    item {
                        Text(
                            stringResource(R.string.stats_recent_posts),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    items(s.posts, key = { it.messageId }) { post -> PostRow(post) { onOpenPost(post.messageId) } }
                }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, modifier: Modifier) {
    Column(
        modifier.background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp)).padding(12.dp),
    ) {
        Text(
            if (metric.isPercent) "${formatNumber(metric.value)}%" else formatNumber(metric.value),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        if (!metric.isPercent && metric.value != metric.previous) {
            val diff = metric.value - metric.previous
            Text(
                "${if (diff >= 0) "+" else "−"}${formatNumber(abs(diff))} (${formatNumber(abs(metric.growthPercent))}%)",
                style = MaterialTheme.typography.labelSmall,
                color = if (diff >= 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
            )
        }
        Text(stringResource(metric.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GraphCard(item: GraphItem) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(stringResource(item.title), style = MaterialTheme.typography.titleSmall)
        when (val g = item.state) {
            GraphState.Loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            is GraphState.Ready -> ChartView(g.chart, Modifier.padding(top = 8.dp))
            is GraphState.Error -> Text(
                stringResource(R.string.stats_graph_error),
                modifier = Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PostRow(post: PostStats, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            post.body?.let { bodySummary(it).replace('\n', ' ') } ?: stringResource(R.string.stats_post),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            listOf(
                pluralStringResource(R.plurals.views, post.views, post.views),
                pluralStringResource(R.plurals.stats_shares, post.shares, post.shares),
                pluralStringResource(R.plurals.stats_reactions, post.reactions, post.reactions),
            ).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun formatNumber(value: Double): String =
    if (value == value.roundToLong().toDouble()) "%,d".format(Locale.getDefault(), value.roundToLong()) else "%.1f".format(Locale.getDefault(), value)

private fun formatDate(unix: Int): String =
    Instant.ofEpochSecond(unix.toLong()).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
