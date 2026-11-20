package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/** Музыкальный трек: название, исполнитель, обложка альбома. */
data class AudioInfo(
    val title: String,
    val performer: String,
    val fileName: String,
    /** Секунды. */
    val duration: Int,
    val file: TdFile,
    val cover: PhotoInfo?,
) {
    /** Что показывать как название: title, иначе имя файла. */
    val displayTitle: String get() = title.ifBlank { fileName }
}

internal fun TdApi.Audio.toAudioInfo(): AudioInfo {
    val thumbnail = albumCoverThumbnail?.takeIf {
        it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatPng || it.format is TdApi.ThumbnailFormatWebp
    }
    val cover = if (thumbnail != null || albumCoverMinithumbnail != null) {
        PhotoInfo(
            width = thumbnail?.width ?: albumCoverMinithumbnail?.width ?: 0,
            height = thumbnail?.height ?: albumCoverMinithumbnail?.height ?: 0,
            minithumbnail = albumCoverMinithumbnail?.data,
            preview = thumbnail?.file?.toTdFile(),
            full = thumbnail?.file?.toTdFile(),
        )
    } else {
        null
    }
    return AudioInfo(title, performer, fileName, duration, audio.toTdFile(), cover)
}
