package org.readerka

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.readerka.data.LibraryDatabase

class ReaderkaApp : Application() {
    val database by lazy { LibraryDatabase.open(this) }
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
    }
}
