package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Стикер. Статичный WebP рисуется сам ([file]), анимированный TGS — через Lottie,
 * видеостикер WebM — своим декодером с альфой (ADR-0008).
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
    /** Видеостикер (VP9 с альфа-каналом). */
    val isWebm: Boolean,
    val file: TdFile,
    /** Картинка-миниатюра (WebP/JPEG/PNG), если есть. */
    val thumbnail: TdFile?,
    /** Id стикера в паке (`sticker.id`); 0 — неизвестен. */
    val id: Long = 0,
    /**
     * Постоянный remote id файла: по нему стикер отправляется (`InputFileRemote`).
     * Пусто — файл ещё не на сервере, тогда отправка идёт по [TdFile.id] этой сессии.
     */
    val remoteId: String = "",
)

internal fun TdApi.Sticker.toInfo() = StickerInfo(
    width = width,
    height = height,
    emoji = emoji,
    setId = setId,
    isStatic = format is TdApi.StickerFormatWebp,
    isTgs = format is TdApi.StickerFormatTgs,
    isWebm = format is TdApi.StickerFormatWebm,
    file = sticker.toTdFile(),
    thumbnail = thumbnail?.takeIf {
        it.format is TdApi.ThumbnailFormatWebp || it.format is TdApi.ThumbnailFormatJpeg || it.format is TdApi.ThumbnailFormatPng
    }?.file?.toTdFile(),
    id = id,
    remoteId = sticker.remote.id,
)
