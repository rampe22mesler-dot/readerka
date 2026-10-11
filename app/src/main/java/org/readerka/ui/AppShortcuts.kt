package org.readerka.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class AppShortcut(val action: String, val label: String, val keys: String, val code: Int, val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false)
object AppShortcuts {
    val global = listOf(
        AppShortcut("commands", "Команды", "Ctrl+P", KeyEvent.KEYCODE_P, ctrl = true),
        AppShortcut("sources", "Открыть PDF", "Ctrl+O", KeyEvent.KEYCODE_O, ctrl = true),
        AppShortcut("import", "Импорт PDF", "Ctrl+Shift+O", KeyEvent.KEYCODE_O, ctrl = true, shift = true),
        AppShortcut("note", "Создать заметку", "Ctrl+N", KeyEvent.KEYCODE_N, ctrl = true),
        AppShortcut("consp", "Создать конспект", "Ctrl+Shift+N", KeyEvent.KEYCODE_N, ctrl = true, shift = true),
        AppShortcut("save", "Сохранить запись", "Ctrl+S", KeyEvent.KEYCODE_S, ctrl = true),
        AppShortcut("save", "Сохранить запись", "Ctrl+Enter", KeyEvent.KEYCODE_ENTER, ctrl = true),
        AppShortcut("sidebar", "Проводник", "Ctrl+Shift+B", KeyEvent.KEYCODE_B, ctrl = true, shift = true),
        AppShortcut("close", "Закрыть вкладку", "Ctrl+W", KeyEvent.KEYCODE_W, ctrl = true),
        AppShortcut("next", "Следующая вкладка", "Ctrl+Tab", KeyEvent.KEYCODE_TAB, ctrl = true),
        AppShortcut("previous", "Предыдущая вкладка", "Ctrl+Shift+Tab", KeyEvent.KEYCODE_TAB, ctrl = true, shift = true),
        AppShortcut("next", "Следующая вкладка", "Ctrl+Alt+T", KeyEvent.KEYCODE_T, ctrl = true, alt = true),
        AppShortcut("previous", "Предыдущая вкладка", "Ctrl+Alt+Shift+T", KeyEvent.KEYCODE_T, ctrl = true, alt = true, shift = true),
        AppShortcut("search", "Поиск в PDF или редакторе", "Ctrl+F", KeyEvent.KEYCODE_F, ctrl = true),
        AppShortcut("properties", "Свойства PDF или записи", "Ctrl+Alt+I", KeyEvent.KEYCODE_I, ctrl = true, alt = true),
        AppShortcut("back", "Назад / вернуться в конспект", "Alt+←", KeyEvent.KEYCODE_DPAD_LEFT, alt = true),
        AppShortcut("forward", "Вперёд по истории PDF", "Alt+→", KeyEvent.KEYCODE_DPAD_RIGHT, alt = true),
        AppShortcut("fullscreen", "Войти / выйти из полного экрана", "F11", KeyEvent.KEYCODE_F11),
        AppShortcut("fullscreen", "Войти / выйти из полного экрана", "Ctrl+Alt+F", KeyEvent.KEYCODE_F, ctrl = true, alt = true),
        AppShortcut("help", "Справка", "F1", KeyEvent.KEYCODE_F1),
        AppShortcut("help", "Справка", "Ctrl+/", KeyEvent.KEYCODE_SLASH, ctrl = true),
        AppShortcut("escape", "Закрыть меню / выйти из полного экрана", "Esc", KeyEvent.KEYCODE_ESCAPE),
        AppShortcut("drafts", "Черновики", "Ctrl+Shift+D", KeyEvent.KEYCODE_D, ctrl = true, shift = true),
        AppShortcut("layout", "Области и размеры", "Ctrl+Alt+L", KeyEvent.KEYCODE_L, ctrl = true, alt = true),
        AppShortcut("split", "Одна / две области", "Ctrl+Alt+S", KeyEvent.KEYCODE_S, ctrl = true, alt = true),
        AppShortcut("maximize", "Развернуть активную область", "Ctrl+Alt+M", KeyEvent.KEYCODE_M, ctrl = true, alt = true),
        AppShortcut("other-pane", "Перейти в другую область", "Ctrl+Alt+J", KeyEvent.KEYCODE_J, ctrl = true, alt = true),
        AppShortcut("right", "Открыть справа", "Ctrl+Alt+R", KeyEvent.KEYCODE_R, ctrl = true, alt = true),
        AppShortcut("pin", "Закрепить / открепить вкладку", "Ctrl+Alt+P", KeyEvent.KEYCODE_P, ctrl = true, alt = true),
        AppShortcut("tab-left", "Переместить вкладку левее", "Ctrl+Shift+Page Up", KeyEvent.KEYCODE_PAGE_UP, ctrl = true, shift = true),
        AppShortcut("tab-right", "Переместить вкладку правее", "Ctrl+Shift+Page Down", KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true, shift = true),
        AppShortcut("library", "Библиотека", "Alt+1", KeyEvent.KEYCODE_1, alt = true),
        AppShortcut("notes", "Каталог заметок и цитат", "Alt+2", KeyEvent.KEYCODE_2, alt = true),
        AppShortcut("consp-catalogue", "Каталог конспектов", "Alt+3", KeyEvent.KEYCODE_3, alt = true),
        AppShortcut("settings", "Настройки", "Alt+4", KeyEvent.KEYCODE_4, alt = true),
        AppShortcut("reading", "Рабочее пространство", "Alt+5", KeyEvent.KEYCODE_5, alt = true),
        AppShortcut("export", "Экспорт сохранённой записи в Markdown", "Ctrl+Alt+X", KeyEvent.KEYCODE_X, ctrl = true, alt = true)
    )
    val editor = listOf(
        AppShortcut("bold", "Жирный", "Ctrl+B", KeyEvent.KEYCODE_B, ctrl = true),
        AppShortcut("italic", "Курсив", "Ctrl+I", KeyEvent.KEYCODE_I, ctrl = true),
        AppShortcut("underline", "Подчёркивание", "Ctrl+U", KeyEvent.KEYCODE_U, ctrl = true),
        AppShortcut("undo", "Отменить", "Ctrl+Z", KeyEvent.KEYCODE_Z, ctrl = true),
        AppShortcut("redo", "Повторить", "Ctrl+Shift+Z", KeyEvent.KEYCODE_Z, ctrl = true, shift = true),
        AppShortcut("redo", "Повторить", "Ctrl+Y", KeyEvent.KEYCODE_Y, ctrl = true),
        AppShortcut("link", "Ссылка на выделенный текст", "Ctrl+K", KeyEvent.KEYCODE_K, ctrl = true),
        AppShortcut("insert-card", "Вставить ссылку блоком", "Ctrl+Shift+K", KeyEvent.KEYCODE_K, ctrl = true, shift = true),
        AppShortcut("insert-content", "Вставить содержимое из библиотеки", "Ctrl+Alt+V", KeyEvent.KEYCODE_V, ctrl = true, alt = true),
        AppShortcut("paragraph", "Обычный абзац", "Ctrl+Alt+0", KeyEvent.KEYCODE_0, ctrl = true, alt = true),
        AppShortcut("heading-1", "Заголовок 1", "Ctrl+Alt+1", KeyEvent.KEYCODE_1, ctrl = true, alt = true),
        AppShortcut("heading-2", "Заголовок 2", "Ctrl+Alt+2", KeyEvent.KEYCODE_2, ctrl = true, alt = true),
        AppShortcut("heading-3", "Заголовок 3", "Ctrl+Alt+3", KeyEvent.KEYCODE_3, ctrl = true, alt = true),
        AppShortcut("bullet", "Маркированный список", "Ctrl+Shift+8", KeyEvent.KEYCODE_8, ctrl = true, shift = true),
        AppShortcut("ordered", "Нумерованный список", "Ctrl+Shift+7", KeyEvent.KEYCODE_7, ctrl = true, shift = true),
        AppShortcut("quote-block", "Цитатный блок", "Ctrl+Shift+9", KeyEvent.KEYCODE_9, ctrl = true, shift = true),
        AppShortcut("code-block", "Блок кода", "Ctrl+Alt+C", KeyEvent.KEYCODE_C, ctrl = true, alt = true),
        AppShortcut("outline", "Оглавление", "Ctrl+Alt+O", KeyEvent.KEYCODE_O, ctrl = true, alt = true),
        AppShortcut("preview", "Чтение / редактирование", "Ctrl+Alt+E", KeyEvent.KEYCODE_E, ctrl = true, alt = true),
        AppShortcut("history", "История изменений", "Ctrl+Alt+H", KeyEvent.KEYCODE_H, ctrl = true, alt = true),
        AppShortcut("checkpoint", "Сохранить контрольную версию", "Ctrl+Alt+Shift+S", KeyEvent.KEYCODE_S, ctrl = true, alt = true, shift = true),
        AppShortcut("markdown", "Исходный Markdown / визуальный редактор", "Ctrl+Alt+D", KeyEvent.KEYCODE_D, ctrl = true, alt = true),
        AppShortcut("link-actions", "Действия текущей ссылки", "Ctrl+Alt+K", KeyEvent.KEYCODE_K, ctrl = true, alt = true),
        AppShortcut("convert", "Преобразовать заметку в конспект", "Ctrl+Alt+N", KeyEvent.KEYCODE_N, ctrl = true, alt = true),
        AppShortcut("discard", "Удалить черновик с подтверждением", "Ctrl+Alt+Backspace", KeyEvent.KEYCODE_DEL, ctrl = true, alt = true),
        AppShortcut("delete", "Удалить запись с подтверждением", "Ctrl+Shift+Delete", KeyEvent.KEYCODE_FORWARD_DEL, ctrl = true, shift = true)
    )
    val pdf = listOf(
        AppShortcut("materials", "Показать / скрыть материалы книги", "Ctrl+M", KeyEvent.KEYCODE_M, ctrl = true),
        AppShortcut("quotes", "Цитаты книги", "Alt+Q", KeyEvent.KEYCODE_Q, alt = true),
        AppShortcut("bookmarks", "Закладки книги", "Alt+B", KeyEvent.KEYCODE_B, alt = true),
        AppShortcut("book-consp", "Конспекты книги", "Alt+C", KeyEvent.KEYCODE_C, alt = true),
        AppShortcut("bookmark", "Добавить закладку", "Ctrl+D", KeyEvent.KEYCODE_D, ctrl = true),
        AppShortcut("page", "Перейти к странице", "Ctrl+G", KeyEvent.KEYCODE_G, ctrl = true),
        AppShortcut("fit", "По ширине / целая страница", "Ctrl+Alt+W", KeyEvent.KEYCODE_W, ctrl = true, alt = true),
        AppShortcut("copy-selection", "Копировать выделение PDF", "Ctrl+C", KeyEvent.KEYCODE_C, ctrl = true),
        AppShortcut("highlight", "Подсветить выделение текущим цветом", "Ctrl+H", KeyEvent.KEYCODE_H, ctrl = true),
        AppShortcut("translate", "Перевести выделение", "Ctrl+T", KeyEvent.KEYCODE_T, ctrl = true),
        AppShortcut("selection-consp", "Добавить выделение в конспект", "Ctrl+Shift+Q", KeyEvent.KEYCODE_Q, ctrl = true, shift = true),
        AppShortcut("comment-selection", "Прокомментировать выделение", "Ctrl+Shift+M", KeyEvent.KEYCODE_M, ctrl = true, shift = true)
    )
    val all get() = global + editor + pdf
    val pdfKeys = mapOf(KeyEvent.KEYCODE_DPAD_RIGHT to 1, KeyEvent.KEYCODE_DPAD_DOWN to 1, KeyEvent.KEYCODE_PAGE_DOWN to 1,
        KeyEvent.KEYCODE_DPAD_LEFT to -1, KeyEvent.KEYCODE_DPAD_UP to -1, KeyEvent.KEYCODE_PAGE_UP to -1, KeyEvent.KEYCODE_MOVE_HOME to Int.MIN_VALUE, KeyEvent.KEYCODE_MOVE_END to Int.MAX_VALUE)
    fun match(event: KeyEvent): AppShortcut? = all.firstOrNull {
        event.keyCode == it.code && event.isCtrlPressed == it.ctrl && event.isAltPressed == it.alt && event.isShiftPressed == it.shift && !event.isMetaPressed
    }
    fun keys(action: String) = all.firstOrNull { it.action == action }?.keys.orEmpty()
    fun label(action: String) = all.firstOrNull { it.action == action }?.label.orEmpty()

}
@Composable fun HelpDialog(onDismiss: () -> Unit) {
    var keysTab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    var query by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Справка Readerka") }, text = {
        Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(keysTab, { keysTab = true }, label = { Text("Горячие клавиши") }, modifier = Modifier.heightIn(min = 48.dp))
                FilterChip(!keysTab, { keysTab = false }, label = { Text("Функции") }, modifier = Modifier.heightIn(min = 48.dp))
            }
            if (!keysTab) listOf(
                "Чтение" to "Импортируйте PDF в библиотеку. Панель чтения открывает поиск, цитаты, закладки, конспекты, свойства, переход к странице и подгонку страницы. Удерживайте слово для выделения; ручки меняют границы. Выделение можно скопировать, перевести, подсветить, прокомментировать или добавить в выбранный конспект. Перевод использует интернет.",
                "Конспекты" to "Создавайте конспект из общего раздела, темы или книги. В свойствах выбирайте связанные книги, темы и теги. Связь с несколькими книгами сохраняет один документ. У книги есть вкладка конспектов. Заметку можно преобразовать без копирования.",
                "Редактор" to "Выделите текст и выберите оформление либо включите оформление для дальнейшего ввода. Доступны заголовки, списки, цитатный блок, код, оглавление, поиск, отмена и повтор. Конспекты с названием сохраняются автоматически; статус показывает результат. Оглавление, Стиль, Оформление и Вставить составляют короткую панель редактора. История и ручное сохранение доступны через статус внизу. Свойства открывают боковую панель книг, тем и тегов; дополнительные команды — через ⋮. В режиме чтения разделы можно сворачивать.",
                "Ссылки и вставки" to "Вставить из библиотеки: ссылка на выделенное слово, ссылка-карточка или содержимое с оформлением / только текст. Можно выбрать фрагмент и добавить источник. Содержание — снимок. В редакторе поставьте курсор в ссылку и выберите ⋮ → Действия текущей ссылки; в режиме чтения касание открывает источник. У карточек есть ⋮ и долгое нажатие. Доступны открыть справа, предпросмотр, изменить и убрать ссылку. Кнопка возврата восстанавливает место в конспекте. Где используется показывает обратные ссылки.",
                "Организация" to "В проводнике и каталогах доступны поиск, папки, вложенные темы, теги и фильтры. ⋮ и долгое нажатие открывают меню. Переместить, Добавить в папку, Открыть справа и Изменить порядок доступны без перетаскивания. Удаление назначения в одну тему сохраняет запись в остальных.",
                "Области и полный экран" to "Области и размеры: слайдеры и готовые пропорции. Ручки границ двигаются касанием. Чтобы свернуть область, перетащите границу к краю до подсказки и отпустите либо нажмите Свернуть область. Закрытие последней вкладки сворачивает пустую область. Повторное включение двух областей восстанавливает вкладки. В полном экране вкладки и кнопки управления остаются доступны. На узком экране клавиатура временно оставляет активную область.",
                "Сохранение и экспорт" to "В настройках создавайте резервный ZIP и восстанавливайте его. ZIP сохраняет PDF, записи, форматирование, связи и контрольные версии; несохранённые черновики сначала сохраните. Экспорт доступны в Markdown, CSV и Obsidian Vault. Внешние изменения Vault не возвращаются в Readerka. Preview хранит библиотеку отдельно от прежнего приложения."
            ).forEach { (title, text) -> Text(title, style = MaterialTheme.typography.titleSmall); Text(text) }
            if (keysTab) {
            OutlinedTextField(query, { query = it }, label = { Text("Найти команду или сочетание") }, modifier = Modifier.fillMaxWidth())
            fun visible(binding: AppShortcut) = query.isBlank() || binding.label.contains(query, true) || binding.keys.contains(query, true)
            Text("Общие команды", style = MaterialTheme.typography.titleSmall)
            val workspaceActions = setOf("layout", "split", "maximize", "other-pane", "right", "pin", "tab-left", "tab-right", "close", "next", "previous", "fullscreen")
            AppShortcuts.global.filter { it.action !in workspaceActions && visible(it) }.forEach { Text("${it.keys} — ${it.label}") }
            Text("Области и вкладки", style = MaterialTheme.typography.titleSmall)
            AppShortcuts.global.filter { it.action in workspaceActions && visible(it) }.forEach { Text("${it.keys} — ${it.label}") }
            Text("В визуальном редакторе", style = MaterialTheme.typography.titleSmall)
            AppShortcuts.editor.filter(::visible).forEach { Text("${it.keys} — ${it.label}") }
            Text("В активном PDF", style = MaterialTheme.typography.titleSmall)
            AppShortcuts.pdf.filter(::visible).forEach { Text("${it.keys} — ${it.label}") }
            Text("PDF: ← / ↑ / Page Up — предыдущая страница; → / ↓ / Page Down — следующая; Home / End — первая / последняя. Ctrl+C / Ctrl+X / Ctrl+V / Ctrl+A — стандартные копирование, вырезание, вставка и выделение всего текста в полях; Ctrl+Shift+V — системная вставка без оформления, если её поддерживает клавиатура. Tab / Shift+Tab — перемещение фокуса; Enter / пробел — действие. Клавиши PDF работают в активной области чтения. Команды редактора применяются к текущему документу; стандартные операции ввода остаются у текстового поля.")
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } })
}

@Composable fun ShortcutMenuItem(action: String, onClick: () -> Unit, enabled: Boolean = true, label: String = AppShortcuts.label(action), modifier: Modifier = Modifier) {
    DropdownMenuItem(text = { Text(label) }, trailingIcon = { Text(AppShortcuts.keys(action), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }, onClick = onClick, enabled = enabled, modifier = modifier)
}
