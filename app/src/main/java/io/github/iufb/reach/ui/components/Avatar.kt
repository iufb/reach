package io.github.iufb.reach.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.iufb.reach.files.TdFile

/**
 * Круглая аватарка. Пока фото нет или оно грузится — инициалы на встроенном цвете Telegram.
 */
@Composable
fun Avatar(title: String, photo: TdFile?, accentColorId: Int, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    var loaded by remember(photo) { mutableStateOf(false) }
    Box(modifier.size(size).clip(CircleShape), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Box(
                Modifier.size(size).background(placeholderColor(accentColorId)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initials(title),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = (size.value * 0.36f).sp,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        if (photo != null) {
            AsyncImage(
                model = photo,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
            )
        }
    }
}

/** Первые буквы двух первых слов: «Анна Петрова» → «АП», «Новости» → «Н». */
internal fun initials(title: String): String =
    title.split(' ', '\n')
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .joinToString("")
        .uppercase()

/** Встроенные цвета Telegram для `accentColorId` 0..6: красный, оранжевый, фиолетовый, зелёный, бирюзовый, синий, розовый. */
private val PLACEHOLDER_COLORS = listOf(
    Color(0xFFE17076),
    Color(0xFFFAA774),
    Color(0xFFA695E7),
    Color(0xFF7BC862),
    Color(0xFF6EC9CB),
    Color(0xFF65AADD),
    Color(0xFFEE7AAE),
)

private fun placeholderColor(accentColorId: Int): Color =
    PLACEHOLDER_COLORS[Math.floorMod(accentColorId, PLACEHOLDER_COLORS.size)]

@Preview
@Composable
private fun AvatarPreview() {
    Avatar(title = "Анна Петрова", photo = null, accentColorId = 2)
}

/**
 * Аватар [content] размера [size] с зелёной точкой «в сети» внизу справа, как в Telegram iOS: точка
 * лежит на краю круга, обведена цветом фона [ring]. Появляется и гаснет масштабом с прозрачностью.
 */
@Composable
fun OnlineAvatar(online: Boolean, size: Dp, ring: Color, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shown by animateFloatAsState(if (online) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "online")
    Box(modifier.size(size)) {
        content()
        if (shown > 0f) {
            val dot = size * ONLINE_DOT_FRACTION
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = size * ONLINE_DOT_INSET, y = size * ONLINE_DOT_INSET)
                    .size(dot)
                    .graphicsLayer {
                        scaleX = shown
                        scaleY = shown
                        alpha = shown.coerceIn(0f, 1f)
                    }
                    .background(ring, CircleShape)
                    .padding(dot * ONLINE_RING_FRACTION)
                    .background(OnlineGreen, CircleShape),
            )
        }
    }
}

/** Зелёный «в сети» — системный зелёный iOS, одинаковый в светлой и тёмной теме. */
val OnlineGreen = Color(0xFF34C759)
// Замер по Mobbin (Telegram iOS, аватар 60 pt): точка с обводкой ~16 pt, обводка ~2,5 pt, сдвиг внутрь круга
private const val ONLINE_DOT_FRACTION = 0.27f
private const val ONLINE_RING_FRACTION = 0.16f
private const val ONLINE_DOT_INSET = -0.02f
