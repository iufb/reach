package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import io.github.iufb.reach.stickers.StickerImage

/**
 * Стикер в ленте: без пузыря, не крупнее [STICKER_SIZE]. Файл качается по правилам автозагрузки,
 * иначе — миниатюра со значком загрузки. Анимации играют, пока стикер в ленте (ушедшие с экрана
 * элементы LazyColumn не рисуются).
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
        val rules = currentAutoDownloadRules()
        var requested by remember(sticker.file) { mutableStateOf(false) }
        val allowed = requested || rules.allows(MediaKind.Sticker)
        StickerImage(sticker, Modifier.fillMaxSize(), load = allowed)
        if (!allowed) DownloadBadge(0, onClick = { requested = true })
    }
}

private val STICKER_SIZE = 160.dp
