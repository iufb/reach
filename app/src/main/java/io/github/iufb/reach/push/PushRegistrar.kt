package io.github.iufb.reach.push

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.BuildConfig
import io.github.iufb.reach.auth.AuthRepository
import io.github.iufb.reach.auth.AuthState
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Push через собственный FCM-проект (ADR-0005): Telegram шлёт push на наш FCM, TDLib их разбирает.
 *
 * Цепочка: FCM-токен → `registerDevice` после входа (и при смене токена) → [ReachMessagingService]
 * отдаёт payload в `processPushNotification`. Без конфига Firebase в `BuildConfig` всё выключено.
 */
@Singleton
class PushRegistrar @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    private val auth: AuthRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    /** Firebase поднят: конфиг есть и инициализация прошла. */
    var isEnabled: Boolean = false
        private set

    /** Вызывается из `Application.onCreate`, до старта сервисов FCM. */
    fun start() {
        val options = firebaseOptions() ?: run {
            Log.i(TAG, "FCM config missing, push disabled")
            return
        }
        isEnabled = runCatching { FirebaseApp.initializeApp(context, options) }
            .onFailure { Log.e(TAG, "FirebaseApp init failed", it) }
            .isSuccess
        if (!isEnabled) return
        // После каждого входа (и при старте с сохранённой сессией) регистрируем текущий токен
        scope.launch {
            auth.state.collect { state ->
                if (state == AuthState.Ready) runCatching { register(currentToken()) }.onFailure { Log.e(TAG, "register failed", it) }
            }
        }
    }

    /** FCM выдал новый токен. До входа регистрировать нечего: зарегистрируем после `Ready`. */
    fun onNewToken(token: String) {
        if (!isEnabled || auth.state.value != AuthState.Ready) return
        scope.launch { runCatching { register(token) }.onFailure { Log.e(TAG, "register failed", it) } }
    }

    /** Перед выходом: отвязываем устройство, чтобы push по этой сессии больше не шли. */
    suspend fun unregister() {
        if (!isEnabled) return
        runCatching { td.send(TdApi.RegisterDevice(TdApi.DeviceTokenFirebaseCloudMessaging("", false), longArrayOf())) }
            .onFailure { Log.w(TAG, "unregister failed", it) }
    }

    private suspend fun register(token: String) {
        td.send(TdApi.RegisterDevice(TdApi.DeviceTokenFirebaseCloudMessaging(token, false), longArrayOf()))
        Log.i(TAG, "device registered for push")
    }

    private suspend fun currentToken(): String = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result) else cont.resumeWithException(task.exception ?: IllegalStateException("no FCM token"))
        }
    }

    private fun firebaseOptions(): FirebaseOptions? {
        val values = listOf(BuildConfig.FCM_PROJECT_ID, BuildConfig.FCM_APP_ID, BuildConfig.FCM_API_KEY, BuildConfig.FCM_SENDER_ID)
        if (values.any { it.isBlank() }) return null
        return FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setApplicationId(BuildConfig.FCM_APP_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
    }

    private companion object {
        const val TAG = "PushRegistrar"
    }
}
