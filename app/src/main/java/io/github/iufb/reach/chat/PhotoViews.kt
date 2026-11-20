package io.github.iufb.reach.chat

import androidx.compose.animation.core.Animatable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Фото в пузыре: сразу миниатюра (размытая), поверх — превью нужного размера.
 * Пропорции известны заранее, поэтому пузырь не прыгает при загрузке.
 */
@Composable
internal fun PhotoThumbnail(photo: PhotoInfo, modifier: Modifier = Modifier, square: Boolean = false) {
    val ratio = if (square) {
        1f
    } else if (photo.width > 0 && photo.height > 0) {
        (photo.width.toFloat() / photo.height).coerceIn(MIN_RATIO, MAX_RATIO)
    } else {
        1f
    }
    val painter = rememberAsyncImagePainter(photo.preview)
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
        if (photo.preview != null) {
            when (state) {
                is AsyncImagePainter.State.Loading -> CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.dp)
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
}

/** Просмотр одних фото (аватарки в профиле). */
@Composable
internal fun PhotoViewer(photos: List<PhotoInfo>, initial: Int, onDismiss: () -> Unit) =
    MediaViewer(photos.map(ViewerItem::Photo), initial, onDismiss)

/**
 * Полноэкранный просмотр: листание между [items], у фото — зум щипком и двойным тапом,
 * у видео — плеер; закрытие свайпом вниз. Играет только видео на открытой странице.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaViewer(items: List<ViewerItem>, initial: Int, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val pager = rememberPagerState(initialPage = initial.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { it }) { page ->
                when (val item = items[page]) {
                    is ViewerItem.Photo -> ZoomableImage(item.photo, onDismiss)
                    is ViewerItem.Video -> VideoPage(item.video, active = page == pager.settledPage, onDismiss)
                }
            }
            IconButton(onClick = onDismiss, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.back), tint = Color.White)
            }
            if (items.size > 1) {
                Text(
                    "${pager.currentPage + 1} / ${items.size}",
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 20.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

/**
 * Картинка с зумом. Жесты отдаются листанию, пока фото не увеличено и палец идёт вбок;
 * вертикальный жест без зума тянет фото и закрывает просмотр.
 */
@Composable
private fun ZoomableImage(photo: PhotoInfo, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val dismissY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val slop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
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
        // Пока оригинал грузится — превью или миниатюра
        if (state !is AsyncImagePainter.State.Success) {
            AsyncImage(photo.preview ?: photo.minithumbnail, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        Image(
            painter,
            null,
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y + dismissY.value
                },
            contentScale = ContentScale.Fit,
        )
        if (state is AsyncImagePainter.State.Loading) CircularProgressIndicator(color = Color.White)
        if (state is AsyncImagePainter.State.Error) Text(stringResource(R.string.photo_load_error), color = Color.White)
    }
}

internal const val MIN_RATIO = 0.6f
internal const val MAX_RATIO = 2f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
internal const val DISMISS_FRACTION = 0.2f
