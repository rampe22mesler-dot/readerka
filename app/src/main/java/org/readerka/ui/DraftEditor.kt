package org.readerka.ui

import android.content.Intent
import androidx.compose.foundation.ScrollState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import android.net.Uri
import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.focusGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.readerka.R
import org.readerka.data.*

@Composable fun NoteDocument(id: String, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int) {
    val workspace by vm.workspace.collectAsState()
    val draft = workspace.drafts.firstOrNull { it.entry.id == id }
    val entry = draft?.entry ?: data.entries.firstOrNull { it.id == id }
    if (entry == null) { Text("Документ недоступен", modifier = Modifier.padding(24.dp)); return }
    if (draft != null) DraftEditor(draft, data, vm, inline = true, onEditorFocus = { if (it) vm.focusPane(pane) }, pane = pane)
    else Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Заметка · сохранена", modifier = Modifier.weight(1f).padding(start = 16.dp), style = MaterialTheme.typography.labelMedium)
            WorkspaceAction(Outline.Edit, "Редактировать", { vm.editEntry(entry, pane) })
            WorkspaceAction(Outline.Split, "Открыть справа", { vm.openRight(WorkspaceTab(id, id, WorkspaceKind.Note), pane) })
        }
        HorizontalDivider()
        Column(Modifier.weight(1f).verticalScroll(rememberNoteScroll(pane, id, vm)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(entry.title, style = MaterialTheme.typography.headlineMedium)
            if (entry.quote.isNotBlank()) { Text("Исходная цитата", style = MaterialTheme.typography.labelLarge); Text(entry.quote) }
            MarkdownPreview(entry.comment, { openNoteLink(it, data, vm, pane) })
        }
    }
}

