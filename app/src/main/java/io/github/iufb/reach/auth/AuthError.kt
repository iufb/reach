package io.github.iufb.reach.auth

import io.github.iufb.reach.core.td.TdException

/** Ошибка шага входа в виде, понятном UI. */
sealed interface AuthError {
    data object InvalidPhoneNumber : AuthError
    data object PhoneNumberBanned : AuthError
    data object InvalidCode : AuthError
    data object CodeExpired : AuthError
    data object InvalidPassword : AuthError
    data class FloodWait(val seconds: Int) : AuthError
    data class Unknown(val message: String) : AuthError
}

internal fun Throwable.toAuthError(): AuthError {
    if (this !is TdException) return AuthError.Unknown(message ?: javaClass.simpleName)
    retryAfterSeconds?.let { return AuthError.FloodWait(it) }
    return when {
        "PHONE_NUMBER_INVALID" in message -> AuthError.InvalidPhoneNumber
        "PHONE_NUMBER_BANNED" in message -> AuthError.PhoneNumberBanned
        "PHONE_CODE_INVALID" in message || "PHONE_CODE_EMPTY" in message -> AuthError.InvalidCode
        "PHONE_CODE_EXPIRED" in message -> AuthError.CodeExpired
        "PASSWORD_HASH_INVALID" in message -> AuthError.InvalidPassword
        else -> AuthError.Unknown(message)
    }
}
