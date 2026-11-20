package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Видео: [poster] — картинка для пузыря и до старта плеера (обложка или миниатюра кадра),
 * [file] — сам ролик, играется стримингом по мере загрузки.
 */
data class VideoInfo(
    val width: Int,
    val height: Int,
    /** Длительность, секунды. */
    val duration: Int,
    val poster: PhotoInfo,
    val file: TdFile,
    /** Размер ролика в байтах; 0 — неизвестен. */
    val size: Long = 0,
)

internal fun TdApi.MessageVideo.toInfo(): VideoInfo {
    val coverInfo = cover?.toInfo()
    // Миниатюра бывает и MPEG4 — её Coil не покажет, такую пропускаем
    val thumbnail = video.thumbnail?.takeIf { it.format.isImage() }
    val width = video.width.takeIf { it > 0 } ?: thumbnail?.width ?: coverInfo?.width ?: 0
    val height = video.height.takeIf { it > 0 } ?: thumbnail?.height ?: coverInfo?.height ?: 0
    return VideoInfo(
        width = width,
        height = height,
        duration = video.duration,
        poster = PhotoInfo(
            width = width,
            height = height,
            minithumbnail = coverInfo?.minithumbnail ?: video.minithumbnail?.data,
            preview = coverInfo?.preview ?: thumbnail?.file?.toTdFile(),
            full = coverInfo?.full ?: thumbnail?.file?.toTdFile(),
        ),
        file = video.video.toTdFile(),
        size = video.video.size.takeIf { it > 0 } ?: video.video.expectedSize,
    )
}

internal fun TdApi.MessageAnimation.toInfo(): VideoInfo {
    val thumbnail = animation.thumbnail?.takeIf { it.format.isImage() }
    val width = animation.width.takeIf { it > 0 } ?: thumbnail?.width ?: 0
    val height = animation.height.takeIf { it > 0 } ?: thumbnail?.height ?: 0
    return VideoInfo(
        width = width,
        height = height,
        duration = animation.duration,
        poster = PhotoInfo(
            width = width,
            height = height,
            minithumbnail = animation.minithumbnail?.data,
            preview = thumbnail?.file?.toTdFile(),
            full = thumbnail?.file?.toTdFile(),
        ),
        file = animation.animation.toTdFile(),
        size = animation.animation.size.takeIf { it > 0 } ?: animation.animation.expectedSize,
    )
}

private fun TdApi.ThumbnailFormat.isImage() =
    this is TdApi.ThumbnailFormatJpeg || this is TdApi.ThumbnailFormatPng || this is TdApi.ThumbnailFormatWebp

/** «1:05», «1:02:03». */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
