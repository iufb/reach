package io.github.iufb.reach.emoji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.format.AppLocale
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EmojiPickerViewModel @Inject constructor(private val repository: EmojiRepository) : ViewModel() {

    private val _categories = MutableStateFlow<List<EmojiCategory>>(emptyList())
    val categories: StateFlow<List<EmojiCategory>> = _categories.asStateFlow()

    val recent: StateFlow<List<String>> = repository.recent

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Результаты поиска; `null` — поиска нет, показываем категории. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results: StateFlow<List<String>?> = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .mapLatest { if (it.isBlank()) null else repository.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    init {
        viewModelScope.launch { _categories.value = repository.categories() }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onPicked(emoji: String) {
        repository.addRecent(emoji)
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** Значок вкладки: первый эмодзи категории; у недавних — часы. */
private const val RECENT_ICON = "🕘"

/**
 * Панель эмодзи вместо клавиатуры (Telegram iOS): поиск сверху, сетка, внизу — ряд категорий.
 * Значок категории прокручивает сетку к ней. Высоту задаёт вызывающий: панель занимает место клавиатуры.
 * [onSearchFocusChange] — фокус в поиске: открывается клавиатура, и панель сжимается до строки результатов.
 */
@Composable
fun EmojiPanel(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSearchFocusChange: (Boolean) -> Unit = {},
    viewModel: EmojiPickerViewModel = hiltViewModel(),
) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val pick = { emoji: String ->
        viewModel.onPicked(emoji)
        onPick(emoji)
    }
    val colors = ReachTheme.colors

    // Секции сетки: недавние (если есть) и категории; индекс заголовка секции в сетке — для значков категорий
    val sections = remember(categories, recent) {
        buildList {
            if (recent.isNotEmpty()) add(RECENT_ICON to recent)
            categories.forEach { add((it.emojis.firstOrNull() ?: "?") to it.emojis) }
        }
    }
    val headerIndex = remember(sections) {
        sections.runningFold(0) { acc, (_, emojis) -> acc + 1 + emojis.size }.dropLast(1)
    }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val selectedTab by remember(headerIndex) {
        derivedStateOf { headerIndex.indexOfLast { it <= grid.firstVisibleItemIndex }.coerceAtLeast(0) }
    }

    Column(modifier.fillMaxWidth()) {
        SearchField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)
                .onFocusChanged { onSearchFocusChange(it.isFocused) },
            placeholder = stringResource(R.string.emoji_search),
        )
        val found = results
        if (found != null) {
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.emoji_nothing_found), color = colors.secondaryLabel)
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(CELL), Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
                    items(found) { EmojiCell(it, pick) }
                }
            }
            return@Column
        }
        LazyVerticalGrid(GridCells.Adaptive(CELL), Modifier.weight(1f).padding(horizontal = 6.dp), state = grid) {
            sections.forEachIndexed { i, (_, emojis) ->
                item(key = "header-$i", span = { GridItemSpan(maxLineSpan) }) {
                    val title = if (i == 0 && recent.isNotEmpty()) stringResource(R.string.emoji_recent) else null
                    if (title != null) {
                        Text(
                            title.uppercase(AppLocale),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = colors.secondaryLabel,
                        )
                    } else {
                        Box(Modifier.height(4.dp))
                    }
                }
                items(emojis, key = { "$i-$it" }) { EmojiCell(it, pick) }
            }
        }
        // Все категории разом, как на iOS: значки делят ширину поровну, прокрутки нет
        if (sections.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().height(TAB_ROW).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                sections.forEachIndexed { i, (icon, _) ->
                    val selected = i == selectedTab
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.fill else Color.Transparent)
                            .clickable { scope.launch { grid.scrollToItem(headerIndex[i]) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(icon, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, onPick: (String) -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).clickable { onPick(emoji) },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 28.sp)
    }
}

private val CELL = 44.dp
private val TAB_ROW = 40.dp
