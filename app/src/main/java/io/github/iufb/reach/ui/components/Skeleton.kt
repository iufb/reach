package io.github.iufb.reach.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.delay

/**
 * Блик скелетона. Фаза берётся из часов кадров, а положение — в координатах окна, поэтому блик
 * один на весь экран: он проходит по всем заглушкам разом, а не по каждой отдельно.
 * Рисуется только поверх уже нарисованного содержимого (форма заглушки сохраняется).
 */
fun Modifier.shimmer(highlight: Color? = null): Modifier = composed {
    val progress = shimmerProgress()
    val colors = ReachTheme.colors
    val glow = highlight ?: if (colors.isDark) Color.White.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.75f)
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()
    var originX by remember { mutableFloatStateOf(0f) }
    onGloballyPositioned { originX = it.positionInWindow().x }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val band = windowWidth * SHIMMER_BAND
            // Блик въезжает из-за левого края и уходит за правый
            val center = -band + progress.value * (windowWidth + 2 * band) - originX
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, glow, Color.Transparent), startX = center - band, endX = center + band),
                blendMode = BlendMode.SrcAtop,
            )
        }
}

/** Фаза блика 0..1 от общих часов кадров — у всех заглушек на экране одна и та же. */
@Composable
private fun shimmerProgress(): State<Float> = produceState(0f) {
    while (true) withFrameMillis { value = (it % SHIMMER_PERIOD_MS) / SHIMMER_PERIOD_MS.toFloat() }
}

/** Заглушка скелетона: прямоугольник, полоска текста или круг (аватар). Цвет — токен `fill`. */
@Composable
fun SkeletonBox(
    modifier: Modifier = Modifier,
    shape: Shape = SkeletonLineShape,
    color: Color = ReachTheme.colors.fill,
    /** Цвет блика; `null` — по теме. */
    highlight: Color? = null,
) {
    Box(modifier.shimmer(highlight).background(color, shape))
}

/** Скругление полоски текста. */
val SkeletonLineShape: Shape = RoundedCornerShape(percent = 50)
val SkeletonCircleShape: Shape = CircleShape

/**
 * Показывать ли скелетон: только если загрузка длится дольше [SkeletonDelayMs] —
 * данные из кэша приходят раньше, и заглушка не успевает мигнуть.
 */
@Composable
fun rememberSkeletonVisible(loading: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        visible = false
        if (loading) {
            delay(SkeletonDelayMs)
            visible = true
        }
    }
    return loading && visible
}

private enum class SkeletonPhase { Blank, Skeleton, Content }

/**
 * Скелетон ↔ содержимое: пока [loading] — пусто, после [SkeletonDelayMs] — [skeleton];
 * когда данные пришли — [content]. Из скелетона в содержимое — crossfade, из пустоты — сразу.
 */
@Composable
fun SkeletonCrossfade(
    loading: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val skeletonVisible = rememberSkeletonVisible(loading)
    val phase = when {
        !loading -> SkeletonPhase.Content
        skeletonVisible -> SkeletonPhase.Skeleton
        else -> SkeletonPhase.Blank
    }
    AnimatedContent(
        phase,
        modifier,
        transitionSpec = {
            if (initialState == SkeletonPhase.Blank) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                fadeIn(tween(SkeletonFadeMs)) togetherWith fadeOut(tween(SkeletonFadeMs))
            }
        },
        label = "skeleton",
    ) { current ->
        when (current) {
            SkeletonPhase.Blank -> Box(Modifier)
            SkeletonPhase.Skeleton -> skeleton()
            SkeletonPhase.Content -> content()
        }
    }
}

/** Задержка показа скелетона: быстрее этого данные приходят из кэша TDLib. */
const val SkeletonDelayMs = 100L

/** Длительность crossfade скелетон → содержимое. */
const val SkeletonFadeMs = 180

/** Стабильная «случайная» доля ширины для заглушки номер [index]: без «забора» из одинаковых полосок. */
fun skeletonFraction(index: Int, min: Float, max: Float): Float {
    val step = ((index * 7919 + 13) % 17) / 16f
    return min + (max - min) * step
}

private const val SHIMMER_PERIOD_MS = 1_500L
private const val SHIMMER_BAND = 0.35f
