package io.github.iufb.reach.auth

import android.util.Log
import io.github.iufb.reach.core.td.TdException
import io.github.iufb.reach.errors.UserError
import io.github.iufb.reach.errors.userErrorFor

/** Ошибка шага входа в виде, понятном UI. */
sealed interface AuthError {
    data object InvalidPhoneNumber : AuthError
    data object PhoneNumberBanned : AuthError
    data object InvalidCode : AuthError
    data object CodeExpired : AuthError
    data object InvalidPassword : AuthError
    /** Telegram не даёт регистрироваться в сторонних приложениях с этим номером (ADR-0007). */
    data object SignUpForbidden : AuthError
    data object InvalidName : AuthError
    data class FloodWait(val seconds: Int) : AuthError
    /** Прочие ошибки — общим переводом [userErrorFor], сырой текст только в лог (#216). */
    data class Unknown(val error: UserError) : AuthError
}

internal fun Throwable.toAuthError(): AuthError {
    if (this !is TdException) return unknown()
    retryAfterSeconds?.let { return AuthError.FloodWait(it) }
    return when {
        "PHONE_NUMBER_INVALID" in message -> AuthError.InvalidPhoneNumber
        "PHONE_NUMBER_BANNED" in message -> AuthError.PhoneNumberBanned
        "PHONE_CODE_INVALID" in message || "PHONE_CODE_EMPTY" in message -> AuthError.InvalidCode
        "PHONE_CODE_EXPIRED" in message -> AuthError.CodeExpired
        "PASSWORD_HASH_INVALID" in message -> AuthError.InvalidPassword
        "SIGNUP_FORBIDDEN" in message -> AuthError.SignUpForbidden
        "FIRSTNAME_INVALID" in message || "LASTNAME_INVALID" in message -> AuthError.InvalidName
        else -> unknown()
    }
}

private fun Throwable.unknown(): AuthError {
    Log.w("AuthError", "auth failed", this)
    return AuthError.Unknown(userErrorFor(this))
}
