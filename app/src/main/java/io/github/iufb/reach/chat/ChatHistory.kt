package io.github.iufb.reach.chat

import android.util.Log
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.files.ImportedImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.chats.toInfo
import io.github.iufb.reach.chats.toFormattedText
import org.drinkless.tdlib.TdApi

/**
 * Загруженный кусок истории одного чата (PRODUCT.md §4.3).
 *
 * История — непрерывный отрезок сообщений, который растёт в обе стороны:
 * [loadOlder] — вверх, [loadNewer] — вниз, пока не дойдём до последнего сообщения.
 * `getChatHistory` сам выбирает, сколько вернуть, поэтому «пришло меньше, чем просили» —
 * не конец; конец — только пустой ответ.
 *
 * [followUpdates] держит отрезок в актуальном виде: новые, изменённые и удалённые сообщения.
 * Новые добавляются, только если отрезок доходит до конца истории — иначе они придут с [loadNewer].
 */
class ChatHistory(private val chatId: Long, private val td: TdClient) {

    data class State(
        /** Сначала новые. */
        val messages: List<Message> = emptyList(),
        val reachedOldest: Boolean = false,
        val reachedNewest: Boolean = false,
        /** Первое непрочитанное на момент открытия; `null` — открыли с конца. */
        val firstUnreadId: Long? = null,
        val opened: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val olderLock = Mutex()
    private val newerLock = Mutex()

    /**
     * Номер текущего отрезка. Переход к сообщению ([openAround]) заменяет отрезок целиком;
     * страница, запрошенная для старого отрезка, в новый не попадает — иначе в истории будет дыра.
     */
    private var segment = 0

    private val _replied = MutableStateFlow<Map<Long, Message?>>(emptyMap())

    /**
     * Сообщения, на которые отвечают, но которых нет в загруженном отрезке; по id исходного.
     * `null` — исходное недоступно (удалено).
     */
    val replied: StateFlow<Map<Long, Message?>> = _replied.asStateFlow()

    /**
     * Открывает историю: с первого непрочитанного, если [lastReadInboxId] указан и за ним есть сообщения,
     * иначе с конца.
     */
    suspend fun open(lastReadInboxId: Long?) {
        val mySegment = ++segment
        if (lastReadInboxId != null && lastReadInboxId != 0L) {
            // PAGE более новых, само прочитанное и чуть старше
            val around = fetch(lastReadInboxId, offset = -PAGE, limit = PAGE * 2)
            val unread = around.filter { it.id > lastReadInboxId }
            if (unread.isNotEmpty() && mySegment == segment) {
                val reachedNewest = unread.size < PAGE
                _state.update { pending ->
                    State(
                        reachedNewest = reachedNewest,
                        firstUnreadId = unread.minOf { it.id },
                        opened = true,
                    ).merge(if (reachedNewest) around + pending.messages else around)
                }
                return
            }
        }
        val latest = fetch(0, 0, PAGE)
        if (mySegment != segment) return
        // Пришедшие во время загрузки новые сообщения не теряем
        _state.update { pending ->
            val live = if (pending.reachedNewest || !pending.opened) pending.messages else emptyList()
            State(reachedNewest = true, opened = true, firstUnreadId = pending.firstUnreadId).merge(latest + live)
        }
    }

    /**
     * Переход к сообщению, которого может не быть в отрезке: загружаем историю вокруг него.
     * Возвращает `false`, если сообщение недоступно.
     */
    suspend fun openAround(messageId: Long): Boolean {
        if (_state.value.messages.any { it.id == messageId }) return true
        val mySegment = ++segment
        val around = fetch(messageId, offset = -PAGE / 2, limit = PAGE)
        if (around.none { it.id == messageId } || mySegment != segment) return false
        // Конец истории неизвестен: loadNewer дойдёт до него и выставит reachedNewest
        _state.update { s -> State(opened = true, firstUnreadId = s.firstUnreadId).merge(around) }
        return true
    }

    /** Подтягивает исходные сообщения для ответов, которых нет в отрезке. Работает, пока не отменят. */
    suspend fun followReplies() {
        val requested = HashSet<Long>()
        _state.collect { s ->
            val loaded = s.messages.mapTo(HashSet()) { it.id }
            val missing = s.messages.filter { m ->
                val reply = m.replyTo ?: return@filter false
                !reply.isExternal && reply.content == null && reply.messageId !in loaded && reply.messageId !in requested
            }
            for (message in missing) {
                val id = message.replyTo!!.messageId
                requested += id
                val original = runCatching { td.send(TdApi.GetRepliedMessage(chatId, message.id)) }
                    .onFailure { Log.w(TAG, "getRepliedMessage(${message.id}) failed", it) }
                    .getOrNull()?.toModel()
                _replied.update { it + (id to original) }
            }
        }
    }

    suspend fun loadOlder() {
        if (!olderLock.tryLock()) return
        try {
            val current = _state.value
            if (!current.opened || current.reachedOldest) return
            val mySegment = segment
            val oldest = current.messages.lastOrNull()?.id ?: 0
            val older = fetch(oldest, 0, PAGE).filter { oldest == 0L || it.id < oldest }
            if (mySegment != segment) return
            _state.update { s -> if (older.isEmpty()) s.copy(reachedOldest = true) else s.merge(older) }
        } finally {
            olderLock.unlock()
        }
    }

    suspend fun loadNewer() {
        if (!newerLock.tryLock()) return
        try {
            val current = _state.value
            if (!current.opened || current.reachedNewest) return
            val mySegment = segment
            val newest = current.messages.firstOrNull()?.id ?: return
            val newer = fetch(newest, offset = -PAGE, limit = PAGE + 1).filter { it.id > newest }
            if (mySegment != segment) return
            _state.update { s -> if (newer.isEmpty()) s.copy(reachedNewest = true) else s.merge(newer) }
        } finally {
            newerLock.unlock()
        }
    }

    /** Применяет апдейты этого чата; работает, пока не отменят. Запускать до [open]. */
    suspend fun followUpdates() {
        td.updates.collect { update ->
            when (update) {
                // Отложенные живут на своём экране, в истории их нет
                is TdApi.UpdateNewMessage -> if (update.message.chatId == chatId && update.message.schedulingState == null) {
                    val message = update.message.toModel()
                    // До open копим всё: open решит, нужны ли они
                    _state.update { s -> if (s.reachedNewest || !s.opened) s.merge(listOf(message)) else s }
                }
                is TdApi.UpdateMessageContent -> if (update.chatId == chatId) {
                    val body = update.newContent.toBody()
                    edit(update.messageId) { copy(content = body) }
                }
                // Голосовое прослушано (нами или собеседником)
                is TdApi.UpdateMessageContentOpened -> if (update.chatId == chatId) {
                    edit(update.messageId) {
                        val body = content
                        if (body is MessageBody.Voice) copy(content = body.copy(isListened = true)) else this
                    }
                }
                // Голоса в опросе: апдейт по id опроса, без id сообщения
                is TdApi.UpdatePoll -> {
                    val poll = update.poll.toInfo()
                    _state.update { s ->
                        if (s.messages.none { (it.content as? MessageBody.Poll)?.poll?.id == poll.id }) {
                            s
                        } else {
                            s.copy(
                                messages = s.messages.map { m ->
                                    if ((m.content as? MessageBody.Poll)?.poll?.id == poll.id) m.copy(content = MessageBody.Poll(poll)) else m
                                },
                            )
                        }
                    }
                }
                is TdApi.UpdateMessageIsPinned -> if (update.chatId == chatId) {
                    edit(update.messageId) { copy(isPinned = update.isPinned) }
                }
                is TdApi.UpdateMessageEdited -> if (update.chatId == chatId) {
                    edit(update.messageId) { copy(editDate = update.editDate) }
                }
                is TdApi.UpdateMessageInteractionInfo -> if (update.chatId == chatId) {
                    val views = update.interactionInfo?.viewCount ?: 0
                    val reactions = update.interactionInfo.toReactions()
                    edit(update.messageId) { copy(viewCount = views, reactions = reactions) }
                }
                // Отправка завершилась: временный id меняется на постоянный
                is TdApi.UpdateMessageSendSucceeded -> if (update.message.chatId == chatId && update.message.schedulingState == null) {
                    replace(update.oldMessageId, update.message.toModel())
                }
                is TdApi.UpdateMessageSendFailed -> if (update.message.chatId == chatId && update.message.schedulingState == null) {
                    Log.w(TAG, "send failed: ${update.error.code} ${update.error.message}")
                    replace(update.oldMessageId, update.message.toModel())
                }
                // fromCache = выгружено из кэша TDLib, а не удалено — такие оставляем
                is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                    val ids = update.messageIds.toSet()
                    _state.update { s -> s.copy(messages = s.messages.filterNot { it.id in ids }) }
                }
            }
        }
    }

