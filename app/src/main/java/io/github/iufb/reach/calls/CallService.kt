package io.github.iufb.reach.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.MainActivity
import io.github.iufb.reach.R
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
 * Foreground service на время звонка (`phoneCall|microphone`, ADR-0015): звонок не обрывается,
 * когда приложение свёрнуто, а в шторке — уведомление CallStyle с «Завершить».
 * Пока звук идёт в наушник, датчик приближения гасит экран у уха.
 */
@AndroidEntryPoint
class CallService : Service() {

    @Inject lateinit var calls: CallManager

    @Inject lateinit var users: UsersRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var proximity: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) calls.hangUp()
        if (!started) {
            started = true
            val call = calls.call.value
            if (call == null || call.isEnded) {
                // Звонок закончился раньше, чем служба поднялась: startForeground обязателен и тогда
                startInForeground(notification(null, ""))
                stopSelf()
                return START_NOT_STICKY
            }
            startInForeground(notification(call, users.users.value[call.userId]?.fullName.orEmpty()))
            observe()
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
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
                    manager.notify(NOTIFICATION_ID, notification(call, name))
                    updateProximity(call)
                }
        }
    }

    private fun updateProximity(call: CallUi) {
        val earpiece = call.route == null || call.route.kind == AudioRoute.Kind.Earpiece
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

    private fun notification(call: CallUi?, name: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_CALL).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangUp = PendingIntent.getService(
            this, 1,
            Intent(this, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val person = Person.Builder().setName(name.ifEmpty { getString(R.string.call_title) }).setImportant(true).build()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_reach)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
        if (call != null) {
            builder.setContentText(getString(callStatusRes(call.phase)))
            call.connectedAt?.let { connected ->
                builder.setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connected))
                    .setShowWhen(true)
            }
        }
        return builder.build()
    }

    override fun onDestroy() {
        releaseProximity()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "calls_ongoing"
        private const val NOTIFICATION_ID = 0x7ca11
        private const val ACTION_HANG_UP = "io.github.iufb.reach.action.HANG_UP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_calls_ongoing),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { setSound(null, null) }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
