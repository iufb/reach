package io.github.iufb.reach.newchat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.UsernameStatus
import io.github.iufb.reach.chats.checkUsername
import io.github.iufb.reach.contacts.ContactsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.files.MediaImporter
import io.github.iufb.reach.navigation.NewChatRoute
import io.github.iufb.reach.users.User
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Шаги мастера. Группа: участники → название. Канал: название → тип → подписчики. */
enum class NewChatStep { Members, Info, Visibility, Subscribers }

data class NewChatState(
    val step: NewChatStep,
    val selected: Set<Long> = emptySet(),
    val title: String = "",
    val description: String = "",
    val photo: Uri? = null,
    /** Идёт запрос: кнопки неактивны. */
    val busy: Boolean = false,
    /** Канал уже создан (шаги после названия). */
    val createdChatId: Long = 0,
    val supergroupId: Long = 0,
    val isPublic: Boolean = false,
    val username: String = "",
    val inviteLink: String = "",
)

/**
 * Создание группы или канала (PRODUCT.md §4.6). Группы создаются супергруппами
 * (`createNewSupergroupChat`): у них сразу есть топики, права админов, ссылки и история для новых
 * участников, а преобразование из обычной группы не нужно.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class NewChatViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    private val contacts: ContactsRepository,
    private val importer: MediaImporter,
) : ViewModel() {

    val isChannel: Boolean = savedStateHandle.toRoute<NewChatRoute>().channel

    private val _state = MutableStateFlow(NewChatState(if (isChannel) NewChatStep.Info else NewChatStep.Members))
    val state: StateFlow<NewChatState> = _state.asStateFlow()

    val contactList: StateFlow<List<User>?> = contacts.contacts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val usernameStatus: StateFlow<UsernameStatus?> = _state
        .mapLatest { it.username to it.createdChatId }
        .distinctUntilChanged()
        .debounce(USERNAME_DEBOUNCE_MS)
        .mapLatest { (name, chatId) ->
            if (name.isEmpty()) null else runCatching { td.checkUsername(chatId, name) }.getOrDefault(UsernameStatus.Invalid)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _done = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** Всё готово: открыть созданный чат. */
    val done: SharedFlow<Long> = _done.asSharedFlow()

    init {
        viewModelScope.launch { runCatching { contacts.load() } }
    }

    fun toggle(user: User) = _state.update {
        it.copy(selected = if (user.id in it.selected) it.selected - user.id else it.selected + user.id)
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(TITLE_MAX)) }
    fun setDescription(value: String) = _state.update { it.copy(description = value.take(DESCRIPTION_MAX)) }
    fun setPhoto(uri: Uri?) = _state.update { it.copy(photo = uri) }
    fun setPublic(value: Boolean) = _state.update { it.copy(isPublic = value) }
    fun setUsername(value: String) = _state.update { it.copy(username = value.filter { c -> c.isLetterOrDigit() || c == '_' }) }

    fun next() {
        val s = _state.value
        when (s.step) {
            NewChatStep.Members -> _state.update { it.copy(step = NewChatStep.Info) }
            NewChatStep.Info -> create()
            NewChatStep.Visibility -> applyVisibility()
            NewChatStep.Subscribers -> addSubscribers()
        }
    }

    /** Назад по шагам; `false` — шагов назад нет, закрыть экран. */
    fun back(): Boolean {
        val s = _state.value
        if (s.busy) return true
        return when {
            s.step == NewChatStep.Info && !isChannel -> { _state.update { it.copy(step = NewChatStep.Members) }; true }
            // Канал уже создан: дальше — только вперёд, «назад» закрывает и открывает канал
            s.createdChatId != 0L -> { _done.tryEmit(s.createdChatId); true }
            else -> false
        }
    }

    private fun create() = work {
        val s = _state.value
        val chat = td.send(TdApi.CreateNewSupergroupChat(s.title.trim(), false, isChannel, s.description.trim(), null, 0, false))
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId ?: 0L
        s.photo?.let { uri ->
            runCatching {
                val path = importer.importSquarePhoto(uri)
                td.send(TdApi.SetChatPhoto(chat.id, TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(path))))
            }.onFailure { Log.w(TAG, "set chat photo failed", it) }
        }
        if (isChannel) {
            _state.update { it.copy(createdChatId = chat.id, supergroupId = supergroupId, step = NewChatStep.Visibility) }
            loadInviteLink(chat.id)
        } else {
            addMembers(chat.id, s.selected)
            _done.emit(chat.id)
        }
    }

    private suspend fun loadInviteLink(chatId: Long) {
        runCatching { td.send(TdApi.ReplacePrimaryChatInviteLink(chatId)).inviteLink }
            .onSuccess { link -> _state.update { it.copy(inviteLink = link) } }
            .onFailure { Log.w(TAG, "invite link failed", it) }
    }

    private fun applyVisibility() = work {
        val s = _state.value
        if (s.isPublic) td.send(TdApi.SetSupergroupUsername(s.supergroupId, s.username))
        _state.update { it.copy(step = NewChatStep.Subscribers) }
    }

    private fun addSubscribers() = work {
        val s = _state.value
        addMembers(s.createdChatId, s.selected)
        _done.emit(s.createdChatId)
    }

    /** Кого не удалось добавить (приватность), сообщаем, но чат уже создан — не теряем его. */
    private suspend fun addMembers(chatId: Long, ids: Set<Long>) {
        if (ids.isEmpty()) return
        runCatching { td.send(TdApi.AddChatMembers(chatId, ids.toLongArray())) }
            .onSuccess { failed ->
                if (failed.failedToAddMembers.isNotEmpty()) _errors.emit(PRIVACY_MARKER + failed.failedToAddMembers.size)
            }
            .onFailure { _errors.emit(userErrors.message(it)) }
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "new chat step failed", it)
                _errors.emit(userErrors.message(it))
            }
            _state.update { it.copy(busy = false) }
        }
    }

    companion object {
        /** Ошибка «N человек не добавлены из-за приватности»: экран подставляет свою строку. */
        const val PRIVACY_MARKER = "privacy:"
        private const val TAG = "NewChatViewModel"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val USERNAME_DEBOUNCE_MS = 400L
        private const val TITLE_MAX = 128
        private const val DESCRIPTION_MAX = 255
    }
}
