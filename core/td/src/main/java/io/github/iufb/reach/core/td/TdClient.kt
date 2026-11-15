package io.github.iufb.reach.core.td

import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Единственная точка входа в TDLib (PRODUCT.md §7).
 *
 * - [send] превращает callback-запрос в suspend-функцию; `TdApi.Error` → [TdException].
 * - [updates] — поток всех апдейтов. Подписываться нужно заранее: апдейты не хранятся,
 *   опоздавший подписчик пропустит прошлые (актуальное состояние берётся запросом, например `getAuthorizationState`).
 * - `setTdlibParameters` отправляется здесь же, это инфраструктура, а не логика авторизации.
 */
@Singleton
class TdClient @Inject constructor(private val config: TdConfig) {

    private val _updates = MutableSharedFlow<TdApi.Object>(extraBufferCapacity = UPDATE_BUFFER)
    val updates: SharedFlow<TdApi.Object> = _updates.asSharedFlow()

    private val client: Client

    init {
        // Нативный лог TDLib: только ошибки
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        client = Client.create(::onUpdate, { Log.e(TAG, "Update handler failed", it) }, { Log.e(TAG, "TDLib exception", it) })
    }

    suspend fun <T : TdApi.Object> send(function: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(function) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWithException(TdException(result))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }

    /** Вызывается в потоке TDLib. */
    private fun onUpdate(update: TdApi.Object) {
        if (config.logUpdates) Log.d(UPDATES_TAG, update.toString())
        if (update is TdApi.UpdateAuthorizationState &&
            update.authorizationState is TdApi.AuthorizationStateWaitTdlibParameters
        ) {
            sendTdlibParameters()
        }
        if (!_updates.tryEmit(update)) Log.e(TAG, "Update buffer overflow, dropped ${update.javaClass.simpleName}")
    }

    private fun sendTdlibParameters() {
        if (!config.hasCredentials) {
            Log.e(TAG, "api_id / api_hash не заданы — сборка без GitHub Secrets (ADR-0003)")
            return
        }
        val params = TdApi.SetTdlibParameters(
            config.useTestDc,
            config.databaseDirectory,
            config.filesDirectory,
            ByteArray(0),
            true,
            true,
            true,
            false,
            config.apiId,
            config.apiHash,
            Locale.getDefault().language,
            Build.MODEL,
            Build.VERSION.RELEASE,
            config.applicationVersion,
        )
        client.send(params) { result ->
            if (result is TdApi.Error) Log.e(TAG, "setTdlibParameters failed: ${result.code} ${result.message}")
        }
    }

    private companion object {
        const val TAG = "TdClient"
        const val UPDATES_TAG = "TdUpdates"
        const val UPDATE_BUFFER = 4096
    }
}
