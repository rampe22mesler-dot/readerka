package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readerka.data.LibrarySnapshot
import org.readerka.export.*

@Composable
fun ImportReviewDialog(
    staged: StagedBackup,
    current: LibrarySnapshot,
    cancel: () -> Unit,
    apply: (ImportPolicy) -> Unit,
) {
    var policy by remember(staged) { mutableStateOf(ImportPolicy.KeepLocal) }
    var replacementConfirmed by remember(staged) { mutableStateOf(false) }
    val conflicts = remember(staged, current) { LibraryMerge.differences(current, staged.data) }
    var detail by remember(staged) { mutableStateOf<ImportDifference?>(null) }
    AlertDialog(
        onDismissRequest = cancel,
        title = { Text("Перенос библиотеки") },
        text = {
            Column(
                Modifier.widthIn(max = 720.dp).heightIn(max = 580.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "В архиве: ${staged.data.documents.size} PDF и ${staged.data.entries.size} записей. Изменений существующих данных: ${conflicts.size}."
                )
                Text(
                    "Все открытые черновики будут сохранены. Перед импортом создаётся автоматическая резервная копия: её можно открыть в настройках."
                )
                listOf(
                        ImportPolicy.KeepLocal to "Сохранить местные: добавить только новые данные",
                        ImportPolicy.UseIncoming to
                            "Обновить из архива: заменить совпадающие записи",
                        ImportPolicy.Replace to
                            "Заменить библиотеку: также удалить отсутствующие данные",
                    )
                    .forEach { (value, label) ->
                        Row {
                            RadioButton(selected = policy == value, onClick = { policy = value })
                            TextButton(onClick = { policy = value }) { Text(label) }
                        }
                    }
                if (policy == ImportPolicy.Replace)
                    Row {
                        Checkbox(replacementConfirmed, { replacementConfirmed = it })
                        Text("Подтверждаю замену всей библиотеки", Modifier.padding(top = 12.dp))
                    }
                if (conflicts.isNotEmpty())
                    LazyColumn(Modifier.weight(1f, false)) {
                        items(conflicts) { c ->
                            TextButton(onClick = { detail = c }) { Text("${c.category}: ${c.title}") }
                        }
                    }
            }
        },
        confirmButton = {
            TextButton(
                enabled = policy != ImportPolicy.Replace || replacementConfirmed,
                onClick = { apply(policy) },
            ) {
                Text("Импортировать")
            }
        },
        dismissButton = { TextButton(onClick = cancel) { Text("Отмена") } },
    )
    detail?.let { c ->
        AlertDialog(
            onDismissRequest = { detail = null },
            title = { Text(c.category) },
            text = {
                LazyColumn(Modifier.heightIn(max = 550.dp)) {
                    item {
                        Text("На планшете", style = MaterialTheme.typography.titleMedium)
                        Text(c.before)
                        HorizontalDivider()
                        Text("В архиве", style = MaterialTheme.typography.titleMedium)
                        Text(c.after)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { detail = null }) { Text("Назад") } },
        )
    }
}
