package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.readerka.export.ImportPolicy

@Composable fun TransferDialog(pending: LibraryViewModel.PendingTransfer, vm: LibraryViewModel, busy: Boolean) {
    var policy by remember(pending) { mutableStateOf(ImportPolicy.KeepLocal) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = { if (!busy) vm.cancelTransfer() }, title = { Text("Сравнение библиотеки") }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZIP проверен. Изменения ещё не применены. Перед импортом будет сохранена резервная копия.")
            pending.counts.forEach { count -> Text("${count.category}: новых ${count.added}, изменённых ${count.changed}, только местных ${count.localOnly}") }
            if (pending.pdfConflicts.isNotEmpty()) Text("PDF с отличающимся содержимым: ${pending.pdfConflicts.size}")
            pending.differences.forEach { d ->
                Text("${d.category} · ${d.title}", style = MaterialTheme.typography.titleSmall)
                Text("Местное: ${d.before}\nВ архиве: ${d.after}", style = MaterialTheme.typography.bodySmall)
            }
            ImportPolicy.entries.forEach { p -> Row {
                RadioButton(policy == p, { policy = p }, enabled = !busy)
                TextButton(onClick = { policy = p }, enabled = !busy) { Text(when (p) {
                    ImportPolicy.KeepLocal -> "Сохранить местные"
                    ImportPolicy.UseIncoming -> "Обновить из архива"
                    ImportPolicy.Replace -> "Заменить библиотеку"
                }) }
            } }
            Text("Поля разрешаются целиком по ID. Это ручной обмен снимками: абзацы и удаления не объединяются автоматически.")
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = {
        if (policy == ImportPolicy.Replace) replaceConfirmation = true else vm.applyTransfer(policy)
    }, modifier = Modifier.testTag("transfer-apply")) { Text("Применить") } }, dismissButton = {
        TextButton(enabled = !busy, onClick = vm::cancelTransfer) { Text("Отмена") }
    })
    if (replaceConfirmation) AlertDialog(onDismissRequest = { replaceConfirmation = false }, title = { Text("Заменить всю библиотеку?") },
        text = { Text("Местные записи, которых нет в архиве, будут удалены из библиотеки. Резервная копия сохранится во внутреннем хранилище приложения; удаление приложения удалит и её. Сначала экспортируйте нужную копию через системный выбор файла.") },
        confirmButton = { TextButton(onClick = { replaceConfirmation = false; vm.applyTransfer(ImportPolicy.Replace) }) { Text("Подтверждаю замену") } },
        dismissButton = { TextButton(onClick = { replaceConfirmation = false }) { Text("Отмена") } })
}
