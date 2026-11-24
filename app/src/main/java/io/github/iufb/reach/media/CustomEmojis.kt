package io.github.iufb.reach.media

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import dagger.hilt.android.EntryPointAccessors
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.chats.toInfo
import io.github.iufb.reach.core.td.TdClient
import io.github.iufb.reach.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Кастомный эмодзи: стикер и нужно ли красить его в цвет текста (`needs_repainting`). */
data class CustomEmoji(val sticker: StickerInfo, val tinted: Boolean)

/**
 * Кастомные эмодзи по `custom_emoji_id` (PRODUCT.md §4.5). Запросы с экрана копятся коротким окном
 * и уходят одним `getCustomEmojiStickers`: в одном тексте их бывают десятки. Результат живёт
 * до конца процесса — стикеры эмодзи не меняются.
 */
@Singleton
class CustomEmojis @Inject constructor(
    private val td: TdClient,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val _loaded = MutableStateFlow<Map<Long, CustomEmoji>>(emptyMap())
    val loaded: StateFlow<Map<Long, CustomEmoji>> = _loaded.asStateFlow()

    private val requested = ConcurrentHashMap.newKeySet<Long>()
    private val queue = Channel<Long>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                val batch = linkedSetOf(queue.receive())
                delay(BATCH_WINDOW_MS)
                while (batch.size < MAX_BATCH) batch += queue.tryReceive().getOrNull() ?: break
                load(batch)
            }
        }
    }

    /** Подгрузить, если ещё не запрашивали; результат придёт в [loaded]. */
    fun request(id: Long) {
        if (requested.add(id)) queue.trySend(id)
    }

    private suspend fun load(ids: Set<Long>) {
        runCatching { td.send(TdApi.GetCustomEmojiStickers(ids.toLongArray())) }
            .onSuccess { result ->
                val found = result.stickers.mapNotNull { sticker ->
                    val type = sticker.fullType as? TdApi.StickerFullTypeCustomEmoji ?: return@mapNotNull null
                    type.customEmojiId to CustomEmoji(sticker.toInfo(), type.needsRepainting)
                }
                _loaded.update { it + found }
            }
            .onFailure {
                Log.w(TAG, "getCustomEmojiStickers failed", it)
                // Пусть следующий показ попробует снова
                requested.removeAll(ids)
            }
    }

    private companion object {
        const val TAG = "CustomEmojis"
        const val BATCH_WINDOW_MS = 30L
        const val MAX_BATCH = 200
    }
}

/**
 * Кастомный эмодзи на всё отведённое место: WebP, TGS или WebM по кругу. Пока не загружен,
 * автозагрузка стикеров выключена или формат не поддержан — [fallback] (обычный эмодзи из текста).
 * Эмодзи с `needs_repainting` красится в текущий цвет текста.
 */
@Composable
internal fun CustomEmojiView(id: Long, fallback: String, modifier: Modifier = Modifier) {
    val emojis = rememberCustomEmojis()
    LaunchedEffect(id) { emojis.request(id) }
    val loaded by emojis.loaded.collectAsState()
    val emoji = loaded[id]
    val allowed = currentAutoDownloadRules().allows(MediaKind.Sticker)
    val color = LocalContentColor.current
    val placeholder = @Composable {
        // Место под эмодзи задаёт текст вокруг; запасной символ — по высоте места
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(fallback, fontSize = (maxHeight.value * FALLBACK_SCALE / LocalDensity.current.fontScale).sp, maxLines = 1)
        }
    }
    Box(modifier.then(if (emoji?.tinted == true) Modifier.tint(color) else Modifier), contentAlignment = Alignment.Center) {
        val sticker = emoji?.sticker
        when {
            sticker == null || !allowed -> placeholder()
            sticker.isWebm -> VideoStickerImage(sticker.file, Modifier.fillMaxSize(), placeholder)
            sticker.isTgs -> TgsImage(sticker.file, Modifier.fillMaxSize(), placeholder = placeholder)
            else -> AsyncImage(sticker.file, fallback, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

/** Перекрашивает непрозрачные пиксели содержимого в [color], сохраняя прозрачность. */
private fun Modifier.tint(color: Color): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        drawRect(color, blendMode = BlendMode.SrcIn)
    }

private const val FALLBACK_SCALE = 0.8f

@Composable
internal fun rememberCustomEmojis(): CustomEmojis {
    val context = LocalContext.current.applicationContext
    return remember(context) { EntryPointAccessors.fromApplication(context, MediaEntryPoint::class.java).customEmojis() }
}