@Composable fun DraftEditor(draft: EntryDraft, data: LibrarySnapshot, vm: LibraryViewModel, inline: Boolean = false, onEditorFocus: (Boolean) -> Unit = {}, pane: Int = vm.workspace.value.activePane) {
    val status by vm.draftStatus.collectAsState()
    val saving by vm.editorSaving.collectAsState()
    var properties by rememberSaveable(draft.entry.id) { mutableStateOf(false) }
    var preview by rememberSaveable(draft.entry.id) { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    val entry = draft.entry
    val titleFocus = remember(entry.id) { FocusRequester() }
    LaunchedEffect(entry.id) { if (entry.title.isBlank()) { withFrameNanos { }; titleFocus.requestFocus() } }
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().testTag("entry-editor")) {
        Column(Modifier.imePadding().onFocusChanged { onEditorFocus(it.hasFocus) }.focusGroup().onPreviewKeyEvent { key ->
            val event = key.nativeKeyEvent
            when {
                event.isCtrlPressed && event.keyCode in listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER) -> { if (event.action == KeyEvent.ACTION_DOWN) vm.saveEditor(entry.id); true }
                event.keyCode == KeyEvent.KEYCODE_ESCAPE -> { if (event.action == KeyEvent.ACTION_DOWN) vm.closeEditor(); true }
                else -> false
            }
        }) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (entry.anchor == null) "Конспект" else "Комментарий к цитате", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                WorkspaceAction(Outline.Settings, "Свойства", { properties = !properties }, Modifier.testTag("note-properties"), selected = properties)
                WorkspaceAction(if (preview) Outline.Edit else Outline.Preview, if (preview) "Редактировать Markdown" else "Просмотр Markdown", { preview = !preview }, Modifier.testTag("markdown-preview"))
                WorkspaceAction(Outline.Close, stringResource(R.string.close_editor), { vm.focusPane(pane); vm.closeEditor() })
            }
            HorizontalDivider()
            Column(Modifier.weight(1f).verticalScroll(rememberNoteScroll(pane, entry.id, vm)).padding(horizontal = if (LocalCompact.current) 16.dp else 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TextField(entry.title, { vm.updateDraft(draft.copy(entry = entry.copy(title = it))) }, enabled = !saving,
                    placeholder = { Text("Название заметки") }, textStyle = MaterialTheme.typography.headlineMedium,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).testTag("entry-title"))
                if (entry.anchor != null) {
                    Text("Исходная цитата · страница ${entry.anchor.page + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(entry.quote, style = MaterialTheme.typography.bodyLarge)
                    Text("Собственный комментарий", style = MaterialTheme.typography.labelMedium)
                }
                if (preview) MarkdownPreview(entry.comment, { openNoteLink(it, data, vm, pane) }, Modifier.testTag("markdown-rendered"))
                else TextField(entry.comment, { vm.updateDraft(draft.copy(entry = entry.copy(comment = it))) }, enabled = !saving,
                    placeholder = { Text("Пишите здесь… Markdown поддерживается") }, textStyle = MaterialTheme.typography.bodyLarge, minLines = 12,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth().testTag("entry-comment"))
                if (properties) {
                    HorizontalDivider()
                    Text("Свойства записи", style = MaterialTheme.typography.titleSmall)
                    data.documents.firstOrNull { it.id == entry.documentId }?.let { Text("Источник · " + it.title, style = MaterialTheme.typography.bodySmall) }
                    Choice(stringResource(R.string.type), entry.type, NoteType.entries, { stringResource(it.labelId()) }, { vm.updateDraft(draft.copy(entry = entry.copy(type = it))) })
                    ColorPalette(entry.color) { vm.updateDraft(draft.copy(entry = entry.copy(color = it))) }
                    OutlinedTextField(draft.tags, { vm.updateDraft(draft.copy(tags = it)) }, enabled = !saving, label = { Text(stringResource(R.string.tags)) }, modifier = Modifier.fillMaxWidth().testTag("entry-tags"))
                    FolderMembershipChoice("Общие папки заметок", data.collections.map { FolderItem(it.id, it.name, it.parentId) }, draft.collections, { vm.updateDraft(draft.copy(collections = it)) }, Modifier.testTag("entry-collections"))
                    if (entry.documentId != null) FolderMembershipChoice("Папки цитат внутри PDF", data.readerFolders.filter { it.documentId == entry.documentId && it.kind == ReaderFolderKind.Quotes }.map { FolderItem(it.id, it.name, it.parentId) }, draft.readerFolders, { vm.updateDraft(draft.copy(readerFolders = it)) }, Modifier.testTag("entry-reader-folders"))
                    TextButton(enabled = !saving, onClick = { discard = true }) { Text("Удалить черновик") }
                    if (data.entries.any { it.id == entry.id }) TextButton(enabled = !saving, onClick = { delete = true }, modifier = Modifier.testTag("delete-quote")) { Text("Удалить запись", color = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f).testTag("draft-status"))
                TextButton(enabled = !saving && entry.title.isNotBlank(), onClick = { vm.saveEditor(entry.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("save-entry")) { Text("Сохранить") }
            }
        }
    }
    if (delete) Confirm(R.string.delete_entries_title, R.string.delete_entries_body, { delete = false }, { vm.deleteEntries(setOf(entry.id)); delete = false })
    if (discard) Confirm(R.string.discard_draft, R.string.discard_draft_body, { discard = false }, { vm.discardDraft(entry.id); discard = false })
}

private fun openNoteLink(link: String, data: LibrarySnapshot, vm: LibraryViewModel, pane: Int) {
    val uri = Uri.parse(link)
    if (uri.scheme == "readerka" && uri.host == "document") {
        val id = uri.pathSegments.firstOrNull()
        data.documents.firstOrNull { it.id == id }?.let { vm.open(it, page = ((uri.getQueryParameter("page")?.toIntOrNull() ?: 1) - 1).coerceAtLeast(0), pane = pane) }
    } else if (uri.scheme in listOf("https", "http")) {
        // External URLs use the user's browser; local files and arbitrary intent schemes are never opened.
        vm.getApplication<android.app.Application>().let { app -> runCatching { app.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

@OptIn(FlowPreview::class)
@Composable private fun rememberNoteScroll(pane: Int, id: String, vm: LibraryViewModel): ScrollState {
    val initial = remember(pane, id) { vm.workspace.value.panes[pane].tabs.firstOrNull { it.kind == WorkspaceKind.Note && it.documentId == id }?.offset ?: 0 }
    val scroll = rememberScrollState(initial)
    LaunchedEffect(pane, id, scroll) {
        withFrameNanos { }; scroll.scrollTo(initial)
        snapshotFlow { scroll.value }.distinctUntilChanged().debounce(350).collect { vm.trackNotePosition(pane, id, it) }
    }
    return scroll
}
