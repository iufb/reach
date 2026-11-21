package io.github.iufb.reach.invites

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.navigation.InviteLinksRoute
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Ссылка-приглашение для экрана. */
data class InviteLink(
    val link: String,
    val name: String,
    /** unix-секунды; 0 — бессрочная. */
    val expirationDate: Int,
    /** 0 — без лимита. */
    val memberLimit: Int,
    val memberCount: Int,
    val pendingRequests: Int,
    val createsJoinRequest: Boolean,
    val isPrimary: Boolean,
    val isRevoked: Boolean,
) {
    val isExpired: Boolean get() = expirationDate != 0 && expirationDate < System.currentTimeMillis() / 1000
}

/** Параметры новой или изменяемой ссылки. */
data class LinkDraft(val name: String, val expiresInSeconds: Int, val memberLimit: Int, val createsJoinRequest: Boolean)

data class JoinRequest(val user: User, val date: Int, val bio: String)

data class InviteLinksState(
    val loaded: Boolean = false,
    val primary: InviteLink? = null,
    val links: List<InviteLink> = emptyList(),
    val revoked: List<InviteLink> = emptyList(),
)

/** Ссылки-приглашения группы или канала и заявки на вступление (PRODUCT.md §4.6). */
@HiltViewModel
class InviteLinksViewModel @Inject constructor(
    private val userErrors: UserErrors,
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    users: UsersRepository,
) : ViewModel() {
    private val chatId = savedStateHandle.toRoute<InviteLinksRoute>().chatId

    private val _state = MutableStateFlow(InviteLinksState())
    val state: StateFlow<InviteLinksState> = _state

    private val requestRows = MutableStateFlow<List<TdApi.ChatJoinRequest>>(emptyList())

    val requests: StateFlow<List<JoinRequest>> = combine(requestRows, users.users) { rows, known ->
        rows.mapNotNull { r -> known[r.userId]?.let { JoinRequest(it, r.date, r.bio) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    init {
        reload()
        viewModelScope.launch {
            td.updates.collect { update ->
                if (update is TdApi.UpdateChatPendingJoinRequests && update.chatId == chatId) loadRequests()
            }
        }
    }

    private fun reload() {
        viewModelScope.launch {
            runCatching {
                val me = (td.send(TdApi.GetOption("my_id")) as? TdApi.OptionValueInteger)?.value ?: 0L
                val active = td.send(TdApi.GetChatInviteLinks(chatId, me, false, 0, "", PAGE)).inviteLinks.map { it.toModel() }
                val revoked = td.send(TdApi.GetChatInviteLinks(chatId, me, true, 0, "", PAGE)).inviteLinks.map { it.toModel() }
                // У нового чата основной ссылки может ещё не быть — создаём
                val primary = active.firstOrNull { it.isPrimary } ?: td.send(TdApi.ReplacePrimaryChatInviteLink(chatId)).toModel()
                _state.value = InviteLinksState(true, primary, active.filterNot { it.isPrimary }, revoked)
            }.onFailure {
                Log.w(TAG, "load invite links failed", it)
                _state.value = _state.value.copy(loaded = true)
                _errors.emit(userErrors.message(it))
            }
            loadRequests()
        }
    }

    private suspend fun loadRequests() {
        runCatching { td.send(TdApi.GetChatJoinRequests(chatId, "", "", null, PAGE)) }
            .onSuccess { requestRows.value = it.requests.toList() }
            .onFailure { Log.w(TAG, "getChatJoinRequests failed", it) }
    }

    fun replacePrimary() = act { td.send(TdApi.ReplacePrimaryChatInviteLink(chatId)) }

    fun create(draft: LinkDraft) = act {
        td.send(TdApi.CreateChatInviteLink(chatId, draft.name.trim(), draft.expiration(), draft.memberLimitOrZero(), draft.createsJoinRequest))
    }

    fun edit(link: InviteLink, draft: LinkDraft) = act {
        td.send(
            TdApi.EditChatInviteLink(chatId, link.link, draft.name.trim(), draft.expiration(), draft.memberLimitOrZero(), draft.createsJoinRequest),
        )
    }

    fun revoke(link: InviteLink) = act { td.send(TdApi.RevokeChatInviteLink(chatId, link.link)) }

    fun deleteRevoked(link: InviteLink) = act { td.send(TdApi.DeleteRevokedChatInviteLink(chatId, link.link)) }

    fun process(request: JoinRequest, approve: Boolean) {
        requestRows.value = requestRows.value.filterNot { it.userId == request.user.id }
        viewModelScope.launch {
            runCatching { td.send(TdApi.ProcessChatJoinRequest(chatId, request.user.id, approve)) }
                .onFailure { _errors.emit(userErrors.message(it)) }
            loadRequests()
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "invite link action failed", it)
                _errors.emit(userErrors.message(it))
            }
            reload()
        }
    }

    // Заявки и лимит участников взаимоисключающие
    private fun LinkDraft.memberLimitOrZero() = if (createsJoinRequest) 0 else memberLimit

    private fun LinkDraft.expiration() = if (expiresInSeconds == 0) 0 else (System.currentTimeMillis() / 1000).toInt() + expiresInSeconds

    private fun TdApi.ChatInviteLink.toModel() = InviteLink(
        inviteLink, name, expirationDate, memberLimit, memberCount, pendingJoinRequestCount, createsJoinRequest, isPrimary, isRevoked,
    )

    private companion object {
        const val TAG = "InviteLinksViewModel"
        const val PAGE = 100
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
