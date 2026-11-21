package io.github.iufb.reach.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import io.github.iufb.reach.ui.theme.ReachColors
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Цвета содержимого пузыря (Telegram iOS): во входящих кнопки, волна, прогресс и ссылки — акцентом,
 * в исходящих — цветом меты исходящего (днём зелёным). Медиа и вложенные блоки берут цвет отсюда.
 */
@Immutable
internal data class BubbleStyle(
    /** Кнопки ▶, значок файла, волна, полосы опроса, цитата. */
    val accent: Color,
    /** Значок на [accent]. */
    val onAccent: Color,
    /** Подписи: размер файла, длительность, исполнитель. */
    val secondary: Color,
)

internal fun ReachColors.bubbleStyle(outgoing: Boolean) = if (outgoing) {
    // Ночью мета исходящего светлая — значок на ней цветом самого пузыря
    BubbleStyle(bubbleOutgoingMeta, if (isDark) bubbleOutgoing else onAccent, bubbleOutgoingMeta)
} else {
    BubbleStyle(accent, onAccent, bubbleIncomingMeta)
}

internal val LocalBubbleStyle = compositionLocalOf<BubbleStyle?> { null }

/** Стиль текущего пузыря; вне пузыря (поле ввода, шторки) — входящий, то есть цвета темы. */
@Composable
@ReadOnlyComposable
internal fun bubbleStyle(): BubbleStyle = LocalBubbleStyle.current ?: ReachTheme.colors.bubbleStyle(outgoing = false)
