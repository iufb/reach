package io.github.iufb.reach.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.iufb.reach.R
import io.github.iufb.reach.ui.components.ActionSheet
import io.github.iufb.reach.ui.components.SheetAction

/**
 * Запрос на удаление: какие сообщения и как их можно удалить.
 * Удалить у всех и только у себя — разные права (`canBeDeletedForAllUsers` / `canBeDeletedOnlyForSelf`).
 */
data class DeleteRequest(
    val messageIds: List<Long>,
    /** Можно удалить у всех (revoke). */
    val canForAll: Boolean,
    /** Можно удалить только у себя. Если нельзя, удаление идёт сразу у всех. */
    val canForSelf: Boolean,
)

/**
 * Подтверждение удаления — action sheet Telegram iOS: заголовок с числом сообщений и красные варианты
 * «Удалить у всех» / «Удалить у меня» (какие доступны), ниже отдельной карточкой «Отмена».
 */
@Composable
internal fun DeleteDialog(request: DeleteRequest, onConfirm: (revoke: Boolean) -> Unit, onDismiss: () -> Unit) {
    val count = request.messageIds.size
    // ActionSheet сначала закрывается, потом выполняет пункт, а закрытие сбрасывает запрос в модели.
    // Поэтому «отмену» откладываем: если следом выбрали удаление, отменять нечего
    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    val confirm = { revoke: Boolean ->
        confirmed = true
        onConfirm(revoke)
    }
    val dismiss = { scope.launch { if (!confirmed) onDismiss() }; Unit }
    val forAll = stringResource(R.string.delete_for_all)
    val forMe = stringResource(R.string.delete_for_me)
    val plain = stringResource(R.string.delete_confirm)
    val actions = buildList {
        when {
            request.canForAll && request.canForSelf -> {
                add(SheetAction(forAll, destructive = true) { confirm(true) })
                add(SheetAction(forMe, destructive = true) { confirm(false) })
            }
            // Выбора нет: удаление идёт так, как разрешено
            else -> add(SheetAction(plain, destructive = true) { confirm(request.canForAll) })
        }
    }
    ActionSheet(actions, onDismiss = dismiss, title = pluralStringResource(R.plurals.delete_title, count, count))
}
