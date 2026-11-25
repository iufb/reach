package io.github.iufb.reach.stickers

import android.content.Context
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.pluralStringResource
import io.github.iufb.reach.ui.components.DelayedSkeleton
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.components.SkeletonBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.airbnb.lottie.compose.LottieAnimation
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.rememberTgsComposition
import io.github.iufb.reach.ui.format.AppLocale
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StickerPanelViewModel @Inject constructor(
    private val repository: StickersRepository,
    @ApplicationContext context: Context,
) : ViewModel() {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Тренды скрыты крестиком; флаг снимается, когда приходят новые непросмотренные паки. */
    private val trendingHidden = MutableStateFlow(prefs.getBoolean(KEY_TRENDING_HIDDEN, false))

    private fun shownTrending(trending: TrendingStickers, hidden: Boolean) =
        if (trendingShown(trending.sets, hidden)) trending.sets else emptyList()

    /** Секции сетки; сразу из уже загруженных списков, чтобы панель не открывалась пустой. */
    val sections: StateFlow<List<StickerSection>> =
        combine(
            repository.recent,
            repository.favorites,
            repository.installed,
            repository.sets,
            combine(repository.trending, trendingHidden, ::shownTrending),
            ::stickerSections,
        ).stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            stickerSections(
                repository.recent.value,
                repository.favorites.value,
                repository.installed.value,
                repository.sets.value,
                shownTrending(repository.trending.value, trendingHidden.value),
            ),
        )

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** Найденное для запроса (#396): пара «запрос — результат»; `null` — поиска нет. */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val results: StateFlow<Pair<String, StickerSearch>?> = _query
        .debounce(SEARCH_DEBOUNCE_MS)
        .mapLatest { q -> if (q.isBlank()) null else q.trim() to repository.search(q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun onQueryChange(value: String) {
        _query.value = value
    }

    /** «Добавить» у найденного пака. */
    fun install(setId: Long) {
        viewModelScope.launch { runCatching { repository.install(setId) } }
    }

    /** Следующая страница трендов, когда строку долистали до конца. */
    fun loadMoreTrending() {
        viewModelScope.launch { repository.loadMoreTrending() }
    }

    /** Паки трендов побывали на экране (или открыты) — снять отметку «новый». */
    fun viewTrending(setIds: List<Long>) = repository.viewTrending(setIds)

    /** Крестик: все загруженные тренды просмотрены, секция скрыта до новых. */
    fun hideTrending() {
        repository.viewTrending(repository.trending.value.sets.map { it.id })
        setTrendingHidden(true)
    }

    private fun setTrendingHidden(hidden: Boolean) {
        trendingHidden.value = hidden
        prefs.edit().putBoolean(KEY_TRENDING_HIDDEN, hidden).apply()
    }

    init {
        // Пришли новые непросмотренные тренды — секция возвращается насовсем, а не до их просмотра
        viewModelScope.launch {
            repository.trending.collect { trending ->
                if (trendingHidden.value && trending.sets.any { !it.isViewed }) setTrendingHidden(false)
            }
        }
        // В списке установленных у паков только обложки — догружаем полные, сверху вниз
        viewModelScope.launch {
            repository.installed.collect { installed ->
                installed.forEach { if (it.id !in repository.sets.value) repository.set(it.id) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SEARCH_DEBOUNCE_MS = 300L
        const val PREFS = "sticker_panel"
        const val KEY_TRENDING_HIDDEN = "trending_hidden"
    }
}

/**
 * Вкладка «Стикеры» панели под полем ввода (Telegram iOS): сверху полоса значков — недавние,
 * избранные, паки; под ней сетка в [COLUMNS] колонок с заголовками секций капсом. Тап по стикеру —
 * [onSend]; панель остаётся открытой. Долгое нажатие — превью с действиями ([StickerPreview]),
 * «Открыть пак» из него — тот же [StickerSetSheet], что и у трендов. Значок прокручивает сетку к секции, а при прокрутке сетки
 * подсвечивается значок текущей секции.
 *
 * Сверху — тренды ([TrendingSection]): строка паков, тап открывает [StickerSetSheet] с кнопкой
 * «Добавить», крестик скрывает секцию до новых трендов.
 *
 * Анимации играют только у ячеек на экране: Lazy-сетка убирает ушедшие (Lottie и декодер WebM
 * останавливаются), а заранее подготовленные ряды за краем получают `animate = false`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StickerPanel(
    onSend: (StickerInfo) -> Unit,
    modifier: Modifier = Modifier,
    /** Фокус в поиске: открывается клавиатура, и панель ужимается до ряда результатов (как у эмодзи). */
    onSearchFocusChange: (Boolean) -> Unit = {},
    viewModel: StickerPanelViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }
    // Ужатый вид — только пока поиск в фокусе И клавиатура на экране: убрали клавиатуру («назад»),
    // фокус остался, а панель должна вернуться во всю высоту с сеткой и паками
    val imeVisible = WindowInsets.isImeVisible
    val compact = focused && imeVisible
    LaunchedEffect(compact) { onSearchFocusChange(compact) }
    Column(modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            modifier = Modifier
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)
                .onFocusChanged { focused = it.isFocused },
            placeholder = stringResource(R.string.stickers_search),
        )
        if (query.isBlank()) {
            StickerBrowse(onSend, Modifier.weight(1f), viewModel)
        } else {
            StickerSearchResults(
                query = query,
                results = results,
                compact = compact,
                onSend = onSend,
                onInstall = viewModel::install,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Поиск в панели стикеров (#396). Пока поле в фокусе (над клавиатурой) — один ряд найденных стикеров;
 * клавиатуру убрали — сетка в [COLUMNS] колонок и ниже найденные паки с «Добавить». Тап — отправить,
 * долгое нажатие — превью; пак — лист пака. Пока ответа нет — скелетон, пусто — «Ничего не нашлось».
 */
@Composable
private fun StickerSearchResults(
    query: String,
    results: Pair<String, StickerSearch>?,
    compact: Boolean,
    onSend: (StickerInfo) -> Unit,
    onInstall: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ReachTheme.colors
    var openedSet by remember { mutableStateOf<Long?>(null) }
    openedSet?.let { StickerSetSheet(it, onDismiss = { openedSet = null }, onSend = onSend) }
    val preview = rememberStickerPreviewState()
    StickerPreview(preview, onSend = onSend, onOpenSet = { openedSet = it.setId })
    // Ответ для другого (прошлого) запроса не показываем — ждём свой
    val found = results?.takeIf { it.first == query.trim() }?.second
    when {
        found == null -> SearchSkeleton(compact, modifier)
        found.isEmpty -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.emoji_nothing_found), color = colors.secondaryLabel)
        }
        compact -> LazyRow(
            modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items(found.stickers, key = { it.file.uniqueId }) { sticker ->
                StickerCell(
                    sticker,
                    Modifier
                        .size(COMPACT_CELL)
                        .clip(RoundedCornerShape(10.dp))
                        .stickerCellGestures(preview, StickerPick(sticker)) { onSend(sticker) },
                )
            }
        }
        else -> LazyVerticalGrid(GridCells.Fixed(COLUMNS), modifier.fillMaxSize(), contentPadding = GRID_PADDING) {
            items(found.stickers, key = { "sticker:${it.file.uniqueId}" }, contentType = { "sticker" }) { sticker ->
                StickerCell(
                    sticker,
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .stickerCellGestures(preview, StickerPick(sticker)) { onSend(sticker) },
                )
            }
            if (found.sets.isNotEmpty()) {
                item(key = "sets-header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionTitle(stringResource(R.string.stickers_found_sets))
                }
                items(found.sets, key = { "set:${it.id}" }, span = { GridItemSpan(maxLineSpan) }, contentType = { "set" }) { set ->
                    FoundSetRow(set, onOpen = { openedSet = set.id }, onInstall = { onInstall(set.id) })
                }
            }
        }
    }
}

/** Найденный пак: обложка, название, число стикеров и «Добавить» (у добавленного — «Добавлен»). */
@Composable
private fun FoundSetRow(set: StickerSetInfo, onOpen: () -> Unit, onInstall: () -> Unit) {
    val colors = ReachTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onOpen)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(SET_COVER), contentAlignment = Alignment.Center) { SetCover(set, Modifier.size(SET_COVER)) }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(set.title, style = MaterialTheme.typography.bodyLarge, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pluralStringResource(R.plurals.stickers_count, set.size, set.size),
                style = MaterialTheme.typography.bodySmall,
                color = colors.secondaryLabel,
            )
        }
        if (set.isInstalled) {
            Text(stringResource(R.string.stickers_set_added), style = MaterialTheme.typography.labelLarge, color = colors.secondaryLabel)
        } else {
            Text(
                stringResource(R.string.stickers_set_add_short),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(colors.accent)
                    .clickable(onClick = onInstall)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = colors.onAccent,
            )
        }
    }
}

