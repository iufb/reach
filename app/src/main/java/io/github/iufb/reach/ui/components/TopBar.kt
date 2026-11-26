package io.github.iufb.reach.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.theme.ReachTheme

/** Высота шапки без статус-бара; столько отступа сверху нужно содержимому под ней. */
val TopBarHeight = 56.dp

/**
 * Плавающая шапка Telegram iOS: слева пилюля «назад» (или своя), по центру заголовок, справа действия.
 * Фона нет — под пилюлями затухающий градиент цвета экрана, содержимое прокручивается под шапкой.
 *
 * [titlePill] — заголовок в стеклянной пилюле (экран чата: имя и статус); иначе просто текст по центру.
 * [onTitleClick] — тап по заголовку (профиль собеседника).
 * [background] — цвет экрана под шапкой, в него затухает градиент (у сгруппированных — `groupedBackground`).
 * [titleAlpha] — прозрачность заголовка: экраны с крупной шапкой проявляют его, когда шапка уехала под бар.
 * [scrimAlpha] — прозрачность градиента под шапкой: у таких экранов его нет, пока крупная шапка на месте.
 */
@Composable
fun ReachTopBar(
    title: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titlePill: Boolean = subtitle != null,
    onTitleClick: (() -> Unit)? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    background: Color = ReachTheme.colors.background,
    titleAlpha: () -> Float = { 1f },
    scrimAlpha: () -> Float = { 1f },
) {
    val colors = ReachTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            // Под статус-баром почти непрозрачно, к низу шапки — затухает: содержимое уходит под неё мягко
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to background,
                        0.45f to background.copy(alpha = 0.88f),
                        1f to background.copy(alpha = 0f),
                    ),
                    alpha = scrimAlpha(),
                )
            }
            .statusBarsPadding()
            .height(TopBarHeight)
            .padding(horizontal = 12.dp),
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            navigation?.invoke()
        }
        if (title != null) {
            val titleModifier = Modifier
                .align(Alignment.Center)
                .graphicsLayer { alpha = titleAlpha() }
                .widthIn(max = 240.dp)
                .then(if (titlePill) Modifier.glass(RoundedCornerShape(percent = 50)) else Modifier)
                .then(if (onTitleClick != null) Modifier.clickable(onClick = onTitleClick) else Modifier)
                // Пилюля плавно меняет ширину, когда статус сменяется на «печатает…» и обратно (#413):
                // стекло снаружи, поэтому рисуется по текущей, а не по конечной ширине
                .then(if (titlePill) Modifier.animateContentSize(PILL_RESIZE, alignment = Alignment.Center) else Modifier)
                .padding(horizontal = if (titlePill) 20.dp else 0.dp, vertical = if (titlePill) 4.dp else 0.dp)
            Column(titleModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (titlePill) {
                    // Старый статус уходит вверх и гаснет, новый приходит снизу; ширину ведёт пилюля
                    AnimatedContent(
                        targetState = subtitle,
                        transitionSpec = {
                            (fadeIn(tween(SUBTITLE_IN_MS, delayMillis = SUBTITLE_OUT_MS / 2)) + slideInVertically(tween(SUBTITLE_IN_MS)) { it / 2 }) togetherWith
                                (fadeOut(tween(SUBTITLE_OUT_MS)) + slideOutVertically(tween(SUBTITLE_OUT_MS)) { -it / 2 }) using
                                SizeTransform(clip = false) { _, _ -> snap() }
                        },
                        contentAlignment = Alignment.Center,
                        label = "subtitle",
                    ) { text ->
                        if (text != null) Subtitle(text)
                    }
                } else if (subtitle != null) {
                    Subtitle(subtitle)
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}

@Composable
private fun Subtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = ReachTheme.colors.secondaryLabel,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Ширина пилюли: без перелёта за край, ~0,25 с; новая цель подхватывается с текущей ширины. */
private val PILL_RESIZE = spring<IntSize>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
private const val SUBTITLE_IN_MS = 220
private const val SUBTITLE_OUT_MS = 140
