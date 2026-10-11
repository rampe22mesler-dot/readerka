@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package org.readerka.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.readerka.data.*
import org.readerka.data.TextStyle
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent

@Composable fun RichPreview(value: RichText, onLink: (String) -> Unit, onLinkMenu: (String) -> Unit, modifier: Modifier = Modifier, onBlockPosition: (String, Int) -> Unit = { _, _ -> }) {
    SelectionContainer(modifier) { RichPreviewContent(value, onLink, onLinkMenu, onBlockPosition = onBlockPosition) }
}
@Composable private fun RichPreviewContent(value: RichText, onLink: (String) -> Unit, onLinkMenu: (String) -> Unit, modifier: Modifier = Modifier, onBlockPosition: (String, Int) -> Unit = { _, _ -> }) {
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val accent = MaterialTheme.colorScheme.primary
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        var hiddenLevel: Int? = null
        value.blocks.forEach { block ->
            if (block.style == BlockStyle.Heading && hiddenLevel != null && block.level <= hiddenLevel!!) hiddenLevel = null
            if (hiddenLevel == null) {
                val end = value.blockEnd(block)
                val raw = value.text.substring(block.start, end)
                if (block.style == BlockStyle.Divider) HorizontalDivider()
                else if (block.style == BlockStyle.LinkCard) Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onLink(block.target) }, onLongClick = { onLinkMenu(block.target) })) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(raw, Modifier.weight(1f), color = accent)
                        WorkspaceAction(Outline.More, "Действия ссылки", { onLinkMenu(block.target) })
                    }
                } else {
                    val annotated = buildAnnotatedString {
                        append(raw)
                        value.marks.filter { it.start < end && it.end > block.start }.forEach { mark ->
                            val start = maxOf(mark.start, block.start) - block.start; val finish = minOf(mark.end, end) - block.start
                            val style = when (mark.style) {
                                TextStyle.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
                                TextStyle.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
                                TextStyle.Underline -> SpanStyle(textDecoration = TextDecoration.Underline)
                                TextStyle.Code, TextStyle.Literal -> SpanStyle(fontFamily = FontFamily.Monospace)
                                TextStyle.Link -> SpanStyle(color = accent, textDecoration = TextDecoration.Underline)
                            }
                            addStyle(style, start, finish)
                            if (mark.style == TextStyle.Link) addLink(LinkAnnotation.Clickable(mark.value) { onLink(mark.value) }, start, finish)
                        }
                    }
                    val typography = when (block.style) { BlockStyle.Heading -> when (block.level) { 1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.headlineSmall; else -> MaterialTheme.typography.titleLarge }; else -> MaterialTheme.typography.bodyLarge }
                    Row(Modifier.fillMaxWidth().onGloballyPositioned { onBlockPosition(block.id, it.positionInParent().y.toInt()) }, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        if (block.style == BlockStyle.Heading) WorkspaceAction(Outline.Down, if (block.id in collapsed) "Развернуть раздел" else "Свернуть раздел", { collapsed = if (block.id in collapsed) collapsed - block.id else collapsed + block.id })
                        if (block.style in listOf(BlockStyle.Bullet, BlockStyle.Ordered, BlockStyle.Quote)) Text(when (block.style) { BlockStyle.Bullet -> "•  "; BlockStyle.Ordered -> "${value.blocks.takeWhile { it.start <= block.start }.takeLastWhile { it.style == BlockStyle.Ordered }.size}.  "; else -> "│  " }, color = accent)
                        Text(annotated, Modifier.weight(1f), style = typography, fontFamily = if (block.style == BlockStyle.Code) FontFamily.Monospace else null)
                    }
                }
                if (block.style == BlockStyle.Heading && block.id in collapsed) hiddenLevel = block.level
            }
        }
    }
}
