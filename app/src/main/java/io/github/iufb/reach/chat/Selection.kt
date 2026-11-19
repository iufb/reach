package io.github.iufb.reach.chat

import android.content.ClipData
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.reachBarColors
import kotlinx.coroutines.launch

/** Что можно сделать со всеми выбранными сразу: действие доступно, только если подходит каждому. */
data class SelectionActions(
    val messageIds: List<Long>,
    /** Тексты выбранных подряд, от старых к новым; `null` — хотя бы одно не скопировать. */
    val copyText: String?,
    val canForward: Boolean,
    /** `null` — хотя бы одно нельзя удалить. */
    val delete: DeleteRequest?,
)

/**
 * [selected] — выбранные id и их права; права `null` — ещё загружаются, тогда действия недоступны.
 * Сообщения, пропавшие из ленты (удалены), в расчёт не идут.
 */
internal fun selectionActions(selected: Map<Long, MessageActions?>, messages: List<Message>): SelectionActions {
    val chosen = messages.filter { it.id in selected }.sortedBy { it.id }
    val actions = chosen.map { selected[it.id] }
    val ready = chosen.isNotEmpty() && actions.all { it != null }
    val all = actions.filterNotNull()
    return SelectionActions(
        messageIds = chosen.map { it.id },
        copyText = if (ready && all.all { it.copyText != null }) all.joinToString("\n\n") { it.copyText.orEmpty() } else null,
        canForward = ready && all.all { it.canForward },
        delete = if (ready && all.all { it.canDelete }) {
            DeleteRequest(
                chosen.map { it.id },
                canForAll = all.all { it.canDeleteForAll },
                // Если хоть одно удаляется только у всех — выбора «только у себя» нет
                canForSelf = all.all { it.canDeleteForSelf },
            )
        } else {
            null
        },
    )
}

/** Шапка в режиме выбора: число выбранных и общие действия. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionBar(
    actions: SelectionActions,
    onClose: () -> Unit,
    onForward: (List<Long>) -> Unit,
    onDelete: (DeleteRequest) -> Unit,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    TopAppBar(
        colors = reachBarColors(),
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.selection_close))
            }
        },
        title = { Text(actions.messageIds.size.toString(), style = MaterialTheme.typography.titleMedium) },
        actions = {
            val text = actions.copyText
            IconButton(
                enabled = text != null,
                onClick = {
                    if (text != null) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
                    onClose()
                },
            ) {
                Icon(painterResource(R.drawable.ic_copy), contentDescription = stringResource(R.string.message_action_copy))
            }
            IconButton(enabled = actions.canForward, onClick = { onForward(actions.messageIds) }) {
                Icon(painterResource(R.drawable.ic_forward), contentDescription = stringResource(R.string.message_action_forward))
            }
            val delete = actions.delete
            IconButton(enabled = delete != null, onClick = { if (delete != null) onDelete(delete) }) {
                Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.message_action_delete))
            }
        },
    )
}
