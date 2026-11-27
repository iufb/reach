package io.github.iufb.reach.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import io.github.iufb.reach.MainActivity
import io.github.iufb.reach.R

/**
 * Уведомления звонка (#409, #410), стиль `CallStyle`:
 * - входящий — «Отклонить / Ответить», full-screen intent на экран вызова, рингтон и вибрация канала
 *   по кругу (`FLAG_INSISTENT`). Громкость, беззвучный режим и «Не беспокоить» применяет система;
 * - текущий — «Завершить» и таймер.
 * Одно и то же id: входящее превращается в текущее после ответа.
 */
object CallNotifications {
    const val ID = 0x7ca11

    private const val CHANNEL_ONGOING = "calls_ongoing"
    private const val CHANNEL_INCOMING = "calls_incoming"

    const val ACTION_HANG_UP = "io.github.iufb.reach.action.HANG_UP"
    const val ACTION_DECLINE = "io.github.iufb.reach.action.DECLINE"

    fun build(context: Context, call: CallUi?, name: String): Notification {
        ensureChannels(context)
        val person = Person.Builder().setName(name.ifEmpty { context.getString(R.string.call_title) }).setImportant(true).build()
        val open = activity(context, 0, MainActivity.ACTION_OPEN_CALL)
        if (call != null && call.phase == CallPhase.Incoming) {
            val decline = PendingIntent.getBroadcast(
                context, 2,
                Intent(context, CallActionReceiver::class.java).setAction(ACTION_DECLINE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val answer = activity(context, 3, MainActivity.ACTION_ANSWER_CALL)
            return Notification.Builder(context, CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_stat_reach)
                .setContentIntent(open)
                .setFullScreenIntent(open, true)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_CALL)
                .setContentText(context.getString(R.string.call_status_incoming))
                .setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
                .build()
                .apply { flags = flags or Notification.FLAG_INSISTENT }
        }
        val hangUp = PendingIntent.getService(
            context, 1,
            Intent(context, CallService::class.java).setAction(ACTION_HANG_UP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_reach)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
            .setStyle(Notification.CallStyle.forOngoingCall(person, hangUp))
        if (call != null) {
            builder.setContentText(context.getString(callStatusRes(call.phase)))
            call.connectedAt?.let { connected ->
                builder.setUsesChronometer(true)
                    .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connected))
                    .setShowWhen(true)
            }
        }
        return builder.build()
    }

    /** Показать без службы — когда foreground service из фона запустить нельзя. */
    fun show(context: Context, call: CallUi, name: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, call, name))
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID)
    }

    private fun activity(context: Context, requestCode: Int, action: String) = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannels(context: Context) {
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.notification_channel_calls_ongoing),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { setSound(null, null) }
        val incoming = NotificationChannel(
            CHANNEL_INCOMING,
            context.getString(R.string.notification_channel_calls_incoming),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 1000, 1000)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ongoing, incoming))
    }
}
