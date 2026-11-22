package io.github.iufb.reach.editchat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * «Изменить» группы или канала как в Telegram iOS: аватар с «Установить новое фото», карточка с
 * названием и описанием, у супергруппы — переключатель «Темы»; «Готово» в шапке сохраняет изменённое.
 */
@Composable
fun EditChatScreen(onBack: () -> Unit, viewModel: EditChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val back by rememberUpdatedState(onBack)
    LaunchedEffect(viewModel) { viewModel.events.collect { if (it == null) back() else snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val colors = ReachTheme.colors
    val nameHint = stringResource(if (state.isChannel) R.string.edit_chat_channel_name else R.string.edit_chat_group_name)
    val descriptionHint = stringResource(R.string.edit_chat_description)
    val descriptionFooter = stringResource(R.string.edit_chat_description_hint)
    val photoLabel = stringResource(R.string.edit_chat_photo_new)
    val topicsLabel = stringResource(R.string.edit_chat_topics)
    val topicsFooter = stringResource(R.string.edit_chat_topics_hint)

    ListScreen(
        title = stringResource(R.string.profile_edit),
        onBack = onBack,
        snackbar = snackbar,
        loading = !state.loaded,
        actions = {
            PillTextButton(
                stringResource(R.string.new_chat_done),
                onClick = viewModel::save,
                bold = true,
                enabled = state.changed && state.title.isNotBlank() && !state.busy,
            )
        },
    ) {
        item(key = "photo") {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val avatar = Modifier.size(AVATAR).clip(CircleShape).clickable(onClickLabel = photoLabel, onClick = pickPhoto)
                val newPhoto = state.newPhoto
                if (newPhoto != null) {
                    AsyncImage(newPhoto, contentDescription = null, modifier = avatar, contentScale = ContentScale.Crop)
                } else {
                    Avatar(state.title, state.photo, state.accentColorId, modifier = avatar, size = AVATAR)
                }
                Text(
                    photoLabel,
                    modifier = Modifier.padding(top = 10.dp).clip(CircleShape).clickable(onClick = pickPhoto).padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.accent,
                )
            }
        }
        item(key = "fields") {
            GroupedSection(footer = descriptionFooter) {
                GroupedTextField(state.title, viewModel::setTitle, placeholder = nameHint)
                GroupedTextField(state.description, viewModel::setDescription, placeholder = descriptionHint, singleLine = false, divider = false)
            }
        }
        if (state.canToggleForum) {
            item(key = "topics") {
                GroupedSection(footer = topicsFooter) {
                    GroupedCell(
                        topicsLabel,
                        modifier = Modifier.toggleable(state.isForum, role = Role.Switch, onValueChange = viewModel::setForum),
                        chevron = false,
                        divider = false,
                        trailing = { Switch(checked = state.isForum, onCheckedChange = null) },
                    )
                }
            }
        }
    }
}

private val AVATAR = 100.dp
