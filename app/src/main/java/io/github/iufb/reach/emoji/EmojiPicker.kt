package io.github.iufb.reach.emoji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.SecondaryScrollableTabRow
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
 * Панель эмодзи вместо клавиатуры: поиск, вкладки категорий и сетка.
 * Вкладки прокручивают сетку к своей категории.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EmojiPanel(onPick: (String) -> Unit, modifier: Modifier = Modifier, viewModel: EmojiPickerViewModel = hiltViewModel()) {
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val pick = { emoji: String ->
        viewModel.onPicked(emoji)
        onPick(emoji)
    }

    // Секции сетки: недавние (если есть) и категории; индекс заголовка секции в сетке — для вкладок
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

    // Ищут — открыта клавиатура: панель сжимается до поиска и строки результатов, иначе вместе
    // с клавиатурой она выдавила бы ленту и шапку (#212)
    val height = if (WindowInsets.isImeVisible) SEARCH_HEIGHT else PANEL_HEIGHT
    Column(modifier.fillMaxWidth().height(height)) {
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            placeholder = { Text(stringResource(R.string.emoji_search)) },
            singleLine = true,
        )
        val found = results
        if (found != null) {
            if (found.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.emoji_nothing_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(GridCells.Adaptive(CELL), Modifier.fillMaxSize()) {
                    items(found) { EmojiCell(it, pick) }
                }
            }
            return@Column
        }
        if (sections.isNotEmpty()) {
            SecondaryScrollableTabRow(selectedTabIndex = selectedTab.coerceIn(0, sections.lastIndex), edgePadding = 0.dp) {
                sections.forEachIndexed { i, (icon, _) ->
                    Tab(
                        selected = i == selectedTab,
                        onClick = { scope.launch { grid.scrollToItem(headerIndex[i]) } },
                        text = { Text(icon, fontSize = 20.sp) },
                    )
                }
            }
        }
        LazyVerticalGrid(GridCells.Adaptive(CELL), Modifier.fillMaxSize(), state = grid) {
            sections.forEachIndexed { i, (_, emojis) ->
                item(key = "header-$i", span = { GridItemSpan(maxLineSpan) }) {
                    if (i == 0 && recent.isNotEmpty()) {
                        Text(
                            stringResource(R.string.emoji_recent),
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Box(Modifier.height(4.dp))
                    }
                }
                items(emojis, key = { "$i-$it" }) { EmojiCell(it, pick) }
            }
        }
    }
}

@Composable
private fun EmojiCell(emoji: String, onPick: (String) -> Unit) {
    Box(
        Modifier.aspectRatio(1f).clickable { onPick(emoji) },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 26.sp)
    }
}

private val CELL = 44.dp
private val PANEL_HEIGHT = 320.dp
private val SEARCH_HEIGHT = 120.dp
