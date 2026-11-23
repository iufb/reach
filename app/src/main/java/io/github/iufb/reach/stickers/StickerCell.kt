package io.github.iufb.reach.stickers

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.VideoStickerImage
import io.github.iufb.reach.media.rememberTgsComposition

/**
 * Картинка стикера с прозрачностью, вписанная в [modifier]: сначала миниатюра (а без неё —
 * эмодзи стилем [emojiStyle]), поверх — сам WebP, TGS-анимация или видеостикер по кругу.
 * [load] = false — только миниатюра, файл стикера не качается (автозагрузка в ленте выключена).
 * [animate] = false — TGS стоит на первом кадре, видеостикер — миниатюрой.
 */
@Composable
fun StickerImage(
    sticker: StickerInfo,
    modifier: Modifier = Modifier,
    load: Boolean = true,
    animate: Boolean = true,
    emojiStyle: TextStyle = MaterialTheme.typography.displayMedium,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val loadStatic = sticker.isStatic && load
        val main = rememberAsyncImagePainter(if (loadStatic) sticker.file else null)
        val state by main.state.collectAsState()
        val animation = rememberTgsComposition(sticker.file.takeIf { sticker.isTgs && load })
        val loaded = (loadStatic && state is AsyncImagePainter.State.Success) || animation != null
        val placeholder = @Composable {
            if (sticker.thumbnail != null) {
                AsyncImage(sticker.thumbnail, sticker.emoji, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(sticker.emoji, style = emojiStyle)
            }
        }
        if (sticker.isWebm && load && animate) {
            // Видеостикер прозрачный: миниатюру показывает сам, пока нет первого кадра
            VideoStickerImage(sticker.file, Modifier.fillMaxSize(), placeholder)
        } else if (!loaded) {
            placeholder()
        }
        if (loadStatic) Image(main, sticker.emoji, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (animation != null) {
            val progress by animateLottieCompositionAsState(
                animation,
                isPlaying = animate,
                iterations = LottieConstants.IterateForever,
            )
            LottieAnimation(animation, progress = { if (animate) progress else 0f }, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Квадратная ячейка сетки стикеров (лист пака, панель стикеров): только картинка, вписанная
 * с отступом; файл качается всегда, без значка автозагрузки и без ограничения размера ленты.
 * Нажатия (тап, долгое) вешает вызывающий через [modifier]. [animate] = false — без анимации.
 */
@Composable
fun StickerCell(sticker: StickerInfo, modifier: Modifier = Modifier, animate: Boolean = true) {
    Box(modifier.aspectRatio(1f)) {
        StickerImage(
            sticker,
            Modifier.fillMaxSize().padding(CELL_PADDING),
            animate = animate,
            emojiStyle = MaterialTheme.typography.headlineMedium,
        )
    }
}

private val CELL_PADDING = 4.dp
