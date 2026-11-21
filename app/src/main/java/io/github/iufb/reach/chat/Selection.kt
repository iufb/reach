package io.github.iufb.reach.chat

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.glass
import io.github.iufb.reach.ui.theme.ReachTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import kotlinx.coroutines.launch

/** Что можно сделать со всеми выбранными сразу: действие доступно, только если подходит каждому. */
data class SelectionActions(
    val messageIds: List<Long>,
    /** Тексты выбранных подряд, от старых к новым; `null` — хотя бы одно не скопировать. */
    val copyText: String?,
    val canForward: Boolean,
    /** `null` — хотя бы одно нельзя удалить. */
    val delete: DeleteRequest?,
)

/**
 * [selected] — выбранные id и их права; права `null` — ещё загружаются, тогда действия недоступны.
 * Сообщения, пропавшие из ленты (удалены), в расчёт не идут.
 */
internal fun selectionActions(selected: Map<Long, MessageActions?>, messages: List<Message>): SelectionActions {
    val chosen = messages.filter { it.id in selected }.sortedBy { it.id }
    val actions = chosen.map { selected[it.id] }
    val ready = chosen.isNotEmpty() && actions.all { it != null }
    val all = actions.filterNotNull()
    return SelectionActions(
        messageIds = chosen.map { it.id },
        copyText = if (ready && all.all { it.copyText != null }) all.joinToString("\n\n") { it.copyText.orEmpty() } else null,
        canForward = ready && all.all { it.canForward },
        delete = if (ready && all.all { it.canDelete }) {
            DeleteRequest(
                chosen.map { it.id },
                canForAll = all.all { it.canDeleteForAll },
                // Если хоть одно удаляется только у всех — выбора «только у себя» нет
                canForSelf = all.all { it.canDeleteForSelf },
            )
        } else {
            null
        },
    )
}

/** Режим выбора на экране: общие действия и что с ними делать. */
internal class SelectionControls(
    val actions: SelectionActions,
    val onClose: () -> Unit,
    val onForward: (List<Long>) -> Unit,
    val onDelete: (DeleteRequest) -> Unit,
)

/** Шапка режима выбора (Telegram iOS): по центру пилюля «Выбрано: N», справа «Отмена». */
@Composable
internal fun SelectionTopBar(controls: SelectionControls) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.height(PillHeight).glass(RoundedCornerShape(percent = 50)).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.selection_count, controls.actions.messageIds.size),
                style = MaterialTheme.typography.titleLarge,
                color = ReachTheme.colors.label,
            )
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            PillTextButton(stringResource(R.string.cancel), onClick = controls.onClose)
        }
    }
}

/** Вместо поля ввода: «Удалить» слева, «Копировать» и «Переслать» справа — стеклянными кругами. */
@Composable
internal fun SelectionBottomBar(controls: SelectionControls) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val actions = controls.actions
    val colors = ReachTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val delete = actions.delete
        SelectionAction(R.drawable.ic_delete, R.string.message_action_delete, enabled = delete != null, tint = colors.destructive) {
            if (delete != null) controls.onDelete(delete)
        }
        Spacer(Modifier.weight(1f))
        val text = actions.copyText
        SelectionAction(R.drawable.ic_copy, R.string.message_action_copy, enabled = text != null) {
            if (text != null) scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text))) }
            controls.onClose()
        }
        Spacer(Modifier.width(12.dp))
        SelectionAction(R.drawable.ic_forward, R.string.message_action_forward, enabled = actions.canForward) {
            controls.onForward(actions.messageIds)
        }
    }
}

@Composable
private fun SelectionAction(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    tint: Color = ReachTheme.colors.accent,
    onClick: () -> Unit,
) {
    val description = stringResource(label)
    Box(
        Modifier
            .size(PillHeight)
            .glass(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, Modifier.size(22.dp), tint = if (enabled) tint else ReachTheme.colors.tertiaryLabel)
    }
}

/** Кружок выбора слева от сообщения: пустой или акцентный с галочкой. */
@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    val description = stringResource(R.string.message_selected)
    Box(
        modifier
            .padding(start = 8.dp, end = 4.dp)
            .size(CHECK_SIZE)
            .clip(CircleShape)
            .then(
                if (selected) {
                    Modifier.background(colors.accent).semantics { contentDescription = description }
                } else {
                    Modifier.border(1.5.dp, colors.secondaryLabel, CircleShape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(16.dp), tint = colors.onAccent)
    }
}

private val CHECK_SIZE = 24.dp
