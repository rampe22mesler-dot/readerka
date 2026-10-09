package org.readerka.ui

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readerka.R
import org.readerka.data.*

data class QuickCommand(val id: String, val title: String, val detail: String = "", val run: () -> Unit)

@Composable fun QuickCommands(commands: List<QuickCommand>, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selected by remember(query) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val matches = remember(query, commands) {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        commands.filter { command -> words.all { (command.title + " " + command.detail).contains(it, ignoreCase = true) } }.take(100)
    }
    fun run(command: QuickCommand) { onDismiss(); command.run() }
    LaunchedEffect(selected, matches.size) {
        selected = selected.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
        if (matches.isNotEmpty()) list.animateScrollToItem(selected)
    }
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().imePadding().testTag("command-palette")) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable(onClick = onDismiss))
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 8.dp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(24.dp).widthIn(max = 600.dp).fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
        Column(Modifier.padding(20.dp).fillMaxWidth().heightIn(max = 550.dp).onPreviewKeyEvent { key ->
            val event = key.nativeKeyEvent
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) selected = (selected + if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1).coerceIn(0, (matches.size - 1).coerceAtLeast(0))
                    true
                }
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> { if (event.action == KeyEvent.ACTION_DOWN) matches.getOrNull(selected)?.let(::run); true }
                KeyEvent.KEYCODE_ESCAPE -> { if (event.action == KeyEvent.ACTION_DOWN) onDismiss(); true }
                else -> false
            }
        }) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.quick_commands), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
            OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(stringResource(R.string.command_search)) }, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("command-query"))
            LaunchedEffect(Unit) { focus.requestFocus() }
            Text(stringResource(R.string.commands_hint), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 8.dp))
            if (matches.isEmpty()) Text(stringResource(R.string.no_search_results))
            LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(matches, key = { _, command -> command.id }) { index, command ->
                    ListItem(headlineContent = { Text(command.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(command.detail, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        colors = ListItemDefaults.colors(containerColor = if (index == selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                        modifier = Modifier.clickable { run(command) }.testTag("command-" + command.id))
                }
            }
        }
        }
    }
}

@Composable fun DraftList(drafts: List<EntryDraft>, data: LibrarySnapshot, vm: LibraryViewModel, onDismiss: () -> Unit) {
    var discarded by remember { mutableStateOf<EntryDraft?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.drafts)) }, text = {
        if (drafts.isEmpty()) Text(stringResource(R.string.empty_drafts))
        else LazyColumn(Modifier.heightIn(max = 500.dp)) {
            itemsIndexed(drafts.sortedByDescending { it.modifiedAt }, key = { _, draft -> draft.entry.id }) { _, draft ->
                ListItem(headlineContent = { Text(draft.entry.title.ifBlank { stringResource(R.string.untitled_draft) }, maxLines = 2) },
                    supportingContent = { Text(data.documents.firstOrNull { it.id == draft.entry.documentId }?.title ?: draft.entry.comment.take(100), maxLines = 2) },
                    modifier = Modifier.clickable { onDismiss(); vm.resumeDraft(draft) }.testTag("draft-" + draft.entry.id),
                    trailingContent = { TextButton(onClick = { discarded = draft }) { Text(stringResource(R.string.delete)) } })
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
    discarded?.let { draft -> Confirm(R.string.discard_draft, R.string.discard_draft_body, { discarded = null }, { vm.discardDraft(draft.entry.id); discarded = null }) }
}
