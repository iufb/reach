package io.github.iufb.reach.files

import io.github.iufb.reach.core.td.TdClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Загрузка файлов TDLib (PRODUCT.md §4.4): `downloadFile` запускает загрузку,
 * прогресс приходит апдейтами `UpdateFile`.
 */
@Singleton
class FilesRepository @Inject constructor(private val td: TdClient) {

    /**
     * Запускает загрузку и отдаёт прогресс до завершения включительно.
     * Если файл уже скачан, поток сразу отдаёт готовое состояние и завершается.
     * Ошибка `downloadFile` (файл недоступен) вылетает как `TdException`.
     */
    fun download(fileId: Int, priority: Int = PRIORITY_DEFAULT): Flow<FileState> = td.updates
        // Подписываемся на апдейты до запроса, чтобы не пропустить прогресс между ответом и подпиской
        .onSubscription { emit(TdApi.UpdateFile(td.send(TdApi.DownloadFile(fileId, priority, 0, 0, false)))) }
        .filterIsInstance<TdApi.UpdateFile>()
        .filter { it.file.id == fileId }
        .map { it.file.toState() }
        .transformWhile {
            emit(it)
            !it.isDownloaded
        }

    /** Доля отправленного на сервер (0..1) по апдейтам файла; для своих отправляющихся медиа. */
    fun uploadProgress(fileId: Int): Flow<Float> = td.updates
        .filterIsInstance<TdApi.UpdateFile>()
        .filter { it.file.id == fileId }
        .map { update ->
            val file = update.file
            val total = file.size.takeIf { it > 0 } ?: file.expectedSize
            if (total > 0) (file.remote.uploadedSize.toFloat() / total).coerceIn(0f, 1f) else 0f
        }

    /** Останавливает скачивание; уже скачанная часть остаётся. */
    suspend fun cancelDownload(fileId: Int) {
        td.send(TdApi.CancelDownloadFile(fileId, false))
    }

    /** Путь к скачанному файлу; ждёт окончания загрузки. */
    suspend fun awaitDownloaded(fileId: Int, priority: Int = PRIORITY_DEFAULT): String =
        download(fileId, priority).first { it.isDownloaded }.path

    companion object {
        /** Приоритеты `downloadFile`: 1..32, больше — раньше. */
        const val PRIORITY_DEFAULT = 1
        const val PRIORITY_AVATAR = 8
        /** Документ, который пользователь сам попросил скачать. */
        const val PRIORITY_DOCUMENT = 16
    }
}
