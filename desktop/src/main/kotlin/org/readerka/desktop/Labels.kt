package org.readerka.desktop

import java.awt.Component
import javax.swing.*
import org.readerka.data.*

fun displayLabel(value: Any?): String =
    when (value) {
        EntryKind.Note -> "Заметка"
        EntryKind.Quote -> "Цитата"
        EntryKind.Consp -> "Конспект"
        CollectionScope.Notes -> "Заметки и цитаты"
        CollectionScope.Consp -> "Конспекты"
        NoteSort.Newest -> "Новые сначала"
        NoteSort.Oldest -> "Старые сначала"
        NoteSort.Source -> "По источнику"
        NoteSort.Author -> "По автору"
        NoteSort.Page -> "По странице"
        NoteSort.Type -> "По типу"
        Marker.Yellow -> "● Жёлтый"
        Marker.Green -> "● Зелёный"
        Marker.Blue -> "● Синий"
        Marker.Pink -> "● Розовый"
        Marker.Purple -> "● Фиолетовый"
        Marker.Orange -> "● Оранжевый"
        NoteType.Quote -> "Цитата"
        NoteType.Summary -> "Краткое изложение"
        NoteType.Question -> "Вопрос"
        NoteType.Objection -> "Возражение"
        NoteType.Agreement -> "Согласие"
        NoteType.Idea -> "Идея"
        NoteType.Connection -> "Связь"
        NoteType.GeneralNote -> "Общая заметка"
        else -> value?.toString().orEmpty()
    }

fun localize(component: Component) {
    if (component is JComboBox<*>) {
        component.renderer =
            object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?,
                    value: Any?,
                    index: Int,
                    selected: Boolean,
                    focus: Boolean,
                ): Component {
                    super.getListCellRendererComponent(list, value, index, selected, focus)
                    text = displayLabel(value)
                    return this
                }
            }
    }
    if (component is java.awt.Container) component.components.forEach(::localize)
}
