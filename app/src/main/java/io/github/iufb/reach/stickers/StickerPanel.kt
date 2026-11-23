package io.github.iufb.reach.stickers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.rememberTgsComposition
import io.github.iufb.reach.ui.format.AppLocale
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StickerPanelViewModel @Inject constructor(private val repository: StickersRepository) : ViewModel() {

    /** Секции сетки; сразу из уже загруженных списков, чтобы панель не открывалась пустой. */
    val sections: StateFlow<List<StickerSection>> =
        combine(repository.recent, repository.favorites, repository.installed, repository.sets, ::stickerSections)
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                stickerSections(
                    repository.recent.value,
                    repository.favorites.value,
                    repository.installed.value,
                    repository.sets.value,
                ),
            )

    init {
        // В списке установленных у паков только обложки — догружаем полные, сверху вниз
        viewModelScope.launch {
            repository.installed.collect { installed ->
                installed.forEach { if (it.id !in repository.sets.value) repository.set(it.id) }
            }
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Вкладка «Стикеры» панели под полем ввода (Telegram iOS): сверху полоса значков — недавние,
 * избранные, паки; под ней сетка в [COLUMNS] колонок с заголовками секций капсом. Тап по стикеру —
 * [onSend]; панель остаётся открытой. Значок прокручивает сетку к секции, а при прокрутке сетки
 * подсвечивается значок текущей секции.
 *
 * Анимации играют только у ячеек на экране: Lazy-сетка убирает ушедшие (Lottie и декодер WebM
 * останавливаются), а заранее подготовленные ряды за краем получают `animate = false`.
 */
@Composable
fun StickerPanel(
    onSend: (StickerInfo) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StickerPanelViewModel = hiltViewModel(),
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val colors = ReachTheme.colors
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
            Modifier.fillMaxWidth().weight(1f),
            state = grid,
            contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 8.dp),
        ) {
            sections.forEachIndexed { s, section ->
                val start = starts[s]
                item(key = "header-${section.key}", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    SectionHeader(section)
                }
                itemsIndexed(
                    section.stickers,
                    key = { _, sticker -> "${section.key}:${sticker.file.uniqueId}" },
                    contentType = { _, _ -> "sticker" },
                ) { i, sticker ->
                    StickerCell(
                        sticker,
                        Modifier.clip(RoundedCornerShape(10.dp)).clickable { onSend(sticker) },
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
    }
    Text(
        title.uppercase(AppLocale),
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 10.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = ReachTheme.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Значок секции: часы, звезда или обложка пака (статично; без обложки — первый стикер). */
@Composable
private fun SectionIcon(section: StickerSection) {
    val tint = ReachTheme.colors.secondaryLabel
    when (section.kind) {
        StickerSection.Kind.Recent -> Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(24.dp), tint = tint)
        StickerSection.Kind.Favorites -> Icon(painterResource(R.drawable.ic_star), null, Modifier.size(24.dp), tint = tint)
        StickerSection.Kind.Set -> {
            val thumbnail = section.set?.thumbnail?.takeIf { !it.isWebm }
            val iconModifier = Modifier.size(ICON)
            when {
                thumbnail == null -> section.stickers.firstOrNull()?.let {
                    StickerImage(it, iconModifier, animate = false, emojiStyle = MaterialTheme.typography.titleLarge)
                }
                thumbnail.isTgs -> {
                    val composition = rememberTgsComposition(thumbnail.file)
                    if (composition != null) LottieAnimation(composition, progress = { 0f }, modifier = iconModifier)
                }
                else -> AsyncImage(thumbnail.file, section.set?.title, iconModifier, contentScale = ContentScale.Fit)
            }
        }
    }
}

/** Колонок в сетке, как в Telegram iOS. */
private const val COLUMNS = 5
private val STRIP_HEIGHT = 44.dp
private val ICON_BOX = 38.dp
private val ICON = 28.dp
