package io.github.iufb.reach.stickers

import io.github.iufb.reach.chat.ChatScope
import io.github.iufb.reach.chat.SendOptions
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.MemberStatus
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.chats.toInfo
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Обложка пака для полосы иконок. Формат как у стикеров: статичная картинка,
 * TGS ([isTgs]) или WebM ([isWebm]). Если обложки нет, иконкой служит первый стикер пака.
 */
data class StickerSetThumbnail(val file: TdFile, val isTgs: Boolean, val isWebm: Boolean)

/**
 * Стикерпак (только обычные стикеры, `StickerTypeRegular`).
 *
 * Из списков (установленные, тренды) пак приходит неполным: в [stickers] — несколько
 * стикеров-обложек, а всего их [size]. Полный пак — [StickersRepository.set].
 */
data class StickerSetInfo(
    val id: Long,
    val title: String,
    /** Короткое имя пака для ссылки `t.me/addstickers/<name>`. */
    val name: String,
    val thumbnail: StickerSetThumbnail?,
    val stickers: List<StickerInfo>,
    /** Сколько стикеров в паке всего. */
    val size: Int,
    val isInstalled: Boolean,
    val isArchived: Boolean,
    /** Официальный пак Telegram. */
    val isOfficial: Boolean,
    /** Трендовый пак уже показывали (`viewTrendingStickerSets`); непросмотренные помечаются как новые. */
    val isViewed: Boolean,
) {
    /** В [stickers] все стикеры пака, а не только обложки. */
    val isFull: Boolean get() = stickers.size >= size
}

/** Трендовые паки, загруженные постранично. */
data class TrendingStickers(
    val sets: List<StickerSetInfo> = emptyList(),
    /** Сервер отдаст ещё страницу ([StickersRepository.loadMoreTrending]). */
    val hasMore: Boolean = true,
    val isLoading: Boolean = false,
)

internal fun TdApi.Thumbnail.toSetThumbnail(): StickerSetThumbnail? = when (format) {
    is TdApi.ThumbnailFormatWebp, is TdApi.ThumbnailFormatPng, is TdApi.ThumbnailFormatJpeg ->
        StickerSetThumbnail(file.toTdFile(), isTgs = false, isWebm = false)
    is TdApi.ThumbnailFormatTgs -> StickerSetThumbnail(file.toTdFile(), isTgs = true, isWebm = false)
    is TdApi.ThumbnailFormatWebm -> StickerSetThumbnail(file.toTdFile(), isTgs = false, isWebm = true)
    else -> null
}

/** Полный пак (`getStickerSet`, `updateStickerSet`). */
internal fun TdApi.StickerSet.toModel() = StickerSetInfo(
    id = id,
    title = title,
    name = name,
    thumbnail = thumbnail?.toSetThumbnail(),
    stickers = stickers.map { it.toInfo() },
    size = stickers.size,
    isInstalled = isInstalled,
    isArchived = isArchived,
    isOfficial = isOfficial,
    isViewed = isViewed,
)

/** Пак из списка: только обложки (`covers`). */
internal fun TdApi.StickerSetInfo.toModel() = StickerSetInfo(
    id = id,
    title = title,
    name = name,
    thumbnail = thumbnail?.toSetThumbnail(),
    stickers = covers.map { it.toInfo() },
    size = size,
    isInstalled = isInstalled,
    isArchived = isArchived,
    isOfficial = isOfficial,
    isViewed = isViewed,
)

/**
 * Список из апдейта приходит только id. Если все id уже известны — новый порядок без запроса;
 * `null` — есть новые элементы, список нужно перезапросить.
 */
internal fun <T, K> reorderKnown(current: List<T>, ids: List<K>, key: (T) -> K): List<T>? {
    val byKey = current.associateBy(key)
    return ids.map { byKey[it] ?: return null }
}

/** Пак по короткому имени из ссылки: в ссылках регистр имени не важен. */
internal fun Iterable<StickerSetInfo>.findByName(name: String): StickerSetInfo? =
    firstOrNull { it.name.equals(name, ignoreCase = true) }

/** Флаг «установлен» по актуальному списку установленных паков. */
internal fun StickerSetInfo.withInstalled(installedIds: Set<Long>): StickerSetInfo {
    val installed = id in installedIds
    return if (installed == isInstalled) this else copy(isInstalled = installed, isArchived = isArchived && !installed)
}

/** Следующая страница трендов: дубли (паки, сдвинувшиеся между страницами) отбрасываются. */
internal fun TrendingStickers.appendPage(page: List<StickerSetInfo>, totalCount: Int): TrendingStickers {
    val known = sets.mapTo(HashSet()) { it.id }
    val merged = sets + page.filter { it.id !in known }
    return TrendingStickers(merged, hasMore = page.isNotEmpty() && merged.size < totalCount, isLoading = false)
}

/**
 * `updateTrendingStickerSets` приносит первую страницу заново: она заменяет загруженное,
 * листание дальше начинается после неё.
 */
internal fun trendingFromUpdate(page: List<StickerSetInfo>, totalCount: Int) =
    TrendingStickers().appendPage(page, totalCount)

/**
 * Можно ли отправлять стикеры в чат (`canSendOtherMessages`): личка — всегда;
 * канал — владельцу и админам с правом публикации; группа — по статусу участника и общим
 * разрешениям чата. Пока статус группы неизвестен — нельзя (панель покажет заглушку).
 * [status] — статус в группе из `GroupsRepository.statuses` по [Chat.group].
 */
fun Chat.canSendStickers(status: MemberStatus?): Boolean {
    if (group == null) return true
    return when (status) {
        null, MemberStatus.Left -> false
        is MemberStatus.Creator -> status.isMember
        is MemberStatus.Administrator -> kind != ChatKind.Channel || status.canPostMessages
        MemberStatus.Member -> kind != ChatKind.Channel && canSendOtherMessages
        is MemberStatus.Restricted -> kind != ChatKind.Channel && status.isMember && status.canSendOther && canSendOtherMessages
    }
}

/** Файл стикера для запросов: постоянный remote id, если есть, иначе id файла в этой сессии. */
internal fun StickerInfo.inputFile(): TdApi.InputFile =
    if (remoteId.isNotEmpty()) TdApi.InputFileRemote(remoteId) else TdApi.InputFileId(file.id)

/** Содержимое сообщения-стикера: файл, размеры и эмодзи. */
internal fun StickerInfo.toInputContent(): TdApi.InputMessageSticker =
    TdApi.InputMessageSticker(TdApi.InputSticker(inputFile(), null, width, height), emoji)

/** `sendMessage` со стикером: тема (тред / топик) и ответ — как у текста в `ChatHistory.sendText`. */
internal fun stickerMessage(
    chatId: Long,
    scope: ChatScope,
    replyToMessageId: Long?,
    sticker: StickerInfo,
    options: SendOptions,
) = TdApi.SendMessage(
    chatId,
    scope.toTd(),
    replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") },
    options.toTd(),
    null,
    sticker.toInputContent(),
)
