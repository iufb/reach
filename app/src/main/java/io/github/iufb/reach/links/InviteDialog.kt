package io.github.iufb.reach.links

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.Avatar

/** Превью чата по приглашению: вступить, отправить заявку или закрыть. */
@Composable
fun InviteDialog(preview: InvitePreview, onJoin: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Avatar(preview.title, preview.photo, preview.accentColorId, size = 72.dp)
                Text(preview.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (preview.memberCount > 0) {
                    Text(
                        pluralStringResource(
                            if (preview.isChannel) R.plurals.subscribers else R.plurals.members,
                            preview.memberCount,
                            preview.memberCount,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.description.isNotBlank()) {
                    Text(preview.description, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onJoin) {
                Text(
                    stringResource(
                        when {
                            preview.createsJoinRequest -> R.string.invite_request
                            preview.isChannel -> R.string.invite_join_channel
                            else -> R.string.invite_join_group
                        },
                    ),
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
