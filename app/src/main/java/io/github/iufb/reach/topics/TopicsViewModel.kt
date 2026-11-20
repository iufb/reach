package io.github.iufb.reach.topics

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.TopicsRoute
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Топик форума в списке. */
data class Topic(
    val id: Int,
    val name: String,
    /** Цвет значка, RGB; кастомные эмодзи-значки пока рисуются кругом этого цвета (v0.6). */
    val iconColor: Int,
    val isGeneral: Boolean,
    val isClosed: Boolean,
    val isPinned: Boolean,
    /** Свой топик: можно править и закрывать без прав админа. */
    val isOutgoing: Boolean,
    val unreadCount: Int,
    val lastMessage: MessageBody?,
    val lastMessageDate: Int,
    val order: Long,
)

/** Что можно делать с топиками в этом форуме. */
data class TopicRights(val canCreate: Boolean = false, val canManage: Boolean = false)

/** Список топиков форума (PRODUCT.md §4.6). */
@OptIn(FlowPreview::class)
@HiltViewModel
class TopicsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    chatsRepository: ChatsRepository,
) : ViewModel() {

    val chatId: Long = savedStateHandle.toRoute<TopicsRoute>().chatId

    val chat: StateFlow<Chat?> = chatsRepository.chats.map { it[chatId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, chatsRepository.chats.value[chatId])

    private val _topics = MutableStateFlow<List<Topic>?>(null)
    /** По `order` (закреплённые выше); `null` — первая страница ещё грузится. */
    val topics: StateFlow<List<Topic>?> = _topics.asStateFlow()

    private val _rights = MutableStateFlow(TopicRights())
    val rights: StateFlow<TopicRights> = _rights.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _openTopic = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    /** Только что созданный топик — открыть. */
    val openTopic: SharedFlow<Int> = _openTopic.asSharedFlow()

    private var next: TdApi.ForumTopics? = null
    private var reachedEnd = false
    private val loadLock = Mutex()
    private val refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { followUpdates() }
        // Новые сообщения двигают топики и меняют последнее сообщение: перечитываем загруженное, не чаще раза в полсекунды
        viewModelScope.launch { refresh.debounce(REFRESH_DEBOUNCE_MS).collect { reload() } }
        viewModelScope.launch { loadMore() }
        viewModelScope.launch { loadRights() }
    }

    fun loadMore() {
        viewModelScope.launch {
            loadLock.withLock {
                if (reachedEnd) return@withLock
                val offset = next
                val page = runCatching {
                    td.send(
                        TdApi.GetForumTopics(
                            chatId, "", offset?.nextOffsetDate ?: 0, offset?.nextOffsetMessageId ?: 0,
                            offset?.nextOffsetForumTopicId ?: 0, PAGE,
                        ),
                    )
                }.onFailure { Log.w(TAG, "getForumTopics failed", it) }.getOrNull()
                if (page == null) {
                    _topics.update { it ?: emptyList() }
                    return@withLock
                }
                next = page
                reachedEnd = page.topics.isEmpty() || page.nextOffsetForumTopicId == 0
                _topics.update { current -> merge(current.orEmpty(), page.topics.map { it.toModel() }) }
            }
        }
    }

    private suspend fun reload() {
        loadLock.withLock {
            val count = (_topics.value?.size ?: 0).coerceAtLeast(PAGE)
            val page = runCatching { td.send(TdApi.GetForumTopics(chatId, "", 0, 0, 0, count)) }
                .onFailure { Log.w(TAG, "reload topics failed", it) }.getOrNull() ?: return
            next = page
            reachedEnd = page.topics.isEmpty() || page.nextOffsetForumTopicId == 0
            _topics.value = merge(emptyList(), page.topics.map { it.toModel() })
        }
    }

    private suspend fun followUpdates() {
        td.updates.collect { update ->
            when (update) {
                is TdApi.UpdateForumTopicInfo -> if (update.info.chatId == chatId) {
                    val info = update.info
                    edit(info.forumTopicId) {
                        copy(name = info.name, iconColor = info.icon.color, isClosed = info.isClosed)
                    }
                }
                is TdApi.UpdateForumTopic -> if (update.chatId == chatId) {
                    edit(update.forumTopicId) { copy(isPinned = update.isPinned) }
                    refresh.tryEmit(Unit)
                }
                is TdApi.UpdateNewMessage -> if (update.message.chatId == chatId) refresh.tryEmit(Unit)
                is TdApi.UpdateChatReadInbox -> if (update.chatId == chatId) refresh.tryEmit(Unit)
            }
        }
    }

    private inline fun edit(topicId: Int, crossinline change: Topic.() -> Topic) {
        _topics.update { list -> list?.map { if (it.id == topicId) it.change() else it } }
    }

    /** Права: владелец и админы с `can_manage_topics` управляют, участники создают при общем разрешении. */
    private suspend fun loadRights() {
        runCatching {
            val chat = td.send(TdApi.GetChat(chatId))
            val group = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: return
            val status = td.send(TdApi.GetSupergroup(group)).status
            val manage = when (status) {
                is TdApi.ChatMemberStatusCreator -> true
                is TdApi.ChatMemberStatusAdministrator -> status.rights.canManageTopics
                else -> false
            }
            val create = manage || when (status) {
                is TdApi.ChatMemberStatusMember -> chat.permissions.canCreateTopics
                is TdApi.ChatMemberStatusRestricted -> status.isMember && status.permissions.canCreateTopics
                else -> false
            }
            _rights.value = TopicRights(canCreate = create, canManage = manage)
        }.onFailure { Log.w(TAG, "load topic rights failed", it) }
    }

    fun create(name: String) {
        viewModelScope.launch {
            runCatching {
                td.send(TdApi.CreateForumTopic(chatId, name.trim(), false, TdApi.ForumTopicIcon(TOPIC_COLORS.random(), 0)))
            }.onSuccess {
                reload()
                _openTopic.emit(it.forumTopicId)
            }.onFailure { _errors.emit(it.message.orEmpty()) }
        }
    }

    fun rename(topic: Topic, name: String) = act { td.send(TdApi.EditForumTopic(chatId, topic.id, name.trim(), false, 0)) }

    fun setClosed(topic: Topic, closed: Boolean) = act { td.send(TdApi.ToggleForumTopicIsClosed(chatId, topic.id, closed)) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "topic action failed", it)
                _errors.emit(it.message.orEmpty())
            }
        }
    }

    private fun merge(current: List<Topic>, added: List<Topic>): List<Topic> =
        (current.associateBy { it.id } + added.associateBy { it.id }).values.sortedByDescending { it.order }

    private fun TdApi.ForumTopic.toModel() = Topic(
        id = info.forumTopicId,
        name = info.name,
        iconColor = info.icon.color,
        isGeneral = info.isGeneral,
        isClosed = info.isClosed,
        isPinned = isPinned,
        isOutgoing = info.isOutgoing,
        unreadCount = unreadCount,
        lastMessage = lastMessage?.content?.toBody(),
        lastMessageDate = lastMessage?.date ?: info.creationDate,
        order = order,
    )

    private companion object {
        const val TAG = "TopicsViewModel"
        const val PAGE = 50
        const val REFRESH_DEBOUNCE_MS = 500L
        /** Цвета значков, которые принимает Telegram. */
        val TOPIC_COLORS = listOf(0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)
    }
}
