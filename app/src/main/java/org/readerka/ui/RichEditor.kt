package org.readerka.ui

import android.content.Context
import android.graphics.Typeface
import android.text.*
import android.text.style.*
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.readerka.data.*

class ResearchEditText(context: Context) : EditText(context) {
    var updating = false
    var selectionObserver: ((Int, Int) -> Unit)? = null
    override fun onSelectionChanged(start: Int, end: Int) { super.onSelectionChanged(start, end); if (!updating) selectionObserver?.invoke(start, end) }
}

@Composable fun RichEditor(value: RichText, selection: TextRange, onChange: (RichText, TextRange) -> Unit, onSelection: (TextRange) -> Unit,
    onFocus: () -> Unit, onView: (ResearchEditText) -> Unit, modifier: Modifier = Modifier, label: String = "Текст документа") {
    val foreground = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val scale = LocalDensity.current.fontScale
    val currentValue by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val select by rememberUpdatedState(onSelection)
    var nativeFocused by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf<ResearchEditText?>(null) }
    AndroidView(modifier = modifier.fillMaxWidth().heightIn(min = 300.dp).testTag("entry-comment").semantics {
        isEditable = true
        focused = nativeFocused
        editableText = AnnotatedString(value.text)
        textSelectionRange = selection
        setText { text -> view?.let { it.setText(text.text); it.setSelection(text.length) }; true }
        insertTextAtCursor { text -> view?.let { it.text.replace(it.selectionStart.coerceAtLeast(0), it.selectionEnd.coerceAtLeast(0), text.text) }; true }
        setSelection { start, end, _ -> view?.setSelection(start.coerceIn(0, value.text.length), end.coerceIn(0, value.text.length)); true }
        requestFocus { view?.requestFocus() == true }
    }, factory = { context -> ResearchEditText(context).apply {
        view = this; onView(this)
        gravity = Gravity.TOP or Gravity.START
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setTextSize(17f * scale); setPadding(0, 8, 0, 24); setBackgroundColor(android.graphics.Color.TRANSPARENT)
        minLines = 12; isVerticalScrollBarEnabled = false
        contentDescription = label
        setOnFocusChangeListener { _, focused -> nativeFocused = focused; if (focused) onFocus() }
        selectionObserver = { a, b -> if (a >= 0 && b >= 0) select(TextRange(a, b)) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!updating) {
                    val next = currentValue.changeText(s.toString())
                    change(next, TextRange(selectionStart.coerceAtLeast(0), selectionEnd.coerceAtLeast(0)))
                }
            }
        })
    } }, update = { editor ->
        editor.contentDescription = label
        editor.setTextColor(foreground); editor.setTextSize(17f * scale)
        editor.updating = true
        if (editor.text.toString() != value.text) editor.setText(value.text)
        val editable = editor.text
        editable.getSpans(0, editable.length, CharacterStyle::class.java).forEach { editable.removeSpan(it) }
        editable.getSpans(0, editable.length, ParagraphStyle::class.java).forEach { editable.removeSpan(it) }
        fun span(mark: TextMark, value: Any) { if (mark.start >= 0 && mark.end <= editable.length) editable.setSpan(value, mark.start, mark.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        value.marks.forEach { mark -> when (mark.style) {
            TextStyle.Bold -> span(mark, StyleSpan(Typeface.BOLD))
            TextStyle.Italic -> span(mark, StyleSpan(Typeface.ITALIC))
            TextStyle.Underline -> span(mark, UnderlineSpan())
            TextStyle.Code, TextStyle.Literal -> span(mark, TypefaceSpan("monospace"))
            TextStyle.Link -> { span(mark, UnderlineSpan()); span(mark, ForegroundColorSpan(accent)) }
        } }
        value.blocks.forEach { block ->
            val end = value.blockEnd(block)
            val paragraphEnd = if (end < editable.length) end + 1 else end
            if (end > block.start && block.style in listOf(BlockStyle.Bullet, BlockStyle.Ordered, BlockStyle.Quote)) {
                val marker: ParagraphStyle = when (block.style) {
                    BlockStyle.Bullet -> BulletSpan(12, accent)
                    BlockStyle.Quote -> QuoteSpan(accent)
                    else -> object : LeadingMarginSpan {
                        val number = value.blocks.takeWhile { it.start <= block.start }.takeLastWhile { it.style == BlockStyle.Ordered }.size
                        override fun getLeadingMargin(first: Boolean) = 40
                        override fun drawLeadingMargin(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: android.text.Layout) { if (first) canvas.drawText("$number.", x.toFloat(), baseline.toFloat(), paint) }
                    }
                }
                editable.setSpan(marker, block.start, paragraphEnd, Spanned.SPAN_PARAGRAPH)
            }
            if (end > block.start) when (block.style) {
                BlockStyle.Heading -> { editable.setSpan(RelativeSizeSpan(when (block.level) { 1 -> 1.6f; 2 -> 1.35f; else -> 1.15f }), block.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); editable.setSpan(StyleSpan(Typeface.BOLD), block.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                BlockStyle.LinkCard -> {
                    editable.setSpan(ForegroundColorSpan(accent), block.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    editable.setSpan(object : LineBackgroundSpan {
                        override fun drawBackground(canvas: android.graphics.Canvas, paint: android.graphics.Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
                            val previous = paint.color; paint.color = accent and 0x00ffffff or 0x18000000
                            canvas.drawRoundRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), 6f, 6f, paint); paint.color = previous
                        }
                    }, block.start, paragraphEnd, Spanned.SPAN_PARAGRAPH)
                    editable.setSpan(object : LeadingMarginSpan {
                        override fun getLeadingMargin(first: Boolean) = (editor.resources.displayMetrics.density * 30).toInt()
                        override fun drawLeadingMargin(canvas: android.graphics.Canvas, paint: android.graphics.Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: android.text.Layout) {
                            if (first) { val previous = paint.color; paint.color = accent; canvas.drawText("↗", x.toFloat(), baseline.toFloat(), paint); paint.color = previous }
                        }
                    }, block.start, paragraphEnd, Spanned.SPAN_PARAGRAPH)
                }
                BlockStyle.Code -> editable.setSpan(TypefaceSpan("monospace"), block.start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else -> Unit
            }
        }
        val start = selection.start.coerceIn(0, editable.length); val end = selection.end.coerceIn(0, editable.length)
        if (editor.selectionStart != start || editor.selectionEnd != end) editor.setSelection(start, end)
        editor.updating = false
    })
}
