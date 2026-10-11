package org.readerka.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AppShortcut(val action: String, val label: String, val keys: String, val code: Int, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)
object AppShortcuts {
    val global = listOf(
        AppShortcut("commands", "Команды", "Ctrl+P", KeyEvent.KEYCODE_P, ctrl = true),
        AppShortcut("sources", "Открыть PDF", "Ctrl+O", KeyEvent.KEYCODE_O, ctrl = true),
        AppShortcut("note", "Создать заметку", "Ctrl+N", KeyEvent.KEYCODE_N, ctrl = true),
        AppShortcut("consp", "Создать конспект", "Ctrl+Shift+N", KeyEvent.KEYCODE_N, ctrl = true, shift = true),
        AppShortcut("save", "Сохранить запись", "Ctrl+S", KeyEvent.KEYCODE_S, ctrl = true),
        AppShortcut("save", "Сохранить запись", "Ctrl+Enter", KeyEvent.KEYCODE_ENTER, ctrl = true),
        AppShortcut("sidebar", "Проводник", "Ctrl+B", KeyEvent.KEYCODE_B, ctrl = true),
        AppShortcut("close", "Закрыть вкладку", "Ctrl+W", KeyEvent.KEYCODE_W, ctrl = true),
        AppShortcut("next", "Следующая вкладка", "Ctrl+Tab", KeyEvent.KEYCODE_TAB, ctrl = true),
        AppShortcut("previous", "Предыдущая вкладка", "Ctrl+Shift+Tab", KeyEvent.KEYCODE_TAB, ctrl = true, shift = true),
        AppShortcut("search", "Поиск в PDF или редакторе", "Ctrl+F", KeyEvent.KEYCODE_F, ctrl = true),
        AppShortcut("back", "Назад / вернуться в конспект", "Alt+←", KeyEvent.KEYCODE_DPAD_LEFT, alt = true),
        AppShortcut("forward", "Вперёд по истории PDF", "Alt+→", KeyEvent.KEYCODE_DPAD_RIGHT, alt = true),
        AppShortcut("fullscreen", "Полный экран", "F11", KeyEvent.KEYCODE_F11),
        AppShortcut("help", "Справка", "F1", KeyEvent.KEYCODE_F1),
        AppShortcut("escape", "Выйти из полного экрана / закрыть окно", "Esc", KeyEvent.KEYCODE_ESCAPE)
    )
    val editor = listOf(
        AppShortcut("bold", "Жирный", "Ctrl+B", KeyEvent.KEYCODE_B, ctrl = true),
        AppShortcut("italic", "Курсив", "Ctrl+I", KeyEvent.KEYCODE_I, ctrl = true),
        AppShortcut("underline", "Подчёркивание", "Ctrl+U", KeyEvent.KEYCODE_U, ctrl = true),
        AppShortcut("undo", "Отменить", "Ctrl+Z", KeyEvent.KEYCODE_Z, ctrl = true),
        AppShortcut("redo", "Повторить", "Ctrl+Shift+Z", KeyEvent.KEYCODE_Z, ctrl = true, shift = true),
        AppShortcut("redo", "Повторить", "Ctrl+Y", KeyEvent.KEYCODE_Y, ctrl = true),
        AppShortcut("link", "Ссылка на выделенный текст", "Ctrl+K", KeyEvent.KEYCODE_K, ctrl = true),
        AppShortcut("find", "Найти в документе", "Ctrl+F", KeyEvent.KEYCODE_F, ctrl = true)
    )
    val pdfKeys = mapOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1, KeyEvent.KEYCODE_DPAD_DOWN to 1, KeyEvent.KEYCODE_PAGE_DOWN to 1,
        KeyEvent.KEYCODE_DPAD_LEFT to -1, KeyEvent.KEYCODE_DPAD_UP to -1, KeyEvent.KEYCODE_PAGE_UP to -1, KeyEvent.KEYCODE_MOVE_HOME to Int.MIN_VALUE, KeyEvent.KEYCODE_MOVE_END to Int.MAX_VALUE)
    fun match(event: KeyEvent, editorContext: Boolean = false): AppShortcut? = (if (editorContext) editor else global).firstOrNull {
        event.keyCode == it.code && event.isCtrlPressed == it.ctrl && event.isAltPressed == it.alt && event.isShiftPressed == it.shift && !event.isMetaPressed
    }
}
@Composable fun HelpDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Справка Readerka") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(
                "Чтение" to "Импортируйте PDF в библиотеку. Панель чтения открывает поиск, цитаты, закладки, конспекты, свойства, переход к странице и подгонку страницы. Удерживайте слово для выделения; ручки меняют границы. Выделение можно скопировать, перевести, подсветить, прокомментировать или добавить в выбранный конспект. Перевод использует интернет.",
                "Конспекты" to "Создавайте конспект из общего раздела, темы или книги. В свойствах выбирайте связанные книги, темы и теги. Связь с несколькими книгами сохраняет один документ. У книги есть вкладка конспектов. Заметку можно преобразовать без копирования.",
                "Редактор" to "Выделите текст и выберите оформление либо включите оформление для дальнейшего ввода. Доступны заголовки, списки, цитатный блок, код, оглавление, поиск, отмена и повтор. Конспекты с названием сохраняются автоматически; статус показывает результат. В свойствах доступны история, контрольная версия и исходный Markdown. В режиме чтения разделы можно сворачивать.",
                "Ссылки и вставки" to "Вставить из библиотеки: ссылка на выделенное слово, ссылка-карточка или содержимое с оформлением / только текст. Можно выбрать фрагмент и добавить источник. Содержание — снимок. В редакторе поставьте курсор в ссылку и нажмите Действия ссылки; в режиме чтения касание открывает источник. У карточек есть ⋮ и долгое нажатие. Доступны открыть справа, предпросмотр, изменить и убрать ссылку. Кнопка возврата восстанавливает место в конспекте. Где используется показывает обратные ссылки.",
                "Организация" to "В проводнике и каталогах доступны поиск, папки, вложенные темы, теги и фильтры. ⋮ и долгое нажатие открывают меню. Переместить, Добавить в папку, Открыть справа и Изменить порядок доступны без перетаскивания. Удаление назначения в одну тему сохраняет запись в остальных.",
                "Области и полный экран" to "Области и размеры: слайдеры и готовые пропорции. Ручки границ двигаются касанием. Чтобы свернуть область, перетащите границу к краю до подсказки и отпустите либо нажмите Свернуть область. Закрытие последней вкладки сворачивает пустую область. Повторное включение двух областей восстанавливает вкладки. В полном экране вкладки и кнопки управления остаются доступны. На узком экране клавиатура временно оставляет активную область.",
                "Сохранение и экспорт" to "В настройках создавайте резервный ZIP и восстанавливайте его. ZIP сохраняет PDF, записи, форматирование, связи и контрольные версии; несохранённые черновики сначала сохраните. Экспорт доступны в Markdown, CSV и Obsidian Vault. Внешние изменения Vault не возвращаются в Readerka. Preview хранит библиотеку отдельно от прежнего приложения."
            ).forEach { (title, text) -> Text(title, style = MaterialTheme.typography.titleSmall); Text(text) }
            Text("Горячие клавиши", style = MaterialTheme.typography.titleSmall)
            AppShortcuts.global.forEach { Text("${it.keys} — ${it.label}") }
            Text("В визуальном редакторе", style = MaterialTheme.typography.titleSmall)
            AppShortcuts.editor.forEach { Text("${it.keys} — ${it.label}") }
            Text("PDF: ← / ↑ / Page Up — предыдущая страница; → / ↓ / Page Down — следующая; Home / End — первая / последняя. Tab / Shift+Tab — перемещение фокуса; Enter / пробел — действие. Клавиши PDF работают в активной области чтения.")
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}