/** Скелетон результатов: ряд (над клавиатурой) или сетка ячеек, проявляется после задержки. */
@Composable
private fun SearchSkeleton(compact: Boolean, modifier: Modifier) {
    DelayedSkeleton(modifier.fillMaxWidth()) {
        if (compact) {
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(SKELETON_ROW_CELLS) { SkeletonBox(Modifier.size(COMPACT_CELL - 8.dp), RoundedCornerShape(12.dp)) }
            }
        } else {
            Column(Modifier.padding(GRID_PADDING), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(SKELETON_GRID_ROWS) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(COLUMNS) { SkeletonBox(Modifier.weight(1f).aspectRatio(1f), RoundedCornerShape(12.dp)) }
                    }
                }
            }
        }
    }
}

/** Вкладка без поиска: недавние, избранные, паки и тренды. */
@Composable
private fun StickerBrowse(
    onSend: (StickerInfo) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StickerPanelViewModel,
) {
    val sectionsState = viewModel.sections.collectAsStateWithLifecycle()
    // Снимок списка на эту композицию: контент Lazy-сетки вычисляется вне композиции, и чтение
    // состояния там дало бы список новее, чем `starts` (падение по индексу при добавлении пака)
    val sections = sectionsState.value
    val colors = ReachTheme.colors
    var openedSet by remember { mutableStateOf<Long?>(null) }
    openedSet?.let { StickerSetSheet(it, onDismiss = { openedSet = null }, onSend = onSend) }
    if (sections.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.stickers_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.secondaryLabel,
                textAlign = TextAlign.Center,
            )
        }
        return
    }
    val starts = remember(sections) { sectionStarts(sections) }
    val preview = rememberStickerPreviewState()
    val picks = remember(sections) { sectionPicks(sections) }
    StickerPreview(preview, onSend = onSend, onOpenSet = { openedSet = it.setId })
    val grid = rememberLazyGridState()
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Выбранный тапом значок держится, пока сетку не потянут: короткий последний пак не докрутить
    // до верха, и по позиции сетки подсветился бы предыдущий
    var tapped by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(grid) {
        grid.interactionSource.interactions.collect { if (it is DragInteraction.Start) tapped = null }
    }
    val scrolled by remember(starts) { derivedStateOf { sectionAt(starts, grid.firstVisibleItemIndex) } }
    val selected = tapped?.takeIf { it < sections.size } ?: scrolled
    val visible by remember {
        derivedStateOf {
            val items = grid.layoutInfo.visibleItemsInfo
            if (items.isEmpty()) IntRange.EMPTY else items.first().index..items.last().index
        }
    }
    LaunchedEffect(selected) {
        val shown = strip.layoutInfo.visibleItemsInfo
        val fully = shown.filter { it.offset >= 0 && it.offset + it.size <= strip.layoutInfo.viewportEndOffset }
        if (fully.none { it.index == selected }) strip.animateScrollToItem((selected - 2).coerceAtLeast(0))
    }

    Column(modifier.fillMaxSize()) {
        LazyRow(
            Modifier.fillMaxWidth().height(STRIP_HEIGHT),
            state = strip,
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(sections, key = { _, section -> section.key }) { i, section ->
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(ICON_BOX)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (i == selected) colors.fill else Color.Transparent)
                        .clickable {
                            tapped = i
                            scope.launch { grid.scrollToItem(starts[i]) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    SectionIcon(section)
                }
            }
        }
        LazyVerticalGrid(
            GridCells.Fixed(COLUMNS),
            Modifier.fillMaxWidth().weight(1f).stickerPreviewDrag(preview, grid, GRID_PADDING) { picks[it] },
            state = grid,
            contentPadding = GRID_PADDING,
        ) {
            sections.forEachIndexed { s, section ->
                val start = starts[s]
                if (section.kind == StickerSection.Kind.Trending) {
                    item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "trending") {
                        TrendingSection(
                            section.trending,
                            onOpen = { set ->
                                viewModel.viewTrending(listOf(set.id))
                                openedSet = set.id
                            },
                            onHide = viewModel::hideTrending,
                            onLoadMore = viewModel::loadMoreTrending,
                            onSeen = viewModel::viewTrending,
                        )
                    }
                    return@forEachIndexed
                }
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader(section)
                }
                val recent = section.kind == StickerSection.Kind.Recent
                itemsIndexed(
                    section.stickers,
                    key = { _, sticker -> stickerKey(section, sticker) },
                    contentType = { _, _ -> "sticker" },
                ) { i, sticker ->
                    StickerCell(
                        sticker,
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .stickerCellGestures(preview, StickerPick(sticker, recent)) { onSend(sticker) },
                        animate = start + 1 + i in visible,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: StickerSection) {
    val title = when (section.kind) {
        StickerSection.Kind.Recent -> stringResource(R.string.stickers_recent)
        StickerSection.Kind.Favorites -> stringResource(R.string.stickers_favorites)
        StickerSection.Kind.Set -> section.set?.title.orEmpty()
        StickerSection.Kind.Trending -> stringResource(R.string.stickers_trending)
    }
    SectionTitle(title)
}

@Composable
private fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        title.uppercase(AppLocale),
        modifier = modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = ReachTheme.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Секция трендов (Telegram iOS): заголовок с крестиком и горизонтальная строка паков. Обложки
 * статичные — без лишней нагрузки на анимации (#304). Непросмотренный пак отмечен точкой; паки,
 * побывавшие на экране, отмечаются просмотренными, когда секция уходит с экрана ([onSeen]).
 * Долистали почти до конца — [onLoadMore].
 */
@Composable
private fun TrendingSection(
    sets: List<StickerSetInfo>,
    onOpen: (StickerSetInfo) -> Unit,
    onHide: () -> Unit,
    onLoadMore: () -> Unit,
    onSeen: (List<Long>) -> Unit,
) {
    val colors = ReachTheme.colors
    val row = rememberLazyListState()
    val seen = remember { mutableSetOf<Long>() }
    val currentSets by rememberUpdatedState(sets)
    val currentOnLoadMore by rememberUpdatedState(onLoadMore)
    val currentOnSeen by rememberUpdatedState(onSeen)
    LaunchedEffect(row) {
        snapshotFlow { row.layoutInfo.visibleItemsInfo.map { it.index } }.collect { indices ->
            val shown = currentSets
            indices.forEach { i -> shown.getOrNull(i)?.let { seen += it.id } }
            if (indices.isNotEmpty() && indices.last() >= shown.size - TRENDING_LOAD_AHEAD) currentOnLoadMore()
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (seen.isNotEmpty()) currentOnSeen(seen.toList()) }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.stickers_trending), Modifier.weight(1f))
            Icon(
                painterResource(R.drawable.ic_close),
                stringResource(R.string.stickers_trending_hide),
                Modifier
                    .padding(end = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onHide)
                    .padding(6.dp),
                tint = colors.secondaryLabel,
            )
        }
        LazyRow(Modifier.fillMaxWidth(), state = row) {
            items(sets, key = { it.id }) { set ->
                Box(
                    Modifier
                        .size(TRENDING_CELL)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpen(set) },
                    contentAlignment = Alignment.Center,
                ) {
                    SetCover(set, Modifier.size(TRENDING_COVER))
                    if (!set.isViewed) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.accent),
                        )
                    }
                }
            }
        }
    }
}

