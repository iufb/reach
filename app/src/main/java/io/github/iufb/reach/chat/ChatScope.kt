package io.github.iufb.reach.chat

import org.drinkless.tdlib.TdApi

/**
 * Какая часть чата открыта на экране: весь чат, тред комментариев к посту канала
 * (в группе обсуждения) или топик форума. История, отправка, черновик и «прочитано» —
 * в пределах этой части (PRODUCT.md §4.6).
 */
sealed interface ChatScope {
    data object Whole : ChatScope

    /** Комментарии: [threadId] — id пересланного поста в группе обсуждения. */
    data class Thread(val threadId: Long) : ChatScope

    data class Topic(val topicId: Int) : ChatScope

    fun toTd(): TdApi.MessageTopic? = when (this) {
        Whole -> null
        is Thread -> TdApi.MessageTopicThread(threadId)
        is Topic -> TdApi.MessageTopicForum(topicId)
    }

    /** Сообщение из апдейта относится к этой части. */
    fun contains(message: TdApi.Message): Boolean = when (this) {
        Whole -> true
        is Thread -> (message.topicId as? TdApi.MessageTopicThread)?.messageThreadId == threadId
        is Topic -> (message.topicId as? TdApi.MessageTopicForum)?.forumTopicId == topicId
    }

    /** Откуда просмотрены сообщения — для `viewMessages`. */
    fun viewSource(): TdApi.MessageSource = when (this) {
        Whole -> TdApi.MessageSourceChatHistory()
        is Thread -> TdApi.MessageSourceMessageThreadHistory()
        is Topic -> TdApi.MessageSourceForumTopicHistory()
    }

    companion object {
        fun of(threadId: Long, topicId: Int): ChatScope = when {
            threadId != 0L -> Thread(threadId)
            topicId != 0 -> Topic(topicId)
            else -> Whole
        }
    }
}
