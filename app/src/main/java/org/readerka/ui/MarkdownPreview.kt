package org.readerka.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.commonmark.node.*
import org.commonmark.parser.Parser

private fun children(node: Node): List<Node> = buildList { var n = node.firstChild; while (n != null) { add(n); n = n.next } }

@Composable fun MarkdownPreview(source: String, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val root = remember(source) { MarkdownDocument.parse(source) }
    SelectionContainer(modifier) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { children(root).forEach { MarkdownBlock(it, onLink) } } }
}
@Composable private fun MarkdownBlock(node: Node, onLink: (String) -> Unit) {
    val currentOnLink by rememberUpdatedState(onLink)
    when (node) {
        is BulletList, is OrderedList -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            children(node).forEachIndexed { i, child ->
                Row {
                    Text(if (node is OrderedList) "${node.startNumber + i}.  " else "•  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) { children(child).forEach { MarkdownBlock(it, onLink) } }
                }
            }
        }
        is BlockQuote -> Row { VerticalDivider(Modifier.height(48.dp), color = MaterialTheme.colorScheme.primary); Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { children(node).forEach { MarkdownBlock(it, onLink) } } }
        is FencedCodeBlock -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) { Text(node.literal.trimEnd(), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp)) }
        is IndentedCodeBlock -> Text(node.literal.trimEnd(), fontFamily = FontFamily.Monospace)
        is ThematicBreak -> HorizontalDivider()
        else -> {
            val accent = MaterialTheme.colorScheme.primary
            val text = remember(node, accent) { buildAnnotatedString { appendInline(node, accent) { currentOnLink(it) } } }
            val style = when ((node as? Heading)?.level) { 1 -> MaterialTheme.typography.headlineMedium; 2 -> MaterialTheme.typography.headlineSmall; 3 -> MaterialTheme.typography.titleLarge; 4, 5, 6 -> MaterialTheme.typography.titleMedium; else -> MaterialTheme.typography.bodyLarge }
            Text(text, style = style, modifier = Modifier.fillMaxWidth())
        }
    }
}
private fun AnnotatedString.Builder.appendInline(node: Node, accent: Color, onLink: (String) -> Unit) {
    when (node) {
        is org.commonmark.node.Text -> append(node.literal.replace(Regex("^\\[ \\] "), "☐ ").replace(Regex("^\\[[xX]\\] "), "☑ "))
        is Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = accent.copy(alpha = .08f))) { append(node.literal) }
        is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children(node).forEach { appendInline(it, accent, onLink) } }
        is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children(node).forEach { appendInline(it, accent, onLink) } }
        is Link -> withLink(LinkAnnotation.Clickable(node.destination, TextLinkStyles(SpanStyle(color = accent, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))) { onLink(node.destination) }) { children(node).forEach { appendInline(it, accent, onLink) } }
        is Image -> { append("[Изображение: "); children(node).forEach { appendInline(it, accent, onLink) }; append("]") }
        is SoftLineBreak, is HardLineBreak -> append("\n")
        is HtmlInline -> append(node.literal)
        is HtmlBlock -> append(node.literal)
        else -> children(node).forEach { appendInline(it, accent, onLink) }
    }
}
