package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Фото в трёх качествах: [minithumbnail] (крошечный JPEG прямо в сообщении — показывается мгновенно),
 * [preview] (под ширину пузыря) и [full] (самый большой размер, для полноэкранного просмотра).
 */
class PhotoInfo(
    val width: Int,
    val height: Int,
    val minithumbnail: ByteArray?,
    val preview: TdFile?,
    val full: TdFile?,
    /** Размер [preview] в байтах (0 — неизвестен) — для правил автозагрузки. */
    val size: Long = 0,
    /** [preview] уже на диске: показываем без оглядки на автозагрузку. */
    val downloaded: Boolean = false,
) {
    // Сравнение по файлам: байты миниатюры для одного и того же фото не меняются
    override fun equals(other: Any?): Boolean =
        other is PhotoInfo && other.width == width && other.height == height && other.preview == preview && other.full == full

    override fun hashCode(): Int = (preview?.hashCode() ?: 0) * 31 + (full?.hashCode() ?: 0)
}

internal fun TdApi.Photo.toInfo(): PhotoInfo {
    val largest = sizes.maxByOrNull { it.width.toLong() * it.height }
    val preview = bestSize(PREVIEW_WIDTH)
    return PhotoInfo(
        width = largest?.width ?: minithumbnail?.width ?: 0,
        height = largest?.height ?: minithumbnail?.height ?: 0,
        minithumbnail = minithumbnail?.data,
        preview = preview?.photo?.toTdFile(),
        full = largest?.photo?.toTdFile(),
        size = preview?.photo?.let { it.size.takeIf { s -> s > 0 } ?: it.expectedSize } ?: 0,
        downloaded = preview?.photo?.local?.isDownloadingCompleted == true,
    )
}

/** Фото профиля (пользователя, группы, канала) — те же три качества. */
internal fun TdApi.ChatPhoto.toInfo(): PhotoInfo {
    val largest = sizes.maxByOrNull { it.width.toLong() * it.height }
    val preview = sizes.filter { it.width >= PREVIEW_WIDTH }.minByOrNull { it.width } ?: largest
    return PhotoInfo(
        width = largest?.width ?: 0,
        height = largest?.height ?: 0,
        minithumbnail = minithumbnail?.data,
        preview = preview?.photo?.toTdFile(),
        full = largest?.photo?.toTdFile(),
    )
}

/** Ширина пузыря в пикселях на плотном экране — с запасом. */
private const val PREVIEW_WIDTH = 800
