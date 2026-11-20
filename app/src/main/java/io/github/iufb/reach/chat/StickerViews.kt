package io.github.iufb.reach.chat

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.StickerInfo

/**
 * Стикер без пузыря, с прозрачностью. Сначала миниатюра, поверх — сам WebP.
 * Анимированные и видеостикеры (до v0.6) — миниатюра, а без неё — крупный эмодзи.
 */
@Composable
internal fun StickerView(sticker: StickerInfo, modifier: Modifier = Modifier) {
    val ratio = if (sticker.width > 0 && sticker.height > 0) sticker.width.toFloat() / sticker.height else 1f
    Box(
        modifier
            .sizeIn(maxWidth = STICKER_SIZE, maxHeight = STICKER_SIZE)
            .aspectRatio(ratio.coerceIn(0.5f, 2f)),
        contentAlignment = Alignment.Center,
    ) {
        val main = rememberAsyncImagePainter(if (sticker.isStatic) sticker.file else null)
        val state by main.state.collectAsState()
        val loaded = sticker.isStatic && state is AsyncImagePainter.State.Success
        if (!loaded) {
            if (sticker.thumbnail != null) {
                AsyncImage(sticker.thumbnail, sticker.emoji, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            } else {
                Text(sticker.emoji, style = MaterialTheme.typography.displayMedium)
            }
        }
        if (sticker.isStatic) Image(main, sticker.emoji, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

/** Название пака по тапу на стикер. */
@Composable
internal fun StickerSetDialog(title: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sticker_set)) },
        text = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}

private val STICKER_SIZE = 160.dp
