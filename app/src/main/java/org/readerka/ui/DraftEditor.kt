package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import android.view.KeyEvent
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.readerka.R
import org.readerka.data.*

@Composable fun DraftEditor(draft: EntryDraft, data: LibrarySnapshot, vm: LibraryViewModel, inline: Boolean = false, onEditorFocus: (Boolean) -> Unit = {}) {
    val status by vm.draftStatus.collectAsState()
    val saving by vm.editorSaving.collectAsState()
    var delete by remember(draft.entry.id) { mutableStateOf(false) }
    var discard by remember(draft.entry.id) { mutableStateOf(false) }
    val entry = draft.entry
    val content: @Composable () -> Unit = {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize().testTag("entry-editor")) {
            Column(Modifier.padding(12.dp).onFocusChanged { onEditorFocus(it.hasFocus) }.focusGroup().onPreviewKeyEvent { key ->
                val event = key.nativeKeyEvent
                when {
                    event.isCtrlPressed && event.keyCode in listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> { if (event.action == KeyEvent.ACTION_DOWN) vm.saveEditor(); true }
                    event.keyCode == KeyEvent.KEYCODE_ESCAPE -> { if (event.action == KeyEvent.ACTION_DOWN) vm.closeEditor(); true }
                    else -> false
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (entry.anchor == null) R.string.standalone_note else R.string.highlights), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(enabled = !saving, onClick = vm::closeEditor) { Icon(Icons.Default.Close, stringResource(R.string.close_editor)) }
                }
                Text(status, style = MaterialTheme.typography.labelSmall, modifier = Modifier.testTag("draft-status"))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(entry.title, { vm.updateDraft(draft.copy(entry = entry.copy(title = it))) }, enabled = !saving, label = { Text(stringResource(R.string.title)) }, modifier = Modifier.fillMaxWidth().testTag("entry-title"))
                    data.documents.firstOrNull { it.id == entry.documentId }?.let { document ->
                        Text(document.title, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    }
                    if (entry.anchor != null) {
                        Text(stringResource(R.string.source_quote), style = MaterialTheme.typography.labelLarge)
                        Text(entry.quote, style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.source_page, entry.anchor.page + 1), style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedTextField(entry.comment, { vm.updateDraft(draft.copy(entry = entry.copy(comment = it))) }, enabled = !saving, label = { Text(stringResource(if (entry.anchor == null) R.string.note_text else R.string.comment)) }, minLines = 4, modifier = Modifier.fillMaxWidth().testTag("entry-comment"))
                    Choice(stringResource(R.string.type), entry.type, NoteType.entries, { stringResource(it.labelId()) }, { vm.updateDraft(draft.copy(entry = entry.copy(type = it))) })
                    ColorPalette(entry.color) { vm.updateDraft(draft.copy(entry = entry.copy(color = it))) }
                    OutlinedTextField(draft.tags, { vm.updateDraft(draft.copy(tags = it)) }, enabled = !saving, label = { Text(stringResource(R.string.tags)) }, modifier = Modifier.fillMaxWidth().testTag("entry-tags"))
                    if (entry.documentId != null) {
                        FolderMembershipChoice(stringResource(R.string.folders_in_pdf), data.readerFolders.filter { it.documentId == entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { FolderItem(it.id, it.name, it.parentId) }, draft.readerFolders, { vm.updateDraft(draft.copy(readerFolders = it)) }, Modifier.testTag("entry-reader-folders"))
                    }
                    FolderMembershipChoice(stringResource(R.string.collections), data.collections.map { FolderItem(it.id, it.name, it.parentId) }, draft.collections, { vm.updateDraft(draft.copy(collections = it)) }, Modifier.testTag("entry-collections"))
                    TextButton(enabled = !saving, onClick = { discard = true }, modifier = Modifier.testTag("discard-draft")) { Text(stringResource(R.string.discard_draft)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (data.entries.any { it.id == entry.id }) TextButton(enabled = !saving, onClick = { delete = true }, modifier = Modifier.testTag("delete-quote")) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    TextButton(enabled = !saving, onClick = vm::closeEditor) { Text(stringResource(R.string.close)) }
                    Button(enabled = !saving && entry.title.isNotBlank(), onClick = { vm.saveEditor() }, modifier = Modifier.testTag("save-entry")) { Text(stringResource(R.string.save)) }
                }
            }
        }
    }
    if (inline) Box(Modifier.width(360.dp).fillMaxHeight()) { content() }
    else Dialog(onDismissRequest = vm::closeEditor, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.widthIn(max = 600.dp).fillMaxWidth(.94f).fillMaxHeight(.9f)) { content() }
    }
    if (delete) Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { delete = false }, { vm.deleteEntries(setOf(entry.id)); delete = false })
    if (discard) Confirm(R.string.discard_draft, R.string.discard_draft_body, { discard = false }, { vm.discardDraft(entry.id); discard = false })
}
