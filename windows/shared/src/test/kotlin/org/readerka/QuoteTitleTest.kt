package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.quoteTitle

class QuoteTitleTest {
    @Test fun shortQuotesKeepAllWordsAndWhitespaceIsNormalized() {
        assertEquals("Первые слова цитаты", quoteTitle("  Первые\nслова\tцитаты  "))
        assertEquals("a".repeat(60), quoteTitle("a".repeat(60)))
        assertEquals("Цитата", quoteTitle(" \n "))
    }

    @Test fun longQuotesEndAtAWholeWordAndHaveAnEllipsis() {
        val text = "word ".repeat(20).trim()
        val title = quoteTitle(text)
        assertEquals("word ".repeat(12).trimEnd() + "…", title)
        assertEquals(60, title.length)
        assertEquals("first…", quoteTitle("first " + "x".repeat(100)))
        assertEquals("x".repeat(59) + "…", quoteTitle("x".repeat(100)))
        assertFalse(quoteTitle("x".repeat(58) + "\uD83D\uDE00xyz").dropLast(1).last().isHighSurrogate())
    }
}
