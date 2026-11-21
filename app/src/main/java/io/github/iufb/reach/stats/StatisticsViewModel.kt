package io.github.iufb.reach.stats

import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.navigation.StatisticsRoute
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Показатель с динамикой за период. */
data class Metric(@StringRes val label: Int, val value: Double, val previous: Double, val growthPercent: Double, val isPercent: Boolean = false)

/** График: загружается, готов или с ошибкой. */
sealed interface GraphState {
    data object Loading : GraphState
    data class Ready(val chart: Chart) : GraphState
    data class Error(val message: String) : GraphState
}

data class GraphItem(@StringRes val title: Int, val state: GraphState)

/** Пост из «последних»: превью и цифры. */
data class PostStats(val messageId: Long, val body: MessageBody?, val views: Int, val shares: Int, val reactions: Int)

sealed interface StatsState {
    data object Loading : StatsState
    /** Статистика недоступна (мелкий канал, нет прав) — с текстом TDLib. */
    data class Unavailable(val message: String) : StatsState
    data class Ready(val periodStart: Int, val periodEnd: Int, val metrics: List<Metric>, val graphs: List<GraphItem>, val posts: List<PostStats>) : StatsState
}

/** Статистика канала (PRODUCT.md §4.6): обзор, графики, последние посты. */
@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
) : ViewModel() {
    private val chatId = savedStateHandle.toRoute<StatisticsRoute>().chatId

    private val _state = MutableStateFlow<StatsState>(StatsState.Loading)
    val state: StateFlow<StatsState> = _state.asStateFlow()

    private var started = false

    /** Первый показ: [isDark] — цвета графиков под тему. */
    fun load(isDark: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch {
            val stats = runCatching { td.send(TdApi.GetChatStatistics(chatId, isDark)) }
                .onFailure { Log.w(TAG, "getChatStatistics failed", it) }
                .getOrElse {
                    _state.value = StatsState.Unavailable(userErrors.message(it))
                    return@launch
                }
            val channel = stats as? TdApi.ChatStatisticsChannel ?: run {
                _state.value = StatsState.Unavailable("")
                return@launch
            }
            val graphs = listOf(
                R.string.stats_graph_growth to channel.memberCountGraph,
                R.string.stats_graph_followers to channel.joinGraph,
                R.string.stats_graph_notifications to channel.muteGraph,
                R.string.stats_graph_views_by_hour to channel.viewCountByHourGraph,
                R.string.stats_graph_views_by_source to channel.viewCountBySourceGraph,
                R.string.stats_graph_followers_by_source to channel.joinBySourceGraph,
                R.string.stats_graph_languages to channel.languageGraph,
                R.string.stats_graph_interactions to channel.messageInteractionGraph,
                R.string.stats_graph_reactions to channel.messageReactionGraph,
            )
            _state.value = StatsState.Ready(
                periodStart = channel.period.startDate,
                periodEnd = channel.period.endDate,
                metrics = listOf(
                    channel.memberCount.metric(R.string.stats_followers),
                    channel.meanMessageViewCount.metric(R.string.stats_views_per_post),
                    channel.meanMessageShareCount.metric(R.string.stats_shares_per_post),
                    channel.meanMessageReactionCount.metric(R.string.stats_reactions_per_post),
                    Metric(R.string.stats_notifications_enabled, channel.enabledNotificationsPercentage, 0.0, 0.0, isPercent = true),
                ),
                graphs = graphs.map { (title, graph) -> GraphItem(title, graph.immediate()) },
                posts = emptyList(),
            )
            // Асинхронные графики догружаем по токену, параллельно
            graphs.mapIndexed { index, (_, graph) ->
                async {
                    if (graph is TdApi.StatisticalGraphAsync) {
                        val loaded = runCatching { td.send(TdApi.GetStatisticalGraph(chatId, graph.token, 0)) }
                            .getOrElse { TdApi.StatisticalGraphError(userErrors.message(it)) }
                        updateGraph(index, loaded.immediate())
                    }
                }
            }.awaitAll()
            loadPosts(channel.recentInteractions)
        }
    }

    private fun updateGraph(index: Int, graph: GraphState) = _state.update { s ->
        if (s !is StatsState.Ready) s else s.copy(graphs = s.graphs.mapIndexed { i, g -> if (i == index) g.copy(state = graph) else g })
    }

    private suspend fun loadPosts(interactions: Array<TdApi.ChatStatisticsInteractionInfo>) {
        val posts = interactions.mapNotNull { info ->
            val id = (info.objectType as? TdApi.ChatStatisticsObjectTypeMessage)?.messageId ?: return@mapNotNull null
            PostStats(id, null, info.viewCount, info.forwardCount, info.reactionCount)
        }.take(MAX_POSTS)
        if (posts.isEmpty()) return
        val messages = runCatching { td.send(TdApi.GetMessages(chatId, posts.map { it.messageId }.toLongArray())).messages }
            .getOrNull().orEmpty()
        val bodies = messages.filterNotNull().associate { it.id to it.content.toBody() }
        _state.update { s -> if (s is StatsState.Ready) s.copy(posts = posts.map { it.copy(body = bodies[it.messageId]) }) else s }
    }

    private fun TdApi.StatisticalGraph.immediate(): GraphState = when (this) {
        is TdApi.StatisticalGraphData -> parseChart(jsonData)?.let { GraphState.Ready(it) } ?: GraphState.Error("")
        is TdApi.StatisticalGraphError -> GraphState.Error(errorMessage)
        else -> GraphState.Loading
    }

    private fun TdApi.StatisticalValue.metric(@StringRes label: Int) = Metric(label, value, previousValue, growthRatePercentage)

    private companion object {
        const val TAG = "StatisticsViewModel"
        const val MAX_POSTS = 20
    }
}
