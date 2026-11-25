package io.github.iufb.reach.topics

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.bodySummary
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillGroup
import io.github.iufb.reach.ui.components.PillGroupIcon
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.components.SkeletonCircleShape
import io.github.iufb.reach.ui.components.TextInputSheet
import io.github.iufb.reach.ui.components.skeletonFraction
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * Форум в стиле Telegram iOS: темы списком как чаты (значок темы, название, последнее сообщение, время,
 * непрочитанные), в шапке — «новая тема» и «ещё» (показать сообщениями). Долгий тап — лист управления.
 */
@Composable
fun TopicsScreen(
    onBack: () -> Unit,
    onOpenTopic: (chatId: Long, topicId: Int) -> Unit,
    onOpenAsMessages: (chatId: Long) -> Unit,
    onOpenProfile: (chatId: Long) -> Unit,
    viewModel: TopicsViewModel = hiltViewModel(),
) {
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val topics by viewModel.topics.collectAsStateWithLifecycle()
    val rights by viewModel.rights.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    val open by rememberUpdatedState(onOpenTopic)
    LaunchedEffect(viewModel) { viewModel.openTopic.collect { open(viewModel.chatId, it) } }
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Topic?>(null) }
    var managing by remember { mutableStateOf<Topic?>(null) }
    var menu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 10 }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }
    val list = topics

    ListScreen(
        title = chat?.title.orEmpty(),
        onBack = onBack,
        background = ReachTheme.colors.background,
        snackbar = snackbar,
        loading = list == null,
        state = listState,
        skeleton = { items(SKELETON_TOPICS, contentType = { "skeleton" }) { SkeletonTopicRow(it) } },
        onTitleClick = { onOpenProfile(viewModel.chatId) },
        actions = {
            PillGroup {
                if (rights.canCreate) PillGroupIcon(painterResource(R.drawable.ic_edit), stringResource(R.string.topic_new), { creating = true })
                PillGroupIcon(painterResource(R.drawable.ic_more), stringResource(R.string.chat_list_menu), { menu = true })
            }
        },
    ) {
        items(list.orEmpty(), key = { it.id }) { topic ->
            TopicRow(
                topic,
                onClick = { onOpenTopic(viewModel.chatId, topic.id) },
                onLongClick = if (rights.canManage || topic.isOutgoing) ({ managing = topic }) else null,
            )
        }
    }

    if (menu) {
        ActionSheet(
            actions = listOf(SheetAction(stringResource(R.string.topics_as_messages)) { onOpenAsMessages(viewModel.chatId) }),
            onDismiss = { menu = false },
        )
    }
    managing?.let { topic ->
        ActionSheet(
            title = topic.name,
            actions = buildList {
                if (!topic.isGeneral) add(SheetAction(stringResource(R.string.topic_rename)) { renaming = topic })
                add(SheetAction(stringResource(if (topic.isClosed) R.string.topic_reopen else R.string.topic_close)) { viewModel.setClosed(topic, !topic.isClosed) })
            },
            onDismiss = { managing = null },
        )
    }
    if (creating) {
        TextInputSheet(
            title = stringResource(R.string.topic_new),
            initial = "",
            placeholder = stringResource(R.string.topic_name_hint),
            maxLength = TOPIC_NAME_MAX,
            onSave = {
                creating = false
                viewModel.create(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { topic ->
        TextInputSheet(
            title = stringResource(R.string.topic_rename),
            initial = topic.name,
            placeholder = stringResource(R.string.topic_name_hint),
            maxLength = TOPIC_NAME_MAX,
            onSave = {
                renaming = null
                viewModel.rename(topic, it)
            },
            onDismiss = { renaming = null },
        )
    }
}

/** Скелетон строки темы: та же раскладка, что у [TopicRow]. */
@Composable
internal fun SkeletonTopicRow(index: Int) {
    val colors = ReachTheme.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            SkeletonBox(Modifier.size(TOPIC_ICON), SkeletonCircleShape)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index, 0.3f, 0.6f)).height(14.dp)) }
                    SkeletonBox(Modifier.size(width = 30.dp, height = 10.dp))
                }
                Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                    SkeletonBox(Modifier.fillMaxWidth(skeletonFraction(index + 4, 0.55f, 0.9f)).height(12.dp))
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp + TOPIC_ICON + 12.dp), thickness = 0.5.dp, color = colors.separator)
    }
}

private const val SKELETON_TOPICS = 12

/** Строка темы как строка чата: значок, название (замок / булавка), время, последнее сообщение, непрочитанные. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopicRow(topic: Topic, onClick: () -> Unit, onLongClick: (() -> Unit)?) {
    val colors = ReachTheme.colors
    Column(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            TopicIcon(topic)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Название с замком / булавкой забирает всё место, время прижато вправо
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            topic.name,
                            modifier = Modifier.weight(1f, fill = false),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (topic.isClosed) StatusIcon(R.drawable.ic_lock, R.string.topic_closed)
                        if (topic.isPinned) StatusIcon(R.drawable.ic_pin, R.string.topic_pinned)
                    }
                    Text(
                        Dates.chatListTime(topic.lastMessageDate.toLong()),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.secondaryLabel,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.lastMessage?.let { bodySummary(it).replace('\n', ' ') }.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.secondaryLabel,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.unreadCount > 0) {
                        Text(
                            topic.unreadCount.toString(),
                            modifier = Modifier
                                .padding(start = 8.dp)
                                .background(colors.accent, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.onAccent,
                        )
                    }
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp + TOPIC_ICON + 12.dp), thickness = 0.5.dp, color = colors.separator)
    }
}

@Composable
private fun StatusIcon(@DrawableRes icon: Int, @StringRes description: Int) {
    Icon(
        painterResource(icon),
        contentDescription = stringResource(description),
        modifier = Modifier.padding(start = 4.dp).size(14.dp),
        tint = ReachTheme.colors.secondaryLabel,
    )
}

/** Значок: круг цвета темы с первой буквой; «#» у общей темы. */
@Composable
private fun TopicIcon(topic: Topic) {
    val colors = ReachTheme.colors
    // Цвет значка задаёт сама тема (Telegram) — он из данных, а не из палитры приложения
    val color = if (topic.isGeneral) colors.fill else Color(0xFF000000 or topic.iconColor.toLong())
    Box(Modifier.size(TOPIC_ICON).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            if (topic.isGeneral) "#" else topic.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = if (topic.isGeneral) colors.secondaryLabel else colors.onAccent,
        )
    }
}

private val TOPIC_ICON = 44.dp
private const val TOPIC_NAME_MAX = 128
