package io.github.iufb.reach.profile

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chat.PhotoViewer
import io.github.iufb.reach.chat.RichTextView
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.theme.reachBarColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenChat: (Long) -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val open by rememberUpdatedState(onOpenChat)
    LaunchedEffect(viewModel) { viewModel.openChat.collect { open(it) } }
    var viewingPhoto by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = reachBarColors(),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(stringResource(R.string.profile_title)) },
            )
        },
    ) { padding ->
        val current = profile
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(
                current.title,
                current.photo,
                current.accentColorId,
                modifier = Modifier.clickable(enabled = current.fullPhoto != null) { viewingPhoto = true },
                size = 112.dp,
            )
            Text(
                current.title.ifBlank { stringResource(R.string.chat_deleted_account) },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            if (current.memberCount > 0) {
                Text(
                    pluralStringResource(
                        if (current.isChannel) R.plurals.subscribers else R.plurals.members,
                        current.memberCount,
                        current.memberCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.canWrite) {
                Button(onClick = viewModel::write) { Text(stringResource(R.string.profile_write)) }
            }
            HorizontalDivider()
            current.isMuted?.let { muted ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.profile_notifications), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = !muted, onCheckedChange = viewModel::setNotificationsEnabled)
                }
            }
            if (current.phoneNumber.isNotBlank()) InfoRow("+${current.phoneNumber}", R.string.profile_phone)
            if (current.username.isNotBlank()) InfoRow("@${current.username}", R.string.profile_username)
            if (current.username.isNotBlank() && !current.isUser) {
                InfoRow("https://t.me/${current.username}", R.string.profile_link)
            }
            if (!current.about.isBlank()) {
                Column(Modifier.fillMaxWidth()) {
                    RichTextView(current.about, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(if (current.isUser) R.string.profile_bio else R.string.profile_description),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (viewingPhoto) current.fullPhoto?.let { PhotoViewer(listOf(it), 0) { viewingPhoto = false } }
    }
}

@Composable
private fun InfoRow(value: String, @StringRes label: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text(value, style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
