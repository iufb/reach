package io.github.iufb.reach.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.theme.reachBarColors

/** Новый опрос перед отправкой. */
data class PollDraft(
    val question: String,
    val options: List<String>,
    val isAnonymous: Boolean,
    val allowsMultipleAnswers: Boolean,
    /** Номера правильных вариантов в [options]; `null` — обычный опрос. */
    val correctOptionIds: List<Int>?,
    val explanation: String,
)

/**
 * Экран создания опроса поверх чата. [forceAnonymous] — канал: там опросы только анонимные.
 * [maxOptions] — `poll_answer_count_max` из опций TDLib.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CreatePollDialog(forceAnonymous: Boolean, maxOptions: Int, onCreate: (PollDraft) -> Unit, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    val options = remember { mutableStateListOf("", "") }
    var anonymous by remember { mutableStateOf(true) }
    var multiple by remember { mutableStateOf(false) }
    var quiz by remember { mutableStateOf(false) }
    var correct by remember { mutableStateOf(emptySet<Int>()) }
    var explanation by remember { mutableStateOf("") }
    val filled = options.withIndex().filter { it.value.isNotBlank() }
    val valid = question.isNotBlank() && question.length <= QUESTION_MAX && filled.size >= 2 &&
        options.all { it.length <= OPTION_MAX } && (!quiz || correct.any { options.getOrNull(it)?.isNotBlank() == true })

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            topBar = {
                TopAppBar(
                    colors = reachBarColors(),
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
                        }
                    },
                    title = { Text(stringResource(if (quiz) R.string.quiz_new else R.string.poll_new)) },
                    actions = {
                        TextButton(
                            enabled = valid,
                            onClick = {
                                // Пустые варианты выкидываем; номера правильных пересчитываем под оставшиеся
                                val kept = filled.map { it.index }
                                onCreate(
                                    PollDraft(
                                        question = question.trim(),
                                        options = filled.map { it.value.trim() },
                                        isAnonymous = anonymous || forceAnonymous,
                                        allowsMultipleAnswers = multiple,
                                        correctOptionIds = if (quiz) kept.withIndex().filter { it.value in correct }.map { it.index } else null,
                                        explanation = explanation.trim(),
                                    ),
                                )
                            },
                        ) { Text(stringResource(R.string.chat_send)) }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = question,
                    onValueChange = { question = it.take(QUESTION_MAX) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.poll_question)) },
                )
                Text(stringResource(R.string.poll_options), style = MaterialTheme.typography.titleSmall)
                options.forEachIndexed { i, text ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (quiz) {
                            if (multiple) {
                                Checkbox(checked = i in correct, onCheckedChange = { correct = if (it) correct + i else correct - i })
                            } else {
                                RadioButton(selected = i in correct, onClick = { correct = setOf(i) })
                            }
                        }
                        OutlinedTextField(
                            value = text,
                            onValueChange = { value ->
                                options[i] = value.take(OPTION_MAX)
                                // Последний заполнили — добавляем пустой, пока не упёрлись в лимит
                                if (i == options.lastIndex && value.isNotBlank() && options.size < maxOptions) options.add("")
                            },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text(stringResource(R.string.poll_option_hint, i + 1)) },
                            singleLine = true,
                        )
                        if (options.size > 2) {
                            IconButton(onClick = {
                                options.removeAt(i)
                                correct = correct.filter { it != i }.map { if (it > i) it - 1 else it }.toSet()
                            }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.poll_option_remove))
                            }
                        }
                    }
                }
                if (!forceAnonymous) SwitchRow(stringResource(R.string.poll_setting_anonymous), anonymous) { anonymous = it }
                SwitchRow(stringResource(R.string.poll_setting_multiple), multiple) {
                    multiple = it
                    if (!it && correct.size > 1) correct = setOf(correct.min())
                }
                SwitchRow(stringResource(R.string.poll_setting_quiz), quiz) { quiz = it }
                if (quiz) {
                    Text(
                        stringResource(R.string.quiz_pick_correct),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = explanation,
                        onValueChange = { explanation = it.take(EXPLANATION_MAX) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.quiz_explanation)) },
                        maxLines = 3,
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private const val QUESTION_MAX = 255
private const val OPTION_MAX = 100
private const val EXPLANATION_MAX = 200
