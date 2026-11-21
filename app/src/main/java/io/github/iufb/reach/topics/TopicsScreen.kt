package io.github.iufb.reach.topics

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import io.github.iufb.reach.ui.format.Dates
import io.github.iufb.reach.ui.theme.reachBarColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** Форум: список топиков вместо общей истории. */
@OptIn(ExperimentalMaterial3Api::class)
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
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = {
                    Text(
                        chat?.title.orEmpty(),
                        modifier = Modifier.combinedClickable(onClick = { onOpenProfile(viewModel.chatId) }),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(R.drawable.ic_more), contentDescription = stringResource(R.string.chat_list_menu))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.topics_as_messages)) },
                                onClick = {
                                    menu = false
                                    onOpenAsMessages(viewModel.chatId)
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (rights.canCreate) {
                FloatingActionButton(onClick = { creating = true }) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = stringResource(R.string.topic_new))
                }
            }
        },
    ) { padding ->
        val list = topics
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val listState = rememberLazyListState()
        LaunchedEffect(listState) {
            snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 10 }
                .distinctUntilChanged()
                .filter { it }
                .collect { viewModel.loadMore() }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding), state = listState) {
            items(list, key = { it.id }) { topic ->
                TopicRow(
                    topic,
                    canManage = rights.canManage || topic.isOutgoing,
                    onClick = { onOpenTopic(viewModel.chatId, topic.id) },
                    onRename = { renaming = topic },
                    onToggleClosed = { viewModel.setClosed(topic, !topic.isClosed) },
                )
            }
        }
    }
    if (creating) {
        TopicNameDialog(
            title = stringResource(R.string.topic_new),
            initial = "",
            onConfirm = {
                creating = false
                viewModel.create(it)
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { topic ->
        TopicNameDialog(
            title = stringResource(R.string.topic_rename),
            initial = topic.name,
            onConfirm = {
                renaming = null
                viewModel.rename(topic, it)
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun TopicRow(topic: Topic, canManage: Boolean, onClick: () -> Unit, onRename: () -> Unit, onToggleClosed: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = if (canManage) ({ menu = true }) else null)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopicIcon(topic)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.name,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.isClosed) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = stringResource(R.string.topic_closed),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (topic.isPinned) {
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painterResource(R.drawable.ic_pin),
                            contentDescription = stringResource(R.string.topic_pinned),
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        Dates.chatListTime(topic.lastMessageDate.toLong()),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        topic.lastMessage?.let { bodySummary(it).replace('\n', ' ') }.orEmpty(),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (topic.unreadCount > 0) {
                        Text(
                            topic.unreadCount.toString(),
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!topic.isGeneral) {
                DropdownMenuItem(text = { Text(stringResource(R.string.topic_rename)) }, onClick = { menu = false; onRename() })
            }
            DropdownMenuItem(
                text = { Text(stringResource(if (topic.isClosed) R.string.topic_reopen else R.string.topic_close)) },
                onClick = { menu = false; onToggleClosed() },
            )
        }
    }
}

/** Значок: круг цвета топика с первой буквой; «#» у общего топика. */
@Composable
private fun TopicIcon(topic: Topic) {
    val color = if (topic.isGeneral) MaterialTheme.colorScheme.surfaceVariant else Color(0xFF000000 or topic.iconColor.toLong())
    Box(Modifier.size(40.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            if (topic.isGeneral) "#" else topic.name.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium,
            color = if (topic.isGeneral) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
        )
    }
}

@Composable
private fun TopicNameDialog(title: String, initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(TOPIC_NAME_MAX) },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.topic_name_hint)) },
            )
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onConfirm(name) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val TOPIC_NAME_MAX = 128
