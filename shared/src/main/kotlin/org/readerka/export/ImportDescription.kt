package org.readerka.export

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.json.Json
import org.readerka.data.*

internal fun importTitle(value: Any): String =
    when (value) {
        is Entry -> value.title
        is Document -> value.title
        is org.readerka.data.Collection -> value.name
        is DocumentFolder -> value.name
        is ReaderFolder -> value.name
        is Bookmark -> value.title
        is Tag -> value.name
        is NoteVersion -> value.label
        else -> "Запись"
    }

internal fun importDescription(value: Any, data: LibrarySnapshot): String {
    fun book(id: String?) = data.documents.firstOrNull { it.id == id }?.title ?: "Без книги"
    fun date(time: Long) =
        if (time == 0L) "—"
        else SimpleDateFormat("dd.MM.yyyy HH:mm:ss.SSS", Locale("ru")).format(Date(time))
    return when (value) {
        is Entry ->
            buildString {
                appendLine("Название: ${value.title}")
                appendLine(
                    "Категория: ${when(value.kind){EntryKind.Quote->"Цитата"
EntryKind.Note->"Заметка"
EntryKind.Consp->"Конспект"}}"
                )
                appendLine(
                    "Книги: ${value.sourceBooks().joinToString { book(it) }.ifEmpty{"Без книги"}}"
                )
                value.anchor?.let {
                    appendLine(
                        "Источник: страница ${it.page+1}, символы ${it.start+1}–${it.end}, ${it.rects.size} областей выделения"
                    )
                }
                appendLine("Тип: ${when(value.type){NoteType.Quote->"Цитата";NoteType.Summary->"Краткое изложение";NoteType.Question->"Вопрос";NoteType.Objection->"Возражение";NoteType.Agreement->"Согласие";NoteType.Idea->"Идея";NoteType.Connection->"Связь";NoteType.GeneralNote->"Общая заметка"}}")
                appendLine("Маркер: ${when(value.color){Marker.Yellow->"Жёлтый";Marker.Green->"Зелёный";Marker.Blue->"Синий";Marker.Pink->"Розовый";Marker.Purple->"Фиолетовый";Marker.Orange->"Оранжевый"}}")
                appendLine("Изменено: ${date(value.updatedAt)}")
                if (value.quote.isNotEmpty()) appendLine("\nЦитата:\n${value.quote}")
                val content = value.richText()
                appendLine("\nТекст / комментарий:\n${content.text}")
                appendLine("\nОформление:")
                content.blocks
                    .filter { it.style != BlockStyle.Paragraph }
                    .forEach { b ->
                        appendLine(
                            "${when(b.style){BlockStyle.Heading->"Заголовок ${b.level}"
BlockStyle.Quote->"Цитатный блок"
BlockStyle.Bullet->"Список"
BlockStyle.Ordered->"Нумерованный список"
BlockStyle.Code->"Код"
BlockStyle.LinkCard->"Карточка-ссылка: ${b.target}"
BlockStyle.Divider->"Разделитель"
else->"Абзац"}}: ${content.text.substring(b.start,content.blockEnd(b)).take(100)}"
                        )
                    }
                content.marks.forEach { m ->
                    appendLine(
                        "${when(m.style){TextStyle.Bold->"Жирный"
TextStyle.Italic->"Курсив"
TextStyle.Underline->"Подчёркнутый"
TextStyle.Link->"Ссылка: ${m.value}"
TextStyle.Code->"Код"
TextStyle.Literal->"Буквальный текст"}}: ${content.text.substring(m.start,m.end).take(100)}"
                    )
                }
            }
        is Document ->
            "Название: ${value.title}\nФайл: ${value.originalName}\nАвтор: ${value.authors}\nГод: ${value.year}\nИздательство: ${value.publisher}\nDOI: ${value.doi}\nURL: ${value.url}\nСтраниц: ${value.pageCount}\nПоложение чтения: страница ${value.lastPage+1}, ${value.lastOffset/100}%\nОткрыто: ${date(value.lastOpenedAt)}\nОписание: ${value.description}"
        is org.readerka.data.Collection ->
            "Папка: ${value.name}\nРаздел: ${if(value.scope==CollectionScope.Consp)"Конспекты"else"Заметки и цитаты"}\nРодитель: ${data.collections.firstOrNull{it.id==value.parentId}?.name?:"Корень"}"
        is DocumentFolder ->
            "Папка PDF: ${value.name}\nРодитель: ${data.documentFolders.firstOrNull{it.id==value.parentId}?.name?:"Корень"}"
        is ReaderFolder ->
            "Папка книги: ${value.name}\nКнига: ${book(value.documentId)}\nРаздел: ${if(value.kind==ReaderFolderKind.Quotes)"Цитаты"else"Закладки"}\nРодитель: ${data.readerFolders.firstOrNull{it.id==value.parentId}?.name?:"Корень"}"
        is Bookmark ->
            "Закладка: ${value.title}\nКнига: ${book(value.documentId)}\nСтраница: ${value.page+1}\nКомментарий: ${value.comment}\nРодитель: ${data.bookmarks.firstOrNull{it.id==value.parentId}?.title?:"Корень"}\nПапка: ${data.readerFolders.firstOrNull{it.id==value.folderId}?.name?:"Без папки"}"
        is Tag -> "Тег: ${value.name}"
        is NoteVersion ->
            "Версия: ${value.label}\nДата: ${date(value.createdAt)}\n" +
                runCatching {
                        importDescription(
                            Json.decodeFromString<EntryDraft>(value.snapshot).entry,
                            data,
                        )
                    }
                    .getOrDefault("Изменено содержимое версии")
        else -> importTitle(value)
    }
}
