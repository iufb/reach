package io.github.iufb.reach.chat

import androidx.annotation.StringRes
import io.github.iufb.reach.chats.ContentKind
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.fonts.LocalFont
import io.github.iufb.reach.fonts.isFontFileName
import org.drinkless.tdlib.TdApi

/**
 * Что можно сделать с сообщением. Права берём из `getMessageProperties`,
 * чтобы не показывать пункты, которые TDLib всё равно отклонит.
 */
data class MessageActions(
    /** Текст или подпись, которые можно скопировать; `null` — копировать нечего или нельзя. */
    val copyText: String?,
    val canReply: Boolean,
    val canEdit: Boolean,
    val canDeleteForAll: Boolean,
    val canDeleteForSelf: Boolean,
    val canForward: Boolean,
    /** Можно закрепить или открепить (`canBePinned`); что именно — по `Message.isPinned`. */
    val canPin: Boolean = false,
    /** Своё сообщение с открытым опросом: «Остановить опрос». */
    val canStopPoll: Boolean = false,
    /** «Отменить голос». */
    val canRetractVote: Boolean = false,
    /** Документ `.ttf`/`.otf`: «Установить как шрифт» (#275). */
    val canInstallFont: Boolean = false,
) {
    val canDelete: Boolean get() = canDeleteForAll || canDeleteForSelf
    val isEmpty: Boolean get() = copyText == null && !canReply && !canEdit && !canDelete && !canForward && !canPin &&
        !canStopPoll && !canRetractVote && !canInstallFont
}

/** Открытое меню действий над [message]; [reactions] — ряд реакций сверху. */
data class MessageMenu(
    val message: Message,
    val actions: MessageActions,
    val reactions: AvailableReactions = AvailableReactions.None,
)

/** Текст, который копируется из сообщения: сам текст или подпись к медиа. */
internal fun MessageBody.copyableText(): String? = when (this) {
    is MessageBody.Text -> text.text
    is MessageBody.Unsupported -> caption.text.ifBlank { null }
    is MessageBody.Photo -> caption.text.ifBlank { null }
    is MessageBody.Video -> caption.text.ifBlank { null }
    is MessageBody.Animation -> caption.text.ifBlank { null }
    is MessageBody.Sticker -> null
    is MessageBody.AnimatedEmoji -> emoji
    is MessageBody.Poll -> null
    is MessageBody.Voice -> caption.text.ifBlank { null }
    is MessageBody.VideoNote -> null
    is MessageBody.Audio -> caption.text.ifBlank { null }
    is MessageBody.Document -> caption.text.ifBlank { null }
    is MessageBody.Service -> null
    is MessageBody.Call -> null
}

/** [properties] `null` — запрос не удался: показываем только то, что не требует прав сервера. */
internal fun messageActions(message: Message, properties: TdApi.MessageProperties?): MessageActions {
    // Защищённый контент (запрет копирования в чате) копировать нельзя
    val protected = properties != null &&
        (properties.hasProtectedContentByCurrentUser || properties.hasProtectedContentByOtherUser)
    val poll = (message.content as? MessageBody.Poll)?.poll
    return MessageActions(
        copyText = message.content.copyableText().takeUnless { protected },
        canReply = properties?.canBeReplied == true,
        canEdit = properties?.canBeEdited == true && message.content.editableText() != null,
        canDeleteForAll = properties?.canBeDeletedForAllUsers == true,
        canDeleteForSelf = properties?.canBeDeletedOnlyForSelf == true,
        canForward = properties?.canBeForwarded == true,
        canPin = properties?.canBePinned == true,
        // stopPoll проверяется по can_be_edited
        canStopPoll = poll != null && !poll.isClosed && message.isOutgoing && properties?.canBeEdited == true,
        canRetractVote = poll?.canRetract == true,
        canInstallFont = (message.content as? MessageBody.Document)?.document?.fileName?.let(::isFontFileName) == true,
    )
}

/** Что правится в поле ввода: текст сообщения или подпись к медиа (пустую тоже можно дописать). */
internal fun MessageBody.editableText(): RichText? = when (this) {
    is MessageBody.Text -> text
    is MessageBody.Unsupported -> caption.takeIf { kind in CAPTIONED }
    is MessageBody.Photo -> caption
    is MessageBody.Video -> caption
    is MessageBody.Animation -> caption
    is MessageBody.Sticker -> null
    is MessageBody.AnimatedEmoji -> null
    is MessageBody.Poll -> null
    is MessageBody.Voice -> caption
    is MessageBody.VideoNote -> null
    is MessageBody.Audio -> caption
    is MessageBody.Document -> caption
    is MessageBody.Service -> null
    is MessageBody.Call -> null
}

/** Типы медиа, у которых есть подпись. */
private val CAPTIONED = setOf(
    ContentKind.Photo, ContentKind.Video, ContentKind.Document, ContentKind.Audio,
    ContentKind.VoiceNote, ContentKind.Animation,
)

/** Итог «Установить как шрифт»: шрифт добавлен или ошибка ([message] `null` — общий текст). */
sealed interface FontNotice {
    data class Added(val font: LocalFont) : FontNotice
    /** [message] — строковый ресурс с причиной; `null` — общая ошибка. */
    data class Failed(@StringRes val message: Int?) : FontNotice
}
