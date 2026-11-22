package io.github.iufb.reach.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
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
) {
    val colors = ReachTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            // Под статус-баром почти непрозрачно, к низу шапки — затухает: содержимое уходит под неё мягко
            .background(
                Brush.verticalGradient(
                    0f to background,
                    0.45f to background.copy(alpha = 0.88f),
                    1f to background.copy(alpha = 0f),
                ),
            )
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
                .padding(horizontal = if (titlePill) 20.dp else 0.dp, vertical = if (titlePill) 4.dp else 0.dp)
            Column(titleModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            actions()
        }
    }
}
