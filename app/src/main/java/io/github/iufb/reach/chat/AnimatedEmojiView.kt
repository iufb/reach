package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.media.ONE_SHOT_FPS
import io.github.iufb.reach.media.TgsImage
import io.github.iufb.reach.media.TgsPlayback

/**
 * Большой анимированный эмодзи без пузыря. Анимация проигрывается один раз при появлении
 * на экране и останавливается на последнем кадре; тап ([replay] меняется) — повтор.
 * Пока TGS не загружен или его нет — крупный символ шрифтом того же размера.
 */
@Composable
internal fun AnimatedEmojiView(emoji: String, animation: TdFile?, replay: Int, modifier: Modifier = Modifier) {
    val allowed = currentAutoDownloadRules().allows(MediaKind.Sticker)
    // Круг из кеша — первый кадр сразу, без заглушки. Если TGS ещё грузится, шрифтовой эмодзи
    // того же размера плавно сменяется анимацией (#295).
    // Элемент ленты создаётся заново при появлении на экране — отсюда и «играть при появлении».
    // Смена id после отправки элемент не пересоздаёт: ключ ленты — Message.listKey
    TgsImage(
        animation?.takeIf { allowed },
        modifier.size(EMOJI_SIZE),
        playback = TgsPlayback.Once(replay),
        fps = ONE_SHOT_FPS,
        fadeIn = true,
    ) {
        // В dp, а не sp: заглушка совпадает с анимацией при любом масштабе шрифта
        Text(emoji, fontSize = with(LocalDensity.current) { FALLBACK_FONT.toSp() })
    }
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
    val shift = (EFFECT_SIZE - EMOJI_SIZE) / 2
    TgsImage(
        effect.animation,
        modifier
            .wrapContentSize(unbounded = true)
            .offset(x = if (towardsStart) -shift else shift)
            .size(EFFECT_SIZE)
            // Анимации эффектов нарисованы «влево»; у входящих отражаем
            .graphicsLayer { scaleX = if (towardsStart) 1f else -1f },
        playback = TgsPlayback.Once(),
        fps = ONE_SHOT_FPS,
        onStart = { onShown(effect) },
        onEnd = { onDone(effect) },
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
/** Шрифтовой эмодзи с этим кеглем по размеру близок к рисунку анимации в [EMOJI_SIZE]. */
private val FALLBACK_FONT = 96.dp
