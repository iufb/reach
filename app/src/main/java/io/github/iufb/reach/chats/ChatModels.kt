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

/** Папка из `UpdateChatFolders`. */
data class ChatFolder(val id: Int, val title: String)

/** Папки в порядке пользователя; основной список стоит на позиции [mainPosition]. */
data class ChatFolders(val folders: List<ChatFolder>, val mainPosition: Int) {
    /** Вкладки по порядку: папки и «Все чаты» на своём месте. Без папок — пусто. */
    val lists: List<ChatListType>
        get() = if (folders.isEmpty()) emptyList() else folders.map<ChatFolder, ChatListType> { ChatListType.Folder(it.id) }
            .toMutableList().apply { add(mainPosition.coerceIn(0, size), ChatListType.Main) }

    companion object {
        val Empty = ChatFolders(emptyList(), 0)
    }
}

/** Счётчики списка из `UpdateUnreadChatCount`: всего чатов и сколько из них непрочитанных. */
data class ChatListCounts(val total: Int, val unread: Int, val unreadUnmuted: Int)

enum class ChatKind { Private, Secret, Group, Channel }

/** Место чата в одном списке. `order` больше — выше; закреплённые TDLib уже ставит наверх через `order`. */
data class ChatPosition(val order: Long, val isPinned: Boolean)

data class LastMessage(
    val id: Long,
    /** Unix-время, секунды. */
    val date: Int,
    val isOutgoing: Boolean,
    val content: MessageBody,
)

/** Черновик: незаконченный текст в поле ввода, синхронизируется TDLib между клиентами. */
data class Draft(
    val text: RichText,
    /** Unix-время, секунды. */
    val date: Int,
    /** Сообщение этого чата, на которое начат ответ. */
    val replyToMessageId: Long? = null,
)

/** Неизменяемый снимок чата, собранный из апдейтов. */
data class Chat(
    val id: Long,
    val kind: ChatKind,
    /** Группа или канал за чатом; `null` у личных чатов. */
    val group: GroupRef?,
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
    /** Общее разрешение участникам писать текст (`ChatPermissions.canSendBasicMessages`). */
    val canSendBasicMessages: Boolean,
    /** Общее разрешение на видеокружки (`ChatPermissions.canSendVideoNotes`). */
    val canSendVideoNotes: Boolean = true,
    val draft: Draft? = null,
    /** Собеседник личного (и секретного) чата. */
    val userId: Long? = null,
    /** Своя настройка «без звука», секунды; `null` — по умолчанию для типа чата. */
    val muteFor: Int? = null,
    /** Итог с учётом настроек по умолчанию: уведомления выключены. */
    val isMuted: Boolean = false,
    /** Пользователь вручную пометил чат непрочитанным. */
    val isMarkedAsUnread: Boolean = false,
    /** Чат с самим собой — Избранное. */
    val isSavedMessages: Boolean = false,
    /** Отправлять без звука по умолчанию (`default_disable_notification`): колокольчик в поле ввода канала. */
    val defaultSilent: Boolean = false,
    /** Есть отложенные сообщения: в шапке чата вход на их экран. */
    val hasScheduledMessages: Boolean = false,
    /** Автоудаление новых сообщений, секунды; 0 — выключено. */
    val autoDeleteTime: Int = 0,
    /** Общее разрешение участникам менять описание и настройки (`ChatPermissions.canChangeInfo`). */
    val canChangeInfoByDefault: Boolean = false,
    /** Форум, который показывается списком топиков. */
    val viewAsTopics: Boolean = false,
    /** Собеседник (или чат) в чёрном списке. */
    val isBlocked: Boolean = false,
    /** Общее разрешение участникам приглашать (`ChatPermissions.canInviteUsers`). */
    val canInviteByDefault: Boolean = false,
) {
    /** Есть что читать: непрочитанные сообщения или ручная пометка. */
    val hasUnread: Boolean get() = unreadCount > 0 || isMarkedAsUnread

    val notificationScope: NotificationScope
        get() = when (kind) {
            ChatKind.Private, ChatKind.Secret -> NotificationScope.Private
            ChatKind.Group -> NotificationScope.Group
            ChatKind.Channel -> NotificationScope.Channel
        }

    /** Пересчитывает [isMuted] по настройкам по умолчанию [scopes] (секунды «без звука» по типу чата). */
    fun withMute(scopes: Map<NotificationScope, Int>): Chat =
        copy(isMuted = (muteFor ?: scopes[notificationScope] ?: 0) > 0)
}