/** Значок секции: тренды, часы, звезда или обложка пака. */
@Composable
private fun SectionIcon(section: StickerSection) {
    val tint = ReachTheme.colors.secondaryLabel
    when (section.kind) {
        StickerSection.Kind.Trending -> Icon(painterResource(R.drawable.ic_trending), null, Modifier.size(24.dp), tint = tint)
        StickerSection.Kind.Recent -> Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(24.dp), tint = tint)
        StickerSection.Kind.Favorites -> Icon(painterResource(R.drawable.ic_star), null, Modifier.size(24.dp), tint = tint)
        StickerSection.Kind.Set -> {
            val set = section.set
            if (set != null) {
                SetCover(set, Modifier.size(ICON))
            } else {
                section.stickers.firstOrNull()?.let {
                    StickerImage(it, Modifier.size(ICON), animate = false, emojiStyle = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}

/** Обложка пака, всегда статичная: TGS — первый кадр; WebM и пак без обложки — первый стикер. */
@Composable
private fun SetCover(set: StickerSetInfo, modifier: Modifier) {
    val thumbnail = set.thumbnail?.takeIf { !it.isWebm }
    when {
        thumbnail == null -> set.stickers.firstOrNull()?.let {
            StickerImage(it, modifier, animate = false, emojiStyle = MaterialTheme.typography.titleLarge)
        }
        thumbnail.isTgs -> {
            val composition = rememberTgsComposition(thumbnail.file)
            if (composition != null) LottieAnimation(composition, progress = { 0f }, modifier = modifier)
        }
        else -> AsyncImage(thumbnail.file, set.title, modifier, contentScale = ContentScale.Fit)
    }
}

/** Ключ ячейки стикера в сетке: стикер может быть и в недавних, и в паке. */
private fun stickerKey(section: StickerSection, sticker: StickerInfo): String = "${section.key}:${sticker.file.uniqueId}"

/** Стикер по ключу ячейки — для перелистывания превью пальцем. */
private fun sectionPicks(sections: List<StickerSection>): Map<Any, StickerPick> = buildMap {
    sections.forEach { section ->
        val recent = section.kind == StickerSection.Kind.Recent
        section.stickers.forEach { put(stickerKey(section, it), StickerPick(it, recent)) }
    }
}

private val GRID_PADDING = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp)

/** Колонок в сетке, как в Telegram iOS. */
private const val COLUMNS = 5
private val STRIP_HEIGHT = 44.dp
private val ICON_BOX = 38.dp
private val ICON = 28.dp
private val TRENDING_CELL = 64.dp
private val TRENDING_COVER = 52.dp
private val COMPACT_CELL = 64.dp
private val SET_COVER = 44.dp
private const val SKELETON_ROW_CELLS = 5
private const val SKELETON_GRID_ROWS = 3

/** За сколько паков до конца строки трендов грузить следующую страницу. */
private const val TRENDING_LOAD_AHEAD = 4
