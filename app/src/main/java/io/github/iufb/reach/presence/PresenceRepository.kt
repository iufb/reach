package io.github.iufb.reach.presence

import io.github.iufb.reach.chat.Sender
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Что сейчас делает участник чата: «печатает», «записывает голосовое»… */
enum class ActivityKind { Typing, RecordingVoice, RecordingVideo, UploadingPhoto, UploadingFile, ChoosingSticker }

/**
 * Действия в чатах (`UpdateChatAction`) и число участников в сети (`UpdateChatOnlineMemberCount`).
 * TDLib сам присылает отмену действия, но на случай потерянного апдейта действие гаснет через [ACTION_TTL_MS].
 */
@Singleton
class PresenceRepository @Inject constructor(td: TdClient, @ApplicationScope private val scope: CoroutineScope) {

    private val _activities = MutableStateFlow<Map<Long, Map<Sender, ActivityKind>>>(emptyMap())
    /** По чату: кто что делает. Пустые чаты удаляются. */
    val activities: StateFlow<Map<Long, Map<Sender, ActivityKind>>> = _activities.asStateFlow()

    private val _online = MutableStateFlow<Map<Long, Int>>(emptyMap())
    /** Участников в сети по чату; TDLib присылает только для открытых групп. */
    val online: StateFlow<Map<Long, Int>> = _online.asStateFlow()

    private val expiry = HashMap<Pair<Long, Sender>, Job>()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                when (update) {
                    // Действия в темах форумов не показываем: темы — v0.5
                    is TdApi.UpdateChatAction -> if (update.topicId == null) onAction(update)
                    is TdApi.UpdateChatOnlineMemberCount -> _online.update { it + (update.chatId to update.onlineMemberCount) }
                    is TdApi.UpdateAuthorizationState -> if (update.authorizationState is TdApi.AuthorizationStateClosed) {
                        _activities.value = emptyMap()
                        _online.value = emptyMap()
                    }
                }
            }
        }
    }

    private fun onAction(update: TdApi.UpdateChatAction) {
        val sender = when (val s = update.senderId) {
            is TdApi.MessageSenderUser -> Sender.User(s.userId)
            is TdApi.MessageSenderChat -> Sender.Chat(s.chatId)
            else -> return
        }
        val key = update.chatId to sender
        expiry.remove(key)?.cancel()
        val kind = update.action.toKind()
        if (kind == null) {
            remove(update.chatId, sender)
            return
        }
        _activities.update { all -> all + (update.chatId to (all[update.chatId].orEmpty() + (sender to kind))) }
        expiry[key] = scope.launch {
            delay(ACTION_TTL_MS)
            remove(update.chatId, sender)
        }
    }

    private fun remove(chatId: Long, sender: Sender) {
        _activities.update { all ->
            val rest = all[chatId].orEmpty() - sender
            if (rest.isEmpty()) all - chatId else all + (chatId to rest)
        }
    }

    private companion object {
        const val ACTION_TTL_MS = 7_000L
    }
}

/** `null` — отмена или действие, которое не показываем. */
private fun TdApi.ChatAction.toKind(): ActivityKind? = when (this) {
    is TdApi.ChatActionTyping -> ActivityKind.Typing
    is TdApi.ChatActionRecordingVoiceNote -> ActivityKind.RecordingVoice
    is TdApi.ChatActionRecordingVideo, is TdApi.ChatActionRecordingVideoNote -> ActivityKind.RecordingVideo
    is TdApi.ChatActionUploadingPhoto -> ActivityKind.UploadingPhoto
    is TdApi.ChatActionUploadingDocument, is TdApi.ChatActionUploadingVideo, is TdApi.ChatActionUploadingVoiceNote,
    is TdApi.ChatActionUploadingVideoNote -> ActivityKind.UploadingFile
    is TdApi.ChatActionChoosingSticker -> ActivityKind.ChoosingSticker
    else -> null
}
