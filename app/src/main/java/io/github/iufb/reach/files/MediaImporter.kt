package io.github.iufb.reach.files

import android.content.Context
import android.graphics.BitmapFactory
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
    }
}
