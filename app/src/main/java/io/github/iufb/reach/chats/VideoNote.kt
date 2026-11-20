package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/** Видеокружок: квадратное видео со стороной [length], показывается кругом. */
data class VideoNoteInfo(
    val length: Int,
    /** Длительность, секунды. */
    val duration: Int,
    val poster: PhotoInfo,
    val file: TdFile,
    /** Размер в байтах; 0 — неизвестен. */
    val size: Long,
)

internal fun TdApi.VideoNote.toInfo(): VideoNoteInfo {
    val thumb = thumbnail?.takeIf {
        it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatPng || it.format is TdApi.ThumbnailFormatWebp
    }?.file?.toTdFile()
    return VideoNoteInfo(
        length = length,
        duration = duration,
        poster = PhotoInfo(length, length, minithumbnail?.data, thumb, thumb),
        file = video.toTdFile(),
        size = video.size.takeIf { it > 0 } ?: video.expectedSize,
    )
}
