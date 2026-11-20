package io.github.iufb.reach.profile

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.chats.ChatKind
import io.github.iufb.reach.chats.ChatsRepository
import io.github.iufb.reach.chats.GroupRef
import io.github.iufb.reach.chats.PhotoInfo
import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.toInfo
import io.github.iufb.reach.chats.toRichText
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.navigation.ProfileRoute
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.iufb.reach.chats.MUTE_FOREVER
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** То, что показывает профиль; пустые поля не показываются. */
data class Profile(
    val title: String,
    val photo: TdFile?,
    val accentColorId: Int,
    /** Большое фото для полноэкранного просмотра. */
    val fullPhoto: PhotoInfo? = null,
    val username: String = "",
    val phoneNumber: String = "",
    /** О себе (пользователь) или описание (группа, канал). */
    val about: RichText = RichText.Empty,
    /** Участники группы или подписчики канала; 0 — неизвестно или не группа. */
    val memberCount: Int = 0,
    val isChannel: Boolean = false,
    val isUser: Boolean = false,
    /** Показать «Написать»: профиль пользователя открыт не из его чата. */
    val canWrite: Boolean = false,
    /** Уведомления чата выключены; `null` — профиль открыт не из чата, переключателя нет. */
    val isMuted: Boolean? = null,
)

/** Полная информация, которую TDLib отдаёт отдельным запросом. */
private data class FullInfo(
    val photo: PhotoInfo? = null,
    val about: RichText = RichText.Empty,
    val memberCount: Int = 0,
    val username: String = "",
)

/**
 * Профиль пользователя ([ProfileRoute.userId]) или чата ([ProfileRoute.chatId]); личный чат
 * показывает профиль собеседника. Имя и фото — из репозиториев, остальное — `get*FullInfo`,
 * обновляется по апдейтам.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val td: TdClient,
    private val chats: ChatsRepository,
    users: UsersRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ProfileRoute>()
    private val routeChat = chats.chats.value[route.chatId]
    /** Пользователь: из маршрута или собеседник личного чата. */
    private val userId: Long? = route.userId.takeIf { it != 0L } ?: routeChat?.userId
    private val group: GroupRef? = routeChat?.group

    private val full = MutableStateFlow(FullInfo())

    val profile: StateFlow<Profile?> = combine(chats.chats, users.users, full) { chatMap, userMap, info ->
        val user = userId?.let(userMap::get)
        val chat = chatMap[route.chatId]
        when {
            user != null -> Profile(
                title = user.fullName,
                photo = user.photo,
                accentColorId = user.accentColorId,
                fullPhoto = info.photo,
                username = user.username,
                phoneNumber = user.phoneNumber,
                about = info.about,
                canWrite = route.userId != 0L,
                isUser = true,
                isMuted = chat?.isMuted,
            )
            chat != null -> Profile(
                title = chat.title,
                photo = chat.photo,
                accentColorId = chat.accentColorId,
                fullPhoto = info.photo,
                username = info.username,
                about = info.about,
                memberCount = info.memberCount,
                isChannel = chat.kind == ChatKind.Channel,
                isMuted = chat.isMuted,
            )
            else -> null
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _openChat = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    /** «Написать» — открыть личный чат. */
    val openChat: SharedFlow<Long> = _openChat.asSharedFlow()

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                val changed = when (update) {
                    is TdApi.UpdateUserFullInfo -> update.userId == userId
                    is TdApi.UpdateSupergroupFullInfo -> (group as? GroupRef.Super)?.id == update.supergroupId
                    is TdApi.UpdateSupergroup -> (group as? GroupRef.Super)?.id == update.supergroup.id
                    is TdApi.UpdateBasicGroupFullInfo -> (group as? GroupRef.Basic)?.id == update.basicGroupId
                    else -> false
                }
                if (changed) load()
            }
        }
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        runCatching {
            full.value = when {
                userId != null -> {
                    val info = td.send(TdApi.GetUserFullInfo(userId))
                    FullInfo(photo = (info.photo ?: info.publicPhoto)?.toInfo(), about = info.bio.toRichText())
                }
                group is GroupRef.Super -> {
                    val info = td.send(TdApi.GetSupergroupFullInfo(group.id))
                    val supergroup = td.send(TdApi.GetSupergroup(group.id))
                    FullInfo(
                        photo = info.photo?.toInfo(),
                        about = RichText(info.description),
                        memberCount = info.memberCount.takeIf { it > 0 } ?: supergroup.memberCount,
                        username = supergroup.usernames?.activeUsernames?.firstOrNull().orEmpty(),
                    )
                }
                group is GroupRef.Basic -> {
                    val info = td.send(TdApi.GetBasicGroupFullInfo(group.id))
                    FullInfo(photo = info.photo?.toInfo(), about = RichText(info.description), memberCount = info.members.size)
                }
                else -> FullInfo()
            }
        }.onFailure { Log.w(TAG, "load full info failed", it) }
    }

    /** Переключатель «Уведомления»: выключить навсегда или включить. */
    fun setNotificationsEnabled(enabled: Boolean) {
        if (route.chatId == 0L) return
        viewModelScope.launch {
            runCatching { chats.setMuteFor(route.chatId, if (enabled) 0 else MUTE_FOREVER) }
                .onFailure { Log.w(TAG, "setMuteFor failed", it) }
        }
    }

    fun write() {
        val id = userId ?: return
        viewModelScope.launch {
            runCatching { td.send(TdApi.CreatePrivateChat(id, false)) }
                .onSuccess { _openChat.emit(it.id) }
                .onFailure { Log.w(TAG, "createPrivateChat failed", it) }
        }
    }

    private companion object {
        const val TAG = "ProfileViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
