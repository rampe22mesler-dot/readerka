package org.readerka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readerka.R

data class SearchOption(val id: String, val title: String, val detail: String = "", val recent: Boolean = false)

@Composable fun SearchChoice(label: String, selected: String?, options: List<SearchOption>, searchLabel: String,
    onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var opened by remember { mutableStateOf(false) }
    OutlinedButton(onClick = { opened = true }, modifier = modifier.fillMaxWidth()) {
        Text("$label: ${options.firstOrNull { it.id == selected }?.title ?: stringResource(R.string.any_value)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (opened) {
        var query by rememberSaveable { mutableStateOf("") }
        val visible = remember(options, query) { options.filter { query.isBlank() || it.title.contains(query.trim(), true) || it.detail.contains(query.trim(), true) } }
        val recent = visible.filter { it.recent }
        val others = visible.filterNot { it.recent }
        AlertDialog(onDismissRequest = { opened = false }, title = { Text(label) }, text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(query, { query = it }, label = { Text(searchLabel) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("option-search"))
                TextButton(onClick = { onSelect(null); opened = false }) { Text(stringResource(R.string.any_value)) }
                LazyColumn(Modifier.fillMaxWidth().height(330.dp).testTag("search-options")) {
                    if (visible.isEmpty()) item { Text(stringResource(R.string.no_search_results), modifier = Modifier.padding(12.dp)) }
                    if (recent.isNotEmpty()) item { Text(stringResource(R.string.recent_sources), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(recent, key = { it.id }) { option -> SearchOptionRow(option, option.id == selected) { onSelect(option.id); opened = false } }
                    if (recent.isNotEmpty() && others.isNotEmpty()) item { Text(stringResource(R.string.other_sources), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 8.dp)) }
                    items(others, key = { it.id }) { option -> SearchOptionRow(option, option.id == selected) { onSelect(option.id); opened = false } }
                }
            }
        }, confirmButton = { TextButton(onClick = { opened = false }) { Text(stringResource(R.string.close)) } })
    }
}

@Composable private fun SearchOptionRow(option: SearchOption, selected: Boolean, onSelect: () -> Unit) {
    ListItem(headlineContent = { Text(option.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (option.detail.isBlank()) null else { { Text(option.detail, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = if (selected) { { Icon(Icons.Default.Check, null) } } else null,
        modifier = Modifier.clickable(onClick = onSelect).testTag("search-option-" + option.id))
}
