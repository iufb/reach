package io.github.iufb.reach.files

import org.drinkless.tdlib.TdApi

/**
 * Ссылка на файл TDLib для загрузки картинок через Coil.
 * [id] живёт только в текущей сессии, [uniqueId] стабилен — им и ключуется кэш.
 */
data class TdFile(val id: Int, val uniqueId: String)

internal fun TdApi.File.toTdFile() = TdFile(id, remote.uniqueId)

/** Состояние загрузки файла. */
data class FileState(
    val id: Int,
    /** Путь к файлу на диске; полный, только когда [isDownloaded]. */
    val path: String,
    val downloadedSize: Long,
    /** Размер файла, 0 — неизвестен. */
    val size: Long,
    val isDownloaded: Boolean,
)

internal fun TdApi.File.toState() = FileState(
    id = id,
    path = local.path,
    downloadedSize = local.downloadedSize,
    size = size.takeIf { it > 0 } ?: expectedSize,
    isDownloaded = local.isDownloadingCompleted,
)
