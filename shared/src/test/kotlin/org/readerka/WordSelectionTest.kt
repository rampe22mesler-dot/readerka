package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.PageRect
import org.readerka.pdf.Glyph
import org.readerka.pdf.PageText

class WordSelectionTest {
    private fun page(text: String): PageText = PageText(0, text, text.indices.filter { !text[it].isWhitespace() }.map {
        Glyph(it, it + 1, PageRect(0f, 0f, 1f, 1f))
    })
    private fun word(text: String, offset: Int): String {
        val p = page(text)
        val hit = p.glyphs.indexOfFirst { it.start == offset }
        val range = p.wordAt(hit)
        return p.selection(range.first, range.last)!!.first
    }
    @Test fun touchingAnyLetterSelectsWholeLatinOrCyrillicWordWithoutPunctuation() {
        for (text in listOf("these", "понятие", "café")) for (offset in text.indices)
            assertEquals(text, word("$text, next", offset))
        assertEquals("second", word("first\nsecond third", 9))
    }
    @Test fun ligatureGlyphAndCombiningMarksRemainInTheWholeWord() {
        val p = PageText(0, "office", listOf(Glyph(0, 1, PageRect(0f, 0f, 1f, 1f)),
            Glyph(1, 4, PageRect(0f, 0f, 1f, 1f)), Glyph(4, 5, PageRect(0f, 0f, 1f, 1f)), Glyph(5, 6, PageRect(0f, 0f, 1f, 1f))))
        assertEquals("office", p.wordAt(1).let { p.selection(it.first, it.last)!!.first })
        assertEquals("cafe\u0301", word("cafe\u0301 test", 2))
    }
    @Test fun punctuationTouchDoesNotAbsorbNeighboringWords() {
        assertEquals(",", word("hello, world", 5))
        assertEquals("!", word("hello!", 5))
    }
}
