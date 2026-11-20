package io.github.iufb.reach.auth

import io.github.iufb.reach.chats.RichText
import io.github.iufb.reach.chats.toRichText
import org.drinkless.tdlib.TdApi

/**
 * Состояние авторизации, собранное из `UpdateAuthorizationState` (PRODUCT.md §7).
 * UI работает только с этой моделью, классы TDLib наружу не торчат.
 */
sealed interface AuthState {
    /** TDLib ещё стартует (или закрывается): показываем загрузку. */
    data object Loading : AuthState

    data object WaitPhoneNumber : AuthState

    data class WaitCode(
        val phoneNumber: String,
        val delivery: CodeDelivery,
        /** Куда придёт код после `resendAuthenticationCode`; `null` — переотправить нельзя. */
        val nextDelivery: CodeDelivery?,
        /** Через сколько секунд можно запросить код повторно. */
        val resendTimeoutSeconds: Int,
    ) : AuthState

    /** Включена двухэтапная проверка: нужен облачный пароль. */
    data class WaitPassword(
        /** Подсказка, которую пользователь задал вместе с паролем; может быть пустой. */
        val hint: String,
    ) : AuthState

    /** Номер ещё не зарегистрирован: имя и согласие с условиями (ADR-0007). */
    data class WaitRegistration(
        /** Условия использования Telegram. */
        val terms: RichText,
        /** Минимальный возраст; 0 — не указан. */
        val minAge: Int,
    ) : AuthState

    data object Ready : AuthState

    /**
     * Шаг входа, который клиент пока не умеет (email, регистрация...).
     * Показываем его явно, а не молча зависаем (PRODUCT.md §3, принцип 4).
     */
    data class Unsupported(val step: String) : AuthState
}

/** Как Telegram доставляет код подтверждения. */
sealed interface CodeDelivery {
    /** Длина кода, если TDLib её сообщила (0 или `null` — неизвестна). */
    val length: Int?

    /** Сообщение в Telegram на другом устройстве. */
    data class TelegramMessage(override val length: Int) : CodeDelivery
    data class Sms(override val length: Int) : CodeDelivery
    data class Call(override val length: Int) : CodeDelivery
    data object Other : CodeDelivery {
        override val length: Int? get() = null
    }
}

internal fun TdApi.AuthorizationState.toAuthState(): AuthState = when (this) {
    is TdApi.AuthorizationStateWaitTdlibParameters,
    is TdApi.AuthorizationStateLoggingOut,
    is TdApi.AuthorizationStateClosing,
    is TdApi.AuthorizationStateClosed -> AuthState.Loading

    is TdApi.AuthorizationStateWaitPhoneNumber -> AuthState.WaitPhoneNumber

    is TdApi.AuthorizationStateWaitCode -> AuthState.WaitCode(
        phoneNumber = codeInfo.phoneNumber,
        delivery = codeInfo.type.toCodeDelivery(),
        nextDelivery = codeInfo.nextType?.toCodeDelivery(),
        resendTimeoutSeconds = codeInfo.timeout,
    )

    is TdApi.AuthorizationStateWaitPassword -> AuthState.WaitPassword(passwordHint)

    is TdApi.AuthorizationStateWaitRegistration -> AuthState.WaitRegistration(
        termsOfService.text.toRichText(),
        termsOfService.minUserAge,
    )

    is TdApi.AuthorizationStateReady -> AuthState.Ready

    else -> AuthState.Unsupported(javaClass.simpleName.removePrefix("AuthorizationState"))
}

private fun TdApi.AuthenticationCodeType.toCodeDelivery(): CodeDelivery = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> CodeDelivery.TelegramMessage(length)
    is TdApi.AuthenticationCodeTypeSms -> CodeDelivery.Sms(length)
    is TdApi.AuthenticationCodeTypeCall -> CodeDelivery.Call(length)
    else -> CodeDelivery.Other
}
