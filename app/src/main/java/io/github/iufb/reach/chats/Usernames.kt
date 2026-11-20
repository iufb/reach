package io.github.iufb.reach.chats

import androidx.annotation.StringRes
import io.github.iufb.reach.R
import io.github.iufb.reach.core.td.TdClient
import org.drinkless.tdlib.TdApi

/** Итог проверки username при вводе (свой профиль, публичный канал или группа). */
enum class UsernameStatus(@StringRes val message: Int, val isOk: Boolean = false) {
    Ok(R.string.username_ok, isOk = true),
    Invalid(R.string.username_invalid),
    Occupied(R.string.username_occupied),
    Purchasable(R.string.username_purchasable),
    TooManyPublic(R.string.username_too_many),
    Unavailable(R.string.username_unavailable),
}

/**
 * `checkChatUsername`: [chatId] — свой чат с собой (профиль), канал или группа; 0 — чат ещё создаётся.
 * Пустой username — «убрать», это всегда можно.
 */
suspend fun TdClient.checkUsername(chatId: Long, username: String): UsernameStatus {
    if (username.isEmpty()) return UsernameStatus.Ok
    return when (send(TdApi.CheckChatUsername(chatId, username))) {
        is TdApi.CheckChatUsernameResultOk -> UsernameStatus.Ok
        is TdApi.CheckChatUsernameResultUsernameOccupied -> UsernameStatus.Occupied
        is TdApi.CheckChatUsernameResultUsernamePurchasable -> UsernameStatus.Purchasable
        is TdApi.CheckChatUsernameResultPublicChatsTooMany -> UsernameStatus.TooManyPublic
        is TdApi.CheckChatUsernameResultPublicGroupsUnavailable -> UsernameStatus.Unavailable
        else -> UsernameStatus.Invalid
    }
}
