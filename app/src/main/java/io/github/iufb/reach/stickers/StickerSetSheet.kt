package io.github.iufb.reach.stickers

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.chats.StickerInfo
import io.github.iufb.reach.errors.UserErrors
import io.github.iufb.reach.ui.components.AccentPillButton
import io.github.iufb.reach.ui.components.DelayedSkeleton
import io.github.iufb.reach.ui.components.PillHeight
import io.github.iufb.reach.ui.components.SkeletonBox
import io.github.iufb.reach.ui.theme.ReachTheme
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Что показывает лист пака. */
sealed interface StickerSetState {
    data object Loading : StickerSetState
    data object Failed : StickerSetState
    data class Loaded(val set: StickerSetInfo) : StickerSetState
}

/** Лист пака: полный пак из [StickersRepository], добавление и удаление. Один экземпляр на пак. */
@HiltViewModel
class StickerSetViewModel @Inject constructor(
    private val stickers: StickersRepository,
    private val userErrors: UserErrors,
) : ViewModel() {

    private val setId = MutableStateFlow<Long?>(null)
    private val failed = MutableStateFlow(false)

    /** Пак из кеша репозитория: флаг «установлен» меняется там же после добавления / удаления. */
    val state: StateFlow<StickerSetState> = combine(setId, stickers.sets, failed) { id, sets, failed ->
        val set = id?.let(sets::get)
        when {
            set != null -> StickerSetState.Loaded(set)
            failed -> StickerSetState.Failed
            else -> StickerSetState.Loading
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StickerSetState.Loading)

    private val _busy = MutableStateFlow(false)

    /** Идёт добавление или удаление — кнопка неактивна. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    fun load(id: Long) {
        if (setId.value == id) return
        setId.value = id
        failed.value = false
        viewModelScope.launch { if (stickers.set(id) == null) failed.value = true }
    }

    /** «Добавить» для неустановленного (и архивного) пака, «Удалить» — для установленного. */
    fun toggleInstalled() {
        val set = (state.value as? StickerSetState.Loaded)?.set ?: return
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            runCatching { if (set.isInstalled) stickers.uninstall(set.id) else stickers.install(set.id) }
                .onFailure { _errors.tryEmit(userErrors.message(it)) }
            _busy.value = false
        }
    }
}

/**
 * Лист пака стикеров, как в Telegram iOS: название, сетка всех стикеров ([StickerCell]) и внизу
 * «Добавить N стикеров» или красное «Удалить N стикеров».
 *
 * Открывается тапом на стикер в ленте, из трендов, из превью стикера и по ссылке
 * `t.me/addstickers/<name>` (id пака — [StickersRepository.setByName]).
 * [onSend] — отправить стикер в чат, из которого открыт лист: тап по стикеру отправляет его
 * и закрывает лист. `null` — отправлять некуда или нельзя, стикеры только для просмотра.
 * Долгое нажатие на стикер — превью с действиями ([StickerPreview]), без «Открыть пак».
 */
