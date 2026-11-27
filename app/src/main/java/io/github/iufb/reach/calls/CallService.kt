package io.github.iufb.reach.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.users.UsersRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Foreground service на время звонка (ADR-0015): звонок не обрывается, когда приложение свёрнуто,
 * а в шторке — уведомление [CallNotifications]. Пока входящий звонит, тип только `phoneCall`
 * (микрофон из фона брать нельзя); после ответа — `phoneCall|microphone`.
 * Пока звук идёт в наушник, датчик приближения гасит экран у уха.
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var calls: CallManager

    @Inject lateinit var users: UsersRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var proximity: PowerManager.WakeLock? = null
    private var started = false
    private var withMicrophone = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CallNotifications.ACTION_HANG_UP) calls.hangUp()
        val call = calls.call.value
        val name = call?.let { users.users.value[it.userId]?.fullName }.orEmpty()
        if (!started) {
            started = true
            if (call == null || call.isEnded) {
                // Звонок закончился раньше, чем служба поднялась: startForeground обязателен и тогда
                startInForeground(CallNotifications.build(this, null, ""), microphone = false)
                stopSelf()
                return START_NOT_STICKY
            }
            startInForeground(CallNotifications.build(this, call, name), microphone = call.phase != CallPhase.Incoming)
            observe()
        } else if (call != null && !call.isEnded && call.phase != CallPhase.Incoming && !withMicrophone) {
            // Ответили на входящий: теперь нужен микрофон
            startInForeground(CallNotifications.build(this, call, name), microphone = true)
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification, microphone: Boolean) {
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or
            (if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        try {
            ServiceCompat.startForeground(this, CallNotifications.ID, notification, types)
            withMicrophone = microphone
        } catch (e: Exception) {
            // Микрофон из фона не дают — остаёмся phoneCall, звук всё равно идёт, пока приложение на экране
            Log.w(TAG, "startForeground microphone=$microphone", e)
            if (microphone) ServiceCompat.startForeground(this, CallNotifications.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
        }
    }

    private fun observe() {
        val manager = getSystemService(NotificationManager::class.java)
        scope.launch {
            combine(calls.call, users.users) { call, users -> call to users[call?.userId]?.fullName.orEmpty() }
                .distinctUntilChanged()
                .collect { (call, name) ->
                    if (call == null || call.isEnded) {
                        stopSelf()
                        return@collect
                    }
                    if (call.phase != CallPhase.Incoming && !withMicrophone) {
                        startInForeground(CallNotifications.build(this@CallService, call, name), microphone = true)
                    } else {
                        manager.notify(CallNotifications.ID, CallNotifications.build(this@CallService, call, name))
                    }
                    updateProximity(call)
                }
        }
    }

    private fun updateProximity(call: CallUi) {
        val earpiece = call.phase != CallPhase.Incoming && (call.route == null || call.route.kind == AudioRoute.Kind.Earpiece)
        if (earpiece && proximity == null) {
            val power = getSystemService(PowerManager::class.java)
            if (power.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                proximity = power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "reach:call").apply { acquire() }
            }
        } else if (!earpiece) {
            releaseProximity()
        }
    }

    private fun releaseProximity() {
        proximity?.takeIf { it.isHeld }?.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY)
        proximity = null
    }

    override fun onDestroy() {
        releaseProximity()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallService"

        /** Поднять службу. Из фона Android может не дать — тогда `false`, и уведомление показывают без неё. */
        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException
            Log.w(TAG, "foreground service not allowed", e)
            false
        }
    }
}
