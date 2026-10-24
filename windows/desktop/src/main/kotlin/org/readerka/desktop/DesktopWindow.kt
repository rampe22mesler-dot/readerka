package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
import java.awt.datatransfer.*
import java.io.File
import java.net.URI
import javax.swing.*
import javax.swing.event.*
import javax.swing.table.DefaultTableModel
import javax.swing.tree.*
import org.readerka.data.*
import org.readerka.export.*

data class Place(val category: Int, val id: String?, val label: String) {
    override fun toString() = label
}

data class CatalogueRow(val kind: String, val id: String, val title: String, val detail: String)

data class OpenedTab(val id: String, val kind: WorkspaceKind, var pinned: Boolean = false)

data class DesktopCommand(val title: String, val key: String?, val action: () -> Unit)

fun button(label: String, action: () -> Unit) =
    MotionButton(label).apply {
        addActionListener { action() }
        minimumSize = Dimension(40, 36)
    }

fun flow(vararg components: Component) =
    JPanel(WrapFlowLayout()).apply {
        var previousWidth = 0
        addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    if (width != previousWidth) {
                        previousWidth = width
                        SwingUtilities.invokeLater { parent?.revalidate() }
                    }
                }
            }
        )
        components.forEach {
            localize(it)
            add(it)
        }
    }

fun fieldPanel(vararg fields: Pair<String, Component>) =
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        fields.forEach { (label, c) ->
            localize(c)
            add(JLabel(label).apply { border = BorderFactory.createEmptyBorder(10, 0, 4, 0) })
            add(c)
        }
    }

fun confirm(owner: Component, message: String) =
    JOptionPane.showConfirmDialog(owner, message, "Readerka", JOptionPane.OK_CANCEL_OPTION) ==
        JOptionPane.OK_OPTION

class DesktopWindow(val session: LibrarySession) : JFrame("Readerka ${ReaderkaVersion.current}") {
    init {
        configureChrome()
        ChromeMotion.configure(session.repository.preference("optimized-ui") == "true")
    }
    val panes = arrayOf(MotionTabbedPane(), MotionTabbedPane())
    private var draggedTab: Component? = null
    private val tabFlavor = DataFlavor("application/x-readerka-tab;class=java.lang.String")
    val tabs = mutableMapOf<Component, OpenedTab>()
    val editors = mutableMapOf<String, EntryPanel>()
    val pdfs = mutableMapOf<String, PdfPanel>()
    val researchFields = mutableMapOf<String, ResearchFieldPanel>()
    var libraryGraph: LibraryGraphPanel? = null
        private set
    internal val explorer = ExplorerPanel(this)
    private val tree get() = explorer.tree
    private val model =
        object : DefaultTableModel(arrayOf("Название", "Содержимое / источник"), 0) {
            override fun isCellEditable(r: Int, c: Int) = false
        }
    val table = JTable(model)
    private val search = JTextField(24)
    private val tagFilter = JTextField(10)
    private val flat = JCheckBox("Все записи")
    private val sort = JComboBox(NoteSort.entries.toTypedArray())
    private val breadcrumb = JLabel()
    private val status = JLabel(" ")
    private var rows = emptyList<CatalogueRow>()
    private var filters = NoteQuery()
    var place = Place(0, null, "Книги")
        private set

    private var rebuilding = false
    private var activePane = 0
    private var splitting = false
    private var fullscreen = false
    private var closing = false
    private var returnToEntry: Pair<String, Int>? = null

    fun returnToNote() {
        returnToEntry?.let { (id, position) ->
            openEntry(id)
            editors[id]?.editor?.jump(position)
        }
    }

