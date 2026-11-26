package io.github.iufb.reach.chat

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.media.PhotoCamera
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.geometry.Rect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.lazy.grid.GridItemSpan
import android.util.Log
import io.github.iufb.reach.ui.components.AccentPillButton
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.PillIconButton
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.ReachTabBar
import io.github.iufb.reach.ui.components.TabBarHeight
import io.github.iufb.reach.ui.components.TabItem
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что делать со скрепкой: фото из сетки, системная галерея, файл, опрос (`null` — недоступен). */
internal class AttachActions(
    val onSendPhotos: (List<Uri>) -> Unit,
    val onSystemPicker: () -> Unit,
    val onFile: () -> Unit,
    val onPoll: (() -> Unit)?,
    val maxPhotos: Int,
)

/** Доступ к фото: полный, только к выбранным пользователем (Android 14+) или никакого. */
private enum class PhotoAccess { Full, Partial, None }

/**
 * Разрешения для сетки фото: Android 14+ — все фото или только выбранные, Android 13 —
 * READ_MEDIA_IMAGES, Android 12 — READ_EXTERNAL_STORAGE (в манифесте до maxSdkVersion 32).
 */
private val photoPermissions: Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun Context.photoAccess(): PhotoAccess {
    fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return when {
        granted(photoPermissions[0]) -> PhotoAccess.Full
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.Partial
        else -> PhotoAccess.None
    }
}

/**
 * Меню скрепки Telegram iOS: шторка с сеткой последних фото и стеклянными вкладками внизу
 * (Галерея / Файл / Опрос). Тап по фото отмечает его номером; с отмеченными вместо вкладок —
 * «Отправить N», дальше привычное превью с подписью ([SendPhotosDialog]).
 */
@Composable
internal fun AttachSheet(actions: AttachActions, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val colors = ReachTheme.colors
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    // Закрытие — сначала шторка уезжает, потом действие (файл, опрос) и снятие диалога
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    var closing by remember { mutableStateOf(false) }
    fun close(then: (() -> Unit)? = null) {
        afterClose = then
        closing = true
        shown.targetState = false
    }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (closing && shown.isIdle && !shown.currentState) {
            onDismiss()
            afterClose?.invoke()
        }
    }

    var access by remember { mutableStateOf(context.photoAccess()) }
    // Доступ могли поменять в настройках системы, пока шторка открыта
    var reloads by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        access = context.photoAccess()
        reloads++
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        access = context.photoAccess()
        reloads++
    }
    val askAccess = {
        request.launch(photoPermissions)
    }
    val photos by produceState<List<Uri>?>(null, access, reloads) {
        value = if (access == PhotoAccess.None) emptyList() else withContext(Dispatchers.IO) { context.contentResolver.recentPhotos() }
    }
    val picked = remember { mutableStateListOf<Uri>() }

    // Камера в первой ячейке (#421): открывается, когда шторка доехала, и отпускается при закрытии
    val lifecycleOwner = LocalLifecycleOwner.current
    val camera = remember { PhotoCamera(context) }
    var cameraGranted by remember { mutableStateOf(context.cameraGranted()) }
    var cameraFull by remember { mutableStateOf(false) }
    var cameraFrom by remember { mutableStateOf(Rect.Zero) }
    val requestCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (granted) cameraFull = true
    }
    val sheetOpen = shown.isIdle && shown.currentState && !closing
    LaunchedEffect(cameraGranted, sheetOpen) {
        if (cameraGranted && sheetOpen) runCatching { camera.open(lifecycleOwner) }.onFailure { Log.w("AttachSheet", "camera failed", it) }
    }
    LaunchedEffect(closing) { if (closing) camera.close() }
    DisposableEffect(camera) { onDispose { camera.close() } }
    val openCamera = {
        if (cameraGranted) {
            // Стоп-кадр берём до перехода: иначе ячейка на кадр чернеет, пока камера переключается
            camera.freeze()
            cameraFull = true
        } else {
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }
    val cameraCell: @Composable (Modifier) -> Unit = { modifier ->
        CameraCell(
            camera,
            cameraGranted,
            onClick = openCamera,
            modifier = modifier.onGloballyPositioned { cameraFrom = it.boundsInWindow() },
        )
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(shown, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                GlassScope {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(SHEET_FRACTION)
                            .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
                            .background(colors.background)
                            // Тап по шторке не закрывает её
                            .clickable(remember { MutableInteractionSource() }, indication = null) {},
                    ) {
                        Column(Modifier.fillMaxSize()) {
                            SheetHeader(onClose = { close() })
                            if (access == PhotoAccess.Partial) PartialAccessRow(onChange = askAccess)
                            val bottomInset = TabBarHeight + BOTTOM_EXTRA + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            when {
                                access == PhotoAccess.None -> NoAccess(
                                    onAllow = askAccess,
                                    onSystem = { close(actions.onSystemPicker) },
                                    onCamera = openCamera,
                                )
                                photos == null -> Unit
                                else -> PhotoGrid(
                                    photos.orEmpty(),
                                    picked,
                                    cameraCell = cameraCell,
                                    bottomPadding = bottomInset,
                                    onToggle = { uri ->
                                        if (uri in picked) picked.remove(uri) else if (picked.size < actions.maxPhotos) picked.add(uri)
                                    },
                                )
                            }
                        }
                        Box(Modifier.align(Alignment.BottomCenter)) {
                            if (picked.isEmpty()) {
                                AttachTabs(actions, onFile = { close(actions.onFile) }, onPoll = { poll -> close(poll) })
                            } else {
                                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    AccentPillButton(
                                        stringResource(R.string.attach_send_count, picked.size),
                                        onClick = {
                                            val uris = picked.toList()
                                            close { actions.onSendPhotos(uris) }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (cameraFull) {
                FullScreenCamera(
                    camera,
                    from = cameraFrom,
                    onClosed = {
                        cameraFull = false
                        camera.leaveFull()
                    },
                    onShot = { file -> close { actions.onSendPhotos(listOf(Uri.fromFile(file))) } },
                )
            }
        }
    }
}

private fun Context.cameraGranted() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun SheetHeader(onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillIconButton(painterResource(R.drawable.ic_close), stringResource(R.string.close), onClose)
        Text(
            stringResource(R.string.attach_recents),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            color = ReachTheme.colors.label,
        )
        Spacer(Modifier.width(PillHeight))
    }
}

@Composable
private fun PartialAccessRow(onChange: () -> Unit) {
    val colors = ReachTheme.colors
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onChange).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.attach_access_partial),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryLabel,
        )
        Text(stringResource(R.string.attach_access_change), style = MaterialTheme.typography.bodyMedium, color = colors.accent)
    }
}

