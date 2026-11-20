package io.github.iufb.reach.photoedit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Загрузка исходника и сборка итога по [PhotoEdit] (ADR-0009). Итог — в полном разрешении
 * (но не больше [MAX_SIDE]: Telegram всё равно ужмёт фото до 2560 px), JPEG во временной папке.
 */
@Singleton
class PhotoEditRenderer @Inject constructor(@ApplicationContext private val context: Context) {

    /** Исходник в памяти, изменяемый (программный) — на нём рисует редактор и сборка. */
    suspend fun load(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = true
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / side
                decoder.setTargetSize((info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt())
            }
        }
    }

    /** Итог правок [edit] над исходником [uri] — путь к JPEG и размеры. */
    suspend fun renderToFile(uri: Uri, edit: PhotoEdit): RenderedPhoto = withContext(Dispatchers.Default) {
        val result = render(load(uri), edit)
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.jpg")
            file.outputStream().use { result.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            RenderedPhoto(file.path, result.width, result.height)
        }
    }

    /** Удалить отредактированные копии, которые не понадобились (отмена отправки). */
    fun discard(paths: Collection<String>) {
        paths.forEach { File(it).delete() }
    }

    companion object {
        const val MAX_SIDE = 2560
        private const val DIR = "edited"
        private const val JPEG_QUALITY = 92

        /** Повёрнутый и отражённый исходник — система координат правок. */
        fun orient(source: Bitmap, edit: PhotoEdit): Bitmap {
            if (edit.rotation == 0 && !edit.flipped) return source
            val matrix = Matrix().apply {
                postRotate(edit.rotation.toFloat())
                if (edit.flipped) postScale(-1f, 1f)
            }
            return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }

        /** Поворот → цвет (фильтр и коррекция) → рисунок и надписи → кроп. */
        fun render(source: Bitmap, edit: PhotoEdit): Bitmap {
            var image = orient(source, edit)
            colorMatrixFor(edit.filter, edit.adjust)?.let { matrix ->
                val colored = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(matrix) }
                Canvas(colored).drawBitmap(image, 0f, 0f, paint)
                image = colored
            }
            if (edit.strokes.isNotEmpty() || edit.texts.isNotEmpty()) {
                if (!image.isMutable || image === source) image = image.copy(Bitmap.Config.ARGB_8888, true)
                val area = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat())
                OverlayPainter.draw(Canvas(image), edit, area)
            }
            return crop(image, edit.crop)
        }

        private fun crop(bitmap: Bitmap, rect: NormRect): Bitmap {
            if (rect == NormRect.Full) return bitmap
            val left = (rect.left * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
            val top = (rect.top * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
            val width = (rect.width * bitmap.width).roundToInt().coerceIn(1, bitmap.width - left)
            val height = (rect.height * bitmap.height).roundToInt().coerceIn(1, bitmap.height - top)
            return Bitmap.createBitmap(bitmap, left, top, width, height)
        }
    }
}

class RenderedPhoto(val path: String, val width: Int, val height: Int)