@Composable
fun StickerSetSheet(setId: Long, onDismiss: () -> Unit, onSend: ((StickerInfo) -> Unit)? = null) {
    val viewModel: StickerSetViewModel = hiltViewModel(key = "sticker_set_$setId")
    LaunchedEffect(setId) { viewModel.load(setId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.errors.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }

    val colors = ReachTheme.colors
    val shown = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { shown.targetState = true }
    var closing by remember { mutableStateOf(false) }
    fun close() {
        closing = true
        shown.targetState = false
    }
    LaunchedEffect(shown.isIdle, shown.currentState) {
        if (closing && shown.isIdle && !shown.currentState) onDismiss()
    }

    Dialog(
        onDismissRequest = { close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            val maxSheetHeight = maxHeight * SHEET_MAX_FRACTION
            AnimatedVisibility(shown, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxSheetHeight)
                        .clip(RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER))
                        .background(colors.background)
                        // Тап по листу не закрывает его
                        .clickable(remember { MutableInteractionSource() }, indication = null) {},
                ) {
                    val set = (state as? StickerSetState.Loaded)?.set
                    SheetHeader(set?.title ?: stringResource(R.string.sticker_set), onClose = { close() })
                    when (val current = state) {
                        StickerSetState.Loading -> StickerGridSkeleton()
                        StickerSetState.Failed -> Placeholder {
                            Text(
                                stringResource(R.string.sticker_set_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.secondaryLabel,
                                textAlign = TextAlign.Center,
                            )
                        }
                        is StickerSetState.Loaded -> {
                            StickerGrid(
                                current.set.stickers,
                                Modifier.weight(1f, fill = false),
                                onClick = onSend?.let { send ->
                                    { sticker: StickerInfo ->
                                        send(sticker)
                                        close()
                                    }
                                },
                            )
                            InstallButton(current.set, busy, viewModel::toggleInstalled)
                        }
                    }
                    if (state is StickerSetState.Failed) Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Серый кружок, а не стеклянная пилюля: под шапкой листа нет фона для размытия
        Box(
            Modifier
                .size(PillHeight)
                .clip(CircleShape)
                .background(ReachTheme.colors.fill)
                .clickable(role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close), Modifier.size(22.dp), tint = ReachTheme.colors.label)
        }
        Text(
            title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = ReachTheme.colors.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(PillHeight))
    }
}

@Composable
private fun Placeholder(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxWidth().height(PLACEHOLDER_HEIGHT).padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        content()
    }
}

/** Скелетон набора: ячейки той же сетки и пилюля на месте кнопки «Добавить». */
@Composable
internal fun StickerGridSkeleton() {
    DelayedSkeleton {
        Column {
            Column(Modifier.fillMaxWidth().padding(GRID_PADDING)) {
                repeat(SKELETON_ROWS) {
                    Row(Modifier.fillMaxWidth()) {
                        repeat(GRID_COLUMNS) {
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(SKELETON_CELL_PADDING)) {
                                SkeletonBox(Modifier.fillMaxSize(), RoundedCornerShape(14.dp))
                            }
                        }
                    }
                }
            }
            Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                SkeletonBox(Modifier.fillMaxWidth().height(BUTTON_HEIGHT))
            }
        }
    }
}

@Composable
private fun StickerGrid(stickers: List<StickerInfo>, modifier: Modifier, onClick: ((StickerInfo) -> Unit)?) {
    val preview = rememberStickerPreviewState()
    val grid = rememberLazyGridState()
    val byKey = remember(stickers) { stickers.associateBy { it.file.id } }
    StickerPreview(preview, onSend = onClick, onOpenSet = null)
    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        modifier = modifier
            .fillMaxWidth()
            .stickerPreviewDrag(preview, grid, GRID_PADDING) { key -> byKey[key]?.let { StickerPick(it) } },
        state = grid,
        contentPadding = GRID_PADDING,
    ) {
        items(stickers, key = { it.file.id }) { sticker ->
            StickerCell(
                sticker,
                Modifier.stickerCellGestures(preview, StickerPick(sticker), onClick?.let { { it(sticker) } }),
            )
        }
    }
}

/** «Добавить N стикеров» (заливная) или «Удалить N стикеров» (красный текст на серой пилюле). */
@Composable
private fun InstallButton(set: StickerSetInfo, busy: Boolean, onClick: () -> Unit) {
    val colors = ReachTheme.colors
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (set.isInstalled) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BUTTON_HEIGHT)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.fill)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onClick),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    pluralStringResource(R.plurals.sticker_set_remove, set.size, set.size),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (busy) colors.tertiaryLabel else colors.destructive,
                )
            }
        } else {
            AccentPillButton(
                pluralStringResource(R.plurals.sticker_set_add, set.size, set.size),
                onClick = onClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
    }
}

private val SHEET_CORNER = 20.dp
private val GRID_PADDING = PaddingValues(horizontal = 8.dp)
private val PLACEHOLDER_HEIGHT = 240.dp
private val BUTTON_HEIGHT = 50.dp
private const val SHEET_MAX_FRACTION = 0.85f
private const val GRID_COLUMNS = 5
private const val SKELETON_ROWS = 3
private val SKELETON_CELL_PADDING = 8.dp