@Composable
private fun NoAccess(onAllow: () -> Unit, onSystem: () -> Unit, onCamera: () -> Unit) {
    val colors = ReachTheme.colors
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).padding(bottom = TabBarHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.attach_access_title),
            style = MaterialTheme.typography.titleLarge,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) R.string.attach_access_text else R.string.attach_access_text_full,
            ),
            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
        AccentPillButton(stringResource(R.string.attach_access_allow), onClick = onAllow)
        Spacer(Modifier.height(12.dp))
        PillTextButton(stringResource(R.string.attach_access_system), onClick = onSystem)
        Spacer(Modifier.height(12.dp))
        PillTextButton(stringResource(R.string.attach_camera_take), onClick = onCamera)
    }
}

@Composable
private fun PhotoGrid(
    photos: List<Uri>,
    picked: List<Uri>,
    cameraCell: @Composable (Modifier) -> Unit,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onToggle: (Uri) -> Unit,
) {
    @Composable
    fun cell(uri: Uri, modifier: Modifier = Modifier) {
        val number = picked.indexOf(uri).takeIf { it >= 0 }?.plus(1)
        PhotoCell(uri, number, modifier) { onToggle(uri) }
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = Modifier.fillMaxSize().glassSource(),
        contentPadding = PaddingValues(bottom = bottomPadding),
        horizontalArrangement = Arrangement.spacedBy(GRID_GAP),
        verticalArrangement = Arrangement.spacedBy(GRID_GAP),
    ) {
        // Как в Telegram iOS: камера 2×2 слева, справа — два первых фото столбиком
        item(key = "camera", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                cameraCell(Modifier.weight(2f).aspectRatio(1f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GRID_GAP)) {
                    photos.getOrNull(0)?.let { cell(it) }
                    photos.getOrNull(1)?.let { cell(it) }
                }
            }
        }
        items(photos.drop(2), key = { it.toString() }) { uri -> cell(uri) }
    }
}

/** Миниатюра с кружком выбора в углу; [number] — порядковый номер среди отмеченных. */
@Composable
private fun PhotoCell(uri: Uri, number: Int?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    val description = stringResource(R.string.attach_photo_select)
    Box(
        modifier
            .aspectRatio(1f)
            .background(colors.fill)
            .semantics {
                contentDescription = description
                selected = number != null
            }
            .clickable(role = Role.Checkbox, onClick = onClick),
    ) {
        AsyncImage(model = uri, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(BADGE)
                .clip(CircleShape)
                .then(
                    if (number != null) {
                        Modifier.background(colors.accent)
                    } else {
                        Modifier.background(colors.label.copy(alpha = 0.15f)).border(1.5.dp, colors.onAccent, CircleShape)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (number != null) {
                Text(number.toString(), color = colors.onAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun AttachTabs(actions: AttachActions, onFile: () -> Unit, onPoll: (() -> Unit) -> Unit) {
    val gallery = TabItem(painterResource(R.drawable.ic_photo), stringResource(R.string.attach_gallery))
    val file = TabItem(painterResource(R.drawable.ic_document), stringResource(R.string.attach_file))
    val poll = actions.onPoll?.let { TabItem(painterResource(R.drawable.ic_poll), stringResource(R.string.attach_poll)) }
    ReachTabBar(
        items = listOfNotNull(gallery, file, poll),
        selected = 0,
        onSelect = { index ->
            when (index) {
                1 -> onFile()
                2 -> actions.onPoll?.let(onPoll)
            }
        },
    )
}

/** Последние фото устройства (или те, к которым дан доступ), новые первыми. */
private fun ContentResolver.recentPhotos(): List<Uri> {
    val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val args = Bundle().apply {
        putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
        putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
        putInt(ContentResolver.QUERY_ARG_LIMIT, RECENT_LIMIT)
    }
    return query(collection, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { cursor ->
        val id = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        buildList { while (cursor.moveToNext()) add(ContentUris.withAppendedId(collection, cursor.getLong(id))) }
    }.orEmpty()
}

private const val SHEET_FRACTION = 0.62f
private val SHEET_CORNER = 20.dp
private const val GRID_COLUMNS = 3
private val GRID_GAP = 2.dp
private val BADGE = 24.dp
private val BOTTOM_EXTRA = 16.dp
private const val RECENT_LIMIT = 300
