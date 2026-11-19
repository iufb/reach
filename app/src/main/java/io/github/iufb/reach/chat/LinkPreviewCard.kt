package io.github.iufb.reach.chat

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.LinkPreview

/** Карточка ссылки под текстом сообщения; тап открывает ссылку. */
@Composable
internal fun LinkPreviewCard(preview: LinkPreview, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(accent.copy(alpha = 0.08f))
            .clickable {
                runCatching { uriHandler.openUri(preview.url) }.onFailure { Log.w("LinkPreviewCard", "can't open ${preview.url}", it) }
            }
            .height(IntrinsicSize.Min),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
        PreviewBody(preview, Modifier.padding(8.dp))
    }
}

@Composable
private fun PreviewBody(preview: LinkPreview, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (preview.siteName.isNotBlank()) {
            Text(
                preview.siteName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (preview.title.isNotBlank()) {
            Text(
                preview.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!preview.description.isBlank()) {
            Text(
                preview.description.text,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        preview.photo?.let { photo ->
            AsyncImage(
                model = photo,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .height(160.dp)
                    .clip(RoundedCornerShape(6.dp)),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Превью ссылки из набранного текста над полем ввода; ✕ — отправить без превью. */
@Composable
internal fun ComposerLinkPreview(preview: LinkPreview, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_link), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                preview.siteName.ifBlank { preview.url },
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                preview.title.ifBlank { preview.description.text },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.link_preview_remove))
        }
    }
}
