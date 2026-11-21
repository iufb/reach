package io.github.iufb.reach.chat

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.chats.trimmed
import io.github.iufb.reach.chats.withEditedText
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.navigation.ScheduledRoute
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Отложенное сообщение и когда оно уйдёт. */
data class ScheduledMessage(val message: Message, val schedule: Schedule)

/** Экран отложенных сообщений чата (PRODUCT.md §4.3). */
@HiltViewModel
class ScheduledViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    chatsRepository: ChatsRepository,
) : ViewModel() {

    private val chatId = savedStateHandle.toRoute<ScheduledRoute>().chatId

    val chat: StateFlow<Chat?> = chatsRepository.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chatsRepository.chats.value[chatId])

    private val _messages = MutableStateFlow<List<ScheduledMessage>?>(null)

    /** Ближайшие первыми; `null` — ещё грузятся. */
    val messages: StateFlow<List<ScheduledMessage>?> = _messages.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { followUpdates() }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        runCatching { td.send(TdApi.GetChatScheduledMessages(chatId)) }
            .onSuccess { found -> _messages.value = found.messages.mapNotNull { it.toScheduled() }.sorted() }
            .onFailure {
                Log.e(TAG, "getChatScheduledMessages failed", it)
                _messages.value = _messages.value ?: emptyList()
            }
    }

    /**
     * Новые отложенные, правки и удаление. Отправленное по времени TDLib удаляет из отложенных
     * (`UpdateDeleteMessages`) и присылает новым сообщением в историю.
     */
    private suspend fun followUpdates() {
        td.updates.collect { update ->
            when (update) {
                is TdApi.UpdateNewMessage -> if (update.message.chatId == chatId) upsert(update.message)
                is TdApi.UpdateMessageSendSucceeded -> if (update.message.chatId == chatId) {
                    remove(setOf(update.oldMessageId))
                    upsert(update.message)
                }
                is TdApi.UpdateMessageContent -> if (update.chatId == chatId) {
                    val body = update.newContent.toBody()
                    _messages.update { list ->
                        list?.map { if (it.message.id == update.messageId) it.copy(message = it.message.copy(content = body)) else it }
                    }
                }
                is TdApi.UpdateDeleteMessages -> if (update.chatId == chatId && update.isPermanent) {
                    remove(update.messageIds.toSet())
                }
            }
        }
    }

    private fun upsert(message: TdApi.Message) {
        val scheduled = message.toScheduled() ?: return
        _messages.update { list -> ((list ?: emptyList()).filterNot { it.message.id == message.id } + scheduled).sorted() }
    }

    private fun remove(ids: Set<Long>) {
        _messages.update { list -> list?.filterNot { it.message.id in ids } }
    }

    fun sendNow(item: ScheduledMessage) = changeSchedule(item, null)

    fun reschedule(item: ScheduledMessage, date: Int) = changeSchedule(item, Schedule.At(date))

    /** Перенос меняет id отложенного: список перечитываем целиком. */
    private fun changeSchedule(item: ScheduledMessage, schedule: Schedule?) {
        viewModelScope.launch {
            runCatching { td.send(TdApi.EditMessageSchedulingState(chatId, item.message.id, schedule?.toTd())) }
                .onSuccess { load() }
                .onFailure {
                    Log.w(TAG, "editMessageSchedulingState failed", it)
                    _error.value = userErrors.message(it)
                }
        }
    }

    fun delete(item: ScheduledMessage) {
        remove(setOf(item.message.id))
        viewModelScope.launch {
            runCatching { td.send(TdApi.DeleteMessages(chatId, longArrayOf(item.message.id), true)) }
                .onFailure {
                    Log.w(TAG, "delete scheduled failed", it)
                    _error.value = userErrors.message(it)
                    load()
                }
        }
    }

    /** Правка текста или подписи; оформление вне изменённого куска сохраняется. */
    fun editText(item: ScheduledMessage, original: RichText, newText: String) {
        val edited = original.withEditedText(newText).trimmed()
        if (edited == original) return
        viewModelScope.launch {
            runCatching { td.editMessageText(chatId, item.message, edited) }
                .onFailure {
                    Log.w(TAG, "edit scheduled failed", it)
                    _error.value = userErrors.message(it)
                }
        }
    }

    fun dismissError() {
        _error.value = null
    }

    private fun TdApi.Message.toScheduled(): ScheduledMessage? =
        schedulingState?.toModel()?.let { ScheduledMessage(toModel(), it) }

    /** «Когда будет в сети» — в конце, остальные по времени. */
    private fun List<ScheduledMessage>.sorted() =
        sortedWith(compareBy({ (it.schedule as? Schedule.At)?.date ?: Int.MAX_VALUE }, { it.message.id }))

    private companion object {
        const val TAG = "ScheduledViewModel"
    }
}
