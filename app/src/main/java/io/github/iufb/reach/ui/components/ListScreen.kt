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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
 * снизу запас до жестовой панели. [loading] — вместо списка индикатор по центру.
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
    content: LazyListScope.() -> Unit,
) {
    val colors = ReachTheme.colors
    GlassScope {
        Box(modifier.fillMaxSize().background(background)) {
            if (loading) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().glassSource().imePadding(),
                    state = state,
                    contentPadding = PaddingValues(
                        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight,
                        bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 24.dp,
                    ),
                    content = content,
                )
            }
            ReachTopBar(title = title, navigation = { BackPill(onBack) }, actions = actions, background = background, onTitleClick = onTitleClick)
            snackbar?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) }
        }
    }
}

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