    /**
     * Отправляет текст. Сообщение сразу появляется со статусом «отправляется» (через `UpdateNewMessage`),
     * дальше статус меняют `UpdateMessageSendSucceeded` / `UpdateMessageSendFailed`.
     * Если открыт кусок из середины истории — сначала переходим к концу, иначе своё сообщение не увидеть.
     */
    suspend fun sendText(
        text: RichText,
        replyToMessageId: Long? = null,
        withLinkPreview: Boolean = true,
        options: SendOptions = SendOptions(),
    ) {
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val previewOptions = if (withLinkPreview) null else TdApi.LinkPreviewOptions(true, "", false, false, false)
        val content = TdApi.InputMessageText(text.toFormattedText(), previewOptions, true)
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val pending = td.send(TdApi.SendMessage(chatId, null, replyTo, options.toTd(), null, content))
        mergeSent(listOf(pending))
    }

    /** Фото: одно — обычным сообщением, несколько — альбомом; подпись у первого. */
    suspend fun sendPhotos(
        images: List<ImportedImage>,
        caption: RichText,
        replyToMessageId: Long? = null,
        options: SendOptions = SendOptions(),
        spoiler: Boolean = false,
    ) {
        if (images.isEmpty()) return
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val contents = images.mapIndexed { i, image ->
            val photo = TdApi.InputPhoto(TdApi.InputFileLocal(image.path), null, null, IntArray(0), image.width, image.height)
            val text = if (i == 0) caption.toFormattedText() else null
            TdApi.InputMessagePhoto(photo, text, false, null, spoiler)
        }
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val pending = if (contents.size == 1) {
            listOf(td.send(TdApi.SendMessage(chatId, null, replyTo, options.toTd(), null, contents.single())))
        } else {
            td.send(TdApi.SendMessageAlbum(chatId, null, replyTo, options.toTd(), contents.toTypedArray<TdApi.InputMessageContent>())).messages.toList()
        }
        mergeSent(pending)
    }

