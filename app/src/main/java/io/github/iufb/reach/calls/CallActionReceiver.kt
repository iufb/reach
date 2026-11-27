package io.github.iufb.reach.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** «Отклонить» во входящем уведомлении (#410): без открытия приложения. */
@AndroidEntryPoint
class CallActionReceiver : BroadcastReceiver() {

    @Inject lateinit var calls: CallManager

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == CallNotifications.ACTION_DECLINE) calls.hangUp()
    }
}
