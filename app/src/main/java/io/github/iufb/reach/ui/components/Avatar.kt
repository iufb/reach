package io.github.iufb.reach.ui.components

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