    /** Документ из локального файла [path]; имя берётся из пути. */
    suspend fun sendDocument(path: String, replyToMessageId: Long? = null, options: SendOptions = SendOptions()) {
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val document = TdApi.InputDocument(TdApi.InputFileLocal(path), null, false)
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val pending = td.send(TdApi.SendMessage(chatId, null, replyTo, options.toTd(), null, TdApi.InputMessageDocument(document, null)))
        mergeSent(listOf(pending))
    }

    /** Голосовое (OGG/Opus) с длительностью и waveform. */
    suspend fun sendVoice(
        path: String,
        duration: Int,
        waveform: ByteArray,
        replyToMessageId: Long? = null,
        options: SendOptions = SendOptions(),
    ) {
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val voice = TdApi.InputVoiceNote(TdApi.InputFileLocal(path), duration, waveform)
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val pending = td.send(TdApi.SendMessage(chatId, null, replyTo, options.toTd(), null, TdApi.InputMessageVoiceNote(voice, null, null)))
        mergeSent(listOf(pending))
    }

    /** Опрос или викторина из [PollDraft]. */
    suspend fun sendPoll(draft: PollDraft, replyToMessageId: Long? = null, options: SendOptions = SendOptions()) {
        if (!_state.value.reachedNewest) open(lastReadInboxId = null)
        val type = draft.correctOptionIds?.let { correct ->
            TdApi.InputPollTypeQuiz(correct.toIntArray(), RichText(draft.explanation).toFormattedText(), null)
        } ?: TdApi.InputPollTypeRegular(false)
        val content = TdApi.InputMessagePoll(
            RichText(draft.question).toFormattedText(),
            draft.options.map { TdApi.InputPollOption(RichText(it).toFormattedText(), null) }.toTypedArray(),
            null,
            null,
            draft.isAnonymous,
            draft.allowsMultipleAnswers,
            // Переголосовать можно в обычных опросах; ответ в викторине окончательный
            draft.correctOptionIds == null,
            false,
            emptyArray(),
            false,
            false,
            type,
            0,
            0,
            false,
        )
        val replyTo = replyToMessageId?.let { TdApi.InputMessageReplyToMessage(it, null, 0, "") }
        val pending = td.send(TdApi.SendMessage(chatId, null, replyTo, options.toTd(), null, content))
        mergeSent(listOf(pending))
    }

    /** Новое содержимое придёт апдейтами `UpdateMessageContent` / `UpdateMessageEdited`. */
    suspend fun editText(message: Message, text: RichText) = td.editMessageText(chatId, message, text)

    /** Только что отправленное — в ленту; отложенное в историю не попадает. */
    private fun mergeSent(sent: List<TdApi.Message>) {
        val now = sent.filter { it.schedulingState == null }.map { it.toModel() }
        if (now.isNotEmpty()) _state.update { it.merge(now) }
    }

    private fun replace(oldId: Long, message: Message) {
        _state.update { s -> s.copy(messages = s.messages.filterNot { it.id == oldId || it.id == message.id }).merge(listOf(message)) }
    }

    private inline fun edit(messageId: Long, crossinline change: Message.() -> Message) {
        _state.update { s ->
            if (s.messages.none { it.id == messageId }) s
            else s.copy(messages = s.messages.map { if (it.id == messageId) it.change() else it })
        }
    }

    private suspend fun fetch(fromMessageId: Long, offset: Int, limit: Int): List<Message> =
        runCatching { td.send(TdApi.GetChatHistory(chatId, fromMessageId, offset, limit, false)) }
            .onFailure { Log.e(TAG, "getChatHistory($chatId) failed", it) }
            .getOrNull()?.messages.orEmpty().map { it.toModel() }

    private fun State.merge(added: List<Message>): State {
        val byId = messages.associateBy { it.id } + added.associateBy { it.id }
        return copy(messages = byId.values.sortedByDescending { it.id })
    }

    private companion object {
        const val TAG = "ChatHistory"
        const val PAGE = 50
    }
}

/** Правка своего сообщения: текст — `editMessageText`, подпись к медиа — `editMessageCaption`. */
internal suspend fun TdClient.editMessageText(chatId: Long, message: Message, text: RichText) {
    val formatted = text.toFormattedText()
    when (message.content) {
        is MessageBody.Text -> send(
            TdApi.EditMessageText(chatId, message.id, null, TdApi.InputMessageText(formatted, null, false)),
        )
        else -> send(TdApi.EditMessageCaption(chatId, message.id, null, formatted, false))
    }
}
