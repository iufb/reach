package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Экран-список Telegram iOS: плавающая стеклянная шапка с «назад», под ней прокручивается [content],
 * снизу запас до жестовой панели. [loading] — вместо списка [skeleton] (по умолчанию —
 * сгруппированные карточки из строк), с задержкой и crossfade, как в [SkeletonCrossfade].
 * Фон по умолчанию — `groupedBackground` (экраны из карточек: настройки, права, ссылки).
 */
@Composable
fun ListScreen(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    background: Color = ReachTheme.colors.groupedBackground,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    loading: Boolean = false,
    state: LazyListState = rememberLazyListState(),
    /** Тап по заголовку (профиль чата). */
    onTitleClick: (() -> Unit)? = null,
    /** Скелетон на время [loading]: те же отступы, что у [content]. */
    skeleton: LazyListScope.() -> Unit = { groupedSkeleton() },
    content: LazyListScope.() -> Unit,
) {
    GlassScope {
        Box(modifier.fillMaxSize().background(background)) {
            val padding = PaddingValues(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
            )
            SkeletonCrossfade(
                loading = loading,
                modifier = Modifier.fillMaxSize(),
                skeleton = {
                    LazyColumn(Modifier.fillMaxSize().glassSource(), userScrollEnabled = false, contentPadding = padding, content = skeleton)
                },
            ) {
                LazyColumn(Modifier.fillMaxSize().glassSource().imePadding(), state = state, contentPadding = padding, content = content)
            }
            ReachTopBar(title = title, navigation = { BackPill(onBack) }, actions = actions, background = background, onTitleClick = onTitleClick)
            snackbar?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
        }
    }
}

/**
 * Скелетон экрана из карточек: подзаголовки и строки с полосками разной длины.
 * [sections] — число строк в каждой карточке.
 */
fun LazyListScope.groupedSkeleton(sections: List<Int> = listOf(3, 4, 2)) {
    var seed = 0
    sections.forEachIndexed { section, rows ->
        val base = seed
        item(key = "skeleton:header:$section", contentType = "skeletonHeader") {
            Box(Modifier.padding(start = 32.dp, top = 16.dp, bottom = 10.dp)) {
                SkeletonBox(Modifier.width(60.dp + 50.dp * skeletonFraction(base, 0f, 1f)).height(10.dp))
            }
        }
        items(rows, key = { "skeleton:$section:$it" }, contentType = { "skeletonRow" }) { row ->
            GroupedRow(first = row == 0, last = row == rows - 1) {
                Box(Modifier.fillMaxWidth().height(SKELETON_ROW_HEIGHT).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(base + row + 1, 0.3f, 0.65f)).height(12.dp))
                }
            }
        }
        seed += rows + 1
    }
}

private val SKELETON_ROW_HEIGHT = 44.dp

/** Подзаголовок карточки в ленивом списке — как `header` у [GroupedSection]. */
fun LazyListScope.groupedHeader(text: String, key: Any? = null) {
    item(key = key ?: "header:$text") {
        Text(
            text.uppercase(),
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ReachTheme.colors.secondaryLabel,
        )
    }
}

/** Пояснение под карточкой в ленивом списке — как `footer` у [GroupedSection]. */
fun LazyListScope.groupedFooter(text: String, key: Any? = null) {
    item(key = key ?: "footer:$text") {
        Text(
            text,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 6.dp, bottom = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = ReachTheme.colors.secondaryLabel,
        )
    }
}

/**
 * Строки одной карточки сгруппированного списка, но лениво: первая и последняя скругляют карточку,
 * между строками — разделитель от [dividerInset]. Для длинных списков (участники, ссылки).
 */
fun <T> LazyListScope.groupedItems(
    items: List<T>,
    key: ((T) -> Any)? = null,
    dividerInset: Dp = 16.dp,
    content: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = key?.let { k -> { _: Int, item: T -> k(item) } }) { index, item ->
        GroupedRow(first = index == 0, last = index == items.lastIndex, dividerInset = dividerInset) { content(item) }
    }
}

/** Одна строка карточки: фон `cell`, скругление у краёв карточки, разделитель снизу, если не последняя. */
@Composable
fun GroupedRow(first: Boolean, last: Boolean, dividerInset: Dp = 16.dp, content: @Composable () -> Unit) {
    val colors = ReachTheme.colors
    val shape = RoundedCornerShape(
        topStart = if (first) GroupedCorner else 0.dp,
        topEnd = if (first) GroupedCorner else 0.dp,
        bottomStart = if (last) GroupedCorner else 0.dp,
        bottomEnd = if (last) GroupedCorner else 0.dp,
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(shape).background(colors.cell)) {
        content()
        if (!last) HorizontalDivider(Modifier.padding(start = dividerInset), thickness = 0.5.dp, color = colors.separator)
    }
}
