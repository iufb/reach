package io.github.iufb.reach.settings

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.EmojiSupportMatch
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.iufb.reach.R
import io.github.iufb.reach.fonts.DeviceFonts
import io.github.iufb.reach.fonts.FontImportException
import io.github.iufb.reach.fonts.FontKind
import io.github.iufb.reach.fonts.FontSelection
import io.github.iufb.reach.fonts.FontStore
import io.github.iufb.reach.fonts.LocalFont
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.BackPill
import io.github.iufb.reach.ui.components.GlassScope
import io.github.iufb.reach.ui.components.GroupedCell
import io.github.iufb.reach.ui.components.GroupedSection
import io.github.iufb.reach.ui.components.ReachTopBar
import io.github.iufb.reach.ui.components.SheetAction
import io.github.iufb.reach.ui.components.TopBarHeight
import io.github.iufb.reach.ui.components.glassSource
import io.github.iufb.reach.ui.theme.ReachTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Превью выбора: [typeface] `null` — стандартный Inter; [customEmoji] — эмодзи рисует свой шрифт, не EmojiCompat. */
data class FontPreview(val typeface: Typeface?, val customEmoji: Boolean)

@HiltViewModel
class FontViewModel @Inject constructor(private val store: FontStore, applied: DeviceFonts) : ViewModel() {
    val fonts: StateFlow<List<LocalFont>> = store.fonts()
    val selection: StateFlow<FontSelection> = store.selection()

    /** Что загружено в этом процессе; выбор, отличный от него, применяется перезапуском. */
    val applied = FontSelection(applied.textId, applied.emojiId)

    @OptIn(ExperimentalCoroutinesApi::class)
    val preview: StateFlow<FontPreview> = selection
        .mapLatest { FontPreview(store.preview(it.text, it.emoji), it.emoji != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), FontPreview(null, false))

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    /** Ошибка последнего импорта; пустое сообщение — непредвиденная ошибка (общий текст). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun select(kind: FontKind, id: String?) {
        when (kind) {
            FontKind.Text -> store.selectedText = id
            FontKind.Emoji -> store.selectedEmoji = id
        }
    }

    /** Импорт файла; удачный сразу выбирается в своём разделе (вид определяет сам файл). */
    fun import(uri: Uri) {
        viewModelScope.launch {
            _importing.value = true
            _error.value = null
            store.import(uri)
                .onSuccess { select(it.kind, it.id) }
                .onFailure { _error.value = (it as? FontImportException)?.problem?.message.orEmpty() }
            _importing.value = false
        }
    }

    fun delete(id: String) = store.delete(id)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Настройки → Шрифт (#274): шрифт для текста и для эмодзи выбираются отдельно, свой файл добавляется
 * системным выбором файла. Строка-пример рисуется выбранным сразу, интерфейс — после перезапуска.
 */
@Composable
fun FontScreen(onBack: () -> Unit, viewModel: FontViewModel = hiltViewModel()) {
    val fonts by viewModel.fonts.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::import)
    }
    val addFile = { picker.launch(FONT_MIME_TYPES) }
    val colors = ReachTheme.colors
    GlassScope {
        Box(Modifier.fillMaxSize().background(colors.groupedBackground)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .glassSource()
                    .verticalScroll(rememberScrollState())
                    .padding(top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + TopBarHeight)
                    .navigationBarsPadding()
                    .padding(bottom = 16.dp),
            ) {
                FontSection(
                    header = stringResource(R.string.font_text),
                    standard = stringResource(R.string.font_text_standard),
                    fonts = fonts.filter { it.kind == FontKind.Text },
                    selected = selection.text,
                    importing = importing,
                    onSelect = { viewModel.select(FontKind.Text, it) },
                    onDelete = { deleting = it },
                    onAdd = addFile,
                )
                FontSection(
                    header = stringResource(R.string.font_emoji),
                    standard = stringResource(R.string.font_emoji_standard),
                    fonts = fonts.filter { it.kind == FontKind.Emoji },
                    selected = selection.emoji,
                    importing = importing,
                    onSelect = { viewModel.select(FontKind.Emoji, it) },
                    onDelete = { deleting = it },
                    onAdd = addFile,
                )
                error?.let {
                    Text(
                        it.ifEmpty { stringResource(R.string.font_import_failed) },
                        modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.destructive,
                    )
                }
                GroupedSection(header = stringResource(R.string.font_preview), footer = stringResource(R.string.font_hint)) {
                    Text(
                        stringResource(R.string.font_preview_text),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        style = previewStyle(MaterialTheme.typography.bodyLarge, preview),
                        color = colors.label,
                    )
                }
                if (selection != viewModel.applied) {
                    GroupedSection {
                        GroupedCell(
                            stringResource(R.string.font_restart),
                            onClick = { restartApp(context) },
                            accent = true,
                            chevron = false,
                            divider = false,
                        )
                    }
                }
            }
            ReachTopBar(
                title = stringResource(R.string.font_title),
                navigation = { BackPill(onBack) },
                background = colors.groupedBackground,
            )
        }
    }
    deleting?.let { id ->
        ActionSheet(
            title = fonts.find { it.id == id }?.name,
            actions = listOf(SheetAction(stringResource(R.string.font_delete), destructive = true) { viewModel.delete(id) }),
            onDismiss = { deleting = null },
        )
    }
}

/** Раздел одного вида: стандартный шрифт, импортированные (долгое нажатие — удалить) и «Добавить файл…». */
@Composable
private fun FontSection(
    header: String,
    standard: String,
    fonts: List<LocalFont>,
    selected: String?,
    importing: Boolean,
    onSelect: (String?) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    val context = LocalContext.current
    GroupedSection(header = header) {
        GroupedCell(standard, onClick = { onSelect(null) }, chevron = false, trailing = { SelectedMark(selected == null) })
        fonts.forEach { font ->
            GroupedCell(
                font.name,
                modifier = Modifier.combinedClickable(
                    onClick = { onSelect(font.id) },
                    onLongClick = { onDelete(font.id) },
                ),
                subtitle = Formatter.formatShortFileSize(context, font.sizeBytes),
                trailing = { SelectedMark(selected == font.id) },
            )
        }
        GroupedCell(
            stringResource(if (importing) R.string.font_adding else R.string.font_add),
            onClick = if (importing) null else onAdd,
            accent = !importing,
            chevron = false,
            divider = false,
        )
    }
}

@Composable
private fun SelectedMark(selected: Boolean) {
    if (selected) {
        Icon(
            painterResource(R.drawable.ic_check),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = ReachTheme.colors.accent,
        )
    }
}

/**
 * Пример рисуется кандидатом. Свой emoji-шрифт стоит в fallback у [Typeface], поэтому EmojiCompat
 * (Noto) для примера выключается — иначе он подменит эмодзи своими.
 */
private fun previewStyle(base: TextStyle, preview: FontPreview): TextStyle = base.copy(
    fontFamily = preview.typeface?.let { FontFamily(it) } ?: base.fontFamily,
    platformStyle = PlatformTextStyle(
        emojiSupportMatch = if (preview.customEmoji) EmojiSupportMatch.None else EmojiSupportMatch.Default,
    ),
)

/** Перезапуск процесса: загрузчик шрифтов и EmojiCompat отработают с нуля. */
private fun restartApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    context.startActivity(Intent.makeRestartActivityTask(launch.component))
    Runtime.getRuntime().exit(0)
}

/** Многие файловые менеджеры отдают .ttf как octet-stream. */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/sfnt",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/font-sfnt",
    "application/octet-stream",
)
