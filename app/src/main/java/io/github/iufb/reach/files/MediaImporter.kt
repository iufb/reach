package io.github.iufb.reach.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Картинка, скопированная в кэш приложения: TDLib отправляет только локальные файлы. */
data class ImportedImage(val path: String, val width: Int, val height: Int)

/**
 * Копирует выбранное пользователем (`content://` из Photo Picker и т.п.) в кэш,
 * откуда TDLib сможет загрузить файл (`InputFileLocal`).
 */
@Singleton
class MediaImporter @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun importImage(uri: Uri): ImportedImage = withContext(Dispatchers.IO) {
        val file = copyToCache(uri, "jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        ImportedImage(file.path, bounds.outWidth.coerceAtLeast(0), bounds.outHeight.coerceAtLeast(0))
    }

    /**
     * Фото профиля, группы или канала: центральный квадрат, не больше [SQUARE_MAX] px, JPEG.
     * Telegram хранит такие фото квадратными; своя обрезка — чтобы результат был предсказуем.
     */
    suspend fun importSquarePhoto(uri: Uri): String = withContext(Dispatchers.IO) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            // Сразу уменьшаем при декодировании: огромные фото с камеры не нужны целиком
            val side = minOf(info.size.width, info.size.height)
            if (side > SQUARE_MAX * 2) {
                val scale = (SQUARE_MAX * 2).toFloat() / side
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        val side = minOf(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val scaled = if (side > SQUARE_MAX) Bitmap.createScaledBitmap(square, SQUARE_MAX, SQUARE_MAX, true) else square
        val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        file.path
    }

    /** Любой файл: в отдельную папку под исходным именем — TDLib берёт имя документа из пути. */
    suspend fun importFile(uri: Uri): String = withContext(Dispatchers.IO) {
        val name = displayName(uri)?.replace('/', '_')?.takeIf { it.isNotBlank() } ?: "file"
        val dir = File(File(context.cacheDir, UPLOAD_DIR), UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(dir, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open $uri")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        file.path
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }

    private fun copyToCache(uri: Uri, extension: String): File {
        val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open $uri")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    private companion object {
        const val UPLOAD_DIR = "upload"
        const val SQUARE_MAX = 1024
        const val JPEG_QUALITY = 90
    }
}
