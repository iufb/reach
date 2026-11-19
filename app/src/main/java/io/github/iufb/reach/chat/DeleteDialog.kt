package io.github.iufb.reach.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R

/**
 * Запрос на удаление: какие сообщения и как их можно удалить.
 * Удалить у всех и только у себя — разные права (`canBeDeletedForAllUsers` / `canBeDeletedOnlyForSelf`).
 */
data class DeleteRequest(
    val messageIds: List<Long>,
    /** Можно удалить у всех (revoke). */
    val canForAll: Boolean,
    /** Можно удалить только у себя. Если нельзя, удаление идёт сразу у всех. */
    val canForSelf: Boolean,
)

/** Подтверждение удаления; галочка «у всех» — только когда есть выбор. */
@Composable
internal fun DeleteDialog(request: DeleteRequest, onConfirm: (revoke: Boolean) -> Unit, onDismiss: () -> Unit) {
    val choice = request.canForAll && request.canForSelf
    var forAll by rememberSaveable(request) { mutableStateOf(true) }
    val count = request.messageIds.size
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.delete_title, count, count)) },
        text = if (choice) {
            {
                Row(Modifier.clickable { forAll = !forAll }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = forAll, onCheckedChange = { forAll = it })
                    Text(stringResource(R.string.delete_for_all), style = MaterialTheme.typography.bodyLarge)
                }
            }
        } else {
            null
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(if (choice) forAll else request.canForAll) }) {
                Text(stringResource(R.string.delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
