package io.github.iufb.reach.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Спойлеры (PRODUCT.md §4.3): скрытое закрыто мерцающей «пылью» и раскрывается по тапу.
 * Анимация рисуется только в draw-фазе (без рекомпозиций) и только у того, что на экране:
 * ушедшие из ленты элементы LazyColumn не компонуются.
 */

/** Фаза мерцания 0..1, по кругу. */
@Composable
internal fun rememberSpoilerPhase(): State<Float> =
    rememberInfiniteTransition(label = "spoiler").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SPOILER_PERIOD_MS, easing = LinearEasing)),
        label = "spoilerPhase",
    )

/**
 * Частицы в [area]: положения псевдослучайны, но постоянны для области; каждая мигает в своей фазе
 * и чуть дрейфует. Плотность — по площади, с потолком на случай большого текста.
 */
internal fun DrawScope.drawSpoilerParticles(area: Rect, phase: Float, color: Color) {
    if (area.isEmpty) return
    val step = 6.dp.toPx()
    val count = ((area.width * area.height) / (step * step)).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
    val radius = 0.9.dp.toPx()
    val drift = 2.dp.toPx()
    for (i in 0 until count) {
        val seed = i * 2654435761L
        val x = area.left + fraction(seed) * area.width
        val y = area.top + fraction(seed shr 7) * area.height
        val own = (phase + fraction(seed shr 13)) % 1f
        val alpha = sin(own * PI).toFloat()
        val angle = fraction(seed shr 19) * 2 * PI
        val dx = (kotlin.math.cos(angle) * drift * own).toFloat()
        val dy = (sin(angle) * drift * own).toFloat()
        drawCircle(color.copy(alpha = color.alpha * alpha), radius, Offset(x + dx, y + dy))
    }
}

/** 0..1 из бит [seed]. */
private fun fraction(seed: Long): Float = ((seed xor (seed shr 11)) and 0xFFFF).toFloat() / 0xFFFF

/** Медиа под спойлером: размытие самого фото / видео. */
internal fun Modifier.spoilerBlur(hidden: Boolean): Modifier = if (hidden) blur(SPOILER_BLUR) else this

/** Слой поверх медиа под спойлером: затемнение и частицы. Касания не перехватывает. */
@Composable
internal fun MediaSpoilerCover(hidden: Boolean, modifier: Modifier = Modifier) {
    if (!hidden) return
    val phase = rememberSpoilerPhase()
    Box(
        modifier
            .background(Color.Black.copy(alpha = 0.25f))
            .drawWithContent {
                drawContent()
                drawSpoilerParticles(Rect(Offset.Zero, size), phase.value, Color.White)
            },
    )
}

private val SPOILER_BLUR = 24.dp
private const val SPOILER_PERIOD_MS = 2_400
private const val MIN_PARTICLES = 12
private const val MAX_PARTICLES = 600

/** Медиа под спойлером в ленте: первый тап раскрывает, следующие — открывают просмотр. */
internal class MediaSpoiler(private val revealed: MutableState<Boolean>, private val hasSpoiler: Boolean) {
    val hidden: Boolean get() = hasSpoiler && !revealed.value

    fun open(view: () -> Unit) {
        if (hidden) revealed.value = true else view()
    }
}

@Composable
internal fun rememberMediaSpoiler(messageId: Long, hasSpoiler: Boolean): MediaSpoiler {
    val revealed = rememberSaveable(messageId) { mutableStateOf(false) }
    return remember(revealed, hasSpoiler) { MediaSpoiler(revealed, hasSpoiler) }
}
