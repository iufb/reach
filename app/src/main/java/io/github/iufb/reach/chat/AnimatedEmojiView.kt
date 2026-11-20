package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieAnimatable
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.rememberTgsComposition

/**
 * Большой анимированный эмодзи без пузыря. Анимация проигрывается один раз при появлении
 * на экране и останавливается на последнем кадре; тап ([replay] меняется) — повтор.
 * Пока TGS не загружен или его нет — крупный символ шрифтом Noto.
 */
@Composable
internal fun AnimatedEmojiView(emoji: String, animation: TdFile?, replay: Int, modifier: Modifier = Modifier) {
    val allowed = currentAutoDownloadRules().allows(MediaKind.Sticker)
    val composition = rememberTgsComposition(animation?.takeIf { allowed })
    Box(modifier.size(EMOJI_SIZE), contentAlignment = Alignment.Center) {
        if (composition == null) {
            Text(emoji, fontSize = FALLBACK_FONT)
        } else {
            val animatable = rememberLottieAnimatable()
            // Элемент ленты создаётся заново при появлении на экране — отсюда и «играть при появлении»
            LaunchedEffect(composition, replay) { animatable.animate(composition, iterations = 1) }
            LottieAnimation(composition, progress = { animatable.progress }, modifier = Modifier.size(EMOJI_SIZE))
        }
    }
}

/** Счётчик повторов по тапу; живёт в элементе ленты. */
@Composable
internal fun rememberReplay(): Pair<Int, () -> Unit> {
    var count by remember { mutableIntStateOf(0) }
    return count to { count++ }
}

private val EMOJI_SIZE = 140.dp
private val FALLBACK_FONT = 96.sp
