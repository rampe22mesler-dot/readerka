package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.*
import org.readerka.export.*

@Composable fun SettingsScreen(preferences: ReaderPreferences, data: LibrarySnapshot, vm: LibraryViewModel, onExport: (ExportKind, Set<String>?) -> Unit, onRestore: () -> Unit) {
    var include by remember { mutableStateOf(true) }
    var manageTags by remember { mutableStateOf(false) }
    var editedTag by remember { mutableStateOf<Tag?>(null) }
    var tagName by remember { mutableStateOf("") }
    var deletedTag by remember { mutableStateOf<Tag?>(null) }
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
        Choice(stringResource(R.string.theme), preferences.theme, ThemeMode.entries, { stringResource(when (it) { ThemeMode.System -> R.string.theme_system; ThemeMode.Light -> R.string.theme_light; ThemeMode.Dark -> R.string.theme_dark }) }, { vm.settings(preferences.copy(theme = it)) }, Modifier.widthIn(max = 520.dp))
        Row(Modifier.widthIn(max = 520.dp), verticalAlignment = Alignment.CenterVertically) { Text("Компактная плотность", Modifier.weight(1f)); Switch(preferences.compact, { vm.settings(preferences.copy(compact = it)) }) }
        Text("Области касания остаются не меньше 48 dp в обоих режимах.", style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.default_color), style = MaterialTheme.typography.titleMedium)
        ColorPalette(preferences.defaultColor) { vm.settings(preferences.copy(defaultColor = it)) }
        Text(stringResource(R.string.ui_scale))
        Slider(preferences.scale, { vm.settings(preferences.copy(scale = it)) }, valueRange = .85f..1.4f, steps = 10, modifier = Modifier.widthIn(max = 520.dp))
        TextButton(onClick = { manageTags = true }) { Text(stringResource(R.string.manage_tags)) }
        HorizontalDivider()
        Text(stringResource(R.string.obsidian_explanation), modifier = Modifier.widthIn(max = 650.dp))
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(include, { include = it }); Spacer(Modifier.width(12.dp)); Text(stringResource(R.string.include_pdf)) }
        TextButton(onClick = { vm.includePdf = include; onExport(ExportKind.Obsidian, null) }) { Text(stringResource(R.string.export_all_obsidian)) }
        TextButton(onClick = { onExport(ExportKind.Markdown, null) }) { Text(stringResource(R.string.export_markdown)) }
        TextButton(onClick = { onExport(ExportKind.Csv, null) }) { Text(stringResource(R.string.export_csv)) }
        HorizontalDivider()
        TextButton(onClick = { onExport(ExportKind.Backup, null) }) { Text(stringResource(R.string.backup)) }
        Text(stringResource(R.string.restore_explanation), modifier = Modifier.widthIn(max = 650.dp))
        TextButton(onClick = onRestore) { Text(stringResource(R.string.restore)) }
        var backupsOpen by remember { mutableStateOf(false) }
        TextButton(onClick = { backupsOpen = true }) { Text("Автоматические резервные копии перед переносом") }
        if (backupsOpen) AlertDialog(onDismissRequest = { backupsOpen = false }, title = { Text("Резервные копии") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                val backups = vm.automaticBackups()
                if (backups.isEmpty()) Text("Резервных копий пока нет")
                backups.forEach { file -> TextButton(onClick = { backupsOpen = false; vm.reviewAutomaticBackup(file) }) { Text(java.text.DateFormat.getDateTimeInstance().format(java.util.Date(file.lastModified()))) } }
            }
        }, confirmButton = { TextButton(onClick = { backupsOpen = false }) { Text("Закрыть") } })

        HorizontalDivider()
        Text(stringResource(R.string.keyboard_shortcuts), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.offline_notice), style = MaterialTheme.typography.bodySmall)
    }
    if (manageTags) AlertDialog(onDismissRequest = { manageTags = false }, title = { Text(stringResource(R.string.manage_tags)) }, text = {
        LazyColumn(Modifier.heightIn(max = 450.dp)) { items(data.tags, key = { it.id }) { tag -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tag.name, modifier = Modifier.weight(1f))
            IconButton(onClick = { editedTag = tag; tagName = tag.name }) { Icon(Outline.Edit, stringResource(R.string.rename_tag)) }
            IconButton(onClick = { deletedTag = tag }) { Icon(Icons.Default.Delete, stringResource(R.string.delete_tag)) }
        } } }
    }, confirmButton = { TextButton(onClick = { manageTags = false }) { Text(stringResource(R.string.close)) } })
    editedTag?.let { tag -> AlertDialog(onDismissRequest = { editedTag = null }, title = { Text(stringResource(R.string.rename_tag)) }, text = { OutlinedTextField(tagName, { tagName = it }, label = { Text(stringResource(R.string.tag)) }) },
        confirmButton = { TextButton(enabled = tagName.isNotBlank(), onClick = { vm.renameTag(tag.id, tagName); editedTag = null }) { Text(stringResource(R.string.save)) } }, dismissButton = { TextButton(onClick = { editedTag = null }) { Text(stringResource(R.string.cancel)) } }) }
    deletedTag?.let { tag -> Confirm(R.string.delete_tag, R.string.delete_tag_body, { deletedTag = null }, { vm.deleteTag(tag.id); deletedTag = null }) }
}
