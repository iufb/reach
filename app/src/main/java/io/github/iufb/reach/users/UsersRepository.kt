package io.github.iufb.reach.users

import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
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

data class User(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val photo: TdFile?,
    val accentColorId: Int,
    /** Основной активный username без «@»; пусто — нет. */
    val username: String = "",
    /** Телефон, если пользователь его показывает; без «+». */
    val phoneNumber: String = "",
    val status: UserStatus = UserStatus.Unknown,
) {
    /** Пустое имя у удалённых аккаунтов. */
    val fullName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Пользователи из `UpdateUser`. TDLib присылает пользователя раньше, чем кто-то на него сошлётся,
 * поэтому достаточно копить апдейты (PRODUCT.md §7).
 */
@Singleton
class UsersRepository @Inject constructor(td: TdClient, @ApplicationScope scope: CoroutineScope) {

    private val _users = MutableStateFlow<Map<Long, User>>(emptyMap())
    val users: StateFlow<Map<Long, User>> = _users.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    is TdApi.UpdateUser -> _users.update { it + (update.user.id to update.user.toModel()) }
                    is TdApi.UpdateUserStatus -> _users.update { users ->
                        val user = users[update.userId] ?: return@update users
                        users + (user.id to user.copy(status = update.status.toModel()))
                    }
                    is TdApi.UpdateAuthorizationState ->
                        if (update.authorizationState is TdApi.AuthorizationStateClosed) _users.value = emptyMap()
                }
            }
        }
    }
}

private fun TdApi.User.toModel() = User(
    id = id,
    firstName = firstName,
    lastName = lastName,
    photo = profilePhoto?.small?.toTdFile(),
    accentColorId = accentColorId,
    username = usernames?.activeUsernames?.firstOrNull().orEmpty(),
    phoneNumber = phoneNumber,
    status = status.toModel(),
)
