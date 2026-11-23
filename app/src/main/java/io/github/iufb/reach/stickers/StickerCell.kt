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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import com.airbnb.lottie.RenderMode
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.VideoStickerImage
import io.github.iufb.reach.media.rememberTgsComposition
import io.github.iufb.reach.ui.PerfFlags
import kotlin.math.floor

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
            // Замеры #304: tgs=static — первый кадр, tgs=hw — аппаратный рендер, tgsfps — шаг кадров
            val playing = animate && PerfFlags["tgs"] != "static"
            val progress by animateLottieCompositionAsState(
                animation,
                isPlaying = playing,
                iterations = LottieConstants.IterateForever,
            )
            // Кадры — с шагом 30 fps: ячеек много, а глазу в мелком стикере разницы нет; перерисовка
            // идёт только при смене шага
            val fps = PerfFlags["tgsfps"]?.toIntOrNull() ?: STICKER_FPS
            val steps = (animation.duration / 1000f * fps).coerceAtLeast(1f)
            val stepped by remember(animation) { derivedStateOf { floor(progress * steps) / steps } }
            LottieAnimation(
                animation,
                progress = { if (playing) stepped else 0f },
                modifier = Modifier.fillMaxSize(),
                // Растр в битмап по размеру ячейки: аппаратный рендер путей стикера грузит RenderThread
                // в разы сильнее (замеры #289)
                renderMode = if (PerfFlags["tgs"] == "hw") RenderMode.HARDWARE else RenderMode.SOFTWARE,
            )
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
private const val STICKER_FPS = 30
