package io.github.iufb.reach.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Круглая стеклянная кнопка «вниз» над полем ввода, как в Telegram iOS (#434): появляется, когда
 * лента отлистана от новых сообщений; [unread] — счётчик непрочитанных над кнопкой.
 */
@Composable
internal fun ScrollToBottomButton(visible: Boolean, unread: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible,
        modifier,
        enter = scaleIn(spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), initialScale = HIDDEN_SCALE) + fadeIn(),
        exit = scaleOut(targetScale = HIDDEN_SCALE) + fadeOut(),
    ) {
        // Отступ сверху — место под счётчик, который выступает над кругом
        Box(Modifier.padding(top = BADGE_OVERHANG)) {
            PillIconButton(painterResource(R.drawable.ic_chevron_down), stringResource(R.string.chat_scroll_to_bottom), onClick)
            if (unread > 0) {
                val colors = ReachTheme.colors
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = -BADGE_OVERHANG)
                        .defaultMinSize(minWidth = BADGE_SIZE, minHeight = BADGE_SIZE)
                        .background(colors.accent, CircleShape)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (unread > MAX_UNREAD) "$MAX_UNREAD+" else unread.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onAccent,
                    )
                }
            }
        }
    }
}

/** Индекс 0 у ленты с `reverseLayout` — самое новое сообщение; издалека сначала прыжок, потом анимация. */
internal suspend fun LazyListState.scrollToNewest() {
    if (firstVisibleItemIndex > JUMP_FROM_ITEMS) scrollToItem(JUMP_FROM_ITEMS)
    animateScrollToItem(0)
}

/** Лента отлистана от новых настолько, что нужна кнопка: дальше второго элемента или больше [awayPx]. */
internal fun LazyListState.isAwayFromNewest(awayPx: Float): Boolean =
    firstVisibleItemIndex >= 2 || firstVisibleItemScrollOffset > awayPx

internal val SCROLL_BUTTON_AWAY = 160.dp
private const val JUMP_FROM_ITEMS = 12
private const val HIDDEN_SCALE = 0.5f
private const val MAX_UNREAD = 999
private val BADGE_SIZE = 20.dp
private val BADGE_OVERHANG = 10.dp
