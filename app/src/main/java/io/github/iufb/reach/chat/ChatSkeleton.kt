package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.components.BubbleShape
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.skeletonFraction
import io.github.iufb.reach.ui.theme.ReachTheme

/** Заглушка пузыря: сторона, число строк и есть ли хвостик (последний в серии). */
private data class SkeletonBubble(val outgoing: Boolean, val lines: Int, val tail: Boolean, val seriesStart: Boolean)

/**
 * Серии входящих/исходящих по кругу — как в живой переписке. Индекс 0 — самое новое (низ ленты).
 * В канале — только посты.
 */
private fun skeletonBubble(index: Int, isChannel: Boolean): SkeletonBubble {
    if (isChannel) return SkeletonBubble(outgoing = false, lines = 2 + index % 3, tail = true, seriesStart = true)
    val side = { i: Int -> SIDES[i % SIDES.size] }
    // reverseLayout: соседи по индексу — i-1 новее (ниже), i+1 старше (выше).
    // Хвостик — у самого нового в серии, отступ сверху — у самого старого
    return SkeletonBubble(
        outgoing = side(index),
        lines = LINES[index % LINES.size],
        tail = index == 0 || side(index - 1) != side(index),
        seriesStart = side(index + 1) != side(index),
    )
}

private val SIDES = listOf(true, false, false, true, true, false, true, false, false, false, true)
private val LINES = listOf(1, 2, 1, 1, 3, 1, 2, 1, 1, 2, 1, 3)

/** Скелетон ленты: пузыри у поля ввода, сколько влезает на экран. */
@Composable
internal fun ChatSkeleton(isChannel: Boolean, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val count = LocalConfiguration.current.screenHeightDp / MIN_ITEM_HEIGHT + 1
    LazyColumn(
        modifier.fillMaxSize(),
        reverseLayout = true,
        userScrollEnabled = false,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
    ) {
        items(count, contentType = { "skeleton" }) { SkeletonBubbleRow(it, isChannel) }
    }
}

/** Одна заглушка пузыря: те же отступы, ширина и форма, что у настоящего (пузырь или пост канала). */
@Composable
internal fun SkeletonBubbleRow(index: Int, isChannel: Boolean, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    val bubble = skeletonBubble(index, isChannel)
    // Пост канала — пузырь пошире обычного, но не на весь экран (#392)
    val fraction = if (isChannel) skeletonFraction(index, 0.7f, 0.85f) else skeletonFraction(index, 0.35f, 0.7f) + 0.1f * (bubble.lines - 1)
    val base = if (bubble.outgoing) colors.bubbleOutgoing else colors.bubbleIncoming
    // Полупрозрачные поверх обоев: читается как «здесь будут сообщения», а не как сами сообщения
    val glow = Color.White.copy(alpha = if (colors.isDark) 0.12f else 0.8f)
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = if (bubble.seriesStart) SERIES_GAP else 0.dp),
        horizontalArrangement = if (bubble.outgoing) Arrangement.End else Arrangement.Start,
    ) {
        SkeletonBox(
            Modifier
                .widthIn(max = if (isChannel) MAX_POST else MAX_BUBBLE)
                .fillMaxWidth(fraction.coerceAtMost(1f))
                .height(bubbleHeight(bubble.lines, isChannel)),
            shape = BubbleShape(bubble.outgoing, bubble.tail),
            color = base.copy(alpha = SKELETON_ALPHA),
            highlight = glow,
        )
    }
}

private fun bubbleHeight(lines: Int, isChannel: Boolean): Dp = (if (isChannel) 30.dp else 16.dp) + LINE_HEIGHT * lines

private const val MIN_ITEM_HEIGHT = 40
private const val SKELETON_ALPHA = 0.55f
private val LINE_HEIGHT = 20.dp
private val MAX_BUBBLE = 320.dp
private val MAX_POST = 340.dp
private val SERIES_GAP = 6.dp
