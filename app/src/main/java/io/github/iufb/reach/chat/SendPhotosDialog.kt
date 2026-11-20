package io.github.iufb.reach.chat

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.iufb.reach.R

/** Выбранные фото перед отправкой: превью и подпись. */
@Composable
internal fun SendPhotosDialog(uris: List<Uri>, onSend: (caption: String, silent: Boolean, spoiler: Boolean) -> Unit, onDismiss: () -> Unit) {
    var caption by rememberSaveable { mutableStateOf("") }
    var silent by rememberSaveable { mutableStateOf(false) }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.send_photos_title, uris.size, uris.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(uris, key = { it.toString() }) { uri ->
                        AsyncImage(
                            uri,
                            contentDescription = null,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.photo_caption_hint)) },
                    maxLines = 4,
                )
                CheckRow(stringResource(R.string.send_with_spoiler), spoiler) { spoiler = it }
                CheckRow(stringResource(R.string.send_silent), silent) { silent = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSend(caption, silent, spoiler) }) { Text(stringResource(R.string.chat_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
