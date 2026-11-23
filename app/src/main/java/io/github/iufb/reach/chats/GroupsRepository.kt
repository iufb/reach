package io.github.iufb.reach.chats

import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Ссылка из чата на группу TDLib: у обычных групп и супергрупп (включая каналы) разные id. */
sealed interface GroupRef {
    data class Basic(val id: Long) : GroupRef
    data class Super(val id: Long) : GroupRef
}

/** Статус текущего пользователя в группе или канале — только то, что нужно для права писать. */
sealed interface MemberStatus {
    data class Creator(val isMember: Boolean) : MemberStatus
    data class Administrator(
        val canPostMessages: Boolean,
        val canChangeInfo: Boolean = false,
        val canInviteUsers: Boolean = false,
        val canRestrictMembers: Boolean = false,
        val canPromoteMembers: Boolean = false,
    ) : MemberStatus
    data object Member : MemberStatus
    /** Ограниченный участник: [canSendText] — текст, [canSendOther] — стикеры, GIF и прочее (`canSendOtherMessages`). */
    data class Restricted(val isMember: Boolean, val canSendText: Boolean, val canSendOther: Boolean = true) : MemberStatus
    data object Left : MemberStatus
}

/** Публичное о группе или канале: username (пусто — частный) и число участников (0 — неизвестно). */
data class GroupInfo(val username: String = "", val memberCount: Int = 0)

/** Статусы в группах и их публичные данные из `UpdateBasicGroup` / `UpdateSupergroup`. */
@Singleton
class GroupsRepository @Inject constructor(td: TdClient, @ApplicationScope scope: CoroutineScope) {

    private val _statuses = MutableStateFlow<Map<GroupRef, MemberStatus>>(emptyMap())
    val statuses: StateFlow<Map<GroupRef, MemberStatus>> = _statuses.asStateFlow()

    private val _info = MutableStateFlow<Map<GroupRef, GroupInfo>>(emptyMap())
    /** Username и число участников; подписи в поиске. */
    val info: StateFlow<Map<GroupRef, GroupInfo>> = _info.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateBasicGroup -> {
                        val ref = GroupRef.Basic(update.basicGroup.id)
                        _statuses.update { it + (ref to update.basicGroup.status.toModel()) }
                        _info.update { it + (ref to GroupInfo(memberCount = update.basicGroup.memberCount)) }
                    }
                    is TdApi.UpdateSupergroup -> {
                        val group = update.supergroup
                        val ref = GroupRef.Super(group.id)
                        _statuses.update { it + (ref to group.status.toModel()) }
                        val username = group.usernames?.activeUsernames?.firstOrNull().orEmpty()
                        _info.update { it + (ref to GroupInfo(username, group.memberCount)) }
                    }
                    is TdApi.UpdateAuthorizationState ->
                        if (update.authorizationState is TdApi.AuthorizationStateClosed) {
                            _statuses.value = emptyMap()
                            _info.value = emptyMap()
                        }
                }
            }
        }
    }
}

private fun TdApi.ChatMemberStatus.toModel(): MemberStatus = when (this) {
    is TdApi.ChatMemberStatusCreator -> MemberStatus.Creator(isMember)
    is TdApi.ChatMemberStatusAdministrator -> MemberStatus.Administrator(
        rights.canPostMessages,
        rights.canChangeInfo,
        rights.canInviteUsers,
        rights.canRestrictMembers,
        rights.canPromoteMembers,
    )
    is TdApi.ChatMemberStatusMember -> MemberStatus.Member
    is TdApi.ChatMemberStatusRestricted -> MemberStatus.Restricted(isMember, permissions.canSendBasicMessages, permissions.canSendOtherMessages)
    else -> MemberStatus.Left
}

/**
 * Можно ли писать в чат: личка — всегда; канал — владельцу и админам с правом публикации;
 * группа — по своему статусу и общим разрешениям чата.
 * Пока статус группы неизвестен, считаем, что нельзя: лучше спрятать поле, чем получить ошибку.
 */
fun Chat.canWrite(status: MemberStatus?): Boolean {
    if (group == null) return true
    return when (status) {
        null, MemberStatus.Left -> false
        is MemberStatus.Creator -> status.isMember
        is MemberStatus.Administrator -> kind != ChatKind.Channel || status.canPostMessages
        MemberStatus.Member -> kind != ChatKind.Channel && canSendBasicMessages
        is MemberStatus.Restricted -> kind != ChatKind.Channel && status.isMember && status.canSendText
    }
}

/** Можно ли менять настройки чата (название, описание, автоудаление): личка — всегда, группа — по правам. */
fun Chat.canChangeInfo(status: MemberStatus?): Boolean {
    if (group == null) return !isSavedMessages
    return when (status) {
        is MemberStatus.Creator -> status.isMember
        is MemberStatus.Administrator -> status.canChangeInfo
        MemberStatus.Member -> kind == ChatKind.Group && canChangeInfoByDefault
        else -> false
    }
}
