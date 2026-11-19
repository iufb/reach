package io.github.iufb.reach.users

import org.drinkless.tdlib.TdApi

/** Когда пользователь был в сети — настолько точно, насколько он это показывает. */
sealed interface UserStatus {
    /** В сети до [expires] (unix-секунды). */
    data class Online(val expires: Int) : UserStatus
    /** Был в сети в [wasOnline] (unix-секунды). */
    data class Offline(val wasOnline: Int) : UserStatus
    data object Recently : UserStatus
    data object LastWeek : UserStatus
    data object LastMonth : UserStatus
    /** Не показывает или никогда не был (боты, удалённые). */
    data object Unknown : UserStatus
}

internal fun TdApi.UserStatus?.toModel(): UserStatus = when (this) {
    is TdApi.UserStatusOnline -> UserStatus.Online(expires)
    is TdApi.UserStatusOffline -> UserStatus.Offline(wasOnline)
    is TdApi.UserStatusRecently -> UserStatus.Recently
    is TdApi.UserStatusLastWeek -> UserStatus.LastWeek
    is TdApi.UserStatusLastMonth -> UserStatus.LastMonth
    else -> UserStatus.Unknown
}
