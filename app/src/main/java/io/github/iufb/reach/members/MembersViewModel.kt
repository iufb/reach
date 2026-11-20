package io.github.iufb.reach.members

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.GroupsRepository
import io.github.iufb.reach.chats.MemberStatus
import io.github.iufb.reach.contacts.ContactsRepository
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.navigation.MembersRoute
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Роль участника в списке. */
sealed interface MemberRole {
    data object Owner : MemberRole
    /** [canBeEdited] — текущий пользователь может менять права этого админа. */
    data class Admin(val canBeEdited: Boolean, val rights: TdApi.ChatAdministratorRights) : MemberRole
    data object Member : MemberRole
    data class Restricted(val permissions: TdApi.ChatPermissions, val untilDate: Int) : MemberRole
    data object Banned : MemberRole
}

/** Участник: пока только пользователи (каналы-участники клиент не показывает). */
data class Member(val userId: Long, val role: MemberRole, val title: String)

enum class MembersFilter { All, Admins, Banned }

/** Что текущий пользователь может делать с участниками. */
data class MemberRights(
    val canAdd: Boolean = false,
    val canBan: Boolean = false,
    val canPromote: Boolean = false,
    val isOwner: Boolean = false,
)

/** Участники группы или канала (PRODUCT.md §4.6). */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class MembersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    chats: ChatsRepository,
    users: UsersRepository,
    groups: GroupsRepository,
    private val contacts: ContactsRepository,
) : ViewModel() {

    val chatId: Long = savedStateHandle.toRoute<MembersRoute>().chatId
    private val chat: Chat? = chats.chats.value[chatId]
    val group: GroupRef? = chat?.group
    val isChannel: Boolean = chat?.kind == ChatKind.Channel

    val filter = MutableStateFlow(MembersFilter.All)
    val query = MutableStateFlow("")

    private val members = MutableStateFlow<List<Member>?>(null)
    private var total = Int.MAX_VALUE
    private var loadJob: Job? = null

    /** Участники с данными пользователей; `null` — грузятся. */
    val list: StateFlow<List<Pair<Member, User>>?> = combine(members, users.users) { list, known ->
        list?.mapNotNull { m -> known[m.userId]?.let { m to it } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val rights: StateFlow<MemberRights> = combine(chats.chats, groups.statuses) { all, statuses ->
        val current = all[chatId] ?: return@combine MemberRights()
        when (val status = current.group?.let(statuses::get)) {
            is MemberStatus.Creator -> MemberRights(canAdd = true, canBan = true, canPromote = true, isOwner = true)
            is MemberStatus.Administrator -> MemberRights(
                canAdd = status.canInviteUsers,
                canBan = status.canRestrictMembers,
                canPromote = status.canPromoteMembers,
            )
            MemberStatus.Member -> MemberRights(canAdd = current.kind == ChatKind.Group && current.canInviteByDefault)
            else -> MemberRights()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), MemberRights())

    val contactList: StateFlow<List<User>> = contacts.contacts.map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private val _closed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Вышли из чата или удалили его. */
    val closed: SharedFlow<Unit> = _closed.asSharedFlow()

    init {
        viewModelScope.launch { runCatching { contacts.load() } }
        viewModelScope.launch { combine(filter, query.debounce(SEARCH_DEBOUNCE_MS)) { f, q -> f to q }.collect { reload() } }
        // Изменения состава группы (из этого экрана или извне): перечитываем
        viewModelScope.launch {
            td.updates.collect { update ->
                val ours = when (update) {
                    is TdApi.UpdateChatMember -> update.chatId == chatId
                    is TdApi.UpdateBasicGroupFullInfo -> (group as? GroupRef.Basic)?.id == update.basicGroupId
                    else -> false
                }
                if (ours) reload()
            }
        }
    }

    private fun reload() {
        total = Int.MAX_VALUE
        members.value = null
        loadMore(reset = true)
    }

    fun loadMore(reset: Boolean = false) {
        if (loadJob?.isActive == true && !reset) return
        loadJob?.cancel()
        val current = if (reset) emptyList() else members.value.orEmpty()
        if (current.size >= total) return
        loadJob = viewModelScope.launch {
            val page = runCatching { fetch(current.size) }.onFailure { Log.w(TAG, "load members failed", it) }.getOrNull()
            if (page == null) {
                members.value = current
                return@launch
            }
            total = page.totalCount
            members.value = (current + page.members.mapNotNull { it.toModel() }).distinctBy { it.userId }
        }
    }

    private suspend fun fetch(offset: Int): TdApi.ChatMembers {
        val q = query.value.trim()
        return when (group) {
            is GroupRef.Super -> {
                val tdFilter = when (filter.value) {
                    MembersFilter.All -> if (q.isEmpty()) TdApi.SupergroupMembersFilterRecent() else TdApi.SupergroupMembersFilterSearch(q)
                    MembersFilter.Admins -> TdApi.SupergroupMembersFilterAdministrators()
                    MembersFilter.Banned -> TdApi.SupergroupMembersFilterBanned(q)
                }
                td.send(TdApi.GetSupergroupMembers(group.id, tdFilter, offset, PAGE))
            }
            is GroupRef.Basic -> {
                // У обычной группы все участники приходят одним списком
                if (offset > 0) return TdApi.ChatMembers(total, emptyArray())
                val all = td.send(TdApi.GetBasicGroupFullInfo(group.id)).members.filter { m ->
                    val user = (m.memberId as? TdApi.MessageSenderUser) != null
                    user && when (filter.value) {
                        MembersFilter.All -> true
                        MembersFilter.Admins -> m.status is TdApi.ChatMemberStatusAdministrator || m.status is TdApi.ChatMemberStatusCreator
                        MembersFilter.Banned -> false
                    }
                }
                TdApi.ChatMembers(all.size, all.toTypedArray())
            }
            null -> TdApi.ChatMembers(0, emptyArray())
        }
    }

    fun add(userIds: Set<Long>) = act {
        val failed = td.send(TdApi.AddChatMembers(chatId, userIds.toLongArray())).failedToAddMembers
        if (failed.isNotEmpty()) _errors.emit(PRIVACY_MARKER)
    }

    /** В супергруппе и канале удалённый попадает в «Заблокированные», в обычной группе — просто исключается. */
    fun remove(member: Member) = act { td.send(TdApi.BanChatMember(chatId, TdApi.MessageSenderUser(member.userId), 0, false)) }

    fun unban(member: Member) = act {
        td.send(TdApi.SetChatMemberStatus(chatId, TdApi.MessageSenderUser(member.userId), TdApi.ChatMemberStatusLeft()))
    }

    fun leave() = act {
        td.send(TdApi.LeaveChat(chatId))
        _closed.emit(Unit)
    }

    fun deleteChat() = act {
        td.send(TdApi.DeleteChat(chatId))
        _closed.emit(Unit)
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure {
                Log.w(TAG, "member action failed", it)
                _errors.emit(it.message.orEmpty())
            }
            reload()
        }
    }

    private fun TdApi.ChatMember.toModel(): Member? {
        val userId = (memberId as? TdApi.MessageSenderUser)?.userId ?: return null
        val role = when (val s = status) {
            is TdApi.ChatMemberStatusCreator -> MemberRole.Owner
            is TdApi.ChatMemberStatusAdministrator -> MemberRole.Admin(s.canBeEdited, s.rights)
            is TdApi.ChatMemberStatusRestricted -> MemberRole.Restricted(s.permissions, s.restrictedUntilDate)
            is TdApi.ChatMemberStatusBanned -> MemberRole.Banned
            else -> MemberRole.Member
        }
        return Member(userId, role, tag.orEmpty())
    }

    companion object {
        const val PRIVACY_MARKER = "privacy"
        private const val TAG = "MembersViewModel"
        private const val PAGE = 100
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SEARCH_DEBOUNCE_MS = 300L
    }
}
