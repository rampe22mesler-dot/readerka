package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readerka.data.LibrarySnapshot
import org.readerka.data.ResearchField
import java.util.UUID

@Composable fun FieldInsertionDialog(data: LibrarySnapshot, vm: LibraryViewModel) {
    val entryId by vm.fieldInsertion.collectAsState()
    val id = entryId ?: return
    var title by remember(id) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { vm.fieldInsertion.value = null }, title = { Text("Добавить на исследовательское поле") }, text = {
        Column {
            Text(data.entries.firstOrNull { it.id == id }?.title.orEmpty())
            LazyColumn(Modifier.heightIn(max = 280.dp)) { items(data.researchFields, key = { it.id }) { field ->
                TextButton(onClick = { vm.addEntryToField(id, field.id) }) { Text(field.title) }
            } }
            OutlinedTextField(title, { title = it }, label = { Text("Новое поле") }, modifier = Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = {
        val field = ResearchField(UUID.randomUUID().toString(), title.trim())
        vm.perform { vm.repository.saveResearchField(field); vm.addEntryToField(id, field.id).join() }
    }) { Text("Создать и добавить") } }, dismissButton = { TextButton(onClick = { vm.fieldInsertion.value = null }) { Text("Отмена") } })
}