/** Тип чата для настроек уведомлений по умолчанию. */
enum class NotificationScope { Private, Group, Channel }

internal fun TdApi.NotificationSettingsScope.toModel(): NotificationScope = when (this) {
    is TdApi.NotificationSettingsScopePrivateChats -> NotificationScope.Private
    is TdApi.NotificationSettingsScopeGroupChats -> NotificationScope.Group
    else -> NotificationScope.Channel
}

internal val TdApi.ChatNotificationSettings.ownMuteFor: Int?
    get() = muteFor.takeUnless { useDefaultMuteFor }

internal fun TdApi.Chat.toModel() = Chat(
    id = id,
    kind = type.toKind(),
    group = when (val t = type) {
        is TdApi.ChatTypeBasicGroup -> GroupRef.Basic(t.basicGroupId)
        is TdApi.ChatTypeSupergroup -> GroupRef.Super(t.supergroupId)
        else -> null
    },
    title = title,
    lastMessage = lastMessage?.toLastMessage(),
    positions = positions.toPositionMap(),
    unreadCount = unreadCount,
    lastReadInboxMessageId = lastReadInboxMessageId,
    photo = photo?.small?.toTdFile(),
    accentColorId = accentColorId,
    canSendBasicMessages = permissions.canSendBasicMessages,
    canSendVideoNotes = permissions.canSendVideoNotes,
    draft = draftMessage?.toDraft(),
    userId = when (val t = type) {
        is TdApi.ChatTypePrivate -> t.userId
        is TdApi.ChatTypeSecret -> t.userId
        else -> null
    },
    muteFor = notificationSettings.ownMuteFor,
    isMarkedAsUnread = isMarkedAsUnread,
    defaultSilent = defaultDisableNotification,
    hasScheduledMessages = hasScheduledMessages,
    autoDeleteTime = messageAutoDeleteTime,
    canChangeInfoByDefault = permissions.canChangeInfo,
    viewAsTopics = viewAsTopics,
    isBlocked = blockList is TdApi.BlockListMain,
    canInviteByDefault = permissions.canInviteUsers,
)

/** Черновики голосовых и видеокружков клиент не показывает. */
internal fun TdApi.DraftMessage.toDraft(): Draft? {
    val text = (content as? TdApi.DraftMessageContentText)?.text?.toRichText() ?: return null
    val replyTo = (replyTo as? TdApi.InputMessageReplyToMessage)?.messageId?.takeIf { it != 0L }
    return Draft(text, date, replyTo).takeUnless { text.isBlank() && replyTo == null }
}

private fun TdApi.ChatType.toKind(): ChatKind = when (this) {
    is TdApi.ChatTypePrivate -> ChatKind.Private
    is TdApi.ChatTypeSecret -> ChatKind.Secret
    is TdApi.ChatTypeSupergroup -> if (isChannel) ChatKind.Channel else ChatKind.Group
    else -> ChatKind.Group
}

internal fun TdApi.Message.toLastMessage() = LastMessage(id, date, isOutgoing, content.toBody())

internal fun Array<TdApi.ChatPosition>.toPositionMap(): Map<ChatListType, ChatPosition> =
    mapNotNull { p -> p.list.toModel()?.takeIf { p.order != 0L }?.let { it to ChatPosition(p.order, p.isPinned) } }.toMap()

/** Применяет одну позицию: `order == 0` значит, что чат убран из этого списка. */
internal fun Map<ChatListType, ChatPosition>.with(position: TdApi.ChatPosition): Map<ChatListType, ChatPosition> {
    val list = position.list.toModel() ?: return this
    return if (position.order == 0L) this - list else this + (list to ChatPosition(position.order, position.isPinned))
}
