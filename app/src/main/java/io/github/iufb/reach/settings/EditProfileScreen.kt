package io.github.iufb.reach.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.UsernameStatus
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.GroupedTextField
import io.github.iufb.reach.ui.components.ListScreen
import io.github.iufb.reach.ui.components.PillTextButton
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.SheetHeader
import io.github.iufb.reach.ui.theme.ReachTheme

/**
 * «Изменить профиль» как в Telegram iOS: фото, имя и фамилия, «о себе» со счётчиком, имя пользователя
 * (своя шторка с проверкой по мере ввода), телефон для справки. «Готово» сохраняет изменённое.
 */
@Composable
fun EditProfileScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val bioMax by viewModel.bioMax.collectAsStateWithLifecycle()
    val usernameStatus by viewModel.usernameStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    val me = profile
    // Поля повторяют профиль, пока их не трогали: «о себе» приходит отдельным запросом позже имени
    var first by rememberSaveable { mutableStateOf("") }
    var last by rememberSaveable { mutableStateOf("") }
    var bio by rememberSaveable { mutableStateOf("") }
    var touched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(me) {
        if (me != null && !touched) {
            first = me.user.firstName
            last = me.user.lastName
            bio = me.bio
        }
    }
    var editingUsername by rememberSaveable { mutableStateOf(false) }
    var photoSheet by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::setPhoto) }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    val colors = ReachTheme.colors

    val nameChanged = me != null && (first.trim() != me.user.firstName || last.trim() != me.user.lastName)
    val bioChanged = me != null && bio.trim() != me.bio
    val nameHint = stringResource(R.string.edit_profile_name_hint)
    val bioFooter = stringResource(R.string.edit_profile_bio_hint, (bioMax - bio.length).coerceAtLeast(0))
    val firstHint = stringResource(R.string.contacts_first_name)
    val lastHint = stringResource(R.string.contacts_last_name)
    val bioTitle = stringResource(R.string.profile_bio)
    val usernameTitle = stringResource(R.string.profile_username)
    val usernameEmpty = stringResource(R.string.settings_username_empty)
    val phoneTitle = stringResource(R.string.profile_phone)
    val photoLabel = stringResource(if (me?.hasPhoto == true) R.string.settings_photo_edit else R.string.settings_photo_set)

    ListScreen(
        title = stringResource(R.string.edit_profile_title),
        onBack = onBack,
        snackbar = snackbar,
        loading = me == null,
        actions = {
            PillTextButton(
                stringResource(R.string.new_chat_done),
                onClick = {
                    if (nameChanged) viewModel.setName(first, last)
                    if (bioChanged) viewModel.setBio(bio.trim())
                    onBack()
                },
                bold = true,
                enabled = (nameChanged || bioChanged) && first.isNotBlank(),
            )
        },
    ) {
        if (me == null) return@ListScreen
        item(key = "photo") {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(
                    me.user.fullName,
                    me.user.photo,
                    me.user.accentColorId,
                    modifier = Modifier.clip(CircleShape).clickable { if (me.hasPhoto) photoSheet = true else pick() },
                    size = AVATAR,
                )
                Text(
                    photoLabel,
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .clip(CircleShape)
                        .clickable { if (me.hasPhoto) photoSheet = true else pick() }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.accent,
                )
            }
        }
        item(key = "name") {
            GroupedSection(footer = nameHint) {
                GroupedTextField(first, { touched = true; first = it.take(NAME_MAX) }, placeholder = firstHint)
                GroupedTextField(last, { touched = true; last = it.take(NAME_MAX) }, placeholder = lastHint, divider = false)
            }
        }
        item(key = "bio") {
            GroupedSection(header = bioTitle, footer = bioFooter) {
                GroupedTextField(bio, { touched = true; bio = it.take(bioMax) }, placeholder = bioTitle, singleLine = false, divider = false)
            }
        }
        item(key = "account") {
            GroupedSection {
                GroupedCell(
                    usernameTitle,
                    onClick = {
                        viewModel.usernameInput.value = me.user.username
                        editingUsername = true
                    },
                    value = me.user.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: usernameEmpty,
                )
                GroupedCell(phoneTitle, value = "+${me.user.phoneNumber}", divider = false)
            }
        }
    }

    if (photoSheet && me != null) {
        ActionSheet(
            actions = listOf(
                SheetAction(stringResource(R.string.settings_photo_change)) { pick() },
                SheetAction(stringResource(R.string.settings_photo_delete), destructive = true) { viewModel.deletePhoto() },
            ),
            onDismiss = { photoSheet = false },
        )
    }
    if (editingUsername && me != null) {
        UsernameSheet(
            initial = me.user.username,
            status = usernameStatus,
            onChange = { viewModel.usernameInput.value = it },
            onSave = {
                editingUsername = false
                viewModel.usernameInput.value = null
                viewModel.setUsername(it)
            },
            onDismiss = {
                editingUsername = false
                viewModel.usernameInput.value = null
            },
        )
    }
}

/** Имя пользователя во весь экран: «@» перед полем, итог проверки — под карточкой; пустое — убрать username. */
@Composable
fun UsernameSheet(
    initial: String,
    status: UsernameStatus?,
    onChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val colors = ReachTheme.colors
    val canSave = text.isEmpty() || status?.isOk == true
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(colors.groupedBackground).statusBarsPadding().navigationBarsPadding().imePadding()) {
            SheetHeader(
                title = stringResource(R.string.profile_username),
                cancel = stringResource(R.string.cancel),
                onCancel = onDismiss,
                action = stringResource(R.string.save),
                actionEnabled = canSave,
                onAction = { onSave(text) },
            )
            val footer = when {
                text.isEmpty() -> stringResource(R.string.username_remove_hint)
                status != null -> stringResource(status.message)
                else -> stringResource(R.string.edit_profile_username_hint)
            }
            GroupedSection(footer = footer) {
                GroupedTextField(
                    text,
                    {
                        text = it.removePrefix("@").filter { c -> c.isLetterOrDigit() || c == '_' }
                        onChange(text)
                    },
                    placeholder = stringResource(R.string.profile_username),
                    prefix = "@",
                    divider = false,
                    focusRequester = focus,
                )
            }
        }
    }
}

private val AVATAR = 100.dp
private const val NAME_MAX = 64
