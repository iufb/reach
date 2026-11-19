package io.github.iufb.reach.files

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
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

    private fun copyToCache(uri: Uri, extension: String): File {
        val dir = File(context.cacheDir, UPLOAD_DIR).apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.$extension")
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't open $uri")
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        return file
    }

    private companion object {
        const val UPLOAD_DIR = "upload"
    }
}
