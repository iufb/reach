package io.github.iufb.reach.media.webm

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import java.io.Closeable

/**
 * Декодер WebM с альфа-каналом по кругу (ADR-0008). Штатные декодеры Android отбрасывают альфу VP9,
 * поэтому кадры цвета и альфы ([WebmFrame.alpha]) декодируются двумя отдельными `MediaCodec`
 * в YUV, а ARGB собирается на CPU: яркость альфа-кадра — прозрачность пикселя.
 *
 * Не потокобезопасен: все вызовы — из одной корутины (поток может меняться). Блокирующий.
 *
 * [step] — прореживание при сборке ARGB (2 — вдвое меньше по каждой стороне), когда кадр
 * заметно больше места на экране.
 */
class WebmDecoder(private val video: WebmVideo, private val step: Int = 1) : Closeable {

    private val color = Lane(video) { it.data }
    private val alpha = if (video.hasAlpha) Lane(video) { it.alpha!! } else null

    /** Размер собранного кадра. */
    val width: Int = (video.width + step - 1) / step
    val height: Int = (video.height + step - 1) / step

    private val pixels = IntArray(width * height)
    private val yuv = PlaneCopy()
    private val alphaPlane = PlaneCopy()

    /** Время кадра, отданного последним [next]; растёт по кругу заново после перемотки. */
    var lastPtsUs: Long = 0
        private set

    /** Сколько раз видео дошло до конца и началось заново. */
    var loops: Int = 0
        private set

    /**
     * Следующий кадр в [target] (ARGB_8888, размер [width]×[height]). После последнего кадра
     * начинает сначала. Бросает исключение, если декодер не справился.
     */
    fun next(target: Bitmap) {
        var restarts = 0
        while (true) {
            val colorOut = color.awaitOutput()
            if (colorOut == null) {
                // Конец: перематываем оба декодера к первому (ключевому) кадру
                check(++restarts <= 1) { "WebM без кадров" }
                color.rewind()
                alpha?.rewind()
                loops++
                continue
            }
            val alphaOut = alpha?.awaitOutput()
            try {
                val colorImage = color.codec.getOutputImage(colorOut.index) ?: error("Нет кадра")
                val alphaImage = alphaOut?.let { alpha!!.codec.getOutputImage(it.index) }
                compose(colorImage, alphaImage, color.matrix)
                target.setPixels(pixels, 0, width, 0, 0, width, height)
                lastPtsUs = colorOut.ptsUs
            } finally {
                color.codec.releaseOutputBuffer(colorOut.index, false)
                if (alphaOut != null) alpha!!.codec.releaseOutputBuffer(alphaOut.index, false)
            }
            return
        }
    }

    override fun close() {
        color.close()
        alpha?.close()
    }

