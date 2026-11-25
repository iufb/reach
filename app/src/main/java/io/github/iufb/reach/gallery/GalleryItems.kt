package io.github.iufb.reach.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.AudioRow
import io.github.iufb.reach.chat.DocumentRow
import io.github.iufb.reach.chat.Message
import io.github.iufb.reach.chat.PhotoThumbnail
import io.github.iufb.reach.chat.VideoThumbnail
import io.github.iufb.reach.chat.ViewerItem
import io.github.iufb.reach.chat.VoiceNoteView
import io.github.iufb.reach.chats.DocumentInfo
import io.github.iufb.reach.chats.MessageBody
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.files.FileState
import io.github.iufb.reach.media.AudioState
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.DelayedSkeleton
import io.github.iufb.reach.ui.components.GroupedCorner
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.skeletonFraction
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Что делать с элементом вкладки: открыть медиа в просмотрщике, файл, голосовое, показать в чате. */
class GalleryActions(
    val onOpenMedia: (items: List<ViewerItem>, index: Int) -> Unit,
    val onDocumentClick: (DocumentInfo) -> Unit,
    val onAudioToggle: (Message) -> Unit,
    val onAudioSeek: (Float) -> Unit,
    val onShowInChat: (Message) -> Unit,
)

/**
 * Содержимое вкладки галереи внутри сетки профиля (3 колонки): медиа и GIF — плиткой во всю ширину
 * экрана, файлы, ссылки, голосовые и музыка — карточкой сгруппированного списка.
 */
fun LazyGridScope.galleryItems(
    tab: GalleryTab,
    page: TabPage,
    downloads: Map<Int, FileState>,
    audio: AudioState,
    actions: GalleryActions,
) {
    when (tab) {
        GalleryTab.Media, GalleryTab.Gifs -> {
            val media = page.messages.mapNotNull { m -> m.viewerItem()?.let { m to it } }
            val viewerItems = media.map { it.second }
            items(media, key = { "media:${it.first.id}" }, contentType = { "media" }) { (message, item) ->
                WithShowInChat(
                    onClick = { actions.onOpenMedia(viewerItems, viewerItems.indexOf(item)) },
                    onShowInChat = { actions.onShowInChat(message) },
                ) { modifier ->
                    Box(modifier.fillMaxWidth().aspectRatio(1f).background(ReachTheme.colors.fill)) {
                        when (item) {
                            is ViewerItem.Photo -> PhotoThumbnail(item.photo, square = true)
                            is ViewerItem.Video -> VideoThumbnail(item.video, square = true)
                            is ViewerItem.Animation -> PhotoThumbnail(item.animation.poster, square = true)
                        }
                    }
                }
            }
        }
        else -> itemsIndexed(
            page.messages,
            key = { _, message -> "row:${message.id}" },
            span = { _, _ -> GridItemSpan(maxLineSpan) },
            contentType = { _, _ -> "row" },
        ) { index, message ->
            MessageRow(message, first = index == 0, last = index == page.messages.lastIndex, downloads, audio, actions)
        }
    }
    if (page.loading) {
        val empty = page.messages.isEmpty()
        when (tab) {
            // Плитки той же сетки: пустая вкладка — несколько рядов, подгрузка — один ряд
            GalleryTab.Media, GalleryTab.Gifs -> items(
                if (empty) SKELETON_MEDIA_EMPTY else SKELETON_MEDIA_MORE,
                key = { "loading:$it" },
                contentType = { "skeletonMedia" },
            ) {
                DelayedSkeleton { SkeletonBox(Modifier.fillMaxWidth().aspectRatio(1f), RectangleShape) }
            }
            else -> {
                val count = if (empty) SKELETON_ROWS_EMPTY else SKELETON_ROWS_MORE
                items(
                    count,
                    key = { "loading:$it" },
                    span = { GridItemSpan(maxLineSpan) },
                    contentType = { "skeletonRow" },
                ) { index ->
                    // Подгрузка продолжает карточку: верхние углы скруглены, только если вкладка пуста
                    DelayedSkeleton { SkeletonMessageRow(index, first = empty && index == 0, last = index == count - 1) }
                }
            }
        }
    }
}

/** Скелетон [MessageRow]: та же карточка, значок файла и две полоски. */
@Composable
internal fun SkeletonMessageRow(index: Int, first: Boolean, last: Boolean) {
    val shape = RoundedCornerShape(
        topStart = if (first) GroupedCorner else 0.dp,
        topEnd = if (first) GroupedCorner else 0.dp,
        bottomStart = if (last) GroupedCorner else 0.dp,
        bottomEnd = if (last) GroupedCorner else 0.dp,
    )
    Box(Modifier.padding(horizontal = 16.dp).padding(top = if (first) 8.dp else 0.dp).clip(shape).background(ReachTheme.colors.cell)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(44.dp), RoundedCornerShape(10.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index, 0.4f, 0.75f)).height(12.dp))
                SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index + 3, 0.2f, 0.35f)).height(10.dp))
            }
        }
    }
}

