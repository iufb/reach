package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

/**
 * Барабан выбора iOS: список с доводкой к центру, выбранная строка — на полосе `fill` посередине,
 * дальние строки бледнеют. [onSelect] — когда барабан остановился на новой строке.
 */
@Composable
fun WheelPicker(
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    itemHeight: Dp = WHEEL_ITEM_HEIGHT,
    visibleCount: Int = WHEEL_VISIBLE,
) {
    val colors = ReachTheme.colors
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    // Строка в центре: первая видимая плюс то, насколько она уже уехала вверх
    val centered by remember {
        derivedStateOf {
            val shift = if (state.firstVisibleItemScrollOffset > itemPx / 2) 1 else 0
            (state.firstVisibleItemIndex + shift).coerceIn(0, (items.size - 1).coerceAtLeast(0))
        }
    }
    val select by rememberUpdatedState(onSelect)
    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrollInProgress) null else centered }
            .distinctUntilChanged()
            .collect { index -> if (index != null) select(index) }
    }
    val padding = itemHeight * (visibleCount / 2)
    Box(modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxWidth().height(itemHeight).background(colors.fill, RoundedCornerShape(10.dp)))
        LazyColumn(
            state = state,
            flingBehavior = rememberSnapFlingBehavior(state),
            contentPadding = PaddingValues(vertical = padding),
            modifier = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(items) { index, label ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .graphicsLayer {
                            val distance = abs(index - (state.firstVisibleItemIndex + state.firstVisibleItemScrollOffset / itemPx))
                            alpha = (1f - distance * FADE_PER_ROW).coerceIn(MIN_ALPHA, 1f)
                        }
                        .semantics {
                            role = Role.RadioButton
                            this.selected = index == centered
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = colors.label, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

private val WHEEL_ITEM_HEIGHT = 40.dp
private const val WHEEL_VISIBLE = 5
private const val FADE_PER_ROW = 0.3f
private const val MIN_ALPHA = 0.25f