    /** Цвет из YUV 4:2:0 и прозрачность из яркости альфа-кадра → [pixels] (ARGB без премультипликации). */
    private fun compose(colorImage: Image, alphaImage: Image?, m: YuvMatrix) {
        val crop = colorImage.cropRect
        val planes = colorImage.planes
        val y = yuv.copy(0, planes[0])
        val u = yuv.copy(1, planes[1])
        val v = yuv.copy(2, planes[2])
        val yRow = planes[0].rowStride
        val yPix = planes[0].pixelStride
        val uRow = planes[1].rowStride
        val uPix = planes[1].pixelStride
        val vRow = planes[2].rowStride
        val vPix = planes[2].pixelStride
        val a = alphaImage?.let { alphaPlane.copy(0, it.planes[0]) }
        val aCrop = alphaImage?.cropRect
        val aRow = alphaImage?.planes?.get(0)?.rowStride ?: 0
        val aPix = alphaImage?.planes?.get(0)?.pixelStride ?: 0
        val maxX = crop.width() - 1
        val maxY = crop.height() - 1
        var i = 0
        for (oy in 0 until height) {
            val sy = (oy * step).coerceAtMost(maxY)
            val py = crop.top + sy
            val yBase = py * yRow
            val uBase = (py shr 1) * uRow
            val vBase = (py shr 1) * vRow
            val aBase = if (aCrop != null) (aCrop.top + sy) * aRow else 0
            for (ox in 0 until width) {
                val sx = (ox * step).coerceAtMost(maxX)
                val px = crop.left + sx
                val yy = y[yBase + px * yPix].toInt() and 0xff
                val uu = (u[uBase + (px shr 1) * uPix].toInt() and 0xff) - 128
                val vv = (v[vBase + (px shr 1) * vPix].toInt() and 0xff) - 128
                val l = if (m.limited) (yy - 16) * m.y else yy * m.y
                val r = clamp((l + m.rv * vv + 128) shr 8)
                val g = clamp((l - m.gu * uu - m.gv * vv + 128) shr 8)
                val b = clamp((l + m.bu * uu + 128) shr 8)
                val alphaValue = if (a != null) a[aBase + (aCrop!!.left + sx) * aPix].toInt() and 0xff else 0xff
                pixels[i++] = (alphaValue shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
    }

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c

    /** Копия плоскостей кадра в переиспользуемые массивы: индексация массива быстрее `ByteBuffer.get`. */
    private class PlaneCopy {
        private val arrays = arrayOfNulls<ByteArray>(3)

        fun copy(slot: Int, plane: Image.Plane): ByteArray {
            val buffer = plane.buffer.duplicate().apply { rewind() }
            val size = buffer.remaining()
            val array = arrays[slot]?.takeIf { it.size >= size } ?: ByteArray(size).also { arrays[slot] = it }
            buffer.get(array, 0, size)
            return array
        }
    }

    private class Output(val index: Int, val ptsUs: Long)

    /** Один `MediaCodec` и очередь его входных кадров (синхронный режим). */
    private class Lane(private val video: WebmVideo, private val payload: (WebmFrame) -> ByteArray) : Closeable {
        val codec: MediaCodec = createDecoder(video)
        private val info = MediaCodec.BufferInfo()
        private var nextInput = 0
        private var inputEnded = false
        var matrix: YuvMatrix = runCatching { YuvMatrix.from(codec.outputFormat) }.getOrElse { YuvMatrix.BT601_LIMITED }
            private set

        /** Следующий выходной кадр; `null` — конец потока. */
        fun awaitOutput(): Output? {
            repeat(MAX_WAITS) {
                feed()
                val index = codec.dequeueOutputBuffer(info, WAIT_US)
                when {
                    index >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            codec.releaseOutputBuffer(index, false)
                            return null
                        }
                        return Output(index, info.presentationTimeUs)
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> matrix = YuvMatrix.from(codec.outputFormat)
                }
            }
            error("Декодер не отдал кадр")
        }

        /** Отдаёт декодеру все входные кадры, на которые есть свободные буферы, а в конце — EOS. */
        private fun feed() {
            while (!inputEnded) {
                val index = codec.dequeueInputBuffer(0)
                if (index < 0) return
                if (nextInput < video.frames.size) {
                    val frame = video.frames[nextInput++]
                    val data = payload(frame)
                    val buffer = codec.getInputBuffer(index)!!
                    buffer.clear()
                    buffer.put(data)
                    val flags = if (frame.isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    codec.queueInputBuffer(index, 0, data.size, frame.ptsUs, flags)
                } else {
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEnded = true
                }
            }
        }

        /** К первому кадру. В синхронном режиме после `flush` декодер сразу принимает вход. */
        fun rewind() {
            codec.flush()
            nextInput = 0
            inputEnded = false
        }

        override fun close() {
            runCatching { codec.stop() }
            codec.release()
        }
    }

    /** Коэффициенты YUV → RGB в Q8 для BT.601 / BT.709, ограниченный или полный диапазон. */
    private class YuvMatrix(val limited: Boolean, val y: Int, val rv: Int, val gu: Int, val gv: Int, val bu: Int) {
        companion object {
            val BT601_LIMITED = YuvMatrix(true, 298, 409, 100, 208, 516)

            fun from(format: MediaFormat): YuvMatrix {
                val full = format.getIntOr(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) ==
                    MediaFormat.COLOR_RANGE_FULL
                val bt709 = format.getIntOr(MediaFormat.KEY_COLOR_STANDARD, 0) == MediaFormat.COLOR_STANDARD_BT709
                return when {
                    !full && !bt709 -> BT601_LIMITED
                    !full -> YuvMatrix(true, 298, 459, 55, 136, 541)
                    !bt709 -> YuvMatrix(false, 256, 359, 88, 183, 454)
                    else -> YuvMatrix(false, 256, 403, 48, 120, 475)
                }
            }

            private fun MediaFormat.getIntOr(key: String, default: Int): Int =
                if (containsKey(key)) getInteger(key) else default
        }
    }

    private companion object {
        const val WAIT_US = 10_000L
        /** ~2 с на кадр: дольше — декодер завис. */
        const val MAX_WAITS = 200

        /**
         * Программный декодер, если есть: стикеры маленькие, а аппаратные экземпляры наперечёт
         * и нужны видео. Выход — YUV 4:2:0 в `Image`.
         */
        fun createDecoder(video: WebmVideo): MediaCodec {
            val format = MediaFormat.createVideoFormat(video.mime, video.width, video.height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            val software = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { info ->
                !info.isEncoder && info.isSoftwareOnly &&
                    info.supportedTypes.any { it.equals(video.mime, ignoreCase = true) } &&
                    runCatching { info.getCapabilitiesForType(video.mime).isFormatSupported(format) }.getOrDefault(false)
            }
            val name = software?.name
                ?: MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
                ?: error("Нет декодера ${video.mime}")
            val codec = MediaCodec.createByCodecName(name)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Exception) {
                codec.release()
                throw e
            }
            return codec
        }
    }
}
