package io.github.iufb.reach.core.rlottie

import android.graphics.Bitmap
import java.io.Closeable

/**
 * Lottie-анимация на rlottie (C++, ADR-0013). Не потокобезопасна: один экземпляр — один поток.
 * Разобранную модель rlottie кеширует по [key], так что повторное создание по тому же ключу дешёвое.
 */
class RLottie private constructor(private var ptr: Long) : Closeable {

    /** Кадров в анимации (по её собственной частоте). */
    val frameCount: Int = nativeFrameCount(ptr)

    /** Кадров в секунду; у TGS обычно 60. */
    val frameRate: Double = nativeFrameRate(ptr)

    /**
     * Рисует кадр [frame] в [bitmap] (`ARGB_8888`) по его размеру, с сохранением пропорций.
     * Тот же кадр два раза подряд rlottie не перерисовывает — не просите.
     */
    fun render(frame: Int, bitmap: Bitmap): Boolean {
        check(ptr != 0L) { "closed" }
        return nativeRender(ptr, frame, bitmap)
    }

    override fun close() {
        if (ptr != 0L) nativeDestroy(ptr)
        ptr = 0L
    }

    companion object {
        init {
            System.loadLibrary("rlottiejni")
        }

        /** Анимация из Lottie JSON; `null` — JSON не разобрался. [key] — ключ кеша моделей, пустой — без кеша. */
        fun create(json: String, key: String): RLottie? = nativeCreate(json, key).takeIf { it != 0L }?.let(::RLottie)

        /** Пиксели [bitmap] (`ARGB_8888`), сжатые LZ4 — для кеша кадров. */
        fun compress(bitmap: Bitmap): ByteArray? = nativeCompress(bitmap)

        /** Обратно в [bitmap] того же размера и формата, что и при [compress]. */
        fun decompress(data: ByteArray, bitmap: Bitmap): Boolean = nativeDecompress(data, bitmap)

        @JvmStatic private external fun nativeCreate(json: String, key: String): Long
        @JvmStatic private external fun nativeDestroy(ptr: Long)
        @JvmStatic private external fun nativeFrameCount(ptr: Long): Int
        @JvmStatic private external fun nativeFrameRate(ptr: Long): Double
        @JvmStatic private external fun nativeRender(ptr: Long, frame: Int, bitmap: Bitmap): Boolean
        @JvmStatic private external fun nativeCompress(bitmap: Bitmap): ByteArray?
        @JvmStatic private external fun nativeDecompress(data: ByteArray, bitmap: Bitmap): Boolean
    }
}
