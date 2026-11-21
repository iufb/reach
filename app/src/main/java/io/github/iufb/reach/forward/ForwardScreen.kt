package io.github.iufb.reach.forward

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.Chat
import io.github.iufb.reach.chats.ChatAvatar
import io.github.iufb.reach.chats.chatTitle
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.SearchField
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Пересылка — шторка поверх чата, как в Telegram iOS: «Отмена», заголовок, поиск и чаты карточкой.
 * Тап по чату пересылает и закрывает шторку; [onForwarded] получает название чата для тоста.
 * Показывается как dialog-назначение навигации.
 */
@Composable
fun ForwardScreen(
    onBack: () -> Unit,
    onForwarded: (chatId: Long, title: String) -> Unit,
    viewModel: ForwardViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val colors = ReachTheme.colors
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    // Закрытие: шторка уезжает, потом уходим с экрана (и, если переслали, — тост в чате)
    var afterClose by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun close(then: () -> Unit) {
        afterClose = then
        shown.targetState = false
    }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (shown.isIdle && !shown.currentState) afterClose?.invoke()
    }
    // Название ищем, пока список ещё на экране: после пересылки он может обновиться
    var pickedTitle by remember { mutableStateOf("") }
    val done by rememberUpdatedState(onForwarded)
    LaunchedEffect(viewModel) { viewModel.forwarded.collect { id -> close { done(id, pickedTitle) } } }

    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { close(onBack) },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(SHEET_FRACTION)
                    .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
                    .background(colors.groupedBackground)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .imePadding(),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { PillTextButton(stringResource(R.string.cancel), onClick = { close(onBack) }) }
                    Text(
                        stringResource(R.string.forward_sheet_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.label,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.weight(1f))
                }
                SearchField(
                    query = query,
                    onQueryChange = viewModel::onQueryChange,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    placeholder = stringResource(R.string.forward_search),
                )
                error?.let {
                    Text(
                        stringResource(R.string.forward_error, it),
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.destructive,
                    )
                }
                val list = targets
                when {
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.forward_empty), color = colors.secondaryLabel)
                    }
                    else -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    ) {
                        itemsIndexed(list, key = { _, chat -> chat.id }) { index, chat ->
                            val title = chatTitle(chat)
                            TargetRow(chat, title, first = index == 0, last = index == list.lastIndex) {
                                pickedTitle = title
                                viewModel.forwardTo(chat.id)
                            }
                        }
                        item { Spacer(Modifier.navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

/** Ячейка карточки: аватар и название; у первой и последней — скругления карточки, между — разделитель от текста. */
@Composable
private fun TargetRow(chat: Chat, title: String, first: Boolean, last: Boolean, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    val shape = RoundedCornerShape(
        topStart = if (first) CARD_CORNER else 0.dp,
        topEnd = if (first) CARD_CORNER else 0.dp,
        bottomStart = if (last) CARD_CORNER else 0.dp,
        bottomEnd = if (last) CARD_CORNER else 0.dp,
    )
    Column(Modifier.fillMaxWidth().clip(shape).background(colors.cell).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = PillHeight + 8.dp).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatAvatar(chat, size = AVATAR)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 12.dp + AVATAR + 12.dp), thickness = 0.5.dp, color = colors.separator)
    }
}

private const val SHEET_FRACTION = 0.9f
private val SHEET_CORNER = 20.dp
private val CARD_CORNER = 12.dp
private val AVATAR = 40.dp
