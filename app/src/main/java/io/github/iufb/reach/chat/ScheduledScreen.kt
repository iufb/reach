package io.github.iufb.reach.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.reachBarColors

/** Отложенные сообщения чата: отправить сейчас, перенести, изменить, удалить. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledScreen(onBack: () -> Unit, viewModel: ScheduledViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var rescheduling by remember { mutableStateOf<ScheduledMessage?>(null) }
    var editing by remember { mutableStateOf<ScheduledMessage?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.scheduled_title)) },
            )
        },
    ) { padding ->
        val list = messages
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.scheduled_empty),
                    modifier = Modifier.padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(list, key = { it.message.id }) { item ->
                    ScheduledItem(
                        item,
                        onSendNow = { viewModel.sendNow(item) },
                        onReschedule = { rescheduling = item },
                        onEdit = { editing = item }.takeIf { item.message.content.editableText() != null },
                        onDelete = { viewModel.delete(item) },
                    )
                }
            }
        }
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
            EditTextDialog(
                initial = original.text,
                onSave = { text ->
                    editing = null
                    viewModel.editText(item, original, text)
                },
                onDismiss = { editing = null },
            )
        }
    }
    error?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::dismissError,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = viewModel::dismissError) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@Composable
private fun ScheduledItem(
    item: ScheduledMessage,
    onSendNow: () -> Unit,
    onReschedule: () -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .clickable { menu = true }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                scheduleLabel(item.schedule),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            when (val body = item.message.content) {
                is MessageBody.Text -> RichTextView(body.text)
                else -> Text(bodySummary(body), style = MaterialTheme.typography.bodyLarge)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.scheduled_send_now)) }, onClick = { menu = false; onSendNow() })
            DropdownMenuItem(text = { Text(stringResource(R.string.scheduled_reschedule)) }, onClick = { menu = false; onReschedule() })
            onEdit?.let { edit ->
                DropdownMenuItem(text = { Text(stringResource(R.string.message_action_edit)) }, onClick = { menu = false; edit() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.message_action_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}

@Composable
private fun scheduleLabel(schedule: Schedule): String = when (schedule) {
    Schedule.WhenOnline -> stringResource(R.string.send_when_online)
    is Schedule.At -> stringResource(
        R.string.scheduled_at,
        Dates.dateTime(schedule.date.toLong()),
    )
}

@Composable
private fun EditTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.message_action_edit)) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, maxLines = 8) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

