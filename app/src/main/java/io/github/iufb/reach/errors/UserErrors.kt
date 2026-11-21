package io.github.iufb.reach.errors

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.R
import io.github.iufb.reach.core.td.TdException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Текст ошибки для пользователя (#216). TDLib отвечает по-английски — то кодом сервера
 * (`CHAT_ADMIN_REQUIRED`), то фразой («Chat not found»); на экран идёт перевод известных
 * случаев или общее «Не удалось выполнить действие», а исходный текст — только в лог.
 */
@Singleton
class UserErrors @Inject constructor(@ApplicationContext private val context: Context) {
    fun message(error: Throwable): String {
        Log.w(TAG, "action failed", error)
        val known = userErrorFor(error)
        return if (known.arg != null) context.getString(known.text, known.arg) else context.getString(known.text)
    }

    private companion object {
        const val TAG = "UserErrors"
    }
}

/** Строка для пользователя и подставляемое в неё число (секунды ожидания). */
data class UserError(@StringRes val text: Int, val arg: Int? = null)

/** Отображение ошибки в текст; отдельно от [UserErrors], чтобы проверять без Android. */
fun userErrorFor(error: Throwable): UserError {
    val td = error as? TdException ?: return UserError(R.string.error_generic)
    val message = td.message
    // «Too Many Requests: retry after 35» и FLOOD_WAIT_35 — ждать N секунд
    val wait = RETRY_AFTER.find(message)?.groupValues?.get(1)?.toIntOrNull()
        ?: FLOOD_WAIT.find(message)?.groupValues?.get(1)?.toIntOrNull()
    if (td.code == 429 || wait != null) {
        return if (wait != null) UserError(R.string.error_flood_wait, wait) else UserError(R.string.error_flood)
    }
    KNOWN.entries.firstOrNull { (key, _) -> message.contains(key, ignoreCase = true) }?.let { return UserError(it.value) }
    return when (td.code) {
        401 -> UserError(R.string.error_unauthorized)
        403 -> UserError(R.string.error_forbidden)
        404 -> UserError(R.string.error_not_found)
        else -> UserError(R.string.error_generic)
    }
}

private val RETRY_AFTER = Regex("retry after (\\d+)", RegexOption.IGNORE_CASE)
private val FLOOD_WAIT = Regex("FLOOD_WAIT_(\\d+)")

/** Подстрока ответа TDLib → понятный текст. Порядок важен: более частные ключи выше. */
private val KNOWN: Map<String, Int> = linkedMapOf(
    "PEER_FLOOD" to R.string.error_flood,
    "CHAT_ADMIN_REQUIRED" to R.string.error_admin_required,
    "not enough rights" to R.string.error_admin_required,
    "RIGHT_FORBIDDEN" to R.string.error_admin_required,
    "USER_PRIVACY_RESTRICTED" to R.string.error_privacy_restricted,
    "USER_NOT_MUTUAL_CONTACT" to R.string.error_not_mutual_contact,
    "USER_ALREADY_PARTICIPANT" to R.string.error_already_participant,
    "USERS_TOO_MUCH" to R.string.error_too_many_members,
    "CHANNELS_TOO_MUCH" to R.string.error_too_many_channels,
    "USER_CHANNELS_TOO_MUCH" to R.string.error_too_many_channels,
    "USERNAME_OCCUPIED" to R.string.error_username_occupied,
    "USERNAME_INVALID" to R.string.error_username_invalid,
    "USERNAME_NOT_MODIFIED" to R.string.error_not_modified,
    "MESSAGE_NOT_MODIFIED" to R.string.error_not_modified,
    "MESSAGE_TOO_LONG" to R.string.error_message_too_long,
    "Message is too long" to R.string.error_message_too_long,
    "CHAT_WRITE_FORBIDDEN" to R.string.error_write_forbidden,
    "Have no write access" to R.string.error_write_forbidden,
    "CHAT_SEND_" to R.string.error_write_forbidden,
    "INVITE_HASH_EXPIRED" to R.string.error_invite_expired,
    "INVITE_HASH_INVALID" to R.string.error_invite_expired,
    "Chat not found" to R.string.error_chat_not_found,
    "Message not found" to R.string.error_message_not_found,
    "MESSAGE_ID_INVALID" to R.string.error_message_not_found,
    "USER_BLOCKED" to R.string.error_user_blocked,
    "YOU_BLOCKED_USER" to R.string.error_user_blocked,
    "Notification settings" to R.string.error_cant_change_notifications,
    "maximum number of pinned" to R.string.error_pinned_limit,
    "PINNED_DIALOGS_TOO_MUCH" to R.string.error_pinned_limit,
    "STATS_" to R.string.error_stats_unavailable,
    "Network" to R.string.error_network,
    "Request aborted" to R.string.error_network,
)
