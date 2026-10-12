package org.readerka

import org.junit.Assert.*
import org.junit.Test
import org.readerka.data.*

class RichTextEditingTest {
    private val target = "readerka://entry/source"

    @Test fun typingAfterLinkedWordDoesNotExtendTheLinkButEditingItsInteriorDoes() {
        val linked = RichText.plain("word").style(0, 4, TextStyle.Link, target)
        val appended = RichTextEditing.type(linked, 4, 4, " next word")
        assertEquals("word next word", appended.text)
        assertTrue(appended.marks.filter { it.style == TextStyle.Link }.all { it.end <= 4 })
        val interior = RichTextEditing.type(linked, 2, 2, "XYZ")
        assertTrue(interior.hasStyle(0, interior.text.length, TextStyle.Link))
        val before = RichTextEditing.type(linked, 0, 0, "prefix ")
        assertFalse(before.marks.any { it.style == TextStyle.Link && it.start < 7 })
    }

    @Test fun linkInsertionOnAnExistingWordDoesNotTurnFollowingParagraphsIntoLinks() {
        val source = Entry("source", "Source")
        var value = ResearchInsertion.insert(RichText.plain("word"), 0, 4, source, InsertionMode.InlineLink)
        value = RichTextEditing.type(value, 4, 4, " rest\nnew paragraph")
        assertEquals(listOf(TextMark(0, 4, TextStyle.Link, target)), value.marks)
        assertEquals(BlockStyle.Paragraph, value.blocks.last().style)
    }

    @Test fun newlineInsideLinkDoesNotCarryTheLinkToTheTypedParagraph() {
        val linked = RichText.plain("linked").style(0, 6, TextStyle.Link, target)
        val value = RichTextEditing.type(linked, 3, 3, "\nnew ")
        assertFalse(value.marks.any { it.style == TextStyle.Link && it.start < 8 && it.end > 3 })
        val card = RichText.plain("card").paragraph(0, 0, BlockStyle.LinkCard, target = target)
        assertEquals(BlockStyle.Paragraph, RichTextEditing.enter(card, 4, 4).blocks.last().style)
    }

    @Test fun ordinaryFormattingPersistsAndAdjacentRangesAreRecognizedAsOneSelection() {
        val original = RichText.plain("abc").style(0, 3, TextStyle.Bold).style(0, 3, TextStyle.Italic)
        val typed = RichTextEditing.type(original, 3, 3, "def")
        assertTrue(typed.hasStyle(0, 6, TextStyle.Bold))
        assertTrue(typed.hasStyle(0, 6, TextStyle.Italic))
        assertFalse(typed.style(0, 6, TextStyle.Bold).marks.any { it.style == TextStyle.Bold })
        val plainTail = RichTextEditing.type(typed, 6, 6, "XYZ", mapOf(TextStyle.Bold to null))
        assertFalse(plainTail.hasStyle(6, 9, TextStyle.Bold))
        assertTrue(plainTail.hasStyle(6, 9, TextStyle.Italic))
    }

    @Test fun enterContinuesBothListKindsAndBackspaceCanRemoveOnlyTheMarker() {
        for (style in listOf(BlockStyle.Bullet, BlockStyle.Ordered)) {
            val original = RichText.plain("first").paragraph(0, 0, style)
            val second = RichTextEditing.enter(original, 5, 5)
            assertEquals("first\n", second.text)
            assertEquals(listOf(style, style), second.blocks.map { it.style })
            assertTrue(RichTextEditing.removesListMarker(second, 6, 6))
            val finished = second.paragraph(6, 6, BlockStyle.Paragraph)
            assertEquals("first\n", finished.text)
            assertEquals(original.blocks.first().id, finished.blocks.first().id)
            assertEquals(BlockStyle.Paragraph, finished.blocks.last().style)
            val typed = RichTextEditing.type(second, 6, 6, "second")
            val third = RichTextEditing.enter(typed, typed.text.length, typed.text.length)
            assertEquals(listOf(style, style, style), third.blocks.map { it.style })
            // A second Enter on an empty item also exits, as in Word.
            assertEquals("first\n", RichTextEditing.enter(second, 6, 6).text)
            assertEquals(BlockStyle.Paragraph, RichTextEditing.enter(second, 6, 6).blocks.last().style)
        }
    }

    @Test fun splittingListItemKeepsBothHalvesAndTheFirstIdentity() {
        val original = RichText.plain("abcdef").paragraph(0, 0, BlockStyle.Ordered)
        val next = RichTextEditing.enter(original, 3, 3)
        assertEquals("abc\ndef", next.text)
        assertTrue(next.blocks.all { it.style == BlockStyle.Ordered })
        assertEquals(original.blocks.first().id, next.blocks.first().id)
        assertNotEquals(next.blocks.first().id, next.blocks.last().id)
        assertFalse(RichTextEditing.removesListMarker(next, 5, 5))
        assertTrue(RichTextEditing.enter(original, 0, 0).blocks.all { it.style == BlockStyle.Ordered })
    }
}
