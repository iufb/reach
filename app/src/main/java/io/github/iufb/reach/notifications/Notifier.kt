package io.github.iufb.reach.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.iufb.reach.MainActivity
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.summarize
import io.github.iufb.reach.chats.toBody
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Системные уведомления (PRODUCT.md §4.8). Что показывать, решает TDLib с учётом mute:
 * он присылает группы уведомлений (`UpdateNotificationGroup`, `UpdateActiveNotifications`),
 * а мы только рисуем их — одна группа TDLib = одно уведомление Android с id группы.
 *
 * Апдейты обрабатываются по одному в одной корутине, поэтому [groups] без синхронизации.
 */
@Singleton
class Notifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
    private val visibleChats: VisibleChats,
) {
    private val manager = NotificationManagerCompat.from(context)
    private val groups = mutableMapOf<Int, Group>()

    /** Вызывается из `Application.onCreate`: подписка до первых апдейтов, процесс может быть поднят push'ем. */
    fun start() {
        createChannels()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                runCatching { onUpdate(update) }.onFailure { Log.e(TAG, "notification update failed", it) }
            }
        }
    }

    private suspend fun onUpdate(update: TdApi.Object) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> when (update.authorizationState) {
                // По умолчанию TDLib уведомлений не формирует: включаем
                is TdApi.AuthorizationStateReady -> enableTdNotifications()
                is TdApi.AuthorizationStateClosed -> {
                    groups.clear()
                    manager.cancelAll()
                }
            }
            is TdApi.UpdateActiveNotifications -> {
                groups.keys.filter { id -> update.groups.none { it.id == id } }.forEach { manager.cancel(it) }
                groups.clear()
                update.groups.forEach { g ->
                    val group = Group(g.chatId, g.type, g.notifications.sortedBy { it.id })
                    groups[g.id] = group
                    post(g.id, group, alert = false)
                }
            }
            is TdApi.UpdateNotificationGroup -> {
                val old = groups[update.notificationGroupId]
                val removed = update.removedNotificationIds.toSet()
                val notifications = ((old?.notifications.orEmpty()).filter { it.id !in removed } + update.addedNotifications)
                    .distinctBy { it.id }
                    .sortedBy { it.id }
                if (notifications.isEmpty()) {
                    groups.remove(update.notificationGroupId)
                    manager.cancel(update.notificationGroupId)
                } else {
                    val group = Group(update.chatId, update.type, notifications)
                    groups[update.notificationGroupId] = group
                    post(update.notificationGroupId, group, alert = update.addedNotifications.any { !it.isSilent })
                }
            }
            is TdApi.UpdateNotification -> {
                val group = groups[update.notificationGroupId] ?: return
                val changed = group.copy(
                    notifications = group.notifications.map { if (it.id == update.notification.id) update.notification else it },
                )
                groups[update.notificationGroupId] = changed
                post(update.notificationGroupId, changed, alert = false)
            }
        }
    }

    private suspend fun enableTdNotifications() {
        runCatching {
            td.send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(MAX_GROUPS.toLong())))
            td.send(TdApi.SetOption("notification_group_size_max", TdApi.OptionValueInteger(MAX_GROUP_SIZE.toLong())))
        }.onFailure { Log.e(TAG, "enable notifications failed", it) }
    }

    @SuppressLint("MissingPermission") // проверено в canNotify
    private suspend fun post(groupId: Int, group: Group, alert: Boolean) {
        if (!canNotify()) return
        // Чат открыт на экране — сообщения и так видны; TDLib уберёт группу, когда они будут просмотрены
        if (visibleChats.isVisible(group.chatId)) {
            manager.cancel(groupId)
            return
        }
        val chat = runCatching { td.send(TdApi.GetChat(group.chatId)) }.getOrNull() ?: return
        val isGroupChat = chat.type is TdApi.ChatTypeBasicGroup ||
            (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == false
        val channel = when {
            (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel == true -> CHANNEL_CHANNELS
            isGroupChat -> CHANNEL_GROUPS
            else -> CHANNEL_PRIVATE
        }

        val me = Person.Builder().setName(context.getString(R.string.notification_me)).build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroupChat) chat.title else null)
            .setGroupConversation(isGroupChat)
        val people = mutableMapOf<String, Person>()
        val messageIds = mutableListOf<Long>()
        group.notifications.forEach { notification ->
            val item = describe(notification, chat) ?: return@forEach
            item.messageId?.let(messageIds::add)
            val person = people.getOrPut(item.senderName) { Person.Builder().setName(item.senderName).build() }
            style.addMessage(item.text, notification.date * 1000L, person)
        }
        if (style.messages.isEmpty()) return

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentTitle(chat.title)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(openChatIntent(chat.id, groupId))
            .setAutoCancel(true)
            .setSilent(!alert)
            .setOnlyAlertOnce(!alert)
            .setNumber(group.notifications.size)
            .setWhen(group.notifications.last().date * 1000L)
        avatar(chat)?.let(builder::setLargeIcon)
        if (messageIds.isNotEmpty()) {
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_mark_read,
                    context.getString(R.string.notification_mark_read),
                    actionIntent(NotificationActionReceiver.ACTION_READ, chat.id, groupId, messageIds),
                ).setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        val canReply = (chat.permissions?.canSendBasicMessages ?: true) &&
            (chat.type as? TdApi.ChatTypeSupergroup)?.isChannel != true
        if (canReply) {
            val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY)
                .setLabel(context.getString(R.string.notification_reply))
                .build()
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_reply,
                    context.getString(R.string.notification_reply),
                    actionIntent(NotificationActionReceiver.ACTION_REPLY, chat.id, groupId, messageIds, mutable = true),
                ).addRemoteInput(input)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        manager.notify(groupId, builder.build())
    }

    /** Текст и автор одного уведомления; `null` — тип, который мы не показываем (звонки и т. п.). */
    private suspend fun describe(notification: TdApi.Notification, chat: TdApi.Chat): Item? =
        when (val type = notification.type) {
            is TdApi.NotificationTypeNewMessage -> {
                val message = type.message
                val text = if (type.showPreview) {
                    summarize(message.content.toBody()) { context.getString(it) }.ifBlank { context.getString(R.string.notification_new_message) }
                } else {
                    context.getString(R.string.notification_new_message)
                }
                Item(senderName(message.senderId, chat), text, message.id)
            }
            is TdApi.NotificationTypeNewPushMessage -> {
                val text = (type.content as? TdApi.PushMessageContentText)?.text
                    ?: context.getString(R.string.notification_new_message)
                Item(type.senderName.ifBlank { chat.title }, text, type.messageId)
            }
            else -> null
        }

    private suspend fun senderName(sender: TdApi.MessageSender, chat: TdApi.Chat): String = when (sender) {
        is TdApi.MessageSenderUser -> runCatching { td.send(TdApi.GetUser(sender.userId)) }.getOrNull()
            ?.let { listOf(it.firstName, it.lastName).filter(String::isNotBlank).joinToString(" ") }
            ?.ifBlank { null }
            ?: chat.title
        is TdApi.MessageSenderChat -> if (sender.chatId == chat.id) {
            chat.title
        } else {
            runCatching { td.send(TdApi.GetChat(sender.chatId)).title }.getOrDefault(chat.title)
        }
        else -> chat.title
    }

    /** Маленькая аватарка чата, круглая; скачиваем коротко и синхронно, без неё уведомление тоже уйдёт. */
    private suspend fun avatar(chat: TdApi.Chat): Bitmap? {
        val file = chat.photo?.small ?: return null
        val path = if (file.local.isDownloadingCompleted) {
            file.local.path
        } else {
            withTimeoutOrNull(AVATAR_TIMEOUT_MS) {
                runCatching { td.send(TdApi.DownloadFile(file.id, AVATAR_PRIORITY, 0, 0, true)) }.getOrNull()
            }?.takeIf { it.local.isDownloadingCompleted }?.local?.path
        } ?: return null
        val source = BitmapFactory.decodeFile(path) ?: return null
        return circle(source)
    }

    private fun circle(source: Bitmap): Bitmap {
        val size = minOf(source.width, source.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val clip = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(source, (size - source.width) / 2f, (size - source.height) / 2f, Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun openChatIntent(chatId: Long, groupId: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .putExtra(MainActivity.EXTRA_CHAT_ID, chatId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, groupId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun actionIntent(action: String, chatId: Long, groupId: Int, messageIds: List<Long>, mutable: Boolean = false): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_CHAT_ID, chatId)
            .putExtra(NotificationActionReceiver.EXTRA_MESSAGE_IDS, messageIds.toLongArray())
        // RemoteInput дописывает текст в intent — для ответа PendingIntent должен быть изменяемым
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, groupId * 2 + if (action == NotificationActionReceiver.ACTION_REPLY) 1 else 0, intent, flags)
    }

    // POST_NOTIFICATIONS есть только с Android 13; раньше хватает areNotificationsEnabled()
    private fun canNotify(): Boolean =
        (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            ) &&
            manager.areNotificationsEnabled()

    private fun createChannels() {
        val channels = listOf(
            CHANNEL_PRIVATE to R.string.notification_channel_private,
            CHANNEL_GROUPS to R.string.notification_channel_groups,
            CHANNEL_CHANNELS to R.string.notification_channel_channels,
        ).map { (id, name) -> NotificationChannel(id, context.getString(name), NotificationManager.IMPORTANCE_HIGH) }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(channels)
    }

    private data class Group(val chatId: Long, val type: TdApi.NotificationGroupType, val notifications: List<TdApi.Notification>)

    private data class Item(val senderName: String, val text: String, val messageId: Long?)

    private companion object {
        const val TAG = "Notifier"
        const val CHANNEL_PRIVATE = "private"
        const val CHANNEL_GROUPS = "groups"
        const val CHANNEL_CHANNELS = "channels"
        const val MAX_GROUPS = 25
        const val MAX_GROUP_SIZE = 10
        const val AVATAR_TIMEOUT_MS = 3_000L
        const val AVATAR_PRIORITY = 1
    }
}
