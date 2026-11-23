package io.github.iufb.reach.links

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.files.toTdFile
import io.github.iufb.reach.stickers.StickersRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject

/** Превью чата по ссылке-приглашению перед вступлением. */
data class InvitePreview(
    val link: String,
    val title: String,
    val photo: TdFile?,
    val accentColorId: Int,
    val description: String,
    val memberCount: Int,
    val isChannel: Boolean,
    /** Вместо вступления — заявка админам. */
    val createsJoinRequest: Boolean,
)

/** Куда ведёт ссылка. */
sealed interface LinkEvent {
    data class OpenChat(val chatId: Long, val messageId: Long = 0) : LinkEvent
    /** Не ссылка Telegram или клиент её не поддерживает — открыть во внешнем приложении. */
    data class External(val url: String) : LinkEvent
    data class Message(val text: LinkMessage) : LinkEvent
}

enum class LinkMessage { RequestSent, Invalid }

/**
 * Внутренние ссылки Telegram (PRODUCT.md §4.6): `t.me/+…` / `t.me/joinchat/…` — приглашения,
 * `t.me/username` — публичные чаты, `t.me/username/123` — сообщения, `t.me/addstickers/<name>` —
 * лист пака. Разбор — `getInternalLinkType`, всё остальное открывается снаружи. Общий вход для ссылок из сообщений и из других приложений.
 */
@HiltViewModel
class LinksViewModel @Inject constructor(
    private val td: TdClient,
    private val stickers: StickersRepository,
) : ViewModel() {

    private val _invite = MutableStateFlow<InvitePreview?>(null)
    val invite: StateFlow<InvitePreview?> = _invite.asStateFlow()

    private val _stickerSet = MutableStateFlow<Long?>(null)

    /** Пак по ссылке `t.me/addstickers/…` для листа пака; `null` — лист закрыт. */
    val stickerSet: StateFlow<Long?> = _stickerSet.asStateFlow()

    private val _events = MutableSharedFlow<LinkEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<LinkEvent> = _events.asSharedFlow()

    fun open(url: String) {
        viewModelScope.launch {
            val type = runCatching { td.send(TdApi.GetInternalLinkType(url)) }.getOrNull()
            val event = runCatching { resolve(url, type) }.getOrElse { LinkEvent.External(url) }
            event?.let { _events.emit(it) }
        }
    }

    private suspend fun resolve(url: String, type: TdApi.InternalLinkType?): LinkEvent? = when (type) {
        is TdApi.InternalLinkTypeChatInvite -> {
            val info = td.send(TdApi.CheckChatInviteLink(type.inviteLink))
            // Уже участник (или чат доступен для просмотра) — сразу в чат
            if (info.chatId != 0L && info.accessibleFor == 0) {
                LinkEvent.OpenChat(info.chatId)
            } else {
                _invite.value = InvitePreview(
                    link = type.inviteLink,
                    title = info.title,
                    photo = info.photo?.small?.toTdFile(),
                    accentColorId = info.accentColorId,
                    description = info.description,
                    memberCount = info.memberCount,
                    isChannel = info.type is TdApi.InviteLinkChatTypeChannel,
                    createsJoinRequest = info.createsJoinRequest,
                )
                null
            }
        }
        is TdApi.InternalLinkTypePublicChat -> LinkEvent.OpenChat(td.send(TdApi.SearchPublicChat(type.chatUsername)).id)
        is TdApi.InternalLinkTypeMessage -> {
            val info = td.send(TdApi.GetMessageLinkInfo(type.url))
            if (info.chatId == 0L) LinkEvent.Message(LinkMessage.Invalid) else LinkEvent.OpenChat(info.chatId, info.message?.id ?: 0)
        }
        is TdApi.InternalLinkTypeStickerSet -> {
            val set = stickers.setByName(type.stickerSetName)
            if (set == null) {
                LinkEvent.Message(LinkMessage.Invalid)
            } else {
                _stickerSet.value = set.id
                null
            }
        }
        else -> LinkEvent.External(url)
    }

    fun dismissStickerSet() {
        _stickerSet.value = null
    }

    fun join() {
        val preview = _invite.value ?: return
        _invite.value = null
        viewModelScope.launch {
            runCatching { td.send(TdApi.JoinChatByInviteLink(preview.link)) }
                .onSuccess { result ->
                    _events.emit(
                        when (result) {
                            is TdApi.ChatJoinResultSuccess -> LinkEvent.OpenChat(result.chatId)
                            // Заявка админам (или проверка ботом-стражем) — ждём одобрения
                            is TdApi.ChatJoinResultRequestSent, is TdApi.ChatJoinResultGuardBotApprovalRequired ->
                                LinkEvent.Message(LinkMessage.RequestSent)
                            else -> LinkEvent.Message(LinkMessage.Invalid)
                        },
                    )
                }
                .onFailure { _events.emit(LinkEvent.Message(LinkMessage.Invalid)) }
        }
    }

    fun dismissInvite() {
        _invite.value = null
    }

    companion object {
        private val TELEGRAM_HOSTS = setOf("t.me", "telegram.me", "telegram.dog")

        /** Ссылка, которую разбирает клиент, а не браузер. */
        fun isTelegramLink(url: String): Boolean {
            val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
            return uri.scheme == "tg" || (uri.scheme in setOf("http", "https") && uri.host?.lowercase()?.removePrefix("www.") in TELEGRAM_HOSTS)
        }
    }
}
