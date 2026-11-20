package io.github.iufb.reach.chat

import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/** Реакции, доступные на сообщении: первые — в ряд меню, [all] — по кнопке «ещё». */
data class AvailableReactions(val top: List<ReactionKey>, val all: List<ReactionKey>) {
    val isEmpty: Boolean get() = all.isEmpty()

    companion object {
        val None = AvailableReactions(emptyList(), emptyList())
    }
}

/**
 * Реакции (PRODUCT.md §4.3). Реакцию по умолчанию (для двойного тапа) TDLib присылает один раз
 * при старте `UpdateDefaultReactionType`, поэтому репозиторий подписывается заранее (см. ReachApp).
 */
@Singleton
class ReactionsRepository @Inject constructor(
    private val td: TdClient,
    @ApplicationScope scope: CoroutineScope,
) {
    private val _default = MutableStateFlow<ReactionKey?>(null)

    /** Реакция по двойному тапу; `null` — TDLib ещё не сообщил. */
    val default: StateFlow<ReactionKey?> = _default.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            td.updates.collect { update ->
                if (update is TdApi.UpdateDefaultReactionType) _default.value = update.reactionType.toKey()
            }
        }
    }

    /** Что можно поставить на сообщение с учётом настроек чата; премиум-реакции отбрасываются. */
    suspend fun available(chatId: Long, messageId: Long): AvailableReactions {
        val result = td.send(TdApi.GetMessageAvailableReactions(chatId, messageId, TOP_ROW))
        if (result.unavailabilityReason != null) return AvailableReactions.None
        fun Array<TdApi.AvailableReaction>.keys() = filter { !it.needsPremium }.map { it.type.toKey() }
        val top = result.topReactions.keys()
        val all = (top + result.recentReactions.keys() + result.popularReactions.keys()).distinct()
        return AvailableReactions(top.take(TOP_ROW), all)
    }

    suspend fun add(chatId: Long, messageId: Long, key: ReactionKey) {
        val type = key.toType() ?: return
        td.send(TdApi.AddMessageReaction(chatId, messageId, type, false, true))
    }

    suspend fun remove(chatId: Long, messageId: Long, key: ReactionKey) {
        val type = key.toType() ?: return
        td.send(TdApi.RemoveMessageReaction(chatId, messageId, type))
    }

    private companion object {
        const val TOP_ROW = 7
    }
}

/** Платные реакции ставятся другим запросом — клиент их пока не ставит. */
internal fun ReactionKey.toType(): TdApi.ReactionType? = when (this) {
    is ReactionKey.Emoji -> TdApi.ReactionTypeEmoji(emoji)
    is ReactionKey.Custom -> TdApi.ReactionTypeCustomEmoji(customEmojiId)
    ReactionKey.Paid -> null
}
