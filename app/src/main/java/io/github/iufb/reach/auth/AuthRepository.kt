package io.github.iufb.reach.auth

import android.util.Log
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.stateIn
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стейт-машина авторизации поверх `UpdateAuthorizationState` (PRODUCT.md §4.1, §7).
 *
 * Переходы делает TDLib: методы ниже только отправляют запрос, а новое состояние
 * приходит апдейтом в [state]. Ошибки запросов вылетают как `TdException`.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val td: TdClient,
    @ApplicationScope scope: CoroutineScope,
) {
    val state: StateFlow<AuthState> = td.updates
        // Апдейты без replay: начальное состояние запрашиваем сразу после подписки.
        // Всё, что придёт позже, уже буферизуется для нас и встанет после ответа.
        .onSubscription {
            runCatching { td.send(TdApi.GetAuthorizationState()) }
                .onSuccess { emit(TdApi.UpdateAuthorizationState(it)) }
                .onFailure { Log.e(TAG, "getAuthorizationState failed", it) }
        }
        .filterIsInstance<TdApi.UpdateAuthorizationState>()
        .map { it.authorizationState.toAuthState() }
        .stateIn(scope, SharingStarted.Eagerly, AuthState.Loading)

    suspend fun setPhoneNumber(phoneNumber: String) {
        td.send(TdApi.SetAuthenticationPhoneNumber(phoneNumber, null))
    }

    suspend fun checkCode(code: String) {
        td.send(TdApi.CheckAuthenticationCode(code))
    }

    suspend fun checkPassword(password: String) {
        td.send(TdApi.CheckAuthenticationPassword(password))
    }

    suspend fun resendCode() {
        td.send(TdApi.ResendAuthenticationCode(TdApi.ResendCodeReasonUserRequest()))
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
