package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/** Документ (и аудио — до плеера в v0.4): имя, тип, размер и состояние файла на момент получения. */
data class DocumentInfo(
    val fileName: String,
    val mimeType: String,
    val file: TdFile,
    /** Размер в байтах; 0 — неизвестен. */
    val size: Long,
    /** Уже скачан: тогда [localPath] — полный путь. Дальнейший прогресс приходит апдейтами файла. */
    val downloaded: Boolean,
    val localPath: String,
)

internal fun TdApi.File.toDocumentInfo(fileName: String, mimeType: String) = DocumentInfo(
    fileName = fileName,
    mimeType = mimeType,
    file = toTdFile(),
    size = size.takeIf { it > 0 } ?: expectedSize,
    downloaded = local.isDownloadingCompleted,
    localPath = local.path,
)

internal fun TdApi.Document.toInfo() = document.toDocumentInfo(fileName, mimeType)

internal fun TdApi.Audio.toInfo() = audio.toDocumentInfo(
    fileName.ifBlank { listOf(performer, title).filter { it.isNotBlank() }.joinToString(" — ") },
    mimeType,
)
