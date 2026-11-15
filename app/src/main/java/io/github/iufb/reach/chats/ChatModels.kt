package io.github.iufb.reach.chats

import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import org.drinkless.tdlib.TdApi

/**
 * Тип списка чатов. Основной список, архив и папки — разные `ChatList` в TDLib,
 * поэтому модель с первой версии параметризована списком (PRODUCT.md §7).
 */
sealed interface ChatListType {
    data object Main : ChatListType
    data object Archive : ChatListType
    data class Folder(val id: Int) : ChatListType
}

internal fun ChatListType.toTd(): TdApi.ChatList = when (this) {
    ChatListType.Main -> TdApi.ChatListMain()
    ChatListType.Archive -> TdApi.ChatListArchive()
    is ChatListType.Folder -> TdApi.ChatListFolder(id)
}

internal fun TdApi.ChatList.toModel(): ChatListType? = when (this) {
    is TdApi.ChatListMain -> ChatListType.Main
    is TdApi.ChatListArchive -> ChatListType.Archive
    is TdApi.ChatListFolder -> ChatListType.Folder(chatFolderId)
    else -> null
}

enum class ChatKind { Private, Secret, Group, Channel }

/** Место чата в одном списке. `order` больше — выше; закреплённые TDLib уже ставит наверх через `order`. */
data class ChatPosition(val order: Long, val isPinned: Boolean)

data class LastMessage(
    val id: Long,
    /** Unix-время, секунды. */
    val date: Int,
    val isOutgoing: Boolean,
    val content: MessagePreview,
)

/** Неизменяемый снимок чата, собранный из апдейтов. */
data class Chat(
    val id: Long,
    val kind: ChatKind,
    val title: String,
    val lastMessage: LastMessage?,
    val positions: Map<ChatListType, ChatPosition>,
    val unreadCount: Int,
    /** Последнее прочитанное входящее: с него открывается чат. */
    val lastReadInboxMessageId: Long,
    /** Маленькая аватарка; `null` — фото нет, рисуем инициалы. */
    val photo: TdFile?,
    /** Встроенный цвет Telegram (0..6) для плейсхолдера аватарки. */
    val accentColorId: Int,
)

internal fun TdApi.Chat.toModel() = Chat(
    id = id,
    kind = type.toKind(),
    title = title,
    lastMessage = lastMessage?.toLastMessage(),
    positions = positions.toPositionMap(),
    unreadCount = unreadCount,
    lastReadInboxMessageId = lastReadInboxMessageId,
    photo = photo?.small?.toTdFile(),
    accentColorId = accentColorId,
)

private fun TdApi.ChatType.toKind(): ChatKind = when (this) {
    is TdApi.ChatTypePrivate -> ChatKind.Private
    is TdApi.ChatTypeSecret -> ChatKind.Secret
    is TdApi.ChatTypeSupergroup -> if (isChannel) ChatKind.Channel else ChatKind.Group
    else -> ChatKind.Group
}

internal fun TdApi.Message.toLastMessage() = LastMessage(id, date, isOutgoing, content.toPreview())

internal fun Array<TdApi.ChatPosition>.toPositionMap(): Map<ChatListType, ChatPosition> =
    mapNotNull { p -> p.list.toModel()?.takeIf { p.order != 0L }?.let { it to ChatPosition(p.order, p.isPinned) } }.toMap()

/** Применяет одну позицию: `order == 0` значит, что чат убран из этого списка. */
internal fun Map<ChatListType, ChatPosition>.with(position: TdApi.ChatPosition): Map<ChatListType, ChatPosition> {
    val list = position.list.toModel() ?: return this
    return if (position.order == 0L) this - list else this + (list to ChatPosition(position.order, position.isPinned))
}
