package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Стикер. Статичный WebP рисуется сам ([file]), анимированный TGS — через Lottie;
 * видеостикеры (WebM) до v0.6 показываются миниатюрой [thumbnail] и эмодзи.
 */
data class StickerInfo(
    val width: Int,
    val height: Int,
    val emoji: String,
    /** Стикерпак; 0 — стикер не из пака. */
    val setId: Long,
    val isStatic: Boolean,
    /** Анимированный (Lottie в gzip). */
    val isTgs: Boolean,
    val file: TdFile,
    /** Картинка-миниатюра (WebP/JPEG/PNG), если есть. */
    val thumbnail: TdFile?,
)

internal fun TdApi.Sticker.toInfo() = StickerInfo(
    width = width,
    height = height,
    emoji = emoji,
    setId = setId,
    isStatic = format is TdApi.StickerFormatWebp,
    isTgs = format is TdApi.StickerFormatTgs,
    file = sticker.toTdFile(),
    thumbnail = thumbnail?.takeIf {
        it.format is TdApi.ThumbnailFormatWebp || it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatPng
    }?.file?.toTdFile(),
)
