package io.github.iufb.reach.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.core.td.TdException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/**
 * Push от Telegram через наш FCM-проект (ADR-0005). Payload целиком уходит в TDLib:
 * он сам решает, что показать, и присылает `UpdateNotificationGroup` / `UpdateActiveNotifications`.
 */
@AndroidEntryPoint
class ReachMessagingService : FirebaseMessagingService() {

    @Inject lateinit var td: TdClient

    @Inject lateinit var registrar: PushRegistrar

    override fun onNewToken(token: String) {
        registrar.onNewToken(token)
    }

    /** Вызывается в фоновом потоке; на обработку у нас несколько секунд, поэтому блокируем с таймаутом. */
    override fun onMessageReceived(message: RemoteMessage) {
        val payload = JSONObject(message.data).apply {
            // TDLib просит добавить эти поля к данным push (см. processPushNotification)
            put("google.sentTime", message.sentTime)
            message.notification?.sound?.let { put("google.notification.sound", it) }
        }.toString()
        runBlocking {
            withTimeoutOrNull(PROCESS_TIMEOUT_MS) {
                try {
                    td.send(TdApi.ProcessPushNotification(payload))
                } catch (e: TdException) {
                    // 406 — push без данных: TDLib сам подтянет новое по сети
                    if (e.code != NOT_SUPPORTED) Log.w(TAG, "processPushNotification failed", e)
                }
            } ?: Log.w(TAG, "processPushNotification timed out")
        }
    }

    private companion object {
        const val TAG = "ReachMessagingService"
        const val PROCESS_TIMEOUT_MS = 8_000L
        const val NOT_SUPPORTED = 406
    }
}
