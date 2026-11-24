package io.github.iufb.reach.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.TextInputSheet
import io.github.iufb.reach.ui.components.skeletonFraction
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Отложенные сообщения в стиле Telegram iOS: карточки с временем отправки и текстом; тап — лист
 * «Отправить сейчас / Перенести / Изменить / Удалить». Перенос — шторка с барабанами.
 */
@Composable
fun ScheduledScreen(onBack: () -> Unit, viewModel: ScheduledViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var acting by remember { mutableStateOf<ScheduledMessage?>(null) }
    var rescheduling by remember { mutableStateOf<ScheduledMessage?>(null) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        error?.let {
            viewModel.dismissError()
            snackbar.showSnackbar(it)
        }
    }
    val colors = ReachTheme.colors
    val list = messages
    ListScreen(
        title = stringResource(R.string.scheduled_title),
        onBack = onBack,
        snackbar = snackbar,
        loading = list == null,
        skeleton = {
            items(SKELETON_CARDS, contentType = { "skeleton" }) { index ->
                GroupedSection {
                    // Как ScheduledItem: строка даты акцентом, под ней текст
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                            SkeletonBox(Modifier.width(140.dp).height(10.dp))
                        }
                        Box(Modifier.height(22.dp), contentAlignment = Alignment.CenterStart) {
                            SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index, 0.45f, 0.9f)).height(14.dp))
                        }
                    }
                }
            }
        },
    ) {
        if (list.isNullOrEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.scheduled_empty),
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = colors.secondaryLabel,
                )
            }
        } else {
            items(list, key = { it.message.id }) { item ->
                GroupedSection { ScheduledItem(item) { acting = item } }
            }
        }
    }
    acting?.let { item ->
        ActionSheet(
            title = scheduleLabel(item.schedule),
            actions = buildList {
                add(SheetAction(stringResource(R.string.scheduled_send_now)) { viewModel.sendNow(item) })
                add(SheetAction(stringResource(R.string.scheduled_reschedule)) { rescheduling = item })
                if (item.message.content.editableText() != null) add(SheetAction(stringResource(R.string.message_action_edit)) { editing = item })
                add(SheetAction(stringResource(R.string.message_action_delete), destructive = true) { viewModel.delete(item) })
            },
            onDismiss = { acting = null },
        )
    }
    rescheduling?.let { item ->
        ScheduleDialog(
            initial = (item.schedule as? Schedule.At)?.date,
            onPick = { date ->
                rescheduling = null
                viewModel.reschedule(item, date)
            },
            onDismiss = { rescheduling = null },
        )
    }
    editing?.let { item ->
        val original = item.message.content.editableText()
        if (original == null) {
            editing = null
        } else {
            TextInputSheet(
                title = stringResource(R.string.message_action_edit),
                initial = original.text,
                placeholder = stringResource(R.string.chat_input_placeholder),
                singleLine = false,
                onSave = { text ->
                    editing = null
                    viewModel.editText(item, original, text)
                },
                onDismiss = { editing = null },
            )
        }
    }
}

@Composable
private fun ScheduledItem(item: ScheduledMessage, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(scheduleLabel(item.schedule), style = MaterialTheme.typography.bodySmall, color = colors.accent)
        when (val body = item.message.content) {
            is MessageBody.Text -> RichTextView(body.text)
            else -> Text(bodySummary(body), style = MaterialTheme.typography.bodyLarge, color = colors.label)
        }
    }
}

private const val SKELETON_CARDS = 4

@Composable
private fun scheduleLabel(schedule: Schedule): String = when (schedule) {
    Schedule.WhenOnline -> stringResource(R.string.send_when_online)
    is Schedule.At -> stringResource(
        R.string.scheduled_at,
        Dates.dateTime(schedule.date.toLong()),
    )
}
