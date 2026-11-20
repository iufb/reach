package io.github.iufb.reach.chats

import org.drinkless.tdlib.TdApi

/** Опрос или викторина (PRODUCT.md §4.6). */
data class PollInfo(
    val id: Long,
    val question: RichText,
    /** В порядке показа (`option_order`); [PollOptionInfo.id] — номер для `setPollAnswer`. */
    val options: List<PollOptionInfo>,
    val totalVoterCount: Int,
    val isAnonymous: Boolean,
    val allowsMultipleAnswers: Boolean,
    val isClosed: Boolean,
    /** Результаты видны (после голоса, закрытия или у автора). */
    val canSeeResults: Boolean,
    /** Можно посмотреть, кто голосовал (`getPollVoters`). */
    val canGetVoters: Boolean,
    /** Голосовать можно: опрос открыт и нет ограничений (страна, членство, ещё не отправлен). */
    val canVote: Boolean,
    /** Голос можно поменять или отменить. */
    val allowsRevoting: Boolean,
    /** `null` — обычный опрос. */
    val quiz: Quiz? = null,
) {
    val hasVoted: Boolean get() = options.any { it.isChosen }

    /** Варианты-кнопки: ещё не голосовали и можно. */
    val isVoting: Boolean get() = canVote && !hasVoted

    /** «Отменить голос» в меню: обычный опрос, голос есть, опрос открыт. */
    val canRetract: Boolean get() = hasVoted && !isClosed && quiz == null && allowsRevoting
}

data class Quiz(val correctOptionIds: Set<Int>, val explanation: RichText)

data class PollOptionInfo(
    val id: Int,
    val text: RichText,
    val voterCount: Int,
    val votePercentage: Int,
    val isChosen: Boolean,
    /** Голос отправляется. */
    val isBeingChosen: Boolean,
)

internal fun TdApi.Poll.toInfo(): PollInfo {
    val all = options.mapIndexed { i, o ->
        PollOptionInfo(i, o.text.toRichText(), o.voterCount, o.votePercentage, o.isChosen, o.isBeingChosen)
    }
    val ordered = optionOrder?.takeIf { it.size == all.size }?.mapNotNull { all.getOrNull(it) } ?: all
    return PollInfo(
        id = id,
        question = question.toRichText(),
        options = ordered,
        totalVoterCount = totalVoterCount,
        isAnonymous = isAnonymous,
        allowsMultipleAnswers = allowsMultipleAnswers,
        isClosed = isClosed,
        canSeeResults = canSeeResults || isClosed,
        canGetVoters = canGetVoters,
        canVote = !isClosed && voteRestrictionReason == null,
        allowsRevoting = allowsRevoting,
        quiz = (type as? TdApi.PollTypeQuiz)?.let { Quiz(it.correctOptionIds.toSet(), it.explanation.toRichText()) },
    )
}