    private var oldBounds: Rectangle? = null
    private val workspaceSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panes[0], panes[1])
    private val baseSplit = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, explorer, workspaceSplit)
    private val applicationMenu = JPopupMenu()
    val commands = linkedMapOf<String, DesktopCommand>()
    var busy = false
        private set
    private val backgroundQueue = ArrayDeque<() -> Unit>()

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(640, 480)
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(
            minOf(1320, available.width - 40).coerceAtLeast(920),
            minOf(880, available.height - 60).coerceAtLeast(640),
        )
        setLocationRelativeTo(null)
        tree.rowHeight = if (session.repository.preference("compact") == "true") 24 else 26
        tree.isRootVisible = false
        table.rowHeight = 44
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.columnModel.getColumn(0).preferredWidth = 330
        table.columnModel.getColumn(1).preferredWidth = 440
        val rail = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            preferredSize = Dimension(48, 100)
            name = "readerka-rail"
            background = sidebarColor()
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, chromeLineColor())
            fun tool(icon: String, label: String, action: () -> Unit) {
                add(iconButton(icon, label, action).apply { alignmentX = Component.CENTER_ALIGNMENT })
                add(Box.createVerticalStrut(5))
            }
            add(Box.createVerticalStrut(5))
            tool("more", "Меню приложения") { applicationMenu.show(railAnchor(), 38, 5) }
            tool("sidebar", "Показать / скрыть проводник") { toggleSidebar() }
            tool("search", "Поиск в библиотеке") { if (!explorer.isVisible) toggleSidebar(); explorer.search.requestFocusInWindow() }
            tool("import", "Добавить книгу · ${BookImporter.formats}") { importPdf() }
            tool("note-add", "Создать заметку") { explorer.createEntry(EntryKind.Note) }
            tool("note", "Создать конспект") { explorer.createEntry(EntryKind.Consp) }
            tool("link", "Создать исследовательское поле") { safe { newResearchField() } }
            tool("graph", "Граф библиотеки") { safe { openLibraryGraph() } }
            tool("split", "Две рабочие области") { toggleSplit() }
            add(Box.createVerticalGlue())
            tool("help", "Справка") { help() }
            tool("settings", "Настройки") { settings() }
        }
        add(rail, BorderLayout.WEST)
        add(baseSplit, BorderLayout.CENTER)
        status.name = "readerka-status"
        status.isOpaque = true
        add(status, BorderLayout.SOUTH)
        applyChromeTheme(status)
        table.rowHeight = if (session.repository.preference("compact") == "true") 36 else 44
        workspaceSplit.orientation =
            if (session.repository.preference("vertical") == "true") JSplitPane.VERTICAL_SPLIT
            else JSplitPane.HORIZONTAL_SPLIT
        baseSplit.resizeWeight = 0.0
        baseSplit.dividerLocation = session.workspace.sidebarWidth.toInt()
        baseSplit.dividerSize = 1
        workspaceSplit.resizeWeight = .5
        workspaceSplit.dividerSize = 1
        panes.forEach { it.minimumSize = Dimension(180, 100); it.putClientProperty("JTabbedPane.tabHeight", 38); it.putClientProperty("JTabbedPane.tabType", "card"); it.tabLayoutPolicy = JTabbedPane.SCROLL_TAB_LAYOUT }
        panes.forEachIndexed { index, pane ->
            pane.transferHandler = object : TransferHandler() {
                override fun getSourceActions(c: JComponent) = MOVE
                override fun createTransferable(c: JComponent): Transferable? {
                    draggedTab = pane.selectedComponent?.takeIf { it in tabs } ?: return null
                    return object : Transferable {
                        override fun getTransferDataFlavors() = arrayOf(tabFlavor)
                        override fun isDataFlavorSupported(f: DataFlavor) = f == tabFlavor
                        override fun getTransferData(f: DataFlavor): Any { if (f != tabFlavor) throw UnsupportedFlavorException(f); return "Readerka tab" }
                    }
                }
                override fun canImport(s: TransferSupport) = s.isDrop && s.isDataFlavorSupported(tabFlavor) && draggedTab in tabs
                override fun importData(s: TransferSupport): Boolean {
                    if (!canImport(s)) return false
                    val panel = draggedTab ?: return false
                    val point = s.dropLocation.dropPoint
                    val at = pane.indexAtLocation(point.x, point.y).takeIf { it >= 0 } ?: pane.tabCount
                    safe { moveWorkspaceTab(panel, index, at) }
                    return true
                }
                override fun exportDone(c: JComponent, data: Transferable?, action: Int) { draggedTab = null }
            }
            pane.addChangeListener {
                if (!rebuilding) {
                    activePane = index
                    captureWorkspace()
                }
            }
        }
        workspaceSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (splitting && !rebuilding) {
                captureWorkspace()
            }
        }
        baseSplit.border = BorderFactory.createEmptyBorder()
        workspaceSplit.border = BorderFactory.createEmptyBorder()
        baseSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) { if (!rebuilding && isShowing) captureWorkspace() }
        installDividerCollapse()
        val focusListener =
            java.beans.PropertyChangeListener { event ->
                val component = event.newValue as? Component
                if (component != null) {
                    panes
                        .indexOfFirst { SwingUtilities.isDescendingFrom(component, it) }
                        .takeIf { it >= 0 }
                        ?.let { activePane = it }
                }
            }
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addPropertyChangeListener("permanentFocusOwner", focusListener)
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosed(e: WindowEvent) {
                    if (closing)
                        KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .removePropertyChangeListener("permanentFocusOwner", focusListener)
                }
            }
        )
        registerCommands()
        installMenu()
        if (DesktopPlatform.isMac) {
            (0 until jMenuBar.menuCount).map { jMenuBar.getMenu(it) }.forEach { menu ->
                applicationMenu.add(JMenu(menu.text).apply {
                    menu.menuComponents.filterIsInstance<JMenuItem>().forEach { original ->
                        add(JMenuItem(original.text).apply {
                            accelerator = original.accelerator
                            addActionListener { original.doClick() }
                        })
                    }
                })
            }
        } else {
            (0 until jMenuBar.menuCount).map { jMenuBar.getMenu(it) }.forEach { applicationMenu.add(it) }
            jMenuBar = null
        }
        session.onChange = {
            if (SwingUtilities.isEventDispatchThread()) {
                synchronizeEditors()
                refresh()
                updateTabTitles()
            } else
                SwingUtilities.invokeLater {
                    synchronizeEditors()
                    refresh()
                    updateTabTitles()
                }
        }
        val savedCategory =
            session.repository.preference("category")?.toIntOrNull()?.coerceIn(0, 3) ?: 0
        val savedFolder =
            session.repository
                .preference("folder")
                ?.takeIf { it.isNotEmpty() }
                ?.takeIf { id ->
                    if (savedCategory == 0) session.data.documentFolders.any { it.id == id }
                    else
                        session.data.collections.any {
                            it.id == id &&
                                it.scope ==
                                    if (savedCategory == 2) CollectionScope.Consp
                                    else CollectionScope.Notes
                        }
                }
        place =
            Place(
                savedCategory,
                savedFolder,
                savedFolder?.let(::folderName) ?: categoryName(savedCategory),
            )
        refresh()
        restoreWorkspace()
        addWindowListener(
            object : WindowAdapter() {
                override fun windowClosing(e: WindowEvent) {
                    safe {
                        if (busy) {
                            JOptionPane.showMessageDialog(
                                this@DesktopWindow,
                                "Дождитесь завершения операции",
                            )
                            return@safe
                        }
                        flushEditors()
                        captureWorkspace()
                        editors.values.forEach { it.disposePanel() }
                        pdfs.values.forEach { it.close() }
                        researchFields.values.forEach { it.disposePanel() }
                        libraryGraph?.disposePanel()
                        session.repository.close()
                        closing = true
                        dispose()
                    }
                }
            }
        )
    }

    fun safe(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            e.printStackTrace()
            JOptionPane.showMessageDialog(
                this,
                e.message ?: e.javaClass.simpleName,
                "Не удалось выполнить действие",
                JOptionPane.ERROR_MESSAGE,
            )
        }
    }

    fun <T> background(label: String, work: () -> T, done: (T) -> Unit) {
        if (busy) {
            backgroundQueue.addLast { background(label, work, done) }
            return
        }
        busy = true
        val dialogs = ownedWindows.filter { it.isVisible && it.isEnabled }
        dialogs.forEach { it.isEnabled = false }
        glassPane =
            JPanel().apply {
                isOpaque = false
                addMouseListener(object : MouseAdapter() {})
                cursor = Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)
            }
        glassPane.isVisible = true
        status.text = label
        object : SwingWorker<T, Unit>() {
                override fun doInBackground() = work()

                override fun done() {
                    busy = false
                    glassPane.isVisible = false
                    dialogs.filter { it.isDisplayable }.forEach { it.isEnabled = true }
                    safe {
                        val value = get()
                        done(value)
                    }
                    if (!busy) {
                        status.text = "Готово"
                        if (backgroundQueue.isNotEmpty()) backgroundQueue.removeFirst().invoke()
                    }
                }
            }
            .execute()
    }

    private fun selected() = explorer.nodes().filter { it.item.kind != "section" }.map { CatalogueRow(it.item.kind, it.item.id, it.title, "") }

    fun selectExplorerPlace(category: Int, folder: String?) {
        place = Place(category, folder, folder?.let(::folderName) ?: categoryName(category))
        session.repository.persistPreference("category", category.toString())
        session.repository.persistPreference("folder", folder.orEmpty())
    }

    private fun railAnchor(): Component = contentPane

    private fun toggleSidebar() {
        val visible = !explorer.isVisible
        if (!visible) captureWorkspace()
        explorer.isVisible = visible
        baseSplit.dividerSize = if (visible) 1 else 0
        if (visible) baseSplit.dividerLocation = session.workspace.sidebarWidth.toInt().coerceAtLeast(150)
        captureWorkspace()
    }

    fun navigate(p: Place) {
        place = p
        session.repository.persistPreference("category", p.category.toString())
        session.repository.persistPreference("folder", p.id.orEmpty())
        search.text = ""
        flat.isSelected = false

        activePane = 0
        refreshRows()
    }

    private fun up() {
        val parent =
            if (place.category == 0)
                session.data.documentFolders.firstOrNull { it.id == place.id }?.parentId
            else session.data.collections.firstOrNull { it.id == place.id }?.parentId
        navigate(
            Place(
                place.category,
                parent,
                parent?.let { id -> folderName(id) } ?: categoryName(place.category),
            )
        )
    }

    private fun categoryName(i: Int) =
        when (i) {
            0 -> "Книги"
            1 -> "Заметки и цитаты"
            3 -> "Исследовательские поля"
            else -> "Конспекты"
        }

    private fun folderName(id: String) =
        session.data.collections.firstOrNull { it.id == id }?.name
            ?: session.data.documentFolders.firstOrNull { it.id == id }?.name
            ?: "Папка"

    private fun refresh() {
        explorer.refresh()
        refreshRows()
    }

    private fun refreshRows() {
        val s = session.data
        val query = search.text.trim()
        val result = mutableListOf<CatalogueRow>()
        if (!flat.isSelected && place.category != 3) {
            if (place.category == 0)
                s.documentFolders
                    .filter { it.parentId == place.id }
                    .forEach {
                        result += CatalogueRow("folder", it.id, "▸ ${it.name}", "Папка книг")
                    }
            else
                s.collections
                    .filter {
                        it.parentId == place.id &&
                            it.scope ==
                                if (place.category == 2) CollectionScope.Consp
                                else CollectionScope.Notes
                    }
                    .forEach { result += CatalogueRow("folder", it.id, "▸ ${it.name}", "Папка") }
        }
        if (place.category == 0) {
            val folderIds =
                place.id?.let {
                    FolderTree.descendants(it, s.documentFolders.map { it.id to it.parentId })
                }
            s.documents
                .filter { d ->
                    (flat.isSelected ||
                        if (query.isBlank()) d.folderId == place.id
                        else folderIds == null || d.folderId in folderIds) &&
                        (query.isBlank() ||
                            listOf(d.title, d.authors, d.description).any {
                                it.contains(query, true)
                            }) &&
                        (tagFilter.text.isBlank() ||
                            s.documentTags.any {
                                it.documentId == d.id &&
                                    s.tags.any { t ->
                                        t.id == it.tagId &&
                                            t.name.equals(tagFilter.text.trim(), true)
                                    }
                            })
                }
                .sortedBy { it.title }
                .forEach {
                    result +=
                        CatalogueRow("pdf", it.id, it.title, "${it.pageCount} стр. · ${it.authors}")
                }
        } else if (place.category == 3) {
            s.researchFields.filter { it.title.contains(query, true) }.forEach { result += CatalogueRow("research", it.id, it.title, "${it.placements.size} карточек · ${it.edges.size} связей") }
        } else {
            val tag = s.tags.firstOrNull { it.name.equals(tagFilter.text.trim(), true) }?.id
            val entries =
                if (tagFilter.text.isNotBlank() && tag == null) emptyList()
                else
                    catalogueEntries(
                        s,
                        if (place.category == 2) EntryKind.Consp else EntryKind.Note,
                        place.id,
                        flat.isSelected,
                        filters.copy(
                            text = query,
                            tagId = tag,
                            sort = sort.selectedItem as NoteSort,
                        ),
                    )
            entries.forEach { e ->
                result +=
                    CatalogueRow(
                        "entry",
                        e.id,
                        (if (e.kind == EntryKind.Quote) "❝ "
                        else if (e.kind == EntryKind.Consp) "▤ " else "✎ ") + e.title,
                        (e.quote.ifBlank { e.richText().text }.replace('\n', ' ').take(120)) +
                            " · " +
                            s.documents
                                .filter { it.id in e.sourceBooks() }
                                .joinToString { it.title },
                    )
            }
        }
        rows = result
        model.rowCount = 0
        rows.forEach { model.addRow(arrayOf(it.title, it.detail)) }
        breadcrumb.text =
            categoryName(place.category) + (place.id?.let { " / " + folderName(it) } ?: "")
        status.text =
            "${s.documents.size} книг · ${s.entries.count{it.kind==EntryKind.Consp}} конспектов · ${s.entries.count{it.kind!=EntryKind.Consp}} заметок и цитат"
    }

    private fun openRow(right: Boolean = false) {
        selected().firstOrNull()?.let { r ->
            when (r.kind) {
                "folder" -> navigate(Place(place.category, r.id, folderName(r.id)))
                "pdf" -> openPdf(r.id, right = right)
                else -> openEntry(r.id, right = right)
            }
        }
    }

    fun newEntry(kind: EntryKind, book: String? = null) {
        safe {
            val template =
                if (kind == EntryKind.Consp)
                    JOptionPane.showInputDialog(
                            this,
                            "Структура нового конспекта",
                            "Создать конспект",
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            ConspTemplate.entries.map { it.title }.toTypedArray(),
                            ConspTemplate.Blank.title,
                        )
                        ?.let { choice -> ConspTemplate.entries.first { it.title == choice } }
                        ?: return@safe
                else ConspTemplate.Blank
            val folder = place.id.takeIf { place.category == if (kind == EntryKind.Consp) 2 else 1 }
            val e = session.create(kind, folder, book, template)
            openEntry(e.id)
        }
    }

    fun newFolder() = explorer.createFolder()

    fun chooseFile(save: Boolean, extension: String, default: String = ""): File? {
        val chooser =
            JFileChooser().apply {
                fileFilter =
                    javax.swing.filechooser.FileNameExtensionFilter(
                        extension.uppercase(),
                        extension,
                    )
                if (default.isNotBlank()) selectedFile = File(default)
            }
        val result = if (save) chooser.showSaveDialog(this) else chooser.showOpenDialog(this)
        if (result != JFileChooser.APPROVE_OPTION) return null
        var file = chooser.selectedFile
        if (save && !file.name.endsWith(".$extension", true))
            file = File(file.parentFile, file.name + ".$extension")
        if (save && file.exists() && !confirm(this, "Заменить ${file.name}?")) return null
        return file
    }

    private fun importPdf() {
        val chooser = JFileChooser().apply {
            dialogTitle = "Добавить книгу"
            isAcceptAllFileFilterUsed = false
            fun bookFilter(label: String, vararg extensions: String) = object : javax.swing.filechooser.FileFilter() {
                override fun getDescription() = label
                override fun accept(file: File) = file.isDirectory || extensions.any { file.name.endsWith(".$it", true) }
            }
            fileFilter = bookFilter(BookImporter.formats, *BookImporter.extensions)
            addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("PDF", "pdf"))
            addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("DjVu", "djvu", "djv"))
            addChoosableFileFilter(bookFilter("Электронные книги · EPUB, FB2, FB2.ZIP", "epub", "fb2", "fb2.zip"))
            addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Текстовые документы · TXT, RTF, HTML, DOCX", "txt", "rtf", "html", "htm", "docx"))
            addChoosableFileFilter(javax.swing.filechooser.FileNameExtensionFilter("Комиксы и сканы · CBZ, изображения", "cbz", "png", "jpg", "jpeg", "tif", "tiff", "bmp", "gif"))
            accessory = JLabel("<html>Импорт выполняется локально.<br>Книги преобразуются в PDF для чтения.<br>Вёрстка текстовых форматов упрощается.<br>DOCX: импортируется текст.<br>Исходный файл храните отдельно.</html>").apply {
                border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
            }
        }
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        val folder = place.id.takeIf { place.category == 0 }
        background("Импорт ${file.name}…", { session.importBook(file, folder) }) { d ->
            refresh()
            openPdf(d.id)
        }
    }

    fun exportLibrary() {
        safe {
            flushEditors()
            session.saveAll()
            val file = chooseFile(true, "zip", TransferPanel.suggestedFilename()) ?: return@safe
            background(
                "Создание ZIP библиотеки…",
                {
                    session.repository.export(file)
                    file
                },
            ) {
                JOptionPane.showMessageDialog(
                    this,
                    "Выгрузка создана: ${it.path}\nПеренесите ZIP на другое устройство и выберите «Синхронизация → Загрузить данные».\nДля открытия нужна Readerka с поддержкой ZIP формата 5; прежние версии Windows и Android этот архив не принимают.",
                )
            }
        }
    }

    fun importLibrary(provided: File? = null, deleteProvidedAfterStaging: Boolean = false) {
        val file = provided ?: chooseFile(false, "zip") ?: return
        safe {
            try {
                flushEditors()
                session.saveAll()
            } catch (e: Exception) {
                if (deleteProvidedAfterStaging) file.delete()
                throw e
            }
            val local = session.data
            background("Проверка ZIP и PDF…", {
                val staged = try { session.repository.stage(file) }
                    finally { if (deleteProvidedAfterStaging) file.delete() }
                try {
                    Triple(staged, LibraryMerge.differences(local, staged.data), session.repository.pdfConflicts(staged))
                } catch (e: Exception) {
                    staged.directory.deleteRecursively()
                    throw e
                }
            }) { (staged, metadataConflicts, pdfConflicts) ->
                try {
                    val conflicts = metadataConflicts + pdfConflicts.map { id ->
                        val doc = staged.data.documents.first { it.id == id }
                        ImportDifference("Содержимое PDF", id,
                            "На устройстве сохранён другой PDF. «Сохранить местные» оставляет местный файл для совпадающего ID.",
                            "В ZIP находится PDF с другой контрольной суммой. «Обновить из архива» загружает его вместо местного файла.", doc.title)
                    }
                    val differences =
                        JList(conflicts.map { "${it.category}: ${it.title}" }.toTypedArray())
                    val detail =
                        JTextArea(10, 70).apply {
                            isEditable = false
                            lineWrap = true
                            wrapStyleWord = true
                        }
                    differences.addListSelectionListener {
                        val diff = conflicts.getOrNull(differences.selectedIndex)
                        detail.text =
                            diff?.let { "НА КОМПЬЮТЕРЕ:\n${it.before}\n\nВ АРХИВЕ:\n${it.after}" }
                                ?: ""
                        detail.caretPosition = 0
                    }
                    val content =
                        JPanel(BorderLayout()).apply {
                            add(
                                JPanel(BorderLayout(0, 8)).apply {
                                    add(JLabel("${conflicts.size} различий; изменённых файлов PDF: ${pdfConflicts.size}"), BorderLayout.NORTH)
                                    val summary = TransferSummary.compare(local, staged.data)
                                    add(JScrollPane(JTable(object : DefaultTableModel(
                                        arrayOf("Данные", "В ZIP", "Новые ID", "Изменены", "Только здесь"), 0
                                    ) {
                                        override fun isCellEditable(row: Int, column: Int) = false
                                    }.apply {
                                        summary.forEach { addRow(arrayOf<Any>(it.category, it.incoming, it.added, it.changed, it.localOnly)) }
                                    }).apply {
                                        name = "readerka-transfer-summary"
                                        rowHeight = 24
                                        columnModel.getColumn(0).preferredWidth = 250
                                    }).apply { preferredSize = Dimension(750, 245) }, BorderLayout.CENTER)
                                },
                                BorderLayout.NORTH,
                            )
                            add(
                                JSplitPane(
                                    JSplitPane.VERTICAL_SPLIT,
                                    JScrollPane(differences),
                                    JScrollPane(detail),
                                ).apply {
                                    resizeWeight = .3
                                    dividerLocation = 90
                                    topComponent.minimumSize = Dimension(100, 55)
                                    bottomComponent.minimumSize = Dimension(100, 100)
                                },
                                BorderLayout.CENTER,
                            )
                            add(JTextArea("Новые ID добавляются при обоих вариантах объединения. «Сохранить местные» оставляет совпадающие ID; «Обновить из архива» заменяет их целиком, включая позиции чтения и связи. «Только здесь» удаляется лишь при замене библиотеки. Счётчики сравнивают ID; одинаковые теги объединяются по имени. Изменения связей и PDF показаны в списке различий.").apply {
                                isEditable = false; lineWrap = true; wrapStyleWord = true
                                background = UIManager.getColor("Panel.background")
                                border = BorderFactory.createEmptyBorder(8, 0, 0, 0)
                                rows = 4
                            }, BorderLayout.SOUTH)
                            val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
                            preferredSize = Dimension(minOf(820, available.width - 100), minOf(650, available.height - 160))
                        }
                    if (conflicts.isNotEmpty()) differences.selectedIndex = 0
                    val choices =
                        arrayOf(
                            "Сохранить местные",
                            "Обновить из архива",
                            "Заменить библиотеку",
                            "Отмена",
                        )
                    val choice =
                        JOptionPane.showOptionDialog(
                            this,
                            content,
                            "Сравнение перед импортом",
                            JOptionPane.DEFAULT_OPTION,
                            JOptionPane.PLAIN_MESSAGE,
                            null,
                            choices,
                            choices[0],
                        )
                    if (choice !in 0..2) {
                        staged.directory.deleteRecursively()
                        return@background
                    }
                    if (
                        choice == 2 &&
                            !confirm(
                                this,
                                "Заменить всю библиотеку архивом? Автоматическая резервная копия будет сохранена.",
                            )
                    ) {
                        staged.directory.deleteRecursively()
                        return@background
                    }
                    ownedWindows.filterIsInstance<JDialog>().forEach { it.dispose() }
                    closeAllTabs()
                    background(
                        "Резервная копия и импорт…",
                        { session.repository.applyImport(staged, ImportPolicy.entries[choice]) },
                    ) { backup ->
                        closeAllTabs()
                        session.reload()
                        navigate(Place(0, null, "Книги"))
                        JOptionPane.showMessageDialog(
                            this,
                            "Импорт завершён. Резервная копия:\n${backup.path}",
                        )
                    }
                } catch (e: Exception) {
                    staged.directory.deleteRecursively()
                    throw e
                }
            }
        }
    }

    fun exportNotes(format: String, ids: Set<String>? = null) {
        safe {
            flushEditors()
            session.saveAll()
            val file =
                chooseFile(
                    true,
                    if (format == "vault") "zip" else format,
                    "Readerka.${if(format=="vault")"zip"else format}",
                ) ?: return@safe
            val data =
                session.data.let {
                    if (ids == null) it
                    else
                        it.copy(
                            entries = it.entries.filter { e -> e.id in ids },
                            noteVersions = it.noteVersions.filter { v -> v.entryId in ids },
                        )
                }
            background(
                "Экспорт…",
                {
                    when (format) {
                        "md" -> file.writeText(NotesExporter.markdown(data))
                        "csv" -> file.writeText(NotesExporter.csv(data))
                        else ->
                            file.outputStream().use {
                                VaultExporter()
                                    .write(
                                        it,
                                        data,
                                        true,
                                        { d -> session.repository.pdf(d).inputStream() },
                                    )
                            }
                    }
                    file
                },
            ) {
                status.text = "Экспорт: ${it.path}"
            }
        }
    }

    fun openLibraryGraph(): LibraryGraphPanel {
        flushEditors()
        val panel = libraryGraph ?: LibraryGraphPanel(this).also { libraryGraph = it }
        panel.refreshFromSaved()
        openTab(panel, OpenedTab("library", WorkspaceKind.LibraryGraph), false)
        return panel
    }

    fun newResearchField(): ResearchFieldPanel? {
        val title = JOptionPane.showInputDialog(this, "Название исследовательского проекта")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val field = ResearchField(newId(), title)
        session.change { it.copy(researchFields = it.researchFields + field) }
        return openResearchField(field.id, right = true)
    }

    fun openResearchField(id: String, right: Boolean = false): ResearchFieldPanel {
        require(session.data.researchFields.any { it.id == id }) { "Поле не найдено" }
        if (right) {
            val pdf = activePdf()
            if (pdf != null && panes[1].indexOfComponent(pdf) >= 0) moveWorkspaceTab(pdf, 0, panes[0].tabCount)
        }
        val panel = researchFields.getOrPut(id) { ResearchFieldPanel(this, id) }
        openTab(panel, OpenedTab(id, WorkspaceKind.ResearchField), right)
        return panel
    }

    fun chooseResearchField(): ResearchFieldPanel? {
        val fields = session.data.researchFields
        if (fields.isEmpty()) return newResearchField()
        val labels = fields.map { "${it.title} · ${it.id.take(8)}" } + "Создать новое исследовательское поле…"
        val last = panes.flatMap { p -> listOfNotNull(p.selectedComponent as? ResearchFieldPanel) }.firstOrNull()?.id
        val chosen = JOptionPane.showInputDialog(this, "Проект", "Добавить на исследовательское поле", JOptionPane.PLAIN_MESSAGE,
            null, labels.toTypedArray(), labels[fields.indexOfFirst { it.id == last }.coerceAtLeast(0)]) ?: return null
        val index = labels.indexOf(chosen)
        return if (index == fields.size) newResearchField() else openResearchField(fields[index].id, right = true)
    }

    fun addToResearchField(ids: List<String>) {
        flushEditors()
        val field = chooseResearchField() ?: return
        field.addEntries(ids)
    }

    fun openEntryBesideField(id: String, field: ResearchFieldPanel) {
        showSplit()
        val target = 1 - panes.indexOfFirst { it.indexOfComponent(field) >= 0 }.coerceAtLeast(0)
        activePane = target
        openEntry(id)
        editors[id]?.let { panel -> if (panes[target].indexOfComponent(panel) < 0) moveWorkspaceTab(panel, target, panes[target].tabCount) }
    }

    fun openResearchSource(entry: Entry, field: ResearchFieldPanel? = null) {
        val documentId = entry.documentId ?: return
        var target = activePane
        if (field != null) { showSplit(); target = 1 - panes.indexOfFirst { it.indexOfComponent(field) >= 0 }.coerceAtLeast(0); activePane = target }
        openPdf(documentId, entry.anchor?.page)
        pdfs[documentId]?.let { panel ->
            if (field != null && panes[target].indexOfComponent(panel) < 0) moveWorkspaceTab(panel, target, panes[target].tabCount)
            entry.anchor?.let { panel.showAnchor(entry.quote, it) }
        }
    }

    fun openEntry(id: String, right: Boolean = false, block: String? = null) {
        safe {
            require(
                session.data.entries.any { it.id == id } ||
                    session.workspace.drafts.any { it.entry.id == id }
            ) {
                "Запись не найдена"
            }
            val panel = editors.getOrPut(id) { EntryPanel(this, id) }
            openTab(panel, OpenedTab(id, WorkspaceKind.Note), right)
            block?.let { target ->
                panel.editor.rich.blocks
                    .firstOrNull { it.id == target }
                    ?.let { panel.editor.jump(it.start) }
            }
        }
    }

    fun openPdf(id: String, page: Int? = null, right: Boolean = false, offset: Int? = null, recordHistory: Boolean = true) {
        safe {
            activeEditor()?.let { returnToEntry = it.id to it.editor.textPane.caretPosition }
            val panel = pdfs.getOrPut(id) { PdfPanel(this, id) }
            openTab(panel, OpenedTab(id, WorkspaceKind.Pdf), right)
            val destination = ReadingLocation(id, (page ?: panel.page).coerceIn(0, panel.pageCount - 1), offset ?: if (page == null) panel.currentOffset else 0)
            if (recordHistory) session.setWorkspace(
                session.workspace.copy(
                    history =
                        session.workspace.history.visit(destination)
                )
            )
            if (page != null || offset != null) panel.go(destination.page, destination.offset, updateHistory = false)
            SwingUtilities.invokeLater { panel.focusPage() }
        }
    }

    private fun openTab(panel: Component, metadata: OpenedTab, right: Boolean) {
        if (right) showSplit()
        val target = if (right) 1 else activePane
        val existing = panes.indexOfFirst { it.indexOfComponent(panel) >= 0 }
        if (existing >= 0) {
            if (right && existing != target) {
                panes[existing].remove(panel)
                addTab(target, panel, tabs[panel] ?: metadata)
            } else panes[existing].selectedComponent = panel
        } else addTab(target, panel, metadata)
        activePane = if (existing >= 0 && !right) existing else target
        captureWorkspace()
    }

    private fun title(meta: OpenedTab) =
        if (meta.kind == WorkspaceKind.LibraryGraph) "Граф библиотеки"
        else if (meta.kind == WorkspaceKind.ResearchField) session.data.researchFields.firstOrNull { it.id == meta.id }?.title ?: "Исследовательское поле"
        else if (meta.kind == WorkspaceKind.Note)
            session.workspace.drafts.firstOrNull { it.entry.id == meta.id }?.entry?.title ?: session.data.entries.firstOrNull { it.id == meta.id }?.title ?: "Запись"
        else session.data.documents.firstOrNull { it.id == meta.id }?.title ?: "PDF"

    private fun addTab(pane: Int, panel: Component, meta: OpenedTab) {
        tabs[panel] = meta
        panes[pane].addTab(title(meta), panel)
        val i = panes[pane].indexOfComponent(panel)
        panes[pane].setTabComponentAt(i, tabHeader(panel, panes[pane]))
        panes[pane].selectedComponent = panel
    }

    private fun tabHeader(panel: Component, pane: JTabbedPane): JPanel {
        val label = JLabel(title(tabs.getValue(panel)).take(25))
        val header = compactBar(label, iconButton("close", "Закрыть вкладку") { safe { closeTab(panel) } })
        val mouse = object : MouseAdapter() {
            private var origin: Point? = null
            override fun mousePressed(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e)) { pane.selectedComponent = panel; origin = e.point }
                if (e.isPopupTrigger) tabMenu(panel).show(e.component, e.x, e.y)
            }
            override fun mouseReleased(e: MouseEvent) {
                origin = null
                if (e.isPopupTrigger) tabMenu(panel).show(e.component, e.x, e.y)
            }
            override fun mouseDragged(e: MouseEvent) {
                if (origin?.distance(e.point)?.let { it > 5 } == true) { origin = null; pane.transferHandler.exportAsDrag(pane, SwingUtilities.convertMouseEvent(e.component, e, pane), TransferHandler.MOVE) }
            }
        }
        listOf<Component>(label, header).forEach { it.addMouseListener(mouse); it.addMouseMotionListener(mouse) }
        return header
    }

    private fun moveWorkspaceTab(panel: Component, target: Int, index: Int) {
        if (target == 1 && !splitting) showSplit()
        val source = panes.indexOfFirst { it.indexOfComponent(panel) >= 0 }
        if (source < 0) return
        val previousIndex = panes[source].indexOfComponent(panel)
        val meta = tabs.getValue(panel)
        rebuilding = true
        try {
            panes[source].remove(panel)
            val position = (index - if (source == target && previousIndex < index) 1 else 0).coerceIn(0, panes[target].tabCount)
            panes[target].insertTab(title(meta), null, panel, null, position)
            panes[target].setTabComponentAt(position, tabHeader(panel, panes[target]))
            panes[target].selectedComponent = panel
            activePane = target
        } finally { rebuilding = false }
        captureWorkspace()
    }

    private fun updateTabTitles() {
        panes.forEach { pane ->
            (0 until pane.tabCount).forEach { i ->
                val meta = tabs[pane.getComponentAt(i)]
                if (meta != null) {
                    pane.setTitleAt(i, title(meta))
                    ((pane.getTabComponentAt(i) as? JPanel)?.getComponent(0) as? JLabel)?.text =
                        title(meta).take(25)
                }
            }
        }
    }

    private fun tabMenu(panel: Component): JPopupMenu =
        JPopupMenu().apply {
            fun item(label: String, fn: () -> Unit) {
                add(JMenuItem(label).apply { addActionListener { safe(fn) } })
            }
            val meta = tabs.getValue(panel)
            val pane = panes.indexOfFirst { it.indexOfComponent(panel) >= 0 }
            item(if (meta.pinned) "Открепить" else "Закрепить") {
                meta.pinned = !meta.pinned
                updateTabTitles()
                captureWorkspace()
            }
            item("Открыть в другой области") {
                showSplit()
                panes[pane].remove(panel)
                addTab(1 - pane, panel, meta)
                activePane = 1 - pane
                collapseIfEmpty()
                captureWorkspace()
            }
            listOf(-1 to "Передвинуть влево", 1 to "Передвинуть вправо").forEach { (delta, label) ->
                item(label) {
                    val tabsPane = panes[pane]
                    val i = tabsPane.indexOfComponent(panel)
                    val j = (i + delta).coerceIn(0, tabsPane.tabCount - 1)
                    if (i != j) {
                        tabsPane.remove(panel)
                        tabsPane.insertTab(title(meta), null, panel, null, j)
                        tabsPane.setTabComponentAt(j, tabHeader(panel, tabsPane))
                        tabsPane.selectedComponent = panel
                        captureWorkspace()
                    }
                }
            }
            item("Закрыть вкладку") { closeTab(panel) }
            item("Закрыть эту область") { collapse(1 - pane) }
        }

    fun closeTab(panel: Component) {
        val meta = tabs[panel] ?: return
        if (meta.pinned && !confirm(this, "Закрыть закреплённую вкладку?")) return
        (panel as? EntryPanel)?.flush()
        (panel as? ResearchFieldPanel)?.flush()
        (panel as? LibraryGraphPanel)?.flush()
        panes.forEach { it.remove(panel) }
        tabs.remove(panel)
        when (meta.kind) {
            WorkspaceKind.Note -> editors.remove(meta.id)?.disposePanel()
            WorkspaceKind.Pdf -> pdfs.remove(meta.id)?.close()
            WorkspaceKind.ResearchField -> researchFields.remove(meta.id)?.disposePanel()
            WorkspaceKind.LibraryGraph -> { libraryGraph?.disposePanel(); libraryGraph = null }
        }
        collapseIfEmpty()
        captureWorkspace()
    }

    private fun closeTabsFor(id: String, kind: WorkspaceKind) {
        tabs
            .filterValues { it.id == id && it.kind == kind }
            .keys
            .toList()
            .forEach {
                tabs[it]?.pinned = false
                closeTab(it)
            }
    }

    private fun closeAllTabs() {
        tabs.keys.toList().forEach {
            tabs[it]?.pinned = false
            closeTab(it)
        }
    }

    fun flushEditors() {
        editors.values.forEach { it.flush() }
        researchFields.values.forEach { it.flush() }
        libraryGraph?.flush()
    }

    private fun synchronizeEditors() {
        libraryGraph?.refreshFromSaved()
        researchFields.keys.toList().forEach { id ->
            if (session.data.researchFields.none { it.id == id }) closeTabsFor(id, WorkspaceKind.ResearchField)
            else researchFields[id]?.refreshFromSaved()
        }
        pdfs.keys.toList().filter { id -> session.data.documents.none { it.id == id } }.forEach { closeTabsFor(it, WorkspaceKind.Pdf) }
        pdfs.values.forEach { it.refreshMaterials() }
        editors.keys.toList().forEach { id ->
            if (session.data.entries.none { it.id == id } && session.workspace.drafts.none { it.entry.id == id }) closeTabsFor(id, WorkspaceKind.Note)
            else editors[id]?.synchronizeRelationships()
        }
    }

    fun toggleSplit() {
        if (splitting) collapse(0) else showSplit()
    }

    private fun showSplit() {
        if (splitting && workspaceSplit.rightComponent === panes[1]) return
        val fraction = session.workspace.splitFraction.toDouble().coerceIn(.1, .9)
        val previousRebuilding = rebuilding
        rebuilding = true
        try {
            splitting = true
            workspaceSplit.rightComponent = panes[1]
            panes[1].isVisible = true
            workspaceSplit.dividerSize = 1
            workspaceSplit.setDividerLocation(fraction)
        } finally { rebuilding = previousRebuilding }
        captureWorkspace()
    }

    private fun collapseIfEmpty() {
        if (splitting && panes[1].tabCount == 0) collapse(0)
    }

    private fun installDividerCollapse() {
        (workspaceSplit.ui as? javax.swing.plaf.basic.BasicSplitPaneUI)?.divider?.addMouseListener(object : MouseAdapter() {
            override fun mouseReleased(e: MouseEvent) {
                if (!splitting) return
                val extent = if (workspaceSplit.orientation == JSplitPane.VERTICAL_SPLIT) workspaceSplit.height else workspaceSplit.width
                if (workspaceSplit.dividerLocation < 35) collapse(1)
                else if (extent - workspaceSplit.dividerLocation < 45) collapse(0)
            }
        })
    }

    fun collapse(keep: Int) {
        val retained = panes[keep].selectedComponent
        rebuilding = true
        try {
            if (keep == 1) {
                val old =
                    (0 until panes[0].tabCount)
                        .map { panes[0].getComponentAt(it) }

                old.forEach { (it as? EntryPanel)?.flush() }
                val right = (0 until panes[1].tabCount).map { panes[1].getComponentAt(it) }
                right.forEach { p ->
                    panes[1].remove(p)
                    addTab(0, p, tabs.getValue(p))
                }
            } else {
                val right = (0 until panes[1].tabCount).map { panes[1].getComponentAt(it) }
                right.forEach { p ->
                    panes[1].remove(p)
                    addTab(0, p, tabs.getValue(p))
                }
            }
            if (retained != null && panes[0].indexOfComponent(retained) >= 0)
                panes[0].selectedComponent = retained
            splitting = false
            activePane = 0
            panes[1].isVisible = false
            workspaceSplit.rightComponent = null
            workspaceSplit.dividerSize = 0
        } finally {
            rebuilding = false
        }
        captureWorkspace()
    }

    fun captureWorkspace() {
        if (rebuilding) return
        val previous = session.workspace
        val states =
            panes.map { p ->
                val tabList =
                    (0 until p.tabCount).mapNotNull { i ->
                        val m = tabs[p.getComponentAt(i)] ?: return@mapNotNull null
                        WorkspaceTab(
                            m.kind.name + ":" + m.id,
                            m.id,
                            m.kind,
                            m.pinned,
                            (p.getComponentAt(i) as? PdfPanel)?.page ?: 0,
                            if (m.kind == WorkspaceKind.Pdf)
                                (p.getComponentAt(i) as? PdfPanel)?.currentOffset
                                    ?: 0
                            else 0,
                        )
                    }
                WorkspacePane(
                    tabList,
                    tabs[p.selectedComponent]?.let { it.kind.name + ":" + it.id },
                )
            }
        val splitExtent =
            if (workspaceSplit.orientation == JSplitPane.VERTICAL_SPLIT) workspaceSplit.height
            else workspaceSplit.width
        val fraction =
            if (splitting && splitExtent > 0)
                (workspaceSplit.dividerLocation.toFloat() / splitExtent).coerceIn(.02f, .98f)
            else previous.splitFraction
        session.setWorkspace(
            previous.copy(
                panes = states,
                activePane = activePane,
                split = splitting,
                splitFraction = fraction,
                sidebarWidth = if (explorer.isVisible) baseSplit.dividerLocation.toFloat().coerceAtLeast(150f) else previous.sidebarWidth,
                sidebarVisible = baseSplit.leftComponent.isVisible,
            )
        )
    }

    private fun restoreWorkspace() {
        val saved = session.workspace
        rebuilding = true
        try {
            saved.panes.take(2).forEachIndexed { i, pane ->
                pane.tabs.forEach { t ->
                    safe {
                        val panel: Component =
                            when (t.kind) {
                                WorkspaceKind.Note -> editors.getOrPut(t.documentId) { EntryPanel(this, t.documentId) }
                                WorkspaceKind.ResearchField -> researchFields.getOrPut(t.documentId) { ResearchFieldPanel(this, t.documentId) }
                                WorkspaceKind.LibraryGraph -> libraryGraph ?: LibraryGraphPanel(this).also { libraryGraph = it }
                                WorkspaceKind.Pdf -> pdfs.getOrPut(t.documentId) { PdfPanel(this, t.documentId) }
                            }
                        addTab(i, panel, OpenedTab(t.documentId, t.kind, t.pinned))
                        if (panel is PdfPanel) panel.go(t.page, t.offset, updateHistory = false)
                    }
                }
                val selected = pane.tabs.firstOrNull { it.id == pane.selectedId }
                tabs.keys
                    .firstOrNull { tabs[it]?.id == selected?.documentId && tabs[it]?.kind == selected?.kind && panes[i].indexOfComponent(it) >= 0 }
                    ?.let {
                        if (panes[i].indexOfComponent(it) >= 0) panes[i].selectedComponent = it
                    }

            }
            baseSplit.leftComponent.isVisible = saved.sidebarVisible
            baseSplit.dividerSize = if (saved.sidebarVisible) 1 else 0
            if (saved.split) showSplit()
            else {
                splitting = false
                workspaceSplit.rightComponent = null
                workspaceSplit.dividerSize = 0
            }
            activePane = saved.activePane.coerceIn(0, 1)
        } finally {
            rebuilding = false
        }
    }

    fun toggleFullscreen() {
        fullscreen = !fullscreen
        if (fullscreen) {
            oldBounds = bounds
            dispose()
            isUndecorated = true
            extendedState = MAXIMIZED_BOTH
            isVisible = true
        } else {
            dispose()
            isUndecorated = false
            extendedState = NORMAL
            oldBounds?.let { bounds = it }
            isVisible = true
        }
    }

    fun openLink(value: String) {
        safe {
            val uri = URI(value)
            if (uri.scheme == "readerka") {
                val id = uri.path.trim('/')
                val args =
                    uri.rawQuery
                        ?.split('&')
                        ?.mapNotNull {
                            it.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] }
                        }
                        ?.toMap()
                        .orEmpty()
                when (uri.host) {
                    "entry" -> openEntry(id, block = args["block"])
                    "document" -> {
                        val entry = session.data.entries.firstOrNull { it.id == args["entry"] && it.documentId == id }
                        if (entry?.anchor != null) openResearchSource(entry)
                        else openPdf(id, (args["page"]?.toIntOrNull() ?: 1) - 1)
                    }
                    else -> error("Неизвестная ссылка")
                }
            } else {
                require(uri.scheme in listOf("https", "http", "mailto")) {
                    "Этот тип ссылок не поддерживается"
                }
                Desktop.getDesktop().browse(uri)
            }
        }
    }

    fun activeEditor() = panes[activePane].selectedComponent as? EntryPanel

    fun activePdf() = panes[activePane].selectedComponent as? PdfPanel

    private fun registerCommands() {
        fun cmd(id: String, label: String, key: String?, action: () -> Unit) {
            if (key != null)
                require(commands.values.none { it.key == key }) { "Повтор клавиши $key" }
            commands[id] = DesktopCommand(label, key, { safe(action) })
        }
        cmd("pdf", "Добавить книгу", "control O") { importPdf() }
        cmd("note", "Создать заметку", "control N") { explorer.createEntry(EntryKind.Note) }
        cmd("consp", "Создать конспект", "control shift N") { explorer.createEntry(EntryKind.Consp) }
        cmd("research", "Создать исследовательское поле", null) { newResearchField() }
        cmd("graph", "Граф библиотеки", "control shift G") { openLibraryGraph() }
        cmd("appendResearch", "Добавить на исследовательское поле", "control alt L") {
            val pdf = activePdf()
            if (pdf != null) pdf.addToResearchField()
            else activeEditor()?.let { addToResearchField(listOf(it.id)) }
                ?: addToResearchField(explorer.nodes().filter { it.item.kind == "entry" }.map { it.item.id })
        }
        cmd("save", "Сохранить запись", "control S") { activeEditor()?.save() }
        cmd("saveAll", "Сохранить всё", "control shift S") {
            flushEditors()
            session.saveAll()
        }
        cmd("import", "Импорт ZIP", "control shift O") { importLibrary() }
        cmd("export", "Экспорт ZIP", "control shift E") { exportLibrary() }
        cmd("transfer", "Синхронизация", null) { transfer() }
        cmd("md", "Экспорт Markdown", null) { exportNotes("md") }
        cmd("csv", "Экспорт CSV", null) { exportNotes("csv") }
        cmd("vault", "Экспорт Obsidian Vault", null) { exportNotes("vault") }
        cmd("close", "Закрыть вкладку", "control W") {
            panes[activePane].selectedComponent?.let { closeTab(it) }
        }
        cmd("split", "Две области", "control alt S") { toggleSplit() }
        cmd("collapse", "Закрыть вторую область", "control alt W") { collapse(0) }
        cmd("right", "Открыть выбранное справа", "control alt O") { explorer.nodes().filter { it.item.kind in listOf("entry", "pdf") }.forEach { if (it.item.kind == "pdf") openPdf(it.item.id, right = true) else openEntry(it.item.id, true) } }
        cmd("focusPane", "Перейти в другую область", "F6") {
            if (splitting) {
                activePane = 1 - activePane
                (panes[activePane].selectedComponent as? EntryPanel)
                    ?.editor
                    ?.textPane
                    ?.requestFocusInWindow() ?: panes[activePane].requestFocusInWindow()
            }
        }
        cmd("full", "Войти / выйти из полного экрана", "F11") { toggleFullscreen() }
        cmd("help", "Справка и клавиши", "F1") { help() }
        cmd("find", "Найти в тексте / книге", "control F") {
            when {
                activeEditor() != null -> activeEditor()?.find()
                activePdf() != null -> activePdf()?.search()
                else -> explorer.search.requestFocusInWindow()
            }
        }
        cmd("bold", "Жирный", "control B") {
            activeEditor()?.formattingEditor()?.style(org.readerka.data.TextStyle.Bold)
        }
        cmd("italic", "Курсив", "control I") {
            activeEditor()?.formattingEditor()?.style(org.readerka.data.TextStyle.Italic)
        }
        cmd("underline", "Подчёркивание", "control U") {
            activeEditor()?.formattingEditor()?.style(org.readerka.data.TextStyle.Underline)
        }
        cmd("link", "Вставить ссылку", "control K") { activeEditor()?.link() }
        cmd("insert", "Каталог вставки", "control shift K") { activeEditor()?.insertCatalogue() }
        cmd("undo", "Отменить", "control Z") { (panes[activePane].selectedComponent as? ResearchFieldPanel)?.undo() ?: activeEditor()?.undo() }
        cmd("redo", "Повторить", "control shift Z") { (panes[activePane].selectedComponent as? ResearchFieldPanel)?.redo() ?: activeEditor()?.redo() }
        (1..3).forEach { level ->
            cmd("h$level", "Заголовок $level", "control alt $level") {
                activeEditor()?.formattingEditor()?.paragraph(BlockStyle.Heading, level)
            }
        }
        cmd("paragraph", "Обычный абзац", "control alt 0") {
            activeEditor()?.formattingEditor()?.paragraph(BlockStyle.Paragraph)
        }
        cmd("preview", "Режим чтения записи", "control shift P") {
            activeEditor()?.togglePreview()
        }
        cmd("properties", "Свойства записи / книги", "alt ENTER") {
            activeEditor()?.let { entryProperties(it.id) }
                ?: activePdf()?.let { documentProperties(it.id) }
        }
        cmd("bookmark", "Добавить закладку", "control D") { activePdf()?.bookmark() }
        cmd("bookmarks", "Открыть закладки", "control alt B") { activePdf()?.materials(1) }
        cmd("pdfText", "Текст книги и выделение клавиатурой", "control alt R") {
            activePdf()?.pageText()
        }
        cmd("makeQuote", "Цитата из выделения книги", "control shift Q") { activePdf()?.quote() }
        cmd("appendConsp", "Добавить выделение книги в конспект", "control alt A") {
            activePdf()?.addToConsp()
        }
        cmd("quotes", "Открыть цитаты", "control alt Q") { activePdf()?.materials(0) }
        cmd("bookConsp", "Конспекты книги", "control alt C") { activePdf()?.materials(2) }
        cmd("back", "Вернуться к источнику", "alt LEFT") {
            val h = session.workspace.history.back()
            session.setWorkspace(session.workspace.copy(history = h))
            h.current?.let { openPdf(it.documentId, it.page, offset = it.offset, recordHistory = false) }
        }
        cmd("return", "Вернуться к записи", "alt UP") { returnToNote() }
        cmd("forward", "Следующий источник", "alt RIGHT") {
            val h = session.workspace.history.forward()
            session.setWorkspace(session.workspace.copy(history = h))
            h.current?.let { openPdf(it.documentId, it.page, offset = it.offset, recordHistory = false) }
        }
        cmd("tags", "Управление тегами", "control alt T") { manageTags() }
        cmd("settings", "Настройки", null) { settings() }
        cmd("folder", "Создать папку", "control shift F") { newFolder() }
        commands.forEach { (id, c) ->
            c.key?.let {
                rootPane
                    .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(DesktopPlatform.keyStroke(it), id)
                rootPane.actionMap.put(
                    id,
                    object : AbstractAction() {
                        override fun actionPerformed(e: ActionEvent) {
                            c.action()
                        }
                    },
                )
            }
        }
        // Rich editing shortcuts must win over JTextPane's built in StyledEditorKit bindings.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (busy && SwingUtilities.getWindowAncestor(e.component) === this)
                return@addKeyEventDispatcher true
            if (
                e.id != KeyEvent.KEY_PRESSED ||
                    SwingUtilities.getWindowAncestor(e.component) !== this
            )
                return@addKeyEventDispatcher false
            val c =
                commands.values.firstOrNull {
                    it.key != null &&
                        DesktopPlatform.keyStroke(it.key) == KeyStroke.getKeyStrokeForEvent(e)
                } ?: return@addKeyEventDispatcher false
            if (e.keyCode == KeyEvent.VK_Z && e.component !is JTextPane)
                return@addKeyEventDispatcher false
            c.action()
            true
        }
    }

    private fun installMenu() {
        jMenuBar =
            JMenuBar().apply {
                listOf(
                        "Файл" to
                            listOf(
                                "pdf",
                                "note",
                                "consp",
                                "research",
                                "save",
                                "saveAll",
                                "transfer",
                                "import",
                                "export",
                                "md",
                                "csv",
                                "vault",
                            ),
                        "Вид" to
                            listOf(
                                "graph",
                                "split",
                                "collapse",
                                "right",
                                "focusPane",
                                "full",
                                "preview",
                                "tags",
                                "settings",
                            ),
                        "Запись" to
                            listOf(
                                "bold",
                                "italic",
                                "underline",
                                "link",
                                "insert",
                                "appendResearch",
                                "undo",
                                "redo",
                                "h1",
                                "h2",
                                "h3",
                                "paragraph",
                                "properties",
                            ),
                        "Книга" to
                            listOf(
                                "bookmark",
                                "bookmarks",
                                "pdfText",
                                "makeQuote",
                                "appendConsp",
                                "quotes",
                                "bookConsp",
                                "find",
                                "back",
                                "forward",
                                "return",
                            ),
                        "Справка" to listOf("help"),
                    )
                    .forEach { (title, ids) ->
                        add(
                            JMenu(title).apply {
                                ids.forEach { id ->
                                    val c = commands.getValue(id)
                                    add(
                                        JMenuItem(c.title).apply {
                                            c.key?.let { accelerator = DesktopPlatform.keyStroke(it) }
                                            addActionListener { c.action() }
                                        }
                                    )
                                }
                            }
                        )
                    }
            }
    }

    private fun filterDialog() {
        val types =
            JComboBox((listOf<Any>("Все типы") + NoteType.entries).toTypedArray()).apply {
                selectedIndex = filters.type?.ordinal?.plus(1) ?: 0
            }
        val colors =
            JComboBox((listOf<Any>("Все маркеры") + Marker.entries).toTypedArray()).apply {
                selectedIndex = filters.color?.ordinal?.plus(1) ?: 0
            }
        val books =
            JComboBox(
                    (listOf("Все источники") + session.data.documents.map { it.title })
                        .toTypedArray()
                )
                .apply {
                    selectedIndex =
                        session.data.documents.indexOfFirst { it.id == filters.documentId } + 1
                }
        val after =
            JTextField(
                filters.createdAfter?.let {
                    java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(it))
                } ?: ""
            )
        val panel =
            fieldPanel(
                "Тип" to types,
                "Маркер" to colors,
                "Источник" to books,
                "Создано после (ГГГГ-ММ-ДД, пусто = без ограничения)" to after,
            )
        val choice =
            JOptionPane.showOptionDialog(
                this,
                panel,
                "Фильтры записей",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE,
                null,
                arrayOf("Применить", "Сбросить", "Отмена"),
                null,
            )
        if (choice == 0) {
            val date =
                after.text
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        java.text
                            .SimpleDateFormat("yyyy-MM-dd")
                            .apply { isLenient = false }
                            .parse(it)
                            .time
                    }
            filters =
                NoteQuery(
                    type = NoteType.entries.getOrNull(types.selectedIndex - 1),
                    color = Marker.entries.getOrNull(colors.selectedIndex - 1),
                    documentId = session.data.documents.getOrNull(books.selectedIndex - 1)?.id,
                    createdAfter = date,
                )
        } else if (choice == 1) filters = NoteQuery()
        refreshRows()
    }

    private fun manageTags() {
        val panel = JPanel(BorderLayout())
        val tags = session.data.tags.sortedBy { it.name }
        val list = JList(tags.map { it.name }.toTypedArray())
        panel.add(JScrollPane(list), BorderLayout.CENTER)
        val dialog =
            JDialog(this, "Теги", true).apply {
                add(panel)
                setSize(480, 520)
                setLocationRelativeTo(this@DesktopWindow)
            }
        panel.add(
            flow(
                button("＋") {
                    safe {
                        val name =
                            JOptionPane.showInputDialog(dialog, "Название тега")?.trim()?.takeIf {
                                it.isNotBlank()
                            } ?: return@safe
                        require(session.data.tags.none { it.name == name }) { "Такой тег уже есть" }
                        session.change { it.copy(tags = it.tags + Tag(newId(), name)) }
                        dialog.dispose()
                        manageTags()
                    }
                },
                button("Переименовать") {
                    safe {
                        val tag = tags.getOrNull(list.selectedIndex) ?: return@safe
                        val name =
                            JOptionPane.showInputDialog(dialog, "Название", tag.name)
                                ?.trim()
                                ?.takeIf { it.isNotBlank() } ?: return@safe
                        require(session.data.tags.none { it.name == name && it.id != tag.id }) {
                            "Такой тег уже есть"
                        }
                        flushEditors()
                        session.saveAll()
                        session.change { s ->
                            s.copy(
                                tags =
                                    s.tags.map { if (it.id == tag.id) it.copy(name = name) else it }
                            )
                        }
                        editors.values.forEach { it.refreshFromSaved() }
                        dialog.dispose()
                        manageTags()
                    }
                },
                button("Удалить") {
                    safe {
                        val tag = tags.getOrNull(list.selectedIndex) ?: return@safe
                        if (!confirm(dialog, "Удалить тег ${tag.name} из всех записей и книг?"))
                            return@safe
                        flushEditors()
                        session.saveAll()
                        session.change { s ->
                            s.copy(
                                tags = s.tags.filterNot { it.id == tag.id },
                                entryTags = s.entryTags.filterNot { it.tagId == tag.id },
                                documentTags = s.documentTags.filterNot { it.tagId == tag.id },
                            )
                        }
                        editors.values.forEach { it.refreshFromSaved() }
                        dialog.dispose()
                        manageTags()
                    }
                },
            ),
            BorderLayout.SOUTH,
        )
        dialog.isVisible = true
    }

    fun help() {
        val content = HelpPanel(commands, session.repository.directory.path)
        val dialog = JDialog(this, "Справка Readerka", true).apply {
            defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
            layout = BorderLayout()
            add(content, BorderLayout.CENTER)
            add(flow(button("Закрыть") { dispose() }), BorderLayout.SOUTH)
        }
        dialog.rootPane.registerKeyboardAction({ dialog.dispose() },
            DesktopPlatform.keyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW)
        dialog.rootPane.registerKeyboardAction({ content.search.requestFocusInWindow(); content.search.selectAll() },
            DesktopPlatform.keyStroke("control F"), JComponent.WHEN_IN_FOCUSED_WINDOW)
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        dialog.minimumSize = Dimension(minOf(640, available.width), minOf(440, available.height))
        dialog.setSize(minOf(1080, available.width - 40), minOf(800, available.height - 60))
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    fun transfer() {
        if (busy) return
        val dialog = JDialog(this, "Синхронизация", true)
        val local = TransferPanel(session.data,
            { dialog.dispose(); exportLibrary() },
            { dialog.dispose(); importLibrary() },
        )
        dialog.contentPane = JTabbedPane().apply {
            addTab("Через ZIP", local)
            addTab("Dropbox", DropboxPanel(this@DesktopWindow))
        }
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.rootPane.registerKeyboardAction({ dialog.dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        dialog.pack()
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        dialog.setSize(minOf(dialog.width, available.width - 60), minOf(dialog.height, available.height - 60))
        dialog.setLocationRelativeTo(this)
        dialog.isVisible = true
    }

    fun uploadDropbox() {
        if (busy) return
        if (!confirmDropbox(this, "Выгрузить полную библиотеку в Dropbox?\nКниги, записи и остальные данные будут отправлены в аккаунт ${session.repository.preference("dropbox-account").orEmpty()}.\nСуществующий Readerka-library.zip будет заменён после отдельного подтверждения.")) return
        val client = DropboxClient()
        background("Проверка копии в Dropbox…", {
            val access = client.access(DropboxAccountStore(session.repository.directory).load())
            access to client.metadata(access)
        }) { (access, previous) ->
            if (previous != null && !confirmDropbox(this,
                "Заменить облачную копию местной библиотекой?\nФайл: ${DropboxClient.PATH}\nИзменён: ${previous.modified}\nРазмер: ${previous.size} байт\nМатериалы, существующие только в облачной копии, не войдут в новый ZIP.")) return@background
            flushEditors()
            session.saveAll()
            background("Выгрузка библиотеки в Dropbox…", {
                val archive = java.nio.file.Files.createTempFile(session.repository.directory.toPath(), "dropbox-upload-", ".zip").toFile()
                try {
                    session.repository.export(archive)
                    client.upload(access, archive, previous)
                } finally { archive.delete() }
            }) {
                JOptionPane.showMessageDialog(this, "Библиотека выгружена в Dropbox: ${DropboxClient.PATH}")
            }
        }
    }

    fun downloadDropbox() {
        if (busy) return
        if (!confirmDropbox(this, "Загрузить копию библиотеки из Dropbox?\nАккаунт: ${session.repository.preference("dropbox-account").orEmpty()}\nПосле скачивания появится сравнение. Изменения применятся только после выбора режима импорта; перед применением сохранится резервная копия.")) return
        val client = DropboxClient()
        background("Загрузка библиотеки из Dropbox…", {
            val access = client.access(DropboxAccountStore(session.repository.directory).load())
            val remote = client.metadata(access) ?: error("В Dropbox пока нет копии библиотеки. Сначала выполните выгрузку.")
            val archive = java.nio.file.Files.createTempFile(session.repository.directory.toPath(), "dropbox-download-", ".zip").toFile()
            try { client.download(access, remote, archive); archive }
            catch (e: Exception) { archive.delete(); throw e }
        }) { importLibrary(it, deleteProvidedAfterStaging = true) }
    }

    private fun settings() {
        val dark = JCheckBox("Тёмная тема", UIManager.getLookAndFeel() is FlatDarkLaf)
        val vertical =
            JCheckBox(
                "Области одна над другой",
                workspaceSplit.orientation == JSplitPane.VERTICAL_SPLIT,
            )
        val currentFraction = session.workspace.splitFraction.toDouble()
        val proportions = JComboBox(arrayOf("Текущие", "50 : 50", "35 : 65", "65 : 35"))
        val compact = JCheckBox("Компактные строки проводника", tree.rowHeight == 24)
        val optimized = JCheckBox("Режим оптимизации", ChromeMotion.optimized).apply {
            name = "readerka-optimized-ui"
            toolTipText = "Отключает декоративные анимации. Применяется без перезапуска."
        }
        val motionSettings = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(optimized, BorderLayout.NORTH)
            add(JLabel("Без анимаций наведения и вкладок; меньше перерисовок.").apply {
                name = "readerka-muted"
                foreground = mutedColor()
                font = font.deriveFont(12f)
                border = BorderFactory.createEmptyBorder(0, 22, 4, 0)
            }, BorderLayout.SOUTH)
        }
        val panel =
            fieldPanel(
                "Оформление" to dark,
                "Рабочее пространство" to vertical,
                "Пропорции" to proportions,
                "Плотность" to compact,
                "Производительность" to motionSettings,
                "Перенос между устройствами" to button("Синхронизация…") {
                    SwingUtilities.getWindowAncestor(optimized)?.dispose()
                    transfer()
                },
            )
        panel.border = BorderFactory.createEmptyBorder(4, 8, 8, 8)
        if (
            JOptionPane.showConfirmDialog(this, panel, "Настройки", JOptionPane.OK_CANCEL_OPTION) ==
                JOptionPane.OK_OPTION
        ) {
            if (dark.isSelected) FlatDarkLaf.setup() else FlatLightLaf.setup()
            configureChrome()
            ChromeMotion.configure(optimized.isSelected)
            SwingUtilities.updateComponentTreeUI(this)
            applyChromeTheme(this)
            researchFields.values.forEach { it.refreshTheme() }
            libraryGraph?.refreshTheme()
            tree.background = sidebarColor()
            installDividerCollapse()
            workspaceSplit.orientation =
                if (vertical.isSelected) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
            workspaceSplit.setDividerLocation(
                when (proportions.selectedIndex) {
                    1 -> .5
                    2 -> .35
                    3 -> .65
                    else -> currentFraction
                }
            )
            table.rowHeight = if (compact.isSelected) 36 else 44
            tree.rowHeight = if (compact.isSelected) 24 else 26
            session.repository.persistPreference("dark", dark.isSelected.toString())
            session.repository.persistPreference("vertical", vertical.isSelected.toString())
            session.repository.persistPreference("compact", compact.isSelected.toString())
            session.repository.persistPreference("optimized-ui", optimized.isSelected.toString())
            captureWorkspace()
        }
    }

    fun entryProperties(id: String) {
        editors[id]?.flush()
        val draft = session.draft(id)
        val e = draft.entry
        val title = JTextField(e.title, 35)
        val tags = JTextField(draft.tags, 35)
        val kind =
            JComboBox(EntryKind.entries.toTypedArray()).apply {
                selectedItem = e.kind
                isEnabled = e.anchor == null
            }
        val type = JComboBox(NoteType.entries.toTypedArray()).apply { selectedItem = e.type }
        val color = JComboBox(Marker.entries.toTypedArray()).apply { selectedItem = e.color }
        listOf(title, tags, kind, type, color).forEach { it.maximumSize = Dimension(Int.MAX_VALUE, 32) }
        val sourceBoxes =
            session.data.documents.map { it to JCheckBox(it.title, it.id in e.sourceBooks()) }
        val sources =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                sourceBoxes.forEach { add(it.second) }
            }
        fun <T> path(value: T, items: List<T>, id: (T) -> String, parent: (T) -> String?, name: (T) -> String): String =
            generateSequence(value) { current -> items.firstOrNull { id(it) == parent(current) } }
                .toList().asReversed().joinToString(" / ", transform = name)
        val newCollections = mutableListOf<org.readerka.data.Collection>()
        val newReaderFolders = mutableListOf<ReaderFolder>()
        val collectionBoxes = session.data.collections.map { f ->
            val prefix = if (f.scope == CollectionScope.Consp) "Конспекты" else "Цитаты и заметки"
            f to JCheckBox(prefix + " / " + path(f, session.data.collections, { it.id }, { it.parentId }, { it.name }), f.id in draft.collections)
        }.toMutableList()
        val readerBoxes = session.data.readerFolders.filter { it.kind == ReaderFolderKind.Quotes }.map { f ->
            val book = session.data.documents.first { it.id == f.documentId }
            f to JCheckBox(book.title + " / " + path(f, session.data.readerFolders, { it.id }, { it.parentId }, { it.name }), f.id in draft.readerFolders)
        }.toMutableList()
        val collectionList = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val readerList = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }
        val collectionEmpty = JLabel("Папок пока нет. Нажмите «Создать папку».")
        val readerEmpty = JLabel()
        collectionBoxes.forEach { collectionList.add(it.second) }
        readerBoxes.forEach { readerList.add(it.second) }
        collectionList.add(collectionEmpty)
        readerList.add(readerEmpty)
        fun folderSelector(description: String, list: JPanel, boxes: () -> List<JCheckBox>, create: JButton) = JPanel(BorderLayout(0, 8)).apply {
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
            add(JTextArea(description).apply {
                isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            }, BorderLayout.NORTH)
            add(JScrollPane(list), BorderLayout.CENTER)
            add(flow(create, button("Снять все назначения") { boxes().filter { it.isEnabled && it.isSelected }.forEach { it.doClick() } }), BorderLayout.SOUTH)
        }
        val placementSummary = JLabel()
        fun sourceBook() = sourceBoxes.filter { it.second.isSelected }.map { it.first.id }.singleOrNull()
        lateinit var createReaderFolder: JButton
        fun updateFolders() {
            val selectedKind = kind.selectedItem as EntryKind
            val book = sourceBook()
            collectionBoxes.forEach { (f, box) -> box.isEnabled = f.scope == selectedKind.collectionScope() }
            collectionEmpty.isVisible = collectionBoxes.none { it.second.isEnabled }
            readerBoxes.forEach { (f, box) -> box.isEnabled = selectedKind == EntryKind.Quote && f.documentId == book; box.isVisible = box.isEnabled }
            createReaderFolder.isEnabled = selectedKind == EntryKind.Quote && book != null
            readerEmpty.isVisible = readerBoxes.none { it.second.isEnabled }
            readerEmpty.text = if (selectedKind == EntryKind.Quote && book != null) "Папок пока нет. Нажмите «Создать папку»."
                else "Папки PDF доступны для цитаты с одной книгой-источником"
            val global = collectionBoxes.count { it.second.isEnabled && it.second.isSelected }
            val local = readerBoxes.count { it.second.isEnabled && it.second.isSelected }
            placementSummary.text = "Папки: каталог — $global; внутри PDF — $local"
            listOf(collectionList, readerList).forEach { it.revalidate(); it.repaint() }
        }
        fun folderDetails(owner: Component, parents: List<Place>): Pair<String, String?>? {
            val name = JTextField(30).apply { accessibleContext.accessibleName = "Название папки" }
            val parent = JComboBox((listOf(Place(-1, null, "Корень")) + parents).toTypedArray()).apply {
                accessibleContext.accessibleName = "Родительская папка"
            }
            val fields = fieldPanel("Название папки" to name, "Родительская папка" to parent)
            while (JOptionPane.showConfirmDialog(owner, fields, "Создать папку", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                val title = name.text.trim()
                if (title.isNotEmpty()) return title to (parent.selectedItem as Place).id
                JOptionPane.showMessageDialog(owner, "Введите название папки", "Создать папку", JOptionPane.WARNING_MESSAGE)
                name.requestFocusInWindow()
            }
            return null
        }
        val createCollectionFolder = button("Создать папку") {
            val scope = (kind.selectedItem as EntryKind).collectionScope()
            val folders = collectionBoxes.map { it.first }.filter { it.scope == scope }
            val details = folderDetails(collectionList, folders.map {
                Place(-1, it.id, path(it, folders, { it.id }, { it.parentId }, { it.name }))
            })
            if (details != null) {
                val folder = org.readerka.data.Collection(newId(), details.first, details.second, scope)
                newCollections += folder
                val prefix = if (scope == CollectionScope.Consp) "Конспекты" else "Цитаты и заметки"
                val box = JCheckBox(prefix + " / " + path(folder, folders + folder, { it.id }, { it.parentId }, { it.name }), true)
                box.addActionListener { updateFolders() }
                collectionBoxes += folder to box
                collectionList.add(box, collectionList.componentCount - 1)
                updateFolders()
                SwingUtilities.invokeLater { collectionList.scrollRectToVisible(box.bounds) }
            }
        }.apply { accessibleContext.accessibleName = "Создать папку каталога" }
        createReaderFolder = button("Создать папку") {
            val book = sourceBook()
            if (book != null && kind.selectedItem == EntryKind.Quote) {
                val folders = readerBoxes.map { it.first }.filter { it.documentId == book }
                val details = folderDetails(readerList, folders.map {
                    Place(-1, it.id, path(it, folders, { it.id }, { it.parentId }, { it.name }))
                })
                if (details != null) {
                    val folder = ReaderFolder(newId(), book, ReaderFolderKind.Quotes, details.first, details.second)
                    newReaderFolders += folder
                    val bookTitle = session.data.documents.first { it.id == book }.title
                    val box = JCheckBox(bookTitle + " / " + path(folder, folders + folder, { it.id }, { it.parentId }, { it.name }), true)
                    box.addActionListener { updateFolders() }
                    readerBoxes += folder to box
                    readerList.add(box, readerList.componentCount - 1)
                    updateFolders()
                    SwingUtilities.invokeLater { readerList.scrollRectToVisible(box.bounds) }
                }
            }
        }.apply { accessibleContext.accessibleName = "Создать папку PDF" }
        collectionBoxes.forEach { it.second.addActionListener { updateFolders() } }
        readerBoxes.forEach { it.second.addActionListener { updateFolders() } }
        kind.addActionListener { updateFolders() }
        sourceBoxes.forEach { it.second.addActionListener { updateFolders() } }
        updateFolders()
        val sourceInfo = JTextArea(buildString {
            e.documentId?.let { bookId ->
                session.data.documents.firstOrNull { it.id == bookId }?.let { book ->
                    append(book.title)
                    if (book.authors.isNotBlank()) append(" · ${book.authors}")
                }
            }
            e.anchor?.let { append(" · страница ${it.page + 1}") }
            if (isEmpty()) append("Без привязки к странице PDF")
        }, 2, 35).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, 54)
        }
        val tabs =
            JTabbedPane().apply {
                addTab(
                    "Основное",
                    fieldPanel(
                        "Название" to title,
                        "Категория" to kind,
                        "Тип" to type,
                        "Маркер" to color,
                        "Теги через запятую" to tags,
                        "Источник" to sourceInfo,
                        "Размещение" to placementSummary,
                    ),
                )
                addTab("Папки каталога", folderSelector(
                    "Отметьте все папки, в которых должна находиться запись. Если ничего не выбрано, запись находится в корне каталога.",
                    collectionList, { collectionBoxes.map { it.second } }, createCollectionFolder))
                if (e.kind == EntryKind.Quote || e.documentId != null) addTab("Папки PDF", folderSelector(
                    "Цитату можно включить в несколько папок исходного PDF. Эти назначения независимы от папок каталога. Без отметок цитата находится в корне материалов книги.",
                    readerList, { readerBoxes.map { it.second } }, createReaderFolder))
                if (e.quote.isNotBlank()) addTab("Текст цитаты", JPanel(BorderLayout(0, 8)).apply {
                    border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
                    add(JScrollPane(JTextArea(e.quote).apply {
                        isEditable = false; lineWrap = true; wrapStyleWord = true
                        accessibleContext.accessibleName = "Полный текст цитаты"
                    }), BorderLayout.CENTER)
                    e.anchor?.let { anchor -> e.documentId?.let { book ->
                        add(button("Открыть PDF · страница ${anchor.page + 1}") { openPdf(book, anchor.page) }, BorderLayout.SOUTH)
                    } }
                })
                addTab("Книги-источники", JScrollPane(sources))
                preferredSize = Dimension(680, 500)
            }
        if (
            JOptionPane.showConfirmDialog(
                this,
                tabs,
                "Свойства записи",
                JOptionPane.OK_CANCEL_OPTION,
            ) != JOptionPane.OK_OPTION
        )
            return
        val k = kind.selectedItem as EntryKind
        val books = sourceBoxes.filter { it.second.isSelected }.map { it.first.id }
        require(k == EntryKind.Consp || books.size <= 1) {
            "Для заметки выберите одну книгу; несколько источников доступны конспекту"
        }
        require(e.anchor == null || books.singleOrNull() == e.documentId) {
            "Цитата сохраняет привязку к исходному PDF"
        }
        val next =
            draft.copy(
                entry =
                    e.copy(
                        title = title.text,
                        kind = k,
                        type = type.selectedItem as NoteType,
                        color = color.selectedItem as Marker,
                        documentId = if (k == EntryKind.Consp) null else books.singleOrNull(),
                        sources = if (k == EntryKind.Consp) books else emptyList(),
                    ),
                tags = tags.text,
                collections = collectionBoxes.filter { it.second.isSelected && it.first.scope == k.collectionScope() }.map { it.first.id }.toSet(),
                readerFolders = readerBoxes.filter { it.second.isSelected && k == EntryKind.Quote && it.first.documentId == books.singleOrNull() }.map { it.first.id }.toSet(),
            )
        session.save(next, "Свойства", newCollections, newReaderFolders)
        editors[id]?.refreshFromSaved()
    }

    fun documentProperties(id: String) {
        val d = session.data.documents.first { it.id == id }
        val title = JTextField(d.title, 40)
        val authors = JTextField(d.authors)
        val year = JTextField(d.year)
        val publisher = JTextField(d.publisher)
        val doi = JTextField(d.doi)
        val url = JTextField(d.url)
        val description = JTextArea(d.description, 5, 40)
        val tags =
            JTextField(
                session.data.documentTags
                    .filter { it.documentId == id }
                    .mapNotNull { t -> session.data.tags.firstOrNull { it.id == t.tagId }?.name }
                    .joinToString(", ")
            )
        val panel =
            fieldPanel(
                "Название" to title,
                "Автор" to authors,
                "Год" to year,
                "Издательство" to publisher,
                "DOI" to doi,
                "URL" to url,
                "Описание" to JScrollPane(description),
                "Теги" to tags,
            )
        if (
            JOptionPane.showConfirmDialog(
                this,
                panel,
                "Свойства книги",
                JOptionPane.OK_CANCEL_OPTION,
            ) != JOptionPane.OK_OPTION
        )
            return
        session.change { s ->
            val allTags = s.tags.toMutableList()
            val assigned =
                tags.text
                    .split(',', ';')
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .map { name ->
                        allTags.firstOrNull { it.name == name }
                            ?: Tag(newId(), name).also { allTags += it }
                    }
            s.copy(
                documents =
                    s.documents.map {
                        if (it.id == id)
                            it.copy(
                                title = title.text,
                                authors = authors.text,
                                year = year.text,
                                publisher = publisher.text,
                                doi = doi.text,
                                url = url.text,
                                description = description.text,
                            )
                        else it
                    },
                tags = allTags,
                documentTags =
                    s.documentTags.filterNot { it.documentId == id } +
                        assigned.map { DocumentTag(id, it.id) },
            )
        }
    }
}
