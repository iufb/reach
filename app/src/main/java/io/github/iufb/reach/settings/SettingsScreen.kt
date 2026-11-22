package io.github.iufb.reach.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.UsernameStatus
import io.github.iufb.reach.contacts.ContactNameDialog
import io.github.iufb.reach.navigation.MainTab
import io.github.iufb.reach.navigation.MainTabBar
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.Avatar
import io.github.iufb.reach.ui.components.CellIcon
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.TabBarHeight
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.CellIconColors
import io.github.iufb.reach.ui.theme.ReachTheme

/** Какой пункт профиля сейчас правится. */
private enum class Editing { Name, Bio, Username }

/**
 * Настройки как в Telegram iOS: крупная шапка с аватаром, именем и номером, ниже — сгруппированные
 * карточки с цветными значками. Когда шапка уезжает под бар, в нём проявляется имя.
 */
@Composable
fun SettingsScreen(
    onSelectTab: (MainTab) -> Unit,
    onOpenAutoDownload: () -> Unit,
    onOpenBlocked: (() -> Unit)? = null,
    onOpenContactSync: (() -> Unit)? = null,
    /** Каталог компонентов дизайна; только в debug-сборках. */
    onOpenComponents: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val loggingOut by viewModel.loggingOut.collectAsStateWithLifecycle()
    val bioMax by viewModel.bioMax.collectAsStateWithLifecycle()
    val usernameStatus by viewModel.usernameStatus.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.errors.collect { snackbar.showSnackbar(it) } }
    var editing by rememberSaveable { mutableStateOf<Editing?>(null) }
    var photoSheet by rememberSaveable { mutableStateOf(false) }
    var confirmLogOut by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::setPhoto)
    }
    // С фото — лист «выбрать / удалить», без фото — сразу выбор
    val pickOrEditPhoto = {
        if (profile?.hasPhoto == true) photoSheet = true
        else picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val colors = ReachTheme.colors
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight
    // Низ шапки профиля в координатах прокрутки: когда он ушёл под бар, имя показывается в баре
    var headerBottom by remember { mutableIntStateOf(Int.MAX_VALUE) }
    val collapsed by remember { derivedStateOf { scroll.value > headerBottom - with(density) { topInset.roundToPx() } } }
    val titleAlpha by animateFloatAsState(if (collapsed) 1f else 0f, tween(TITLE_FADE_MS), label = "title")

    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            val me = profile
            if (loggingOut) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = colors.accent)
            } else if (me != null) {
                // Пока профиль не пришёл (кадр-другой после перехода) — пустой фон, без мелькания спиннера
                Column(
                    Modifier
                        .fillMaxSize()
                        .glassSource()
                        .verticalScroll(scroll)
                        .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + HEADER_TOP)
                        .navigationBarsPadding()
                        .padding(bottom = TabBarHeight + 32.dp),
                ) {
                    ProfileHeader(
                        me,
                        onPhoto = pickOrEditPhoto,
                        modifier = Modifier.onPlaced { headerBottom = (it.positionInParent().y + it.size.height).toInt() },
                    )
                    GroupedSection {
                        GroupedCell(
                            stringResource(if (me.hasPhoto) R.string.settings_photo_edit else R.string.settings_photo_set),
                            onClick = pickOrEditPhoto,
                            icon = { CellIcon(painterResource(R.drawable.ic_photo), colors.accent) },
                            accent = true,
                            chevron = false,
                            divider = false,
                        )
                    }
                    GroupedSection {
                        GroupedCell(stringResource(R.string.settings_name), onClick = { editing = Editing.Name }, value = me.user.fullName)
                        GroupedCell(
                            stringResource(R.string.profile_username),
                            onClick = {
                                viewModel.usernameInput.value = me.user.username
                                editing = Editing.Username
                            },
                            value = if (me.user.username.isBlank()) stringResource(R.string.settings_username_empty) else "@${me.user.username}",
                        )
                        GroupedCell(
                            stringResource(R.string.profile_bio),
                            onClick = { editing = Editing.Bio },
                            subtitle = me.bio.ifBlank { stringResource(R.string.settings_bio_empty) },
                        )
                        // Номер виден, но не меняется: смена номера — вне скоупа
                        GroupedCell(stringResource(R.string.profile_phone), value = "+${me.user.phoneNumber}", divider = false)
                    }
                    GroupedSection {
                        val rows = buildList {
                            add(SettingsLink(R.string.autodownload_title, R.drawable.ic_download, CellIconColors.Green, onOpenAutoDownload))
                            onOpenBlocked?.let { add(SettingsLink(R.string.blocked_title, R.drawable.ic_lock, CellIconColors.Gray, it)) }
                            onOpenContactSync?.let { add(SettingsLink(R.string.contact_sync_title, R.drawable.ic_tab_contacts, CellIconColors.Orange, it)) }
                        }
                        rows.forEachIndexed { index, row -> LinkCell(row, divider = index < rows.lastIndex) }
                    }
                    onOpenComponents?.let {
                        GroupedSection { LinkCell(SettingsLink(R.string.components_catalog, R.drawable.ic_select, CellIconColors.Purple, it), divider = false) }
                    }
                    GroupedSection {
                        GroupedCell(stringResource(R.string.logout), onClick = { confirmLogOut = true }, destructive = true, chevron = false, divider = false)
                    }
                }
            }
            ReachTopBar(
                title = me?.user?.fullName,
                background = colors.groupedBackground,
                titleAlpha = { titleAlpha },
                scrimAlpha = { titleAlpha },
            )
            MainTabBar(MainTab.Settings, onSelectTab, Modifier.align(Alignment.BottomCenter))
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = TabBarHeight + 16.dp))
            if (me != null) {
                when (editing) {
                    Editing.Name -> ContactNameDialog(
                        title = stringResource(R.string.settings_name),
                        initialFirst = me.user.firstName,
                        initialLast = me.user.lastName,
                        onSave = { first, last ->
                            editing = null
                            viewModel.setName(first, last)
                        },
                        onDismiss = { editing = null },
                    )
                    Editing.Bio -> TextDialog(
                        title = stringResource(R.string.profile_bio),
                        initial = me.bio,
                        maxLength = bioMax,
                        onSave = {
                            editing = null
                            viewModel.setBio(it)
                        },
                        onDismiss = { editing = null },
                    )
                    Editing.Username -> UsernameDialog(
                        initial = me.user.username,
                        status = usernameStatus,
                        onChange = { viewModel.usernameInput.value = it },
                        onSave = {
                            editing = null
                            viewModel.setUsername(it)
                        },
                        onDismiss = {
                            editing = null
                            viewModel.usernameInput.value = null
                        },
                    )
                    null -> Unit
                }
                if (photoSheet) {
                    ActionSheet(
                        actions = buildList {
                            add(
                                SheetAction(stringResource(R.string.settings_photo_change)) {
                                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                },
                            )
                            if (me.hasPhoto) add(SheetAction(stringResource(R.string.settings_photo_delete), destructive = true) { viewModel.deletePhoto() })
                        },
                        onDismiss = { photoSheet = false },
                    )
                }
            }
        }
    }
    if (confirmLogOut) {
        ActionSheet(
            title = stringResource(R.string.logout_confirm_text),
            actions = listOf(SheetAction(stringResource(R.string.logout), destructive = true) { viewModel.logOut() }),
            onDismiss = { confirmLogOut = false },
        )
    }
}

