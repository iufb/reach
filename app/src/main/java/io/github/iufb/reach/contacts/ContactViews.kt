package io.github.iufb.reach.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.iufb.reach.R
import io.github.iufb.reach.presence.userStatusText
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.ReachTheme
import io.github.iufb.reach.users.User
import io.github.iufb.reach.users.UserStatus

/** Аватар в строке пользователя и отступ текста — от него начинается разделитель. */
val UserRowAvatar = 40.dp
val UserRowTextInset = 16.dp + UserRowAvatar + 12.dp

/**
 * Строка пользователя: аватар, имя, статус («в сети» — акцентом). Общая для контактов, выбора участников и списков
 * участников; [trailing] — галочка выбора или роль, [divider] — разделитель от текста до края.
 */
@Composable
fun UserRow(
    user: User,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    divider: Boolean = false,
) {
    val colors = ReachTheme.colors
    Column(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(12.dp))
            }
            Avatar(user.fullName, user.photo, user.accentColorId, size = UserRowAvatar)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user.fullName.ifBlank { stringResource(R.string.chat_deleted_account) },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val status = subtitle ?: userStatusText(user.status)
                if (status != null) {
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (user.status is UserStatus.Online && subtitle == null) colors.accent else colors.secondaryLabel,
                        maxLines = 1,
                    )
                }
            }
            trailing?.invoke()
        }
        if (divider) {
            HorizontalDivider(Modifier.padding(start = if (leading != null) UserRowTextInset + 36.dp else UserRowTextInset), thickness = 0.5.dp, color = colors.separator)
        }
    }
}
