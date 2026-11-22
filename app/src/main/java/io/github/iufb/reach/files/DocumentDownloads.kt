package io.github.iufb.reach.files

import android.util.Log
import io.github.iufb.reach.chat.OpenFile
import io.github.iufb.reach.chats.DocumentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Тап по документу: скачан — открыть, качается — отменить, иначе — скачать с прогрессом.
 * Живёт в ViewModel экрана ([scope] — её scope): чат, медиа-галерея.
 */
class DocumentDownloads(private val files: FilesRepository, private val scope: CoroutineScope) {

    private val _states = MutableStateFlow<Map<Int, FileState>>(emptyMap())
    /** Состояние документов, которые качаются или скачаны в этом экране; по id файла. */
    val states: StateFlow<Map<Int, FileState>> = _states.asStateFlow()
    private val jobs = HashMap<Int, Job>()

    private val _open = MutableSharedFlow<OpenFile>(extraBufferCapacity = 1)
    /** Скачанный файл, который надо открыть внешним приложением. */
    val open: SharedFlow<OpenFile> = _open.asSharedFlow()

    fun onClick(document: DocumentInfo) {
        val id = document.file.id
        val state = _states.value[id]
        val path = state?.takeIf { it.isDownloaded }?.path ?: document.localPath.takeIf { document.downloaded && state == null }
        when {
            path != null -> _open.tryEmit(OpenFile(path, document.mimeType))
            jobs[id]?.isActive == true -> {
                jobs.remove(id)?.cancel()
                _states.update { it - id }
                scope.launch { runCatching { files.cancelDownload(id) } }
            }
            else -> start(id)
        }
    }

    /**
     * Путь к скачанному документу: качает, если нужно, с тем же прогрессом в ячейке, что и при тапе.
     * `null` — скачать не удалось или загрузку отменили тапом.
     */
    suspend fun awaitPath(document: DocumentInfo): String? {
        val id = document.file.id
        _states.value[id]?.takeIf { it.isDownloaded }?.let { return it.path }
        (jobs[id]?.takeIf { it.isActive } ?: start(id)).join()
        return _states.value[id]?.takeIf { it.isDownloaded }?.path
    }

    private fun start(id: Int): Job = scope.launch {
        runCatching {
            files.download(id, FilesRepository.PRIORITY_DOCUMENT).collect { s -> _states.update { it + (id to s) } }
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e("DocumentDownloads", "download $id failed", it)
            _states.update { map -> map - id }
        }
    }.also { jobs[id] = it }
}
