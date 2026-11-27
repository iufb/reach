package io.github.iufb.reach.chat

import androidx.compose.animation.core.Animatable
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.iufb.reach.media.ExportedMedia
import io.github.iufb.reach.media.rememberMediaExport
import io.github.iufb.reach.ui.components.PillHeight
import kotlinx.coroutines.delay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.PhotoInfo
import io.github.iufb.reach.chats.VideoInfo
import io.github.iufb.reach.media.MediaKind
import io.github.iufb.reach.media.currentAutoDownloadRules
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Фото в пузыре: сразу миниатюра (размытая), поверх — превью нужного размера.
 * Пропорции известны заранее, поэтому пузырь не прыгает при загрузке.
 * [autoDownload] — слушаться правил автозагрузки фото (постеры видео и GIF грузятся всегда:
 * это маленькие миниатюры); если качать нельзя — миниатюра и кнопка скачивания с размером.
 */
@Composable
internal fun PhotoThumbnail(photo: PhotoInfo, modifier: Modifier = Modifier, square: Boolean = false, autoDownload: Boolean = true) {
    val ratio = if (square) {
        1f
    } else if (photo.width > 0 && photo.height > 0) {
        (photo.width.toFloat() / photo.height).coerceIn(MIN_RATIO, MAX_RATIO)
    } else {
        1f
    }
    val rules = currentAutoDownloadRules()
    var requested by remember(photo.preview) { mutableStateOf(false) }
    val load = !autoDownload || photo.downloaded || requested || rules.allows(MediaKind.Photo, photo.size)
    val painter = rememberAsyncImagePainter(if (load) photo.preview else null)
    val state by painter.state.collectAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (state !is AsyncImagePainter.State.Success) {
            photo.minithumbnail?.let {
                AsyncImage(it, null, Modifier.fillMaxSize().blur(8.dp), contentScale = ContentScale.Crop)
            }
        }
        Image(painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (!load && photo.preview != null) {
            DownloadBadge(photo.size, onClick = { requested = true })
        } else if (photo.preview != null) {
            when (state) {
                is AsyncImagePainter.State.Loading -> MediaProgress(progress = null)
                is AsyncImagePainter.State.Error -> Text(stringResource(R.string.photo_load_error), style = MaterialTheme.typography.labelMedium)
                else -> Unit
            }
        }
    }
}

/** Что листается в полноэкранном просмотре: фото и видео (альбомы бывают смешанными). */
sealed interface ViewerItem {
    data class Photo(val photo: PhotoInfo) : ViewerItem
    data class Video(val video: VideoInfo) : ViewerItem
    data class Animation(val animation: VideoInfo) : ViewerItem
}

/** Просмотр одних фото (аватарки в профиле). */
@Composable
internal fun PhotoViewer(photos: List<PhotoInfo>, initial: Int, onDismiss: () -> Unit) =
    MediaViewer(photos.map(ViewerItem::Photo), initial, onDismiss)

