package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Размытие под шапкой, tab bar и плавающими пилюлями, как в Telegram iOS.
 *
 * Экран оборачивает содержимое в [GlassScope] и помечает прокручиваемую часть [glassSource];
 * всё, что рисуется поверх с [glass], размывает то, что под ним. Без [GlassScope]
 * (превью, экраны без размытия) [glass] — просто полупрозрачная подложка.
 */
private val LocalGlass = staticCompositionLocalOf<HazeState?> { null }

@Composable
fun GlassScope(content: @Composable () -> Unit) {
    val state = rememberHazeState()
    CompositionLocalProvider(LocalGlass provides state, content = content)
}

/** Содержимое, которое просвечивает сквозь стеклянные элементы. */
@Composable
fun Modifier.glassSource(): Modifier {
    val state = LocalGlass.current ?: return this
    return hazeSource(state)
}

/**
 * Стеклянная подложка формы [shape]. [elevated] — с мягкой тенью, как у плавающих пилюль;
 * у шапки и tab bar во всю ширину тени нет.
 */
@Composable
fun Modifier.glass(shape: Shape, elevated: Boolean = true, tint: Color = ReachTheme.colors.bar): Modifier {
    val colors = ReachTheme.colors
    val state = LocalGlass.current
    val base = if (elevated) shadow(GLASS_ELEVATION, shape, clip = false, ambientColor = SHADOW, spotColor = SHADOW) else this
    if (state == null) return base.clip(shape).background(tint, shape)
    val style = remember(colors, tint) {
        HazeBlurStyle {
            blurRadius(GLASS_BLUR)
            backgroundColor(colors.background)
            colorEffects(listOf(HazeColorEffect.tint(tint.copy(alpha = GLASS_TINT_ALPHA))))
            fallbackColorEffect(HazeColorEffect.tint(tint))
        }
    }
    return base.clip(shape).hazeBlur(HazeInput.Sources(state), style)
}

private val GLASS_ELEVATION = 10.dp
private val GLASS_BLUR = 24.dp
private const val GLASS_TINT_ALPHA = 0.72f
private val SHADOW = Color.Black.copy(alpha = 0.18f)
