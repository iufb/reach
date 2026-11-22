package io.github.iufb.reach.editchat

import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.MemberStatus
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.files.MediaImporter
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.navigation.EditChatRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Что правится на экране «Изменить» и с чем сравнивать при сохранении. */
data class EditChatState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val title: String = "",
    val description: String = "",
    val isForum: Boolean = false,
    /** Новое фото, ещё не загруженное; `null` — фото не меняли. */
    val newPhoto: Uri? = null,
    /** Текущее фото чата для аватара. */
    val photo: TdFile? = null,
    val accentColorId: Int = 0,
    val isChannel: Boolean = false,
    /** Супергруппа (не канал), где можно включить темы. */
    val canToggleForum: Boolean = false,
    val original: Original = Original(),
) {
    /** Значения при открытии: сохраняем только то, что изменилось. */
    data class Original(val title: String = "", val description: String = "", val isForum: Boolean = false)

    val changed: Boolean
        get() = title.trim() != original.title || description.trim() != original.description ||
            isForum != original.isForum || newPhoto != null
}

/**
 * «Изменить» для группы или канала (#222): название, описание, фото и темы у супергруппы.
 * Сохраняет только изменённое: `setChatTitle`, `setChatDescription`, `setChatPhoto`,
 * `toggleSupergroupIsForum`. Изменения приходят апдейтами и сразу видны в профиле, шапке и списке.
 */
@HiltViewModel
class EditChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    private val chats: ChatsRepository,
    private val groups: GroupsRepository,
    private val importer: MediaImporter,
    private val userErrors: UserErrors,
) : ViewModel() {

    val chatId: Long = savedStateHandle.toRoute<EditChatRoute>().chatId

    private val _state = MutableStateFlow(EditChatState())
    val state: StateFlow<EditChatState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    /** `null` — сохранено, закрыть экран; строка — ошибка. */
    val events: SharedFlow<String?> = _events.asSharedFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        // Экран открывается из профиля чата — чат уже в репозитории
        val chat = chats.chats.value[chatId]
        val group = chat?.group
        runCatching {
            val (description, isForum) = when (group) {
                is GroupRef.Super -> {
                    val info = td.send(TdApi.GetSupergroupFullInfo(group.id))
                    val supergroup = td.send(TdApi.GetSupergroup(group.id))
                    info.description to supergroup.isForum
                }
                is GroupRef.Basic -> td.send(TdApi.GetBasicGroupFullInfo(group.id)).description to false
                null -> "" to false
            }
            val status = group?.let { groups.statuses.value[it] }
            val owner = status is MemberStatus.Creator
            _state.value = EditChatState(
                loaded = true,
                title = chat?.title.orEmpty(),
                description = description,
                isForum = isForum,
                photo = chat?.photo,
                accentColorId = chat?.accentColorId ?: 0,
                isChannel = chat?.kind == ChatKind.Channel,
                // Темы включает владелец или админ с правом менять профиль; только у супергрупп
                canToggleForum = group is GroupRef.Super && chat.kind != ChatKind.Channel &&
                    (owner || (status as? MemberStatus.Administrator)?.canChangeInfo == true),
                original = EditChatState.Original(chat?.title.orEmpty(), description, isForum),
            )
        }.onFailure {
            Log.w(TAG, "load failed", it)
            _state.update { s -> s.copy(loaded = true) }
            _events.emit(userErrors.message(it))
        }
    }

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(TITLE_MAX)) }

    fun setDescription(value: String) = _state.update { it.copy(description = value.take(DESCRIPTION_MAX)) }

    fun setForum(value: Boolean) = _state.update { it.copy(isForum = value) }

    fun setPhoto(uri: Uri?) = _state.update { it.copy(newPhoto = uri) }

    fun save() {
        val s = _state.value
        if (s.busy || s.title.isBlank()) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                val title = s.title.trim()
                val description = s.description.trim()
                if (title != s.original.title) td.send(TdApi.SetChatTitle(chatId, title))
                if (description != s.original.description) td.send(TdApi.SetChatDescription(chatId, description))
                s.newPhoto?.let { uri ->
                    val path = importer.importSquarePhoto(uri)
                    td.send(TdApi.SetChatPhoto(chatId, TdApi.InputChatPhotoStatic(TdApi.InputFileLocal(path))))
                }
                val group = chats.chats.value[chatId]?.group
                if (s.isForum != s.original.isForum && group is GroupRef.Super) {
                    td.send(TdApi.ToggleSupergroupIsForum(group.id, s.isForum, false, false))
                }
            }.onSuccess {
                _events.emit(null)
            }.onFailure {
                _state.update { st -> st.copy(busy = false) }
                _events.emit(userErrors.message(it))
            }
        }
    }

    private companion object {
        const val TAG = "EditChat"
        const val TITLE_MAX = 128
        const val DESCRIPTION_MAX = 255
    }
}