/**
 * Полноэкранный просмотр как в Telegram iOS: листание между [items], у фото — зум щипком и двойным тапом,
 * у видео — плеер. Сверху стеклянные кнопки: закрыть, «N из M», поделиться и сохранить в галерею.
 * Тап прячет и показывает кнопки; свайп вниз закрывает, фон при этом гаснет вслед за пальцем.
 * Цвета не зависят от темы: просмотр всегда на чёрном.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaViewer(items: List<ViewerItem>, initial: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = initial.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
        var chrome by remember { mutableStateOf(true) }
        // Насколько фото утянуто к закрытию: 0 — на месте, 1 — пора закрывать
        val drag = remember { mutableFloatStateOf(0f) }
        val toggleChrome = { chrome = !chrome }
        val onDrag = { progress: Float -> drag.floatValue = progress }
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(VIEWER_BACKGROUND, alpha = 1f - drag.floatValue.coerceIn(0f, 1f) * DRAG_FADE) },
        ) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                when (val item = items[page]) {
                    is ViewerItem.Photo -> ZoomableImage(item.photo, onDismiss, toggleChrome, onDrag)
                    is ViewerItem.Video -> VideoPage(item.video, active = page == pager.settledPage, onDismiss, onDrag = onDrag)
                    is ViewerItem.Animation -> VideoPage(item.animation, active = page == pager.settledPage, onDismiss, gif = true, onDrag = onDrag)
                }
            }
            val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, tween(CHROME_FADE_MS), label = "chrome")
            ViewerTopBar(
                item = items.getOrNull(pager.currentPage),
                counter = if (items.size > 1) stringResource(R.string.viewer_counter, pager.currentPage + 1, items.size) else null,
                onClose = onDismiss,
                modifier = Modifier.graphicsLayer { alpha = chromeAlpha * (1f - drag.floatValue.coerceIn(0f, 1f)) },
                enabled = chrome,
            )
        }
    }
}

/** Верх просмотра: закрыть, счётчик, поделиться и сохранить; под кнопками — короткое сообщение об итоге. */
@Composable
private fun ViewerTopBar(item: ViewerItem?, counter: String?, onClose: () -> Unit, modifier: Modifier, enabled: Boolean) {
    val context = LocalContext.current
    val export = rememberMediaExport()
    val scope = rememberCoroutineScope()
    var notice by remember { mutableStateOf<String?>(null) }
    val savedText = stringResource(R.string.viewer_saved)
    val failedText = stringResource(R.string.viewer_save_failed)
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp)) {
        ViewerButton(R.drawable.ic_close, stringResource(R.string.back), enabled, Modifier.align(Alignment.CenterStart), onClick = onClose)
        if (counter != null) {
            Text(
                counter,
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(VIEWER_PILL)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = VIEWER_CONTENT,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        val media = item?.exported()
        if (media != null) {
            Row(
                Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(percent = 50)).background(VIEWER_PILL),
            ) {
                ViewerButton(R.drawable.ic_share, stringResource(R.string.viewer_share), enabled, background = false) {
                    scope.launch { export.shareIntent(media)?.let(context::startActivity) ?: run { notice = failedText } }
                }
                ViewerButton(R.drawable.ic_download, stringResource(R.string.viewer_save), enabled, background = false) {
                    scope.launch { notice = if (export.save(media)) savedText else failedText }
                }
            }
        }
        notice?.let {
            Text(
                it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = PillHeight + 16.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(VIEWER_PILL)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                color = VIEWER_CONTENT,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Круглая кнопка просмотра: белый значок, по умолчанию на полупрозрачном круге. */
@Composable
private fun ViewerButton(
    @DrawableRes icon: Int,
    description: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    background: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(PillHeight)
            .clip(CircleShape)
            .then(if (background) Modifier.background(VIEWER_PILL) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = VIEWER_CONTENT)
    }
}

/** Файл для «поделиться» и «сохранить»: у фото — оригинал (или превью), у видео и GIF — сам ролик. */
private fun ViewerItem.exported(): ExportedMedia? = when (this) {
    is ViewerItem.Photo -> (photo.full ?: photo.preview)?.let { ExportedMedia(it, isVideo = false) }
    is ViewerItem.Video -> ExportedMedia(video.file, isVideo = true)
    is ViewerItem.Animation -> ExportedMedia(animation.file, isVideo = true)
}

/**
 * Картинка с зумом. Жесты отдаются листанию, пока фото не увеличено и палец идёт вбок;
 * вертикальный жест без зума тянет фото и закрывает просмотр. [onTap] — показать / спрятать кнопки,
 * [onDrag] — насколько фото утянуто к закрытию (0..1), для фона.
 */
@Composable
private fun ZoomableImage(photo: PhotoInfo, onDismiss: () -> Unit, onTap: () -> Unit, onDrag: (Float) -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val dismissY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    var height by remember { mutableIntStateOf(1) }
    LaunchedEffect(dismissY) {
        snapshotFlow { abs(dismissY.value) / (height * DISMISS_FRACTION * 2) }.collect { onDrag(it.coerceIn(0f, 1f)) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = {
                        scale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
                        offset = Offset.Zero
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var total = Offset.Zero
                    var dismissing = false
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        val multiTouch = event.changes.count { it.pressed } > 1
                        if (multiTouch || scale > 1f) {
                            scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                            event.changes.forEach { it.consume() }
                        } else {
                            total += pan
                            if (!dismissing && total.getDistance() > slop && abs(total.y) > abs(total.x)) dismissing = true
                            if (dismissing) {
                                scope.launch { dismissY.snapTo(dismissY.value + pan.y) }
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dismissing) {
                        if (abs(dismissY.value) > size.height * DISMISS_FRACTION) onDismiss()
                        else scope.launch { dismissY.animateTo(0f) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val painter = rememberAsyncImagePainter(photo.full ?: photo.preview)
        val state by painter.state.collectAsState()
        val transform = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationX = offset.x
            translationY = offset.y + dismissY.value
        }
        // Пока оригинал грузится — превью или миниатюра; двигается вместе с жестом
        if (state !is AsyncImagePainter.State.Success) {
            AsyncImage(photo.preview ?: photo.minithumbnail, null, Modifier.fillMaxSize().then(transform), contentScale = ContentScale.Fit)
        }
        Image(painter, null, Modifier.fillMaxSize().then(transform), contentScale = ContentScale.Fit)
        if (state is AsyncImagePainter.State.Loading) MediaProgress(progress = null)
        if (state is AsyncImagePainter.State.Error) Text(stringResource(R.string.photo_load_error), color = VIEWER_CONTENT)
    }
}

private val VIEWER_BACKGROUND = Color.Black
internal val VIEWER_CONTENT = Color.White
private val VIEWER_PILL = Color.White.copy(alpha = 0.16f)
private const val DRAG_FADE = 0.85f
private const val CHROME_FADE_MS = 180
private const val NOTICE_MS = 2_000L

internal const val MIN_RATIO = 0.6f
internal const val MAX_RATIO = 2f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
internal const val DISMISS_FRACTION = 0.2f
