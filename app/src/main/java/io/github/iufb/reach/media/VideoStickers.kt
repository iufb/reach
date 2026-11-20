package io.github.iufb.reach.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.EntryPointAccessors
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.media.webm.WebmDecoder
import io.github.iufb.reach.media.webm.WebmParser
import io.github.iufb.reach.media.webm.WebmVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * Видеостикеры и WebM-эмодзи (PRODUCT.md §4.4, ADR-0008): загрузка и разбор WebM с кэшем
 * по `uniqueId`, ограничение одновременно играющих и потоки для декодирования.
 */
@Singleton
class VideoStickers @Inject constructor(private val files: FilesRepository) {

    private val cache = object : LruCache<String, WebmVideo>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: WebmVideo): Int =
            value.frames.sumOf { it.data.size + (it.alpha?.size ?: 0) }.coerceAtLeast(1)
    }

    /** Разобранный WebM; `null` — не скачался или формат не поддержан. */
    suspend fun load(file: TdFile): WebmVideo? {
        cache.get(file.uniqueId)?.let { return it }
        val video = runCatching {
            val path = files.awaitDownloaded(file.id, FilesRepository.PRIORITY_AVATAR)
            withContext(Dispatchers.IO) { WebmParser.parse(File(path).readBytes()) }
        }.getOrNull() ?: return null
        cache.put(file.uniqueId, video)
        return video
    }

    /** Два декодера на стикер; программных экземпляров много, но CPU — нет. */
    val slots = PlaybackSlots(MAX_PLAYING)

    /** Декодирование блокирующее: отдельные потоки, чтобы не занимать `Default`. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val decoding = Dispatchers.IO.limitedParallelism(DECODE_THREADS)

    private companion object {
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val MAX_PLAYING = 8
        const val DECODE_THREADS = 4
    }
}

/**
 * WebM с прозрачностью по кругу, вписанный в [modifier]. Пока нет первого кадра (грузится,
 * нет места в [VideoStickers.slots], декодер не справился) — [placeholder].
 * Играет, только пока элемент в композиции и экран видим.
 */
@Composable
internal fun VideoStickerImage(file: TdFile, modifier: Modifier = Modifier, placeholder: @Composable () -> Unit) {
    val stickers = rememberVideoStickers()
    val video by produceState<WebmVideo?>(null, file) { value = stickers.load(file) }
    val slots = stickers.slots
    val owner = remember { Any() }
    LaunchedEffect(owner) {
        snapshotFlow { slots.taken }.collect { slots.tryAcquire(owner) }
    }
    DisposableEffect(owner) { onDispose { slots.release(owner) } }

    val frames = remember(file) { FrameHolder() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val ready = video
    if (ready != null && slots.holds(owner) && box != IntSize.Zero && !frames.failed) {
        LaunchedEffect(ready, box) {
            // Кадр крупнее места на экране — прореживаем: меньше работы и памяти
            val step = max(1, min(ready.width / box.width, ready.height / box.height))
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                try {
                    play(ready, step, stickers, frames)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    frames.failed = true
                }
            }
        }
    }

    Box(modifier.onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        if (!frames.shown) placeholder()
        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        Canvas(Modifier.matchParentSize()) {
            frames.tick // перерисовка на каждый новый кадр без рекомпозиции
            val bitmap = frames.current ?: return@Canvas
            val scale = min(size.width / bitmap.width, size.height / bitmap.height)
            val w = (bitmap.width * scale).toInt()
            val h = (bitmap.height * scale).toInt()
            val left = ((size.width - w) / 2).toInt()
            val top = ((size.height - h) / 2).toInt()
            drawIntoCanvas { it.nativeCanvas.drawBitmap(bitmap, null, Rect(left, top, left + w, top + h), paint) }
        }
    }
}

/** Показываемый кадр. Буферов три: пишем в тот, что показывался два кадра назад, не в рисуемый. */
private class FrameHolder {
    var current: Bitmap? = null
    var tick by mutableIntStateOf(0)
    var shown by mutableStateOf(false)
    var failed by mutableStateOf(false)

    fun show(bitmap: Bitmap) {
        current = bitmap
        tick++
        shown = true
    }
}

private suspend fun play(video: WebmVideo, step: Int, stickers: VideoStickers, frames: FrameHolder) {
    withContext(stickers.decoding) {
        WebmDecoder(video, step).use { decoder ->
            val buffers = Array(BUFFERS) { Bitmap.createBitmap(decoder.width, decoder.height, Bitmap.Config.ARGB_8888) }
            var n = 0
            var previousPts = -1L
            var deadline = System.nanoTime()
            while (true) {
                val target = buffers[n++ % BUFFERS]
                decoder.next(target)
                val pts = decoder.lastPtsUs
                // Шаг по времени кадров; на стыке круга длительность неизвестна — берём обычную
                val stepUs = if (previousPts < 0 || pts <= previousPts) DEFAULT_FRAME_US else pts - previousPts
                previousPts = pts
                deadline += stepUs.coerceIn(MIN_FRAME_US, MAX_FRAME_US) * 1000
                val wait = (deadline - System.nanoTime()) / 1_000_000
                if (wait > 0) delay(wait) else deadline = System.nanoTime() // отстали — не догоняем рывком
                withContext(Dispatchers.Main) { frames.show(target) }
            }
        }
    }
}

@Composable
internal fun rememberVideoStickers(): VideoStickers {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).videoStickers() }
}

private const val BUFFERS = 3
private const val DEFAULT_FRAME_US = 33_333L
private const val MIN_FRAME_US = 10_000L
private const val MAX_FRAME_US = 200_000L
