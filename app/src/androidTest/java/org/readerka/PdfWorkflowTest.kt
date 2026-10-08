package org.readerka

import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import org.readerka.pdf.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfWorkflowTest {
    private val context = ApplicationProvider.getApplicationContext<ReaderkaApp>()
    private fun fixture(): File {
        val file = File(context.cacheDir, "selection-fixture.pdf")
        PDDocument().use { pdf ->
            for (rotation in listOf(0, 90, 180, 270)) {
                val page = PDPage(PDRectangle(400f, 600f))
                if (rotation != 0) page.cropBox = PDRectangle(20f, 30f, 370f, 540f)
                page.rotation = rotation; pdf.addPage(page)
                PDPageContentStream(pdf, page).use {
                    it.beginText(); it.setFont(PDType1Font.TIMES_ROMAN, 18f)
                    it.newLineAtOffset(60f, 480f); it.showText("Existence is not a real predicate.")
                    it.newLineAtOffset(0f, -32f); it.showText("Being is not a genus."); it.endText()
                }
            }
            pdf.save(file)
        }
        return file
    }
    @Test fun realTextMultilineGeometryAndRotation() {
        val file = fixture()
        PdfEngine(file, context.cacheDir).use { engine ->
            assertEquals(4, engine.pageCount)
            for (page in 0..3) {
                val text = engine.text(page)
                assertTrue(text.text.contains("Existence is not a real predicate."))
                assertTrue(text.text.contains("Being is not a genus."))
                val selected = text.selection(0, text.glyphs.lastIndex)!!
                assertTrue(selected.first.contains('\n'))
                assertEquals(1, text.matches("real predicate").size)
                val bitmap = engine.render(page, 1000)
                var inkHits = 0
                for (g in text.glyphs.filter { !text.text.substring(it.start, it.end).isBlank() }) {
                    val r = g.rect
                    val x1 = (r.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                    val y1 = (r.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                    val x2 = (r.right * bitmap.width).toInt().coerceIn(x1, bitmap.width - 1)
                    val y2 = (r.bottom * bitmap.height).toInt().coerceIn(y1, bitmap.height - 1)
                    var dark = false
                    for (y in y1..y2) for (x in x1..x2) if (Color.red(bitmap.getPixel(x, y)) < 160) dark = true
                    if (dark) inkHits++
                }
                assertTrue("page=$page glyph rectangles must touch rendered text: $inkHits", inkHits >= text.glyphs.count { !text.text.substring(it.start, it.end).isBlank() } * .90)
                bitmap.recycle()
            }
        }
    }
    @Test fun fingerSelectionPersistsAcrossDatabaseAndPdfReopenAndZoom() { runBlocking {
        val file = fixture()
        val engine = PdfEngine(file, context.cacheDir)
        val text = engine.text(0)
        var selected: Pair<String, Anchor>? = null
        var view: PdfPageView? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = PdfPageView(activity).apply {
                    bitmap = engine.render(0, 800); pageText = text
                    onSelection = { quote, anchor -> selected = quote to anchor }
                }
                activity.setContentView(view)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            fun event(action: Int, index: Int) {
                scenario.onActivity {
                    val r = view!!.screenRect(text.glyphs[index].rect)
                    val now = SystemClock.uptimeMillis()
                    MotionEvent.obtain(now, now, action, r.centerX(), r.centerY(), 0).let { e -> view!!.onTouchEvent(e); e.recycle() }
                }
            }
            event(MotionEvent.ACTION_DOWN, 0)
            SystemClock.sleep(650)
            event(MotionEvent.ACTION_MOVE, text.glyphs.lastIndex)
            event(MotionEvent.ACTION_UP, text.glyphs.lastIndex)
            assertNotNull(selected)
            assertTrue(selected!!.first.contains("Being is not a genus."))
            context.deleteDatabase("pdf-proof.db")
            var db = LibraryDatabase.open(context, "pdf-proof.db")
            val doc = Document("doc", "Proof", file.name, file.name)
            db.dao().putDocument(doc)
            val entry = Entry("quote", "Proof", selected!!.first, "My comment", "doc", selected!!.second, NoteType.Objection)
            db.dao().putEntry(entry); db.close(); engine.close()
            db = LibraryDatabase.open(context, "pdf-proof.db")
            val restored = db.dao().allEntries().single()
            assertEquals(entry, restored)
            PdfEngine(file, context.cacheDir).use { reopened ->
                assertEquals(restored.anchor!!.rects, reopened.text(0).selection(0, text.glyphs.lastIndex)!!.second.rects)
            }
            scenario.onActivity {
                view!!.clearSelection(); view!!.highlights = listOf(restored)
                val r1 = view!!.screenRect(restored.anchor!!.rects.first())
                view!!.setZoom(2f)
                val r2 = view!!.screenRect(restored.anchor!!.rects.first())
                assertEquals(r1.width() * 2, r2.width(), .01f)
                assertEquals(r1.height() * 2, r2.height(), .01f)
            }
            db.dao().putEntry(restored.copy(color = Marker.Green))
            db.close(); db = LibraryDatabase.open(context, "pdf-proof.db")
            assertEquals(Marker.Green, db.dao().allEntries().single().color)
            db.close(); context.deleteDatabase("pdf-proof.db")
        }
    } }
}
