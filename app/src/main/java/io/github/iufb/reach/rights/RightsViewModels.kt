package io.github.iufb.reach.rights

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.AdminRightsRoute
import io.github.iufb.reach.navigation.PermissionsRoute
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Экран флагов: значения по номеру флага в своём списке, что можно менять, загрузка. */
data class FlagsState(
    val loaded: Boolean = false,
    val isChannel: Boolean = false,
    val isForum: Boolean = false,
    /** Номера флагов (в [ADMIN_FLAGS] / [PERMISSION_FLAGS]), которые показываются. */
    val shown: List<Int> = emptyList(),
    val values: Map<Int, Boolean> = emptyMap(),
    /** Флаги, которые текущий пользователь может выдать (у не-владельца — только свои). */
    val editable: Set<Int> = emptySet(),
    /** Уже админ / уже ограничен: есть кнопка «снять». */
    val existing: Boolean = false,
    val title: String = "",
    /** Ограничение до (unix-секунды); 0 — навсегда. */
    val untilDate: Int = 0,
    val busy: Boolean = false,
)

private suspend fun TdClient.myId(): Long =
    (send(TdApi.GetOption("my_id")) as? TdApi.OptionValueInteger)?.value ?: 0L

/** Права админа одного участника (`setChatMemberStatus(Administrator)`, титул — `setChatMemberTag`). */
@HiltViewModel
class AdminRightsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    users: UsersRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<AdminRightsRoute>()

    val user: StateFlow<User?> = users.users.map { it[route.userId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, users.users.value[route.userId])

    private val _state = MutableStateFlow(FlagsState())
    val state: StateFlow<FlagsState> = _state.asStateFlow()
    private var originalTitle = ""

    private val _events = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    /** `null` — сохранено, закрыть; строка — ошибка. */
    val events: SharedFlow<String?> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val chat = td.send(TdApi.GetChat(route.chatId))
                val supergroup = (chat.type as? TdApi.ChatTypeSupergroup)?.let { td.send(TdApi.GetSupergroup(it.supergroupId)) }
                val isChannel = supergroup?.isChannel == true
                val isForum = supergroup?.isForum == true
                val mine = td.send(TdApi.GetChatMember(route.chatId, TdApi.MessageSenderUser(td.myId()))).status
                val target = td.send(TdApi.GetChatMember(route.chatId, TdApi.MessageSenderUser(route.userId)))
                val shown = ADMIN_FLAGS.indices.filter { i ->
                    val f = ADMIN_FLAGS[i]
                    (if (isChannel) f.inChannel else f.inGroup) && (!f.forumOnly || isForum)
                }
                val current = (target.status as? TdApi.ChatMemberStatusAdministrator)?.rights
                val values = shown.associateWith { i -> current?.let(ADMIN_FLAGS[i].get) ?: ADMIN_FLAGS[i].default }
                val editable = when (mine) {
                    is TdApi.ChatMemberStatusCreator -> shown.toSet()
                    is TdApi.ChatMemberStatusAdministrator -> shown.filter { ADMIN_FLAGS[it].get(mine.rights) }.toSet()
                    else -> emptySet()
                }
                originalTitle = target.tag.orEmpty().takeIf { current != null }.orEmpty()
                _state.value = FlagsState(
                    loaded = true, isChannel = isChannel, isForum = isForum, shown = shown,
                    // Новому админу не выдаём то, чего нет у самого
                    values = values.mapValues { (i, v) -> v && (current != null || i in editable) },
                    editable = editable, existing = current != null, title = originalTitle,
                )
            }.onFailure {
                Log.w(TAG, "load admin rights failed", it)
                _events.emit(it.message.orEmpty())
            }
        }
    }

    fun toggle(index: Int, value: Boolean) = _state.update { it.copy(values = it.values + (index to value)) }

    fun setTitle(value: String) = _state.update { it.copy(title = value.take(TITLE_MAX)) }

    fun save() = work {
        val s = _state.value
        val rights = emptyAdminRights()
        s.values.forEach { (i, v) -> ADMIN_FLAGS[i].set(rights, v) }
        val member = TdApi.MessageSenderUser(route.userId)
        td.send(TdApi.SetChatMemberStatus(route.chatId, member, TdApi.ChatMemberStatusAdministrator(true, rights)))
        if (!s.isChannel && s.title.trim() != originalTitle) td.send(TdApi.SetChatMemberTag(route.chatId, route.userId, s.title.trim()))
    }

    fun dismiss() = work {
        td.send(TdApi.SetChatMemberStatus(route.chatId, TdApi.MessageSenderUser(route.userId), TdApi.ChatMemberStatusMember(0)))
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _events.emit(null) }
                .onFailure { _events.emit(it.message.orEmpty()) }
            _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        const val TAG = "AdminRightsViewModel"
        const val TITLE_MAX = 16
    }
}

