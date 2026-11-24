package io.github.iufb.reach.media

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.util.LruCache
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.EntryPointAccessors
import io.github.iufb.reach.core.rlottie.RLottie
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * TGS — это Lottie JSON в gzip (PRODUCT.md §4.5). Рисует rlottie (ADR-0013) в фоновых потоках
 * в битмапы, UI только выводит готовый кадр (#315).
 *
 * Скачивает через TDLib и держит распакованный JSON по стабильному `uniqueId`; готовые круги
 * анимации лежат в [frames] сжатыми кадрами — повторный круг и повторный показ не рисуют заново.
 */
@Singleton
class TgsLoader @Inject constructor(private val files: FilesRepository) {

    private val json = object : LruCache<String, String>(JSON_CACHE_CHARS) {
        override fun sizeOf(key: String, value: String): Int = value.length.coerceAtLeast(1)
    }

    /** Lottie JSON анимации; `null` — не скачалось или файл битый. */
    suspend fun load(file: TdFile): String? {
        json.get(file.uniqueId)?.let { return it }
        val text = runCatching {
            val path = files.awaitDownloaded(file.id, FilesRepository.PRIORITY_AVATAR)
            withContext(Dispatchers.IO) {
                GZIPInputStream(File(path).inputStream().buffered()).use { it.readBytes().decodeToString() }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return null
        json.put(file.uniqueId, text)
        return text
    }

    internal val frames = TgsFrameCache(FRAME_CACHE_BYTES)

    /** rlottie рисует синхронно: свои потоки, чтобы не занимать весь `Default`. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal val rendering = Dispatchers.Default.limitedParallelism(RENDER_THREADS)

    private companion object {
        /** Стикеры панели (экран — 20–30 ячеек), реакции и эмодзи ленты; JSON стикера — 20–200 КБ. */
        const val JSON_CACHE_CHARS = 16 * 1024 * 1024
        const val FRAME_CACHE_BYTES = 48 * 1024 * 1024
        const val RENDER_THREADS = 3
    }
}

/**
 * Готовые круги анимаций: кадры одного размера и шага, сжатые LZ4 (кадр стикера — в основном
 * прозрачный фон и заливки, жмётся в разы). Память ограничена [maxBytes] по сжатому размеру.
 */
internal class TgsFrameCache(private val maxBytes: Int) {

    data class Key(val uniqueId: String, val width: Int, val height: Int, val step: Int)

    class Frames(val width: Int, val height: Int, val data: List<ByteArray>) {
        val bytes = data.sumOf { it.size }
    }

    private val lru = object : LruCache<Key, Frames>(maxBytes) {
        override fun sizeOf(key: Key, value: Frames): Int = value.bytes.coerceAtLeast(1)
    }

    /** Последний сохранённый круг каждого файла — для первого кадра без ожидания. */
    private val latest = LruCache<String, Key>(LATEST_KEYS)

    /** Не берём круг, который займёт больше этой доли кеша: он вытеснит всё остальное. */
    val maxEntryBytes get() = maxBytes / 4

    fun get(key: Key): Frames? = lru.get(key)

    fun put(key: Key, frames: Frames) {
        if (frames.bytes > maxEntryBytes) return
        lru.put(key, frames)
        latest.put(key.uniqueId, key)
    }

    /** Первый кадр любого сохранённого круга файла — сразу, в композиции; `null` — кругов нет. */
    fun firstFrame(uniqueId: String): Bitmap? {
        val frames = latest.get(uniqueId)?.let(lru::get) ?: return null
        val bitmap = Bitmap.createBitmap(frames.width, frames.height, Bitmap.Config.ARGB_8888)
        return bitmap.takeIf { RLottie.decompress(frames.data.first(), it) }
    }

    private companion object {
        const val LATEST_KEYS = 512
    }
}

/** Как играть TGS в [TgsImage]. */
sealed interface TgsPlayback {
    /** По кругу, пока видно. */
    data object Loop : TgsPlayback

    /** Только первый кадр. */
    data object Still : TgsPlayback

    /** Один раз, затем последний кадр остаётся; новый [run] — повтор. */
    data class Once(val run: Int = 0) : TgsPlayback
}

/**
 * TGS-анимация [file], вписанная в [modifier]. Пока нет первого кадра (грузится, файл битый,
 * [file] = `null`) — [placeholder]; [fadeIn] — анимация проявляется поверх заглушки, если её
 * пришлось ждать. Играет, только пока элемент в композиции и экран видим.
 *
 * [onStart] — показан первый кадр проигрывания, [onEnd] — [TgsPlayback.Once] дошёл до конца
 * (если файл не загрузился, [onEnd] не вызывается: тайм-аут — на вызывающем).
 */
@Composable
internal fun TgsImage(
    file: TdFile?,
    modifier: Modifier = Modifier,
    playback: TgsPlayback = TgsPlayback.Loop,
    fps: Int = DEFAULT_FPS,
    fadeIn: Boolean = false,
    onStart: () -> Unit = {},
    onEnd: () -> Unit = {},
    placeholder: @Composable () -> Unit = {},
) {
    val loader = rememberTgsLoader()
    val uniqueId = file?.uniqueId
    // Круг этого файла уже рисовали — первый кадр сразу, без заглушки и мигания (#295)
    val frames = remember(uniqueId) { FrameHolder(uniqueId?.let(loader.frames::firstFrame)) }
    // Пара «файл — JSON»: produceState не сбрасывается при смене ключа, прежний файл не играем ни кадра
    val loaded by produceState<Pair<String, String>?>(null, uniqueId) {
        value = file?.let { f -> loader.load(f)?.let { f.uniqueId to it } }
    }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val started by rememberUpdatedState(onStart)
    val ended by rememberUpdatedState(onEnd)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val source = loaded?.takeIf { it.first == uniqueId }?.second
    if (source != null && uniqueId != null && box.width > 0 && box.height > 0 && !frames.failed) {
        LaunchedEffect(source, box, playback, fps) {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (playback is TgsPlayback.Once && frames.finished == playback) return@repeatOnLifecycle
                try {
                    play(source, uniqueId, box, playback, fps, loader, frames, started)
                    if (playback is TgsPlayback.Once) {
                        frames.finished = playback
                        ended()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    frames.failed = true
                }
            }
        }
    }

    val alpha by animateFloatAsState(
        if (frames.shown || !fadeIn) 1f else 0f,
        tween(FADE_MS),
        label = "tgs-fade",
    )
    Box(modifier.onSizeChanged { box = it }, contentAlignment = Alignment.Center) {
        if (!frames.shown || alpha < 1f) {
            Box(if (fadeIn) Modifier.alpha(1f - alpha) else Modifier, contentAlignment = Alignment.Center) { placeholder() }
        }
        val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
        Canvas(Modifier.matchParentSize().then(if (fadeIn) Modifier.alpha(alpha) else Modifier)) {
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

/** Скачивает и распаковывает TGS заранее, чтобы [TgsImage] потом не ждал файл. */
@Composable
internal fun PrefetchTgs(file: TdFile?) {
    val loader = rememberTgsLoader()
    LaunchedEffect(file?.uniqueId) { file?.let { loader.load(it) } }
}

/**
 * Показываемый кадр. Буферов три: пишем в тот, что показывался два кадра назад, не в рисуемый.
 * Пишется из потока рисования: запись снапшот-состояния из любого потока допустима.
 */
private class FrameHolder(initial: Bitmap?) {
    @Volatile var current: Bitmap? = initial
    var tick by mutableIntStateOf(0)
    var shown by mutableStateOf(initial != null)
    var failed by mutableStateOf(false)

    /** Отыгранный до конца [TgsPlayback.Once]: после возврата на экран не повторяем. */
    @Volatile var finished: TgsPlayback? = null

    fun show(bitmap: Bitmap) {
        current = bitmap
        tick++
        shown = true
    }
}

private suspend fun play(
    json: String,
    uniqueId: String,
    box: IntSize,
    playback: TgsPlayback,
    fps: Int,
    loader: TgsLoader,
    frames: FrameHolder,
    onStart: () -> Unit,
): Unit = withContext(loader.rendering) {
    // Стикеры квадратные (512×512); больше MAX_SIDE не рисуем — дальше растягивает Canvas
    val scale = min(1f, MAX_SIDE.toFloat() / max(box.width, box.height))
    val width = max(1, (box.width * scale).roundToInt())
    val height = max(1, (box.height * scale).roundToInt())
    var animation: RLottie? = null
    try {
        fun open(): RLottie = animation ?: (RLottie.create(json, uniqueId) ?: error("bad TGS")).also { animation = it }

        // Шаг по кадрам анимации: TGS в 60 fps, играем с частотой fps
        val probe = open()
        val step = max(1, (probe.frameRate / fps).roundToInt())
        val count = max(1, ceil(probe.frameCount / step.toDouble()).toInt())
        val frameNs = (step / probe.frameRate.coerceAtLeast(1.0) * 1e9).toLong()
        val key = TgsFrameCache.Key(uniqueId, width, height, step)
        var cached = loader.frames.get(key)?.data
        if (cached != null) {
            probe.close()
            animation = null
        }
        // Круг собираем, пока он помещается в кеш; один кадр — нечего кешировать и нечего играть
        var building: Array<ByteArray?>? = if (cached == null && count > 1 && playback != TgsPlayback.Still) arrayOfNulls(count) else null
        var buildingBytes = 0

        val buffers = Array(BUFFERS) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        var n = 0
        var index = 0
        var deadline = System.nanoTime()
        while (true) {
            val target = buffers[n++ % BUFFERS]
            val fromCache = cached
            if (fromCache != null) {
                check(RLottie.decompress(fromCache[index], target)) { "bad cached frame" }
            } else {
                check(open().render(index * step, target)) { "render failed" }
                building?.let { b ->
                    val packed = RLottie.compress(target)
                    buildingBytes += packed?.size ?: Int.MAX_VALUE / 2
                    if (packed == null || buildingBytes > loader.frames.maxEntryBytes) {
                        building = null
                    } else {
                        b[index] = packed
                        if (index == count - 1) {
                            val data = b.map { it!! }
                            loader.frames.put(key, TgsFrameCache.Frames(width, height, data))
                            cached = data
                            building = null
                            animation?.close()
                            animation = null
                        }
                    }
                }
            }
            val wait = (deadline - System.nanoTime()) / 1_000_000
            if (wait > 0) delay(wait) else deadline = System.nanoTime() // отстали — не догоняем рывком
            deadline += frameNs
            frames.show(target)
            if (n == 1) withContext(Dispatchers.Main) { onStart() }

            when {
                // Один кадр rlottie второй раз подряд не нарисует — да и играть нечего
                playback == TgsPlayback.Still || count == 1 -> break
                index == count - 1 && playback is TgsPlayback.Once -> break
                else -> index = (index + 1) % count
            }
        }
    } finally {
        animation?.close()
    }
}

@Composable
internal fun rememberTgsLoader(): TgsLoader {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).tgsLoader() }
}

/** Мелкие стикеры глазу одинаковы в 30 и 60 fps, а работы вдвое меньше. */
internal const val DEFAULT_FPS = 30

/** Разовые крупные анимации (эмодзи, эффекты, реакции) — с родной частотой TGS. */
internal const val ONE_SHOT_FPS = 60
private const val BUFFERS = 3
private const val MAX_SIDE = 720
private const val FADE_MS = 150
