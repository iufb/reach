package io.github.iufb.reach.chat

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Выбранные фото перед отправкой, как в Telegram iOS: на чёрном фоне фото во весь экран (листаются),
 * над нижней панелью — поле подписи, в панели — «назад», редактор, спойлер, без звука и синяя «отправить».
 * Чёрный фон и белые значки — цвета редактора медиа, они не зависят от темы.
 */
@Composable
internal fun SendPhotosDialog(
    photos: List<PickedPhoto>,
    onSend: (caption: String, silent: Boolean, spoiler: Boolean) -> Unit,
    onEdit: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var caption by rememberSaveable { mutableStateOf("") }
    var silent by rememberSaveable { mutableStateOf(false) }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    val pager = rememberPagerState { photos.size }
    val colors = ReachTheme.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(EDITOR_BACKGROUND)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { photos[it].uri.toString() }) { page ->
                AsyncImage(
                    photos[page].preview,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().padding(top = 48.dp, bottom = 140.dp),
                    contentScale = ContentScale.Fit,
                )
            }
            if (photos.size > 1) {
                Text(
                    stringResource(R.string.photo_page, pager.currentPage + 1, photos.size),
                    modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = EDITOR_CONTENT,
                )
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BasicTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PillHeight)
                        .clip(RoundedCornerShape(PillHeight / 2))
                        .background(EDITOR_FIELD)
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = EDITOR_CONTENT),
                    cursorBrush = SolidColor(colors.accent),
                    maxLines = 4,
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.Center) {
                            if (caption.isEmpty()) {
                                Text(
                                    stringResource(R.string.photo_caption_add),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = EDITOR_CONTENT.copy(alpha = 0.5f),
                                )
                            }
                            Box(Modifier.fillMaxWidth()) { inner() }
                        }
                    },
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton(R.drawable.ic_chevron_left, stringResource(R.string.cancel), outlined = true, onClick = onDismiss)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ToolButton(R.drawable.ic_edit, stringResource(R.string.photo_edit)) { onEdit(pager.currentPage) }
                        ToolButton(R.drawable.ic_spoiler, stringResource(R.string.send_with_spoiler), active = spoiler) { spoiler = !spoiler }
                        ToolButton(
                            if (silent) R.drawable.ic_muted else R.drawable.ic_unmute,
                            stringResource(R.string.send_silent),
                            active = silent,
                        ) { silent = !silent }
                    }
                    val sendDescription = stringResource(R.string.chat_send)
                    Box(
                        Modifier
                            .size(PillHeight)
                            .clip(CircleShape)
                            .background(colors.accent)
                            .clickable(role = Role.Button) { onSend(caption, silent, spoiler) }
                            .semantics { contentDescription = sendDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_send), contentDescription = null, Modifier.size(24.dp), tint = colors.onAccent)
                    }
                }
            }
        }
    }
}

/** Значок нижней панели редактора; [active] — переключатель включён (акцентом). */
@Composable
private fun ToolButton(
    @DrawableRes icon: Int,
    description: String,
    active: Boolean? = null,
    outlined: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (active == true) ReachTheme.colors.accent else EDITOR_CONTENT
    Box(
        Modifier
            .size(PillHeight)
            .clip(CircleShape)
            .then(if (outlined) Modifier.border(1.5.dp, EDITOR_CONTENT, CircleShape) else Modifier)
            .clickable(role = if (active != null) Role.Switch else Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                if (active != null) selected = active
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(24.dp), tint = tint)
    }
}

private val EDITOR_BACKGROUND = Color.Black
private val EDITOR_CONTENT = Color.White
private val EDITOR_FIELD = Color.White.copy(alpha = 0.14f)
