package org.readerka.desktop

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLightLaf
import java.awt.*
import java.awt.event.*
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
    JButton(label).apply {
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

class DesktopWindow(val session: LibrarySession) : JFrame("Readerka 0.9.1 Desktop Preview") {
    val panes = arrayOf(JTabbedPane(), JTabbedPane())
    val tabs = mutableMapOf<Component, OpenedTab>()
    val editors = mutableMapOf<String, EntryPanel>()
    val pdfs = mutableMapOf<String, PdfPanel>()
    private val library = JPanel(BorderLayout())
    private val tree = JTree(DefaultMutableTreeNode("Библиотека"))
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
    var place = Place(0, null, "PDF")
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
    private val baseSplit =
        JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JPanel(BorderLayout()).apply {
                add(
                    flow(
                        JLabel("Проводник"),
                        button("⋮ Папка") { catalogueMenu(place).show(tree, 20, 20) },
                    ),
                    BorderLayout.NORTH,
                )
                add(JScrollPane(tree), BorderLayout.CENTER)
            },
            workspaceSplit,
        )
    val commands = linkedMapOf<String, DesktopCommand>()
    var busy = false
        private set

    init {
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        minimumSize = Dimension(920, 640)
        val available = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
        setSize(
            minOf(1320, available.width - 40).coerceAtLeast(920),
            minOf(880, available.height - 60).coerceAtLeast(640),
        )
        setLocationRelativeTo(null)
        tree.rowHeight = 36
        tree.isRootVisible = false
        table.rowHeight = 44
        table.setShowGrid(false)
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        table.columnModel.getColumn(0).preferredWidth = 330
        table.columnModel.getColumn(1).preferredWidth = 440
        val top =
            flow(
                button("☰") {
                    baseSplit.leftComponent.isVisible = !baseSplit.leftComponent.isVisible
                    baseSplit.dividerSize = if (baseSplit.leftComponent.isVisible) 8 else 0
                    captureWorkspace()
                },
                JLabel("Readerka"),
                button("＋ PDF") { importPdf() },
                button("＋ Заметка") { newEntry(EntryKind.Note) },
                button("＋ Конспект") { newEntry(EntryKind.Consp) },
                button("▥ Две области") { toggleSplit() },
                button("⛶ Полный экран") { toggleFullscreen() },
                button("? Справка") { help() },
            )
        add(top, BorderLayout.NORTH)
        add(baseSplit, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        table.rowHeight = if (session.repository.preference("compact") == "true") 36 else 44
        workspaceSplit.orientation =
            if (session.repository.preference("vertical") == "true") JSplitPane.VERTICAL_SPLIT
            else JSplitPane.HORIZONTAL_SPLIT
        baseSplit.resizeWeight = 0.0
        baseSplit.dividerLocation = session.workspace.sidebarWidth.toInt()
        baseSplit.dividerSize = 8
        workspaceSplit.resizeWeight = .5
        workspaceSplit.dividerSize = 8
        library.add(
            fieldPanel("Поиск в разделе (включая вложенные папки)" to search),
            BorderLayout.NORTH,
        )
        val body =
            JPanel(BorderLayout()).apply {
                add(
                    flow(
                        button("↑") { up() },
                        breadcrumb,
                        button("＋ Папка") { newFolder() },
                        flat,
                        JLabel("Тег:"),
                        tagFilter,
                        sort,
                        button("Фильтры") { filterDialog() },
                        button("⋮ Действия") { catalogueMenu().show(table, 20, 20) },
                    ),
                    BorderLayout.NORTH,
                )
                add(JScrollPane(table), BorderLayout.CENTER)
            }
        library.add(body, BorderLayout.CENTER)
        panes[0].addTab("Библиотека", library)
        panes.forEachIndexed { index, pane ->
            pane.addChangeListener {
                if (!rebuilding) {
                    activePane = index
                    captureWorkspace()
                }
            }
        }
        tree.addTreeSelectionListener {
            if (!rebuilding) {
                val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
                val p = node?.userObject as? Place
                p?.let { navigate(it) }
            }
        }
        table.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) openRow()
                }

                override fun mousePressed(e: MouseEvent) {
                    popup(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    popup(e)
                }

                fun popup(e: MouseEvent) {
                    if (e.isPopupTrigger) {
                        val at = table.rowAtPoint(e.point)
                        if (at >= 0 && !table.isRowSelected(at))
                            table.setRowSelectionInterval(at, at)
                        catalogueMenu().show(table, e.x, e.y)
                    }
                }
            }
        )
        tree.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    if (e.isPopupTrigger) popup(e)
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (e.isPopupTrigger) popup(e)
                }

                fun popup(e: MouseEvent) {
                    tree.getPathForLocation(e.x, e.y)?.let { tree.selectionPath = it }
                    catalogueMenu(place).show(tree, e.x, e.y)
                }
            }
        )
        tree.inputMap.put(KeyStroke.getKeyStroke("shift F10"), "folder-menu")
        tree.actionMap.put(
            "folder-menu",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    catalogueMenu(place).show(tree, 20, 20)
                }
            },
        )
        table.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
        table.actionMap.put(
            "open",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    openRow()
                }
            },
        )
        table.inputMap.put(KeyStroke.getKeyStroke("shift F10"), "menu")
        table.actionMap.put(
            "menu",
            object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) {
                    catalogueMenu().show(table, 20, 20)
                }
            },
        )
        val listener =
            object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = refreshRows()

                override fun removeUpdate(e: DocumentEvent) = refreshRows()

                override fun changedUpdate(e: DocumentEvent) = refreshRows()
            }
        search.document.addDocumentListener(listener)
        tagFilter.document.addDocumentListener(listener)
        flat.addActionListener { refreshRows() }
        sort.addActionListener { refreshRows() }
        workspaceSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY) {
            if (splitting && !rebuilding) {
                captureWorkspace()
            }
        }
        (workspaceSplit.ui as? javax.swing.plaf.basic.BasicSplitPaneUI)
            ?.divider
            ?.addMouseListener(
                object : MouseAdapter() {
                    override fun mouseReleased(e: MouseEvent) {
                        if (splitting) {
                            if (workspaceSplit.dividerLocation < 35) collapse(1)
                            else if (workspaceSplit.width - workspaceSplit.dividerLocation < 45)
                                collapse(0)
                        }
                    }
                }
            )
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
        session.onChange = {
            if (SwingUtilities.isEventDispatchThread()) {
                refresh()
                updateTabTitles()
            } else
                SwingUtilities.invokeLater {
                    refresh()
                    updateTabTitles()
                }
        }
        val savedCategory =
            session.repository.preference("category")?.toIntOrNull()?.coerceIn(0, 2) ?: 0
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
        if (busy) return
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
                    if (!busy) status.text = "Готово"
                }
            }
            .execute()
    }

    private fun selected() = table.selectedRows.toList().mapNotNull { rows.getOrNull(it) }

    fun navigate(p: Place) {
        place = p
        session.repository.persistPreference("category", p.category.toString())
        session.repository.persistPreference("folder", p.id.orEmpty())
        search.text = ""
        flat.isSelected = false
        panes[0].selectedComponent = library
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
            0 -> "PDF"
            1 -> "Заметки и цитаты"
            else -> "Конспекты"
        }

    private fun folderName(id: String) =
        session.data.collections.firstOrNull { it.id == id }?.name
            ?: session.data.documentFolders.firstOrNull { it.id == id }?.name
            ?: "Папка"

    private fun refresh() {
        val expanded =
            (0 until tree.rowCount)
                .mapNotNull {
                    tree.getPathForRow(it)?.takeIf(tree::isExpanded)?.lastPathComponent
                        as? DefaultMutableTreeNode
                }
                .mapNotNull { (it.userObject as? Place)?.id }
                .toSet()
        rebuilding = true
        try {
            val root = DefaultMutableTreeNode("Библиотека")
            (0..2).forEach { cat ->
                val node = DefaultMutableTreeNode(Place(cat, null, categoryName(cat)))
                root.add(node)
                val folders =
                    if (cat == 0)
                        session.data.documentFolders.map { Triple(it.id, it.parentId, it.name) }
                    else
                        session.data.collections
                            .filter {
                                it.scope ==
                                    if (cat == 2) CollectionScope.Consp else CollectionScope.Notes
                            }
                            .map { Triple(it.id, it.parentId, it.name) }
                fun children(parent: DefaultMutableTreeNode, id: String?, depth: Int = 0) {
                    if (depth > 64) return
                    folders
                        .filter { it.second == id }
                        .sortedBy { it.third.lowercase() }
                        .forEach { f ->
                            val child = DefaultMutableTreeNode(Place(cat, f.first, "▸ ${f.third}"))
                            parent.add(child)
                            children(child, f.first, depth + 1)
                        }
                }
                children(node, null)
            }
            tree.model = DefaultTreeModel(root)
            var i = 0
            while (i < tree.rowCount) {
                val p = tree.getPathForRow(i)
                val item = (p?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Place
                if (item?.id == null || item.id in expanded) tree.expandPath(p)
                i++
            }
        } finally {
            rebuilding = false
        }
        refreshRows()
    }

    private fun refreshRows() {
        val s = session.data
        val query = search.text.trim()
        val result = mutableListOf<CatalogueRow>()
        if (!flat.isSelected) {
            if (place.category == 0)
                s.documentFolders
                    .filter { it.parentId == place.id }
                    .forEach {
                        result += CatalogueRow("folder", it.id, "▸ ${it.name}", "Папка PDF")
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
            "${s.documents.size} PDF · ${s.entries.count{it.kind==EntryKind.Consp}} конспектов · ${s.entries.count{it.kind!=EntryKind.Consp}} заметок и цитат · ${rows.size} в списке"
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

    fun newFolder() {
        safe {
            val name =
                JOptionPane.showInputDialog(this, "Название папки")?.trim()?.takeIf {
                    it.isNotBlank()
                } ?: return@safe
            session.change { s ->
                if (place.category == 0)
                    s.copy(
                        documentFolders =
                            s.documentFolders + DocumentFolder(newId(), name, place.id)
                    )
                else
                    s.copy(
                        collections =
                            s.collections +
                                Collection(
                                    newId(),
                                    name,
                                    place.id,
                                    if (place.category == 2) CollectionScope.Consp
                                    else CollectionScope.Notes,
                                )
                    )
            }
        }
    }

    private fun catalogueMenu(context: Place? = null) =
        JPopupMenu().apply {
            val targets =
                if (context?.id != null)
                    listOf(CatalogueRow("folder", context.id, folderName(context.id), ""))
                else selected()
            fun item(label: String, fn: () -> Unit) {
                add(JMenuItem(label).apply { addActionListener { safe(fn) } })
            }
            item("Открыть") { openRow() }
            item("Открыть справа") { openRow(true) }
            item("Создать папку здесь") { newFolder() }
            item("Переместить / Добавить в папку") { moveSelected(targets) }
            item("Свойства / Переименовать") {
                targets.firstOrNull()?.let { r ->
                    when (r.kind) {
                        "entry" -> entryProperties(r.id)
                        "pdf" -> documentProperties(r.id)
                        else -> {
                            val name =
                                JOptionPane.showInputDialog(
                                    this@DesktopWindow,
                                    "Название",
                                    folderName(r.id),
                                ) ?: return@item
                            session.change { s ->
                                if (place.category == 0)
                                    s.copy(
                                        documentFolders =
                                            s.documentFolders.map {
                                                if (it.id == r.id) it.copy(name = name) else it
                                            }
                                    )
                                else
                                    s.copy(
                                        collections =
                                            s.collections.map {
                                                if (it.id == r.id) it.copy(name = name) else it
                                            }
                                    )
                            }
                        }
                    }
                }
            }
            item("Экспорт выбранных в Markdown") {
                exportNotes("md", targets.filter { it.kind == "entry" }.map { it.id }.toSet())
            }
            item("Удалить") { deleteSelected(targets) }
        }

    private fun moveSelected(targets: List<CatalogueRow> = selected()) {
        if (targets.isEmpty()) return
        val folders =
            listOf(Place(place.category, null, "Корень")) +
                (if (place.category == 0)
                    session.data.documentFolders.map { Place(0, it.id, it.name) }
                else
                    session.data.collections
                        .filter {
                            it.scope ==
                                if (place.category == 2) CollectionScope.Consp
                                else CollectionScope.Notes
                        }
                        .map { Place(place.category, it.id, it.name) })
        val p =
            JOptionPane.showInputDialog(
                this,
                "Куда переместить? Для записи доступны несколько папок в свойствах.",
                "Переместить",
                JOptionPane.PLAIN_MESSAGE,
                null,
                folders.toTypedArray(),
                folders[0],
            ) as? Place ?: return
        session.change { s ->
            var next = s
            targets.forEach { r ->
                next =
                    when (r.kind) {
                        "folder" ->
                            if (place.category == 0)
                                next.copy(
                                    documentFolders =
                                        next.documentFolders.map {
                                            if (it.id == r.id) it.copy(parentId = p.id) else it
                                        }
                                )
                            else
                                next.copy(
                                    collections =
                                        next.collections.map {
                                            if (it.id == r.id) it.copy(parentId = p.id) else it
                                        }
                                )
                        "pdf" ->
                            next.copy(
                                documents =
                                    next.documents.map {
                                        if (it.id == r.id) it.copy(folderId = p.id) else it
                                    }
                            )
                        else ->
                            next.copy(
                                memberships =
                                    next.memberships.filterNot { it.entryId == r.id } +
                                        listOfNotNull(p.id?.let { Membership(it, r.id) })
                            )
                    }
            }
            next
        }
    }

    private fun deleteSelected(selected: List<CatalogueRow> = selected()) {
        if (
            selected.isEmpty() ||
                !confirm(
                    this,
                    "Удалить выбранное (${selected.size})? Ссылки на удалённые записи останутся в других текстах.",
                )
        )
            return
        selected.forEach { r ->
            when (r.kind) {
                "entry" -> {
                    closeTabsFor(r.id)
                    session.deleteEntry(r.id)
                }
                "folder" -> {
                    session.change { s ->
                        if (place.category == 0)
                            s.copy(
                                documentFolders =
                                    s.documentFolders
                                        .filterNot { it.id == r.id }
                                        .map {
                                            if (it.parentId == r.id) it.copy(parentId = null)
                                            else it
                                        },
                                documents =
                                    s.documents.map {
                                        if (it.folderId == r.id) it.copy(folderId = null) else it
                                    },
                            )
                        else
                            s.copy(
                                collections =
                                    s.collections
                                        .filterNot { it.id == r.id }
                                        .map {
                                            if (it.parentId == r.id) it.copy(parentId = null)
                                            else it
                                        },
                                memberships = s.memberships.filterNot { it.collectionId == r.id },
                            )
                    }
                }
                "pdf" -> {
                    require(session.data.entries.none { r.id in it.sourceBooks() }) {
                        "Сначала отвяжите записи и конспекты от книги"
                    }
                    closeTabsFor(r.id)
                    val d = session.data.documents.first { it.id == r.id }
                    session.change { s ->
                        val folders =
                            s.readerFolders.filter { it.documentId == r.id }.map { it.id }.toSet()
                        s.copy(
                            documents = s.documents.filterNot { it.id == r.id },
                            bookmarks = s.bookmarks.filterNot { it.documentId == r.id },
                            readerFolders = s.readerFolders.filterNot { it.id in folders },
                            readerMemberships =
                                s.readerMemberships.filterNot { it.folderId in folders },
                            documentTags = s.documentTags.filterNot { it.documentId == r.id },
                        )
                    }
                    session.repository.pdf(d).delete()
                }
            }
        }
    }

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
        val file = chooseFile(false, "pdf") ?: return
        val folder = place.id.takeIf { place.category == 0 }
        background("Импорт PDF…", { session.importPdf(file, folder) }) { d ->
            refresh()
            openPdf(d.id)
        }
    }

    fun exportLibrary() {
        safe {
            flushEditors()
            session.saveAll()
            val file = chooseFile(true, "zip", "Readerka-library.zip") ?: return@safe
            background(
                "Создание ZIP для планшета…",
                {
                    session.repository.export(file)
                    file
                },
            ) {
                JOptionPane.showMessageDialog(
                    this,
                    "Архив создан: ${it.path}\nНа планшете: Настройки → Восстановить ZIP.\nДля обновления существующих записей используйте Android 0.9.1 с выбором конфликтов.",
                )
            }
        }
    }

    fun importLibrary(provided: File? = null) {
        val file = provided ?: chooseFile(false, "zip") ?: return
        safe {
            flushEditors()
            session.saveAll()
            background("Проверка ZIP и PDF…", { session.repository.stage(file) }) { staged ->
                try {
                    val conflicts = LibraryMerge.differences(session.data, staged.data)
                    val pdfConflicts = session.repository.pdfConflicts(staged)
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
                                JLabel(
                                    "${staged.data.documents.size} PDF, ${staged.data.entries.size} записей; ${conflicts.size} изменений, ${pdfConflicts.size} изменённых PDF"
                                ),
                                BorderLayout.NORTH,
                            )
                            add(
                                JSplitPane(
                                    JSplitPane.VERTICAL_SPLIT,
                                    JScrollPane(differences),
                                    JScrollPane(detail),
                                ),
                                BorderLayout.CENTER,
                            )
                            preferredSize = Dimension(800, 440)
                        }
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
                        navigate(Place(0, null, "PDF"))
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

    fun openPdf(id: String, page: Int? = null, right: Boolean = false) {
        safe {
            activeEditor()?.let { returnToEntry = it.id to it.editor.textPane.caretPosition }
            val panel = pdfs.getOrPut(id) { PdfPanel(this, id) }
            openTab(panel, OpenedTab(id, WorkspaceKind.Pdf), right)
            page?.let { panel.go(it) }
            val d = session.data.documents.first { it.id == id }
            session.setWorkspace(
                session.workspace.copy(
                    history =
                        session.workspace.history.visit(ReadingLocation(id, page ?: d.lastPage))
                )
            )
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
        if (meta.kind == WorkspaceKind.Note)
            session.data.entries.firstOrNull { it.id == meta.id }?.title ?: "Запись"
        else session.data.documents.firstOrNull { it.id == meta.id }?.title ?: "PDF"

    private fun addTab(pane: Int, panel: Component, meta: OpenedTab) {
        tabs[panel] = meta
        panes[pane].addTab(title(meta), panel)
        val i = panes[pane].indexOfComponent(panel)
        panes[pane].setTabComponentAt(
            i,
            flow(
                JLabel(title(meta).take(25)),
                button("⋮") { tabMenu(panel).show(panes[pane], 20, 30) },
                button("×") { safe { closeTab(panel) } },
            ),
        )
        panes[pane].selectedComponent = panel
    }

    private fun updateTabTitles() {
        panes.forEach { pane ->
            (0 until pane.tabCount).forEach { i ->
                val meta = tabs[pane.getComponentAt(i)]
                if (meta != null) {
                    pane.setTitleAt(i, title(meta))
                    ((pane.getTabComponentAt(i) as? JPanel)?.getComponent(0) as? JLabel)?.text =
                        (if (meta.pinned) "📌 " else "") + title(meta).take(25)
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
                    val j = (i + delta).coerceIn(if (pane == 0) 1 else 0, tabsPane.tabCount - 1)
                    if (i != j) {
                        tabsPane.remove(panel)
                        tabsPane.insertTab(title(meta), null, panel, null, j)
                        tabsPane.setTabComponentAt(
                            j,
                            flow(
                                JLabel(title(meta).take(25)),
                                button("⋮") { tabMenu(panel).show(tabsPane, 20, 30) },
                                button("×") { closeTab(panel) },
                            ),
                        )
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
        panes.forEach { it.remove(panel) }
        tabs.remove(panel)
        editors.remove(meta.id)?.disposePanel()
        pdfs.remove(meta.id)?.close()
        collapseIfEmpty()
        captureWorkspace()
    }

    private fun closeTabsFor(id: String) {
        tabs
            .filterValues { it.id == id }
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
    }

    fun toggleSplit() {
        if (splitting) collapse(0) else showSplit()
    }

    private fun showSplit() {
        splitting = true
        workspaceSplit.rightComponent = panes[1]
        panes[1].isVisible = true
        workspaceSplit.dividerSize = 8
        workspaceSplit.setDividerLocation(session.workspace.splitFraction.toDouble())
        captureWorkspace()
    }

    private fun collapseIfEmpty() {
        if (splitting && panes[1].tabCount == 0) collapse(0)
    }

    fun collapse(keep: Int) {
        val retained = panes[keep].selectedComponent
        rebuilding = true
        try {
            if (keep == 1) {
                val old =
                    (0 until panes[0].tabCount)
                        .map { panes[0].getComponentAt(it) }
                        .filter { it !== library }
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
                                session.data.documents.firstOrNull { it.id == m.id }?.lastOffset
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
                sidebarWidth = baseSplit.dividerLocation.toFloat(),
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
                            if (t.kind == WorkspaceKind.Note)
                                editors.getOrPut(t.documentId) { EntryPanel(this, t.documentId) }
                            else pdfs.getOrPut(t.documentId) { PdfPanel(this, t.documentId) }
                        addTab(i, panel, OpenedTab(t.documentId, t.kind, t.pinned))
                        if (panel is PdfPanel) panel.go(t.page, t.offset)
                    }
                }
                val selected = pane.tabs.firstOrNull { it.id == pane.selectedId }
                tabs.keys
                    .firstOrNull { tabs[it]?.id == selected?.documentId }
                    ?.let {
                        if (panes[i].indexOfComponent(it) >= 0) panes[i].selectedComponent = it
                    }
            }
            baseSplit.leftComponent.isVisible = saved.sidebarVisible
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
                    "document" -> openPdf(id, (args["page"]?.toIntOrNull() ?: 1) - 1)
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
        cmd("pdf", "Добавить PDF", "control O") { importPdf() }
        cmd("note", "Создать заметку", "control N") { newEntry(EntryKind.Note) }
        cmd("consp", "Создать конспект", "control shift N") { newEntry(EntryKind.Consp) }
        cmd("save", "Сохранить запись", "control S") { activeEditor()?.save() }
        cmd("saveAll", "Сохранить всё", "control shift S") {
            flushEditors()
            session.saveAll()
        }
        cmd("import", "Импорт ZIP", "control shift O") { importLibrary() }
        cmd("export", "Экспорт ZIP", "control shift E") { exportLibrary() }
        cmd("md", "Экспорт Markdown", null) { exportNotes("md") }
        cmd("csv", "Экспорт CSV", null) { exportNotes("csv") }
        cmd("vault", "Экспорт Obsidian Vault", null) { exportNotes("vault") }
        cmd("close", "Закрыть вкладку", "control W") {
            panes[activePane].selectedComponent?.let { closeTab(it) }
        }
        cmd("split", "Две области", "control alt S") { toggleSplit() }
        cmd("collapse", "Закрыть вторую область", "control alt W") { collapse(0) }
        cmd("right", "Открыть выбранное справа", "control alt O") { openRow(true) }
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
        cmd("find", "Найти в тексте / PDF", "control F") {
            when {
                activeEditor() != null -> activeEditor()?.find()
                activePdf() != null -> activePdf()?.search()
                else -> search.requestFocusInWindow()
            }
        }
        cmd("bold", "Жирный", "control B") {
            activeEditor()?.editor?.style(org.readerka.data.TextStyle.Bold)
        }
        cmd("italic", "Курсив", "control I") {
            activeEditor()?.editor?.style(org.readerka.data.TextStyle.Italic)
        }
        cmd("underline", "Подчёркивание", "control U") {
            activeEditor()?.editor?.style(org.readerka.data.TextStyle.Underline)
        }
        cmd("link", "Вставить ссылку", "control K") { activeEditor()?.link() }
        cmd("insert", "Каталог вставки", "control shift K") { activeEditor()?.insertCatalogue() }
        cmd("undo", "Отменить в редакторе", "control Z") { activeEditor()?.editor?.undo() }
        cmd("redo", "Повторить в редакторе", "control shift Z") { activeEditor()?.editor?.redo() }
        (1..3).forEach { level ->
            cmd("h$level", "Заголовок $level", "control alt $level") {
                activeEditor()?.editor?.paragraph(BlockStyle.Heading, level)
            }
        }
        cmd("paragraph", "Обычный абзац", "control alt 0") {
            activeEditor()?.editor?.paragraph(BlockStyle.Paragraph)
        }
        cmd("preview", "Режим чтения записи", "control shift P") {
            activeEditor()?.editor?.togglePreview()
        }
        cmd("properties", "Свойства записи / PDF", "alt ENTER") {
            activeEditor()?.let { entryProperties(it.id) }
                ?: activePdf()?.let { documentProperties(it.id) }
        }
        cmd("bookmark", "Добавить закладку", "control D") { activePdf()?.bookmark() }
        cmd("bookmarks", "Открыть закладки", "control alt B") { activePdf()?.materials(1) }
        cmd("pdfText", "Текст PDF и выделение клавиатурой", "control alt R") {
            activePdf()?.pageText()
        }
        cmd("makeQuote", "Цитата из выделения PDF", "control shift Q") { activePdf()?.quote() }
        cmd("appendConsp", "Добавить выделение PDF в конспект", "control alt A") {
            activePdf()?.addToConsp()
        }
        cmd("quotes", "Открыть цитаты", "control alt Q") { activePdf()?.materials(0) }
        cmd("bookConsp", "Конспекты книги", "control alt C") { activePdf()?.materials(2) }
        cmd("back", "Вернуться к источнику", "alt LEFT") {
            val h = session.workspace.history.back()
            session.setWorkspace(session.workspace.copy(history = h))
            h.current?.let { openPdf(it.documentId, it.page) }
        }
        cmd("return", "Вернуться к записи", "alt UP") { returnToNote() }
        cmd("forward", "Следующий источник", "alt RIGHT") {
            val h = session.workspace.history.forward()
            session.setWorkspace(session.workspace.copy(history = h))
            h.current?.let { openPdf(it.documentId, it.page) }
        }
        cmd("tags", "Управление тегами", "control alt T") { manageTags() }
        cmd("settings", "Настройки", null) { settings() }
        cmd("folder", "Создать папку", "control shift F") { newFolder() }
        commands.forEach { (id, c) ->
            c.key?.let {
                rootPane
                    .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .put(KeyStroke.getKeyStroke(it), id)
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
                        KeyStroke.getKeyStroke(it.key) == KeyStroke.getKeyStrokeForEvent(e)
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
                                "save",
                                "saveAll",
                                "import",
                                "export",
                                "md",
                                "csv",
                                "vault",
                            ),
                        "Вид" to
                            listOf(
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
                                            c.key?.let { accelerator = KeyStroke.getKeyStroke(it) }
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
        val text =
            JTextArea(25, 78).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                text =
                    "Readerka для Windows 10/11 x64 · на основе Android 0.9\n\nПапки: откройте папку двойным щелчком или Enter; в корне показаны папки и записи без папок. «Все записи» включает общий список. Поиск включает вложенные папки. Конспекты используют отдельное дерево папок.\n\nРедактор: выделите текст и примените формат. Свойства содержат теги, папки и источники; конспект можно связать с несколькими книгами или оставить тематическим. Каталог вставки предлагает ссылку на выделенном слове, карточку-ссылку и копию содержимого с ссылкой на источник. Ctrl+щелчок открывает ссылку в редакторе; в режиме чтения достаточно щелчка. Оглавление и обратные ссылки доступны справа. Черновики сохраняются автоматически; Ctrl+S сохраняет запись и историю версий. Экспорт сначала сохраняет все черновики.\n\nPDF: выделение мышью или двойным щелчком на слове; видимые команды создают цитату или добавляют её в существующий конспект. Для сканов без текстового слоя выделение недоступно. Содержимое книги открывает цитаты, закладки и конспекты. Поиск работает по всем страницам. Перевод выделения открывает внешний сервис и требует интернета.\n\nОбласти: кнопка «Две области», «Открыть справа», видимое × каждой вкладки. Меню ⋮ позволяет закреплять и переставлять вкладки. Перетаскивание границы в край закрывает область; открытые вкладки сохраняются в оставшейся. Пропорции и ориентация доступны в настройках.\n\nПеренос: импортируйте ZIP планшета, настройте библиотеку, экспортируйте ZIP на ПК и импортируйте его на планшете. Формат ZIP 4 совместим с 0.9; Android 0.9.1 добавляет обновление существующих записей с просмотром конфликтов. До импорта создаётся резервная копия. «Сохранить местные» добавляет новые записи; «Обновить из архива» заменяет совпадающие ID; «Заменить библиотеку» также удаляет отсутствующие записи. Это ручной перенос, не синхронизация. Не копируйте SQLite между устройствами. Личные данные: ${session.repository.directory.path}\n\nГОРЯЧИЕ КЛАВИШИ\n" +
                        commands.values
                            .filter { it.key != null }
                            .joinToString("\n") {
                                "${it.key!!.replace("control","Ctrl").replace("shift","Shift").replace("alt","Alt")} — ${it.title}"
                            } +
                        "\n\nСтандартные: Ctrl+C/X/V/A — копировать/вырезать/вставить/выделить всё; Tab и Shift+Tab — переход; Enter — открыть; Shift+F10 — контекстное меню; Ctrl+Tab — следующая вкладка."
                caretPosition = 0
            }
        JOptionPane.showMessageDialog(
            this,
            JScrollPane(text),
            "Справка Readerka",
            JOptionPane.PLAIN_MESSAGE,
        )
    }

    private fun settings() {
        val dark = JCheckBox("Тёмная тема", UIManager.getLookAndFeel() is FlatDarkLaf)
        val vertical =
            JCheckBox(
                "Области одна над другой",
                workspaceSplit.orientation == JSplitPane.VERTICAL_SPLIT,
            )
        val proportions = JComboBox(arrayOf("50 : 50", "35 : 65", "65 : 35"))
        val compact = JCheckBox("Компактные списки (область кнопок остаётся удобной)")
        val panel =
            fieldPanel(
                "Оформление" to dark,
                "Рабочее пространство" to vertical,
                "Пропорции" to proportions,
                "Плотность" to compact,
            )
        if (
            JOptionPane.showConfirmDialog(this, panel, "Настройки", JOptionPane.OK_CANCEL_OPTION) ==
                JOptionPane.OK_OPTION
        ) {
            if (dark.isSelected) FlatDarkLaf.setup() else FlatLightLaf.setup()
            SwingUtilities.updateComponentTreeUI(this)
            workspaceSplit.orientation =
                if (vertical.isSelected) JSplitPane.VERTICAL_SPLIT else JSplitPane.HORIZONTAL_SPLIT
            workspaceSplit.setDividerLocation(
                when (proportions.selectedIndex) {
                    1 -> .35
                    2 -> .65
                    else -> .5
                }
            )
            table.rowHeight = if (compact.isSelected) 36 else 44
            session.repository.persistPreference("dark", dark.isSelected.toString())
            session.repository.persistPreference("vertical", vertical.isSelected.toString())
            session.repository.persistPreference("compact", compact.isSelected.toString())
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
        val folderBoxes =
            session.data.collections.map {
                it to JCheckBox(it.name, draft.collections.contains(it.id))
            }
        val folders =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                folderBoxes.forEach { add(it.second) }
            }
        val sourceBoxes =
            session.data.documents.map { it to JCheckBox(it.title, it.id in e.sourceBooks()) }
        val sources =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                sourceBoxes.forEach { add(it.second) }
            }
        fun scope() {
            val current = (kind.selectedItem as EntryKind).collectionScope()
            folderBoxes.forEach { (f, b) -> b.isVisible = f.scope == current }
            folders.revalidate()
        }
        scope()
        kind.addActionListener { scope() }
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
                    ),
                )
                addTab("Папки", JScrollPane(folders))
                addTab("Книги-источники", JScrollPane(sources))
                preferredSize = Dimension(540, 440)
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
                collections =
                    folderBoxes
                        .filter { it.second.isSelected && it.first.scope == k.collectionScope() }
                        .map { it.first.id }
                        .toSet(),
            )
        session.save(next, "Свойства")
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
                "Свойства PDF",
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