/** Аватар (тап — сменить или удалить фото), имя и строка «номер • @username», по центру. */
@Composable
private fun ProfileHeader(me: MyProfile, onPhoto: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ReachTheme.colors
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Avatar(
            me.user.fullName,
            me.user.photo,
            me.user.accentColorId,
            modifier = Modifier.clip(CircleShape).clickable(onClickLabel = stringResource(R.string.settings_photo_change), onClick = onPhoto),
            size = AVATAR,
        )
        Text(
            me.user.fullName,
            modifier = Modifier.padding(top = 12.dp, start = 16.dp, end = 16.dp),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = colors.label,
            textAlign = TextAlign.Center,
        )
        Text(
            listOfNotNull("+${me.user.phoneNumber}", me.user.username.takeIf { it.isNotBlank() }?.let { "@$it" }).joinToString(" • "),
            modifier = Modifier.padding(top = 2.dp, start = 16.dp, end = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.secondaryLabel,
            textAlign = TextAlign.Center,
        )
    }
}

/** Переход в раздел настроек: цветной значок, название, шеврон. */
private class SettingsLink(@StringRes val title: Int, @DrawableRes val icon: Int, val color: Color, val onClick: () -> Unit)

@Composable
private fun LinkCell(link: SettingsLink, divider: Boolean) {
    GroupedCell(
        stringResource(link.title),
        onClick = link.onClick,
        icon = { CellIcon(painterResource(link.icon), link.color) },
        divider = divider,
    )
}

private val HEADER_TOP = 8.dp
private val AVATAR = 100.dp
private const val TITLE_FADE_MS = 180

@Composable
private fun TextDialog(title: String, initial: String, maxLength: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(maxLength) },
                supportingText = { Text("${text.length} / $maxLength") },
                maxLines = 4,
            )
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Username с проверкой по мере ввода; пустой — убрать username. */
@Composable
fun UsernameDialog(
    initial: String,
    status: UsernameStatus?,
    onChange: (String) -> Unit,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.profile_username)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it.removePrefix("@").filter { c -> c.isLetterOrDigit() || c == '_' }
                    onChange(text)
                },
                prefix = { Text("@") },
                singleLine = true,
                isError = status != null && !status.isOk,
                supportingText = {
                    when {
                        text.isEmpty() -> Text(stringResource(R.string.username_remove_hint))
                        status != null -> Text(stringResource(status.message))
                    }
                },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isEmpty() || status?.isOk == true, onClick = { onSave(text) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
