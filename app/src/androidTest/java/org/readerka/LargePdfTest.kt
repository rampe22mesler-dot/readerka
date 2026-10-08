package org.readerka

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.pdf.PdfEngine
import java.io.File

@RunWith(AndroidJUnit4::class)
class LargePdfTest {
    @Test fun lazyAccessToSynthetic100300500PageDocuments() {
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        for (count in listOf(100, 300, 500)) {
            val file = File(app.cacheDir, "large-$count.pdf")
            try {
                PDDocument().use { pdf ->
                    repeat(count) { n ->
                        val page = PDPage(PDRectangle.A4); pdf.addPage(page)
                        if (n in listOf(0, count / 2, count - 1)) PDPageContentStream(pdf, page).use { s ->
                            s.beginText(); s.setFont(PDType1Font.TIMES_ROMAN, 14f); s.newLineAtOffset(50f, 700f); s.showText("Page ${n + 1}: research notes"); s.endText()
                        }
                    }; pdf.save(file)
                }
                PdfEngine(file, app.cacheDir).use { engine ->
                    assertEquals(count, engine.pageCount)
                    for (page in listOf(0, count / 2, count - 1)) {
                        assertTrue(engine.text(page).text.contains("Page ${page + 1}"))
                        val bitmap = engine.render(page, 800)
                        assertTrue(bitmap.allocationByteCount < 5_000_000); bitmap.recycle()
                    }
                }
            } finally { file.delete() }
        }
    }
}
