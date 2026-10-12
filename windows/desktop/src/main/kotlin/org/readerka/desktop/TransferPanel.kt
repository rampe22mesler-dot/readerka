package org.readerka.desktop

import java.awt.BorderLayout
import java.awt.Dimension
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.*
import org.readerka.data.LibrarySnapshot

/** The first transfer preset is deliberately a complete, self-contained library. */
class TransferPanel(data: LibrarySnapshot, export: () -> Unit, load: () -> Unit) : JPanel(BorderLayout(0, 12)) {
    init {
        name = "readerka-transfer"
        border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
        add(JLabel("Синхронизация через файл").apply { font = font.deriveFont(20f) }, BorderLayout.NORTH)
        add(JScrollPane(JTextArea().apply {
            name = "readerka-transfer-description"
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = this@TransferPanel.background
            text = "Шаблон выгрузки: полная библиотека · ZIP\n\n" +
                "Книги: ${data.documents.size}; записи: ${data.entries.size}; " +
                "исследовательские поля: ${data.researchFields.size}; закладки: ${data.bookmarks.size}.\n\n" +
                "В файл входят книги в PDF, цитаты с выделениями, заметки, конспекты, " +
                "папки, теги, закладки, позиции чтения, версии записей и исследовательские поля со связями. " +
                "Перед выгрузкой черновики сохраняются. Исходные EPUB, DOCX и другие файлы, " +
                "настройки оформления и открытые вкладки не переносятся.\n\n" +
                "1. Выгрузите данные в ZIP.\n" +
                "2. Перенесите файл на другое устройство, например через USB.\n" +
                "3. Откройте там Readerka и выберите «Загрузить данные».\n\n" +
                "Перед загрузкой появятся сводка и сравнение изменений. Новые материалы добавляются; " +
                "для совпадающих ID выбирается местная версия или версия из файла. " +
                "Перед импортом сохраняется резервная копия. Отсутствующие в файле материалы " +
                "удаляются только при выборе «Заменить библиотеку».\n\n" +
                "На вкладке Dropbox доступен ручной обмен через аккаунт. Автоматического обмена нет. Файл не зашифрован. " +
                "На другом устройстве нужна Readerka с поддержкой ZIP формата 5. " +
                "Прежние версии Windows и Android этот формат не принимают."
            caretPosition = 0
        }).apply { border = BorderFactory.createEmptyBorder() }, BorderLayout.CENTER)
        add(flow(
            button("Выгрузить данные…", export).apply { name = "readerka-transfer-export" },
            button("Загрузить данные…", load).apply { name = "readerka-transfer-import" },
        ), BorderLayout.SOUTH)
        preferredSize = Dimension(620, 540)
    }

    companion object {
        fun suggestedFilename(now: Date = Date()): String =
            "Readerka-transfer-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(now)}.zip"
    }
}