private const val SKELETON_MEDIA_EMPTY = 15
private const val SKELETON_MEDIA_MORE = 3
private const val SKELETON_ROWS_EMPTY = 6
private const val SKELETON_ROWS_MORE = 2

/** Строка файла, ссылки, голосового или трека; первая и последняя скругляют карточку. */
@Composable
private fun MessageRow(
    message: Message,
    first: Boolean,
    last: Boolean,
    downloads: Map<Int, FileState>,
    audio: AudioState,
    actions: GalleryActions,
) {
    val colors = ReachTheme.colors
    val shape = RoundedCornerShape(
        topStart = if (first) GroupedCorner else 0.dp,
        topEnd = if (first) GroupedCorner else 0.dp,
        bottomStart = if (last) GroupedCorner else 0.dp,
        bottomEnd = if (last) GroupedCorner else 0.dp,
    )
    val uriHandler = LocalUriHandler.current
    Box(Modifier.padding(horizontal = 16.dp).padding(top = if (first) 8.dp else 0.dp).clip(shape).background(colors.cell)) {
        WithShowInChat(
            onClick = {
                when (val body = message.content) {
                    is MessageBody.Document -> actions.onDocumentClick(body.document)
                    is MessageBody.Voice, is MessageBody.Audio -> actions.onAudioToggle(message)
                    // Вкладка «Ссылки»: тап открывает первую ссылку, долгий тап — «Показать в чате»
                    is MessageBody.Text -> firstUrl(body.text.text)?.let { runCatching { uriHandler.openUri(it) } } ?: actions.onShowInChat(message)
                    else -> actions.onShowInChat(message)
                }
            },
            onShowInChat = { actions.onShowInChat(message) },
        ) { modifier ->
            // Строки разделяет промежуток сетки — он и есть разделитель
            Column(modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when (val body = message.content) {
                        is MessageBody.Document -> DocumentRow(body.document, downloads[body.document.file.id])
                        is MessageBody.Voice -> VoiceNoteView(
                            body.voice,
                            isListened = true,
                            playback = audio.takeIf { it.track?.messageId == message.id },
                            onToggle = { actions.onAudioToggle(message) },
                            onSeek = actions.onAudioSeek,
                        )
                        is MessageBody.Audio -> AudioRow(
                            body.audio,
                            playback = audio.takeIf { it.track?.messageId == message.id },
                            onToggle = { actions.onAudioToggle(message) },
                            onSeek = actions.onAudioSeek,
                        )
                        // Простой текст, а не RichTextView: ссылки в нём перехватывали бы тап и долгий тап всей строки
                        is MessageBody.Text -> Text(body.text.text, color = colors.label, maxLines = LINK_PREVIEW_LINES, overflow = TextOverflow.Ellipsis)
                        else -> Text(bodySummary(body), color = colors.label)
                    }
                }
            }
        }
    }
}

/** Элемент с тапом; долгий тап — лист «Показать в чате». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WithShowInChat(onClick: () -> Unit, onShowInChat: () -> Unit, content: @Composable (Modifier) -> Unit) {
    var sheet by remember { mutableStateOf(false) }
    content(Modifier.combinedClickable(onClick = onClick, onLongClick = { sheet = true }))
    if (sheet) {
        ActionSheet(
            actions = listOf(SheetAction(stringResource(R.string.gallery_show_in_chat), onClick = onShowInChat)),
            onDismiss = { sheet = false },
        )
    }
}

/** Догружает вкладку, когда до конца сетки осталось меньше [LOAD_AHEAD] элементов. */
@Composable
fun GalleryLoadMoreEffect(state: LazyGridState, page: TabPage?, onLoadMore: () -> Unit) {
    if (page == null) return
    LaunchedEffect(state, page.messages.size, page.reachedEnd) {
        if (page.reachedEnd) return@LaunchedEffect
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.layoutInfo.totalItemsCount - LOAD_AHEAD }
            .distinctUntilChanged().filter { it }.collect { onLoadMore() }
    }
}

private fun Message.viewerItem(): ViewerItem? = when (val body = content) {
    is MessageBody.Photo -> ViewerItem.Photo(body.photo)
    is MessageBody.Video -> ViewerItem.Video(body.video)
    is MessageBody.Animation -> ViewerItem.Animation(body.animation)
    else -> null
}

private const val LOAD_AHEAD = 12
private const val LINK_PREVIEW_LINES = 3
private val URL = Regex("""(?i)\b((https?://|www\.|t\.me/)[^\s]+)""")

/** Первая ссылка в тексте; без схемы — дописываем https. */
private fun firstUrl(text: String): String? =
    URL.find(text)?.value?.let { if (it.startsWith("http", ignoreCase = true)) it else "https://$it" }
