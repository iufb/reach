package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.PollInfo
import io.github.iufb.reach.chats.PollOptionInfo
import io.github.iufb.reach.files.TdFile
import io.github.iufb.reach.ui.components.Avatar

/**
 * Опрос в пузыре. До голоса — варианты-кнопки (один вариант — голос сразу, несколько — галочки и
 * «Голосовать»); после голоса или закрытия — проценты и полоски. В викторине правильный ответ
 * подсвечивается зелёным, неверный свой — красным, ниже объяснение.
 */
@Composable
internal fun PollView(
    poll: PollInfo,
    onVote: (List<Int>) -> Unit,
    onShowVoters: (PollOptionInfo) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picked by remember(poll.id) { mutableStateOf(emptySet<Int>()) }
    val voting = poll.isVoting && poll.options.none { it.isBeingChosen }
    Column(modifier.widthIn(min = 240.dp).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RichTextView(poll.question, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
        Text(pollTypeLabel(poll), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        poll.options.forEach { option ->
            if (voting) {
                VoteRow(
                    option,
                    multiple = poll.allowsMultipleAnswers,
                    checked = option.id in picked,
                    onClick = {
                        if (poll.allowsMultipleAnswers) {
                            picked = if (option.id in picked) picked - option.id else picked + option.id
                        } else {
                            onVote(listOf(option.id))
                        }
                    },
                )
            } else {
                ResultRow(
                    option,
                    poll,
                    onClick = if (poll.canGetVoters && option.voterCount > 0) ({ onShowVoters(option) }) else null,
                )
            }
        }
        if (voting && poll.allowsMultipleAnswers) {
            TextButton(
                enabled = picked.isNotEmpty(),
                onClick = { onVote(picked.toList()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.poll_vote)) }
        }
        poll.quiz?.explanation?.takeIf { poll.hasVoted && !it.isBlank() }?.let { explanation ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(8.dp),
            ) {
                Text(
                    stringResource(R.string.quiz_explanation),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                RichTextView(explanation, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            if (poll.totalVoterCount > 0) {
                pluralStringResource(R.plurals.poll_votes, poll.totalVoterCount, poll.totalVoterCount)
            } else {
                stringResource(R.string.poll_no_votes)
            },
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun pollTypeLabel(poll: PollInfo): String = stringResource(
    when {
        poll.isClosed -> R.string.poll_closed
        poll.quiz != null && poll.isAnonymous -> R.string.quiz_anonymous
        poll.quiz != null -> R.string.quiz_public
        poll.isAnonymous -> R.string.poll_anonymous
        else -> R.string.poll_public
    },
)

@Composable
private fun VoteRow(option: PollOptionInfo, multiple: Boolean, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiple) Checkbox(checked = checked, onCheckedChange = null) else RadioButton(selected = false, onClick = null)
        Spacer(Modifier.width(8.dp))
        RichTextView(option.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ResultRow(option: PollOptionInfo, poll: PollInfo, onClick: (() -> Unit)?) {
    val correct = poll.quiz?.let { option.id in it.correctOptionIds }
    val barColor = when {
        correct == true -> CORRECT
        correct == false && option.isChosen -> MaterialTheme.colorScheme.error
        option.isChosen -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (poll.canSeeResults) "${option.votePercentage}%" else "",
                modifier = Modifier.width(44.dp),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            RichTextView(option.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            when {
                option.isBeingChosen -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                option.isChosen -> Text(if (correct == false) "✗" else "✓", color = barColor, fontWeight = FontWeight.Bold)
            }
        }
        if (poll.canSeeResults) {
            Box(
                Modifier
                    .padding(start = 44.dp, top = 2.dp)
                    .fillMaxWidth(fraction = (option.votePercentage / 100f).coerceIn(0.02f, 1f))
                    .height(4.dp)
                    .background(barColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

private val CORRECT = Color(0xFF2E7D32)

/** Кто проголосовал за вариант. */
internal data class PollVoters(
    val option: PollOptionInfo,
    val voters: List<PollVoterRow>,
    val totalCount: Int,
    val loading: Boolean,
)

internal data class PollVoterRow(val sender: Sender, val name: String, val photo: TdFile?, val accentColorId: Int)

@Composable
internal fun PollVotersDialog(state: PollVoters, onLoadMore: () -> Unit, onOpen: (Sender) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { RichTextView(state.option.text, style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyColumn {
                items(state.voters, key = { it.sender.toString() }) { voter ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onOpen(voter.sender) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(voter.name, voter.photo, voter.accentColorId, size = 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(voter.name.ifBlank { stringResource(R.string.chat_deleted_account) })
                    }
                }
                if (state.loading) {
                    item { Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else if (state.voters.size < state.totalCount) {
                    item { TextButton(onClick = onLoadMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.poll_voters_more)) } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
