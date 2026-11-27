package io.github.iufb.reach.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.DocumentInfo
import io.github.iufb.reach.files.FileState
import java.io.File

/**
 * Документ в пузыре: кружок с действием (скачать / отменить / открыть), имя и размер.
 * [state] — состояние загрузки в этом экране; `null` — как пришло в сообщении.
 */
@Composable
internal fun DocumentRow(document: DocumentInfo, state: FileState?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val downloaded = state?.isDownloaded ?: document.downloaded
    val downloading = state != null && !state.isDownloaded
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).background(bubbleStyle().accent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            val (icon, description) = when {
                downloaded -> R.drawable.ic_document to R.string.document_open
                downloading -> R.drawable.ic_close to R.string.document_cancel
                else -> R.drawable.ic_download to R.string.document_download
            }
            if (downloading && state != null) {
                val progress = if (state.size > 0) state.downloadedSize.toFloat() / state.size else 0f
                ProgressRing(progress, bubbleStyle().onAccent, Modifier.size(40.dp))
            }
            Icon(painterResource(icon), stringResource(description), tint = bubbleStyle().onAccent)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                document.fileName.ifBlank { stringResource(R.string.content_document) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val total = state?.size?.takeIf { it > 0 } ?: document.size
            Text(
                if (downloading && state != null) {
                    stringResource(R.string.document_progress, fileSize(context, state.downloadedSize), fileSize(context, total))
                } else {
                    fileSize(context, total)
                },
                style = MaterialTheme.typography.labelMedium,
                color = bubbleStyle().secondary,
            )
        }
    }
}

private fun fileSize(context: Context, bytes: Long): String =
    if (bytes > 0) Formatter.formatShortFileSize(context, bytes) else ""

/** Открывает скачанный файл во внешнем приложении через FileProvider. */
internal fun openFile(context: Context, file: OpenFile) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", File(file.path))
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, file.mimeType.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }.onFailure {
        Log.w("DocumentViews", "can't open ${file.path}", it)
        if (it is ActivityNotFoundException || it is IllegalArgumentException) {
            Toast.makeText(context, R.string.document_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
