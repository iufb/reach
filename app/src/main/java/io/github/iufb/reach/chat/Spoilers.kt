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
import android.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import kotlin.math.PI
import kotlin.math.cos
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
 *
 * Рисуется каждый кадр на главном потоке, поэтому без поштучных вызовов: параметры частиц посчитаны
 * один раз ([Particles]), а частицы раскладываются по [ALPHA_LEVELS] уровням яркости и каждый уровень
 * рисуется одним `drawPoints` — несколько вызовов вместо сотен `drawCircle`.
 */
internal fun DrawScope.drawSpoilerParticles(area: Rect, phase: Float, color: Color) {
    if (area.isEmpty) return
    val step = 6.dp.toPx()
    val count = ((area.width * area.height) / (step * step)).toInt().coerceIn(MIN_PARTICLES, MAX_PARTICLES)
    val drift = 2.dp.toPx()
    val buffers = Particles.buffers
    val sizes = Particles.sizes
    sizes.fill(0)
    for (i in 0 until count) {
        val own = (phase + Particles.phase[i]) % 1f
        val level = (sin(own * PI).toFloat() * ALPHA_LEVELS).toInt().coerceAtMost(ALPHA_LEVELS - 1)
        if (level == 0) continue
        val buffer = buffers[level]
        val at = sizes[level]
        buffer[at] = area.left + Particles.x[i] * area.width + Particles.dx[i] * drift * own
        buffer[at + 1] = area.top + Particles.y[i] * area.height + Particles.dy[i] * drift * own
        sizes[level] = at + 2
    }
    val paint = Particles.paint
    paint.strokeWidth = 1.8.dp.toPx()
    drawIntoCanvas { canvas ->
        for (level in 1 until ALPHA_LEVELS) {
            if (sizes[level] == 0) continue
            paint.color = color.copy(alpha = color.alpha * (level + 0.5f) / ALPHA_LEVELS).toArgb()
            canvas.nativeCanvas.drawPoints(buffers[level], 0, sizes[level], paint)
        }
    }
}

/**
 * Неизменные параметры частиц (положение, фаза, направление дрейфа) на [MAX_PARTICLES] штук и рабочие
 * буферы точек по уровням яркости. Рисование идёт только на главном потоке, буферы общие.
 */
private object Particles {
    val x = FloatArray(MAX_PARTICLES)
    val y = FloatArray(MAX_PARTICLES)
    val phase = FloatArray(MAX_PARTICLES)
    val dx = FloatArray(MAX_PARTICLES)
    val dy = FloatArray(MAX_PARTICLES)
    val buffers = Array(ALPHA_LEVELS) { FloatArray(MAX_PARTICLES * 2) }
    val sizes = IntArray(ALPHA_LEVELS)
    val paint = Paint().apply {
        isAntiAlias = true
        strokeCap = Paint.Cap.ROUND
    }

    init {
        for (i in 0 until MAX_PARTICLES) {
            val seed = i * 2654435761L
            x[i] = fraction(seed)
            y[i] = fraction(seed shr 7)
            phase[i] = fraction(seed shr 13)
            val angle = fraction(seed shr 19) * 2 * PI
            dx[i] = cos(angle).toFloat()
            dy[i] = sin(angle).toFloat()
        }
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
/** Уровни яркости частиц; нулевой (почти погасшие) не рисуется. */
private const val ALPHA_LEVELS = 6

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
