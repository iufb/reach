package io.github.iufb.reach.newchat

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.iufb.reach.R
import io.github.iufb.reach.contacts.ContactPicker
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedCorner
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * Мастер новой группы или канала в стиле Telegram iOS: участники (круглые отметки), затем фото и название
 * карточкой, у канала — тип (частный / публичный) и подписчики. «Далее» / «Создать» — пилюля справа в шапке.
 */
@Composable
fun NewChatScreen(onBack: () -> Unit, onCreated: (Long) -> Unit, viewModel: NewChatViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val contacts by viewModel.contactList.collectAsStateWithLifecycle()
    val usernameStatus by viewModel.usernameStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val privacyText = stringResource(R.string.new_chat_privacy_failed)
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { error ->
            snackbar.showSnackbar(if (error.startsWith(NewChatViewModel.PRIVACY_MARKER)) privacyText else error)
        }
    }
    val created by rememberUpdatedState(onCreated)
    LaunchedEffect(viewModel) { viewModel.done.collect { created(it) } }
    val back = { if (!viewModel.back()) onBack() }
    BackHandler(onBack = back)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }

    val canNext = !state.busy && when (state.step) {
        NewChatStep.Members -> true
        NewChatStep.Info -> state.title.isNotBlank()
        NewChatStep.Visibility -> !state.isPublic || usernameStatus?.isOk == true
        NewChatStep.Subscribers -> true
    }
    val picking = state.step == NewChatStep.Members || state.step == NewChatStep.Subscribers
    val colors = ReachTheme.colors
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp

    GlassScope {
        Box(Modifier.fillMaxSize().background(if (picking) colors.background else colors.groupedBackground).imePadding()) {
            val content = Modifier.fillMaxSize().glassSource()
            when (state.step) {
                NewChatStep.Members, NewChatStep.Subscribers -> {
                    val list = contacts
                    if (list != null) {
                        ContactPicker(
                            list, state.selected, viewModel::toggle,
                            content.padding(top = top),
                            contentPadding = PaddingValues(bottom = bottom),
                        )
                    }
                }
                NewChatStep.Info -> Column(content.verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(GroupedCorner)).background(colors.cell),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhotoButton(state.photo, Modifier.padding(12.dp)) {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        GroupedTextField(
                            state.title,
                            viewModel::setTitle,
                            placeholder = stringResource(if (viewModel.isChannel) R.string.new_channel_title else R.string.new_group_title),
                            modifier = Modifier.weight(1f),
                            divider = false,
                        )
                    }
                    if (viewModel.isChannel) {
                        GroupedSection(footer = stringResource(R.string.new_channel_description_hint)) {
                            GroupedTextField(
                                state.description,
                                viewModel::setDescription,
                                placeholder = stringResource(R.string.profile_description),
                                singleLine = false,
                                divider = false,
                            )
                        }
                    }
                }
                NewChatStep.Visibility -> Column(content.verticalScroll(rememberScrollState()).padding(top = top, bottom = bottom)) {
                    GroupedSection(
                        header = stringResource(R.string.new_channel_type).uppercase(),
                        footer = stringResource(if (state.isPublic) R.string.new_channel_public_hint else R.string.new_channel_private_hint),
                    ) {
                        ChoiceCell(stringResource(R.string.new_channel_private), selected = !state.isPublic) { viewModel.setPublic(false) }
                        ChoiceCell(stringResource(R.string.new_channel_public), selected = state.isPublic, divider = false) { viewModel.setPublic(true) }
                    }
                    if (state.isPublic) {
                        val status = usernameStatus
                        GroupedSection(footer = status?.let { stringResource(it.message) }) {
                            GroupedTextField(
                                state.username,
                                viewModel::setUsername,
                                placeholder = stringResource(R.string.new_channel_link_hint),
                                prefix = "t.me/",
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                divider = false,
                            )
                        }
                    } else if (state.inviteLink.isNotEmpty()) {
                        GroupedSection(header = stringResource(R.string.invite_link_title).uppercase()) {
                            GroupedCell(state.inviteLink, divider = false, chevron = false)
                        }
                    }
                }
            }
            ReachTopBar(
                title = stringResource(if (viewModel.isChannel) R.string.new_channel else R.string.new_group),
                subtitle = if (picking) pluralStringResource(R.plurals.new_chat_selected, state.selected.size, state.selected.size) else null,
                titlePill = false,
                navigation = { BackPill(back) },
                actions = {
                    if (state.busy) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                    } else {
                        PillTextButton(
                            stringResource(
                                when (state.step) {
                                    NewChatStep.Info -> R.string.new_chat_create
                                    NewChatStep.Subscribers -> R.string.new_chat_done
                                    else -> R.string.schedule_next
                                },
                            ),
                            onClick = viewModel::next,
                            bold = true,
                            enabled = canNext,
                        )
                    }
                },
                background = if (picking) colors.background else colors.groupedBackground,
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
        }
    }
}

/** Круг фото группы: выбранное фото или акцентный значок камеры на `fill`. */
@Composable
private fun PhotoButton(photo: android.net.Uri?, modifier: Modifier, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Box(
        modifier.size(PHOTO).clip(CircleShape).background(colors.fill).clickable(onClickLabel = stringResource(R.string.new_chat_photo), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (photo != null) {
            AsyncImage(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Icon(painterResource(R.drawable.ic_photo), contentDescription = null, Modifier.size(28.dp), tint = colors.accent)
        }
    }
}

/** Вариант выбора в карточке: галочка акцентом справа у выбранного. */
@Composable
private fun ChoiceCell(title: String, selected: Boolean, divider: Boolean = true, onSelect: () -> Unit) {
    GroupedCell(
        title,
        modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = onSelect),
        chevron = false,
        divider = divider,
        trailing = {
            if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, Modifier.size(20.dp), tint = ReachTheme.colors.accent)
        },
    )
}

private val PHOTO = 64.dp
