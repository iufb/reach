package io.github.iufb.reach.media

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.files.FilesRepository
import io.github.iufb.reach.files.TdFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Фото или видео, которое отдаём наружу: файл TDLib и его тип. */
data class ExportedMedia(val file: TdFile, val isVideo: Boolean)

/**
 * «Сохранить в галерею» и «Поделиться» из полноэкранного просмотра. Файл сначала докачивается
 * (у фото — оригинал), потом копируется в MediaStore (Pictures/Reach, Movies/Reach) или отдаётся
 * другим приложениям через FileProvider. Разрешений не нужно: свои записи в MediaStore — без них.
 */
@Singleton
class MediaExport @Inject constructor(
    @ApplicationContext private val context: Context,
    private val files: FilesRepository,
) {

    /** Копирует в галерею; `false` — не удалось скачать или записать. */
    suspend fun save(media: ExportedMedia): Boolean = runCatching {
        val source = File(files.awaitDownloaded(media.file.id))
        withContext(Dispatchers.IO) {
            val collection = if (media.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType(source, media.isVideo))
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    (if (media.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) + "/" + ALBUM,
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(collection, values)) { "insert failed" }
            try {
                resolver.openOutputStream(uri).use { out -> source.inputStream().use { it.copyTo(checkNotNull(out)) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "save failed", it)
        false
    }

    /** Системный выбор «Поделиться» с файлом; `null` — файл не удалось скачать. */
    suspend fun shareIntent(media: ExportedMedia): Intent? = runCatching {
        val source = File(files.awaitDownloaded(media.file.id))
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", source)
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType(source, media.isVideo))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }.getOrElse {
        Log.w(TAG, "share failed", it)
        null
    }

    private fun mimeType(file: File, isVideo: Boolean): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "mov" -> "video/quicktime"
        "webm" -> "video/webm"
        else -> if (isVideo) "video/mp4" else "image/jpeg"
    }

    private companion object {
        const val TAG = "MediaExport"
        const val ALBUM = "Reach"
    }
}