/**
 * Разрешения: [PermissionsRoute.userId] = 0 — общие для всех участников группы (`setChatPermissions`),
 * иначе ограничение одного участника (`ChatMemberStatusRestricted` со сроком).
 */
@HiltViewModel
class PermissionsViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    users: UsersRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PermissionsRoute>()
    val isDefault: Boolean = route.userId == 0L

    val user: StateFlow<User?> = users.users.map { it[route.userId] }
        .stateIn(viewModelScope, SharingStarted.Eagerly, users.users.value[route.userId])

    private val _state = MutableStateFlow(FlagsState())
    val state: StateFlow<FlagsState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<String?>(extraBufferCapacity = 1)
    val events: SharedFlow<String?> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val chat = td.send(TdApi.GetChat(route.chatId))
                val isForum = (chat.type as? TdApi.ChatTypeSupergroup)?.let { td.send(TdApi.GetSupergroup(it.supergroupId)).isForum } == true
                val shown = PERMISSION_FLAGS.indices.filter { !PERMISSION_FLAGS[it].forumOnly || isForum }
                val restricted = if (isDefault) {
                    null
                } else {
                    td.send(TdApi.GetChatMember(route.chatId, TdApi.MessageSenderUser(route.userId))).status as? TdApi.ChatMemberStatusRestricted
                }
                val source = restricted?.permissions ?: chat.permissions
                _state.value = FlagsState(
                    loaded = true, isForum = isForum, shown = shown,
                    values = shown.associateWith { PERMISSION_FLAGS[it].get(source) },
                    // Участнику нельзя дать больше, чем разрешено всем
                    editable = if (isDefault) shown.toSet() else shown.filter { PERMISSION_FLAGS[it].get(chat.permissions) }.toSet(),
                    existing = restricted != null,
                    untilDate = restricted?.restrictedUntilDate ?: 0,
                )
            }.onFailure {
                Log.w(TAG, "load permissions failed", it)
                _events.emit(it.message.orEmpty())
            }
        }
    }

    fun toggle(index: Int, value: Boolean) = _state.update { it.copy(values = it.values + (index to value)) }

    /** Срок ограничения в секундах от сейчас; 0 — навсегда. */
    fun setDuration(seconds: Int) = _state.update {
        it.copy(untilDate = if (seconds == 0) 0 else (System.currentTimeMillis() / 1000).toInt() + seconds)
    }

    fun save() = work {
        val permissions = emptyPermissions()
        _state.value.values.forEach { (i, v) -> PERMISSION_FLAGS[i].set(permissions, v) }
        if (isDefault) {
            td.send(TdApi.SetChatPermissions(route.chatId, permissions))
        } else {
            val status = TdApi.ChatMemberStatusRestricted(true, _state.value.untilDate, permissions)
            td.send(TdApi.SetChatMemberStatus(route.chatId, TdApi.MessageSenderUser(route.userId), status))
        }
    }

    /** Снять ограничения с участника. */
    fun lift() = work {
        td.send(TdApi.SetChatMemberStatus(route.chatId, TdApi.MessageSenderUser(route.userId), TdApi.ChatMemberStatusMember(0)))
    }

    private fun work(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _events.emit(null) }
                .onFailure { _events.emit(it.message.orEmpty()) }
            _state.update { it.copy(busy = false) }
        }
    }

    private companion object {
        const val TAG = "PermissionsViewModel"
    }
}
