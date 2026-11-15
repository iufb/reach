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

    /** Путь к скачанному файлу; ждёт окончания загрузки. */
    suspend fun awaitDownloaded(fileId: Int, priority: Int = PRIORITY_DEFAULT): String =
        download(fileId, priority).first { it.isDownloaded }.path

    companion object {
        /** Приоритеты `downloadFile`: 1..32, больше — раньше. */
        const val PRIORITY_DEFAULT = 1
        const val PRIORITY_AVATAR = 8
    }
}
