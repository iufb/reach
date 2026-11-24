package io.github.iufb.reach.chat

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.rememberLottieAnimatable
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.rememberTgsComposition

/**
 * Большой анимированный эмодзи без пузыря. Анимация проигрывается один раз при появлении
 * на экране и останавливается на последнем кадре; тап ([replay] меняется) — повтор.
 * Пока TGS не загружен или его нет — крупный символ шрифтом того же размера.
 */
@Composable
internal fun AnimatedEmojiView(emoji: String, animation: TdFile?, replay: Int, modifier: Modifier = Modifier) {
    val allowed = currentAutoDownloadRules().allows(MediaKind.Sticker)
    val composition = rememberTgsComposition(animation?.takeIf { allowed })
    // Готовая композиция из кеша приходит в первом же кадре — без заглушки и без перехода.
    // Если TGS ещё грузится, шрифтовой эмодзи того же размера плавно сменяется анимацией (#295)
    Crossfade(composition, modifier.size(EMOJI_SIZE), animationSpec = tween(CROSSFADE_MS), label = "animated-emoji") { ready ->
        Box(Modifier.size(EMOJI_SIZE), contentAlignment = Alignment.Center) {
            if (ready == null) {
                // Рисунок шрифта — того же размера и там же, где лицо анимации: подмены не видно (#372)
                FittedEmoji(emoji, EMOJI_SIZE, TGS_FACE_FRACTION)
            } else {
                val animatable = rememberLottieAnimatable()
                // Элемент ленты создаётся заново при появлении на экране — отсюда и «играть при появлении».
                // Смена id после отправки элемент не пересоздаёт: ключ ленты — Message.listKey
                LaunchedEffect(ready, replay) { animatable.animate(ready, iterations = 1) }
                LottieAnimation(ready, progress = { animatable.progress }, modifier = Modifier.size(EMOJI_SIZE))
            }
        }
    }
}

/** Один эмодзи текстом (без анимации): рисунок шрифта на месте и в размер лица [AnimatedEmojiView]. */
@Composable
internal fun LoneEmoji(emoji: String, modifier: Modifier = Modifier) {
    FittedEmoji(emoji, EMOJI_SIZE, TGS_FACE_FRACTION, modifier)
}

/**
 * Эффект по тапу на большой эмодзи поверх ленты, крупнее самого эмодзи. У своих сообщений (справа)
 * разлетается влево ([towardsStart]), у чужих — зеркально вправо. По окончании — [onDone].
 */
@Composable
internal fun EmojiEffectView(
    effect: EmojiEffect,
    towardsStart: Boolean,
    onShown: (EmojiEffect) -> Unit,
    onDone: (EmojiEffect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val composition = rememberTgsComposition(effect.animation) ?: return
    val shown by rememberUpdatedState(onShown)
    val done by rememberUpdatedState(onDone)
    val animatable = rememberLottieAnimatable()
    LaunchedEffect(composition) {
        shown(effect)
        animatable.animate(composition, iterations = 1)
        done(effect)
    }
    val shift = (EFFECT_SIZE - EMOJI_SIZE) / 2
    LottieAnimation(
        composition,
        progress = { animatable.progress },
        modifier = modifier
            .wrapContentSize(unbounded = true)
            .offset(x = if (towardsStart) -shift else shift)
            .size(EFFECT_SIZE)
            // Анимации эффектов нарисованы «влево»; у входящих отражаем
            .graphicsLayer { scaleX = if (towardsStart) 1f else -1f },
    )
}

/** Счётчик повторов по тапу; живёт в элементе ленты. */
@Composable
internal fun rememberReplay(): Pair<Int, () -> Unit> {
    var count by remember { mutableIntStateOf(0) }
    return count to { count++ }
}

private val EMOJI_SIZE = 140.dp
private val EFFECT_SIZE = 320.dp
/** Лицо анимированного эмодзи Telegram занимает 399 из 512 px холста TGS. */
private const val TGS_FACE_FRACTION = 0.78f
private const val CROSSFADE_MS = 150
