package io.github.iufb.reach.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import dagger.hilt.android.AndroidEntryPoint
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/**
 * Действия из уведомления: «Прочитано» и «Ответить». После `viewMessages` TDLib сам уберёт
 * прочитанное из групп уведомлений, и [Notifier] снимет уведомление.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var td: TdClient

    @Inject @field:ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra(EXTRA_CHAT_ID, 0).takeIf { it != 0L } ?: return
        val messageIds = intent.getLongArrayExtra(EXTRA_MESSAGE_IDS) ?: LongArray(0)
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
        val pending = goAsync()
        scope.launch {
            try {
                if (intent.action == ACTION_REPLY && !reply.isNullOrEmpty()) {
                    val content = TdApi.InputMessageText(TdApi.FormattedText(reply, emptyArray()), null, false)
                    runCatching { td.send(TdApi.SendMessage(chatId, null, null, null, null, content)) }
                        .onFailure { Log.e(TAG, "reply failed", it) }
                }
                // Ответили — значит, прочитали
                if (messageIds.isNotEmpty()) {
                    runCatching { td.send(TdApi.ViewMessages(chatId, messageIds, TdApi.MessageSourceNotification(), true)) }
                        .onFailure { Log.e(TAG, "mark read failed", it) }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_READ = "io.github.iufb.reach.action.READ"
        const val ACTION_REPLY = "io.github.iufb.reach.action.REPLY"
        const val EXTRA_CHAT_ID = "chat_id"
        const val EXTRA_MESSAGE_IDS = "message_ids"
        const val KEY_REPLY = "reply"
        private const val TAG = "NotificationAction"
    }
}
