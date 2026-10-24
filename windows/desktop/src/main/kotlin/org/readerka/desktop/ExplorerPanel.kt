package org.readerka.desktop

import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import java.util.EventObject
import javax.swing.*
import javax.swing.event.*
import javax.swing.tree.*
import org.readerka.data.*
import org.readerka.data.Collection

data class ExplorerNode(val item: ExplorerItem, val title: String) {
    val key get() = "${item.category}:${item.kind}:${item.id}:${if (item.kind == "entry" || item.kind == "pdf") item.parent.orEmpty() else ""}"
    override fun toString() = title
}

class ExplorerPanel(private val app: DesktopWindow) : JPanel(BorderLayout()) {
    val tree = JTree()
    val search = JTextField()
    private var rebuilding = false
    private val expanded = readKeys("expanded", setOf("0:section::", "1:section::", "2:section::", "3:section::")).toMutableSet()
    private var selection = readKeys("selection", emptySet())
    private var descending = app.session.repository.preference("explorer-sort") == "descending"
    private var dragged = emptyList<ExplorerItem>()
    private val flavor = DataFlavor("application/x-readerka-explorer;class=java.lang.String")

    init {
        name = "readerka-sidebar"
        background = sidebarColor()
        minimumSize = Dimension(150, 100)
        preferredSize = Dimension(298, 600)
        tree.name = "explorer"
        tree.putClientProperty("readerka.sidebar", true)
        tree.background = background
        tree.rowHeight = if (app.session.repository.preference("compact") == "true") 24 else 26
        tree.isRootVisible = false
        tree.showsRootHandles = true
        tree.toggleClickCount = 2
        tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
        tree.accessibleContext.accessibleName = "Проводник библиотеки"
        tree.cellRenderer = object : DefaultTreeCellRenderer() {
            override fun getTreeCellRendererComponent(t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus)
                val node = (value as? DefaultMutableTreeNode)?.userObject as? ExplorerNode
                icon = LineIcon(if (node?.item?.kind in listOf("folder", "section")) "folder" else "note")
                borderSelectionColor = selectionColor()
                backgroundSelectionColor = selectionColor()
                textSelectionColor = UIManager.getColor("Tree.selectionForeground")
                textNonSelectionColor = UIManager.getColor("Tree.foreground")
                backgroundNonSelectionColor = tree.background
                font = tree.font.deriveFont(if (node?.item?.kind == "section") Font.BOLD else Font.PLAIN)
                border = BorderFactory.createEmptyBorder(0, 5, 0, 5)
                return this
            }
        }
        tree.isEditable = true
        tree.cellEditor = object : DefaultTreeCellEditor(tree, tree.cellRenderer as DefaultTreeCellRenderer) {
            override fun isCellEditable(event: EventObject?) = event == null && nodes().singleOrNull()?.item?.kind != "section"
        }
        tree.addTreeSelectionListener {
            if (!rebuilding) {
                selection = nodes().map { it.key }.toSet()
                persist("selection", selection)
                nodes().lastOrNull()?.let { app.selectExplorerPlace(it.item.category, if (it.item.kind == "folder") it.item.id else it.item.parent) }
            }
        }
        tree.addTreeExpansionListener(object : TreeExpansionListener {
            override fun treeExpanded(e: TreeExpansionEvent) = changed(e, true)
            override fun treeCollapsed(e: TreeExpansionEvent) = changed(e, false)
            private fun changed(e: TreeExpansionEvent, open: Boolean) {
                if (rebuilding || search.text.isNotBlank()) return
                val key = node(e.path)?.key ?: return
                if (open) expanded += key else expanded -= key
                persist("expanded", expanded)
            }
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (SwingUtilities.isLeftMouseButton(e) && e.clickCount == 1 && !e.isPopupTrigger && !(DesktopPlatform.isMac && e.isControlDown) && !DesktopPlatform.isShortcutDown(e) && !e.isShiftDown) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    if (e.x >= (tree.getPathBounds(path)?.x ?: 0)) node(path)?.takeIf { it.item.kind in listOf("entry", "pdf", "research") }?.let { open(it) }
                }
            }
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
            private fun popup(e: MouseEvent) {
                tree.getPathForLocation(e.x, e.y)?.let { if (!tree.isPathSelected(it)) tree.selectionPath = it }
                menu().show(tree, e.x, e.y)
            }
        })
        bind("F2") { renameSelected() }
        bind("ENTER") { nodes().firstOrNull()?.let { open(it) } }
        bind("DELETE") { deleteSelected() }
        bind("shift F10") { menu().show(tree, 20, 20) }
        bind("control M") { chooseDestination(false) }
        tree.dropMode = DropMode.ON
        tree.dragEnabled = true
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = MOVE
            override fun createTransferable(c: JComponent): Transferable? {
                dragged = selectedItems()
                if (dragged.isEmpty()) return null
                return object : Transferable {
                    override fun getTransferDataFlavors() = arrayOf(flavor)
                    override fun isDataFlavorSupported(f: DataFlavor) = f == flavor
                    override fun getTransferData(f: DataFlavor): Any { if (f != flavor) throw UnsupportedFlavorException(f); return "Readerka" }
                }
            }
            override fun canImport(s: TransferSupport): Boolean {
                if (!s.isDrop || !s.isDataFlavorSupported(flavor) || dragged.isEmpty()) return false
                val target = node((s.dropLocation as? JTree.DropLocation)?.path) ?: return false
                if (target.item.kind !in listOf("folder", "section")) return false
                return runCatching { ExplorerOperations.move(app.session.data, dragged, target.item.category, target.item.id.takeIf { target.item.kind == "folder" }); true }.getOrDefault(false)
            }
            override fun importData(s: TransferSupport): Boolean {
                if (!canImport(s)) return false
                val target = node((s.dropLocation as JTree.DropLocation).path) ?: return false
                app.safe { move(dragged, target.item.category, target.item.id.takeIf { target.item.kind == "folder" }, false) }
                return true
            }
            override fun exportDone(c: JComponent, data: Transferable?, action: Int) { dragged = emptyList() }
        }
        search.putClientProperty("JTextField.placeholderText", "Поиск в библиотеке")
        search.putClientProperty("JTextField.leadingIcon", LineIcon("search", 14))
        search.margin = Insets(6, 8, 6, 8)
        search.accessibleContext.accessibleName = "Поиск в проводнике"
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = refresh()
            override fun removeUpdate(e: DocumentEvent) = refresh()
            override fun changedUpdate(e: DocumentEvent) = refresh()
        })
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(compactBar(
                iconButton("note-add", "Создать запись в выбранной папке") { createEntry(if (context().first == 2) EntryKind.Consp else EntryKind.Note) },
                iconButton("folder-add", "Создать папку / подпапку") { createFolder() },
                iconButton("sort", "Сортировать по названию") { descending = !descending; app.session.repository.persistPreference("explorer-sort", if (descending) "descending" else "ascending"); refresh() },
                iconButton("collapse", "Свернуть все ветки") { expandAll(false) },
                iconButton("more", "Действия проводника") { menu().show(tree, 20, 20) }
            ), BorderLayout.NORTH)
            add(search, BorderLayout.SOUTH)
            border = BorderFactory.createEmptyBorder(7, 10, 12, 10)
        }
        add(header, BorderLayout.NORTH)
        add(JScrollPane(tree).apply { border = BorderFactory.createEmptyBorder(); viewport.name = "readerka-sidebar"; viewport.background = background }, BorderLayout.CENTER)
        add(JLabel("Readerka").apply {
            name = "readerka-status"
            font = font.deriveFont(12f)
            preferredSize = Dimension(100, 32)
            applyChromeTheme(this)
        }, BorderLayout.SOUTH)
    }

    private fun readKeys(name: String, defaults: Set<String>) = app.session.repository.preference("explorer-$name")?.split('\n')?.filter { it.isNotEmpty() }?.toSet() ?: defaults
    private fun persist(name: String, keys: Set<String>) = app.session.repository.persistPreference("explorer-$name", keys.joinToString("\n"))
    private fun node(path: TreePath?) = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? ExplorerNode
    fun nodes() = tree.selectionPaths.orEmpty().mapNotNull(::node)
    fun selectedItems() = nodes().map { it.item }.filter { it.kind != "section" }
    private fun bind(key: String, action: () -> Unit) {
        tree.inputMap.put(DesktopPlatform.keyStroke(key), key)
        tree.actionMap.put(key, object : AbstractAction() { override fun actionPerformed(e: ActionEvent) = app.safe(action) })
    }
    private fun open(n: ExplorerNode, right: Boolean = false) {
        when (n.item.kind) {
            "entry" -> app.openEntry(n.item.id, right)
            "research" -> app.openResearchField(n.item.id, right)
            "pdf" -> app.openPdf(n.item.id, right = right)
            else -> { val path = paths().firstOrNull { node(it)?.key == n.key }; if (path != null) { if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path) } }
        }
    }
    private fun paths(): List<TreePath> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptyList()
        return root.preorderEnumeration().toList().map { TreePath((it as DefaultMutableTreeNode).path) }
    }

    fun refresh() {
        if (rebuilding) return
        rebuilding = true
        try {
            val data = app.session.data
            val query = search.text.trim()
            val root = DefaultMutableTreeNode("Библиотека")
            for (cat in 0..2) {
                val section = DefaultMutableTreeNode(ExplorerNode(ExplorerItem(cat, "section", ""), listOf("Книги", "Заметки и цитаты", "Конспекты")[cat]))
                val folders = if (cat == 0) data.documentFolders.map { Triple(it.id, it.parentId, it.name) }
                    else data.collections.filter { it.scope == if (cat == 2) CollectionScope.Consp else CollectionScope.Notes }.map { Triple(it.id, it.parentId, it.name) }
                val folderNames = folders.filter { it.third.contains(query, true) }.map { it.first }.toSet()
                fun children(parent: DefaultMutableTreeNode, folder: String?, depth: Int = 0, matchingParent: Boolean = false) {
                    if (depth > 64) return
                    val insideMatch = matchingParent || folder in folderNames && query.isNotBlank()
                    val sorted = folders.filter { it.second == folder }.sortedBy { it.third.lowercase() }.let { if (descending) it.reversed() else it }
                    sorted.forEach { f ->
                        val child = DefaultMutableTreeNode(ExplorerNode(ExplorerItem(cat, "folder", f.first, folder), f.third))
                        children(child, f.first, depth + 1, insideMatch)
                        if (query.isBlank() || insideMatch || f.first in folderNames || child.childCount > 0) parent.add(child)
                    }
                    val documents = if (cat == 0) data.documents.filter { it.folderId == folder && (query.isBlank() || insideMatch || listOf(it.title, it.authors, it.description).any { text -> text.contains(query, true) }) }
                        .map { ExplorerNode(ExplorerItem(cat, "pdf", it.id, folder), it.title) }
                    else data.entries.filter { e ->
                        e.kind.collectionScope() == (if (cat == 2) CollectionScope.Consp else CollectionScope.Notes) &&
                            (if (folder == null) data.memberships.none { it.entryId == e.id } else data.memberships.any { it.entryId == e.id && it.collectionId == folder }) &&
                            (query.isBlank() || insideMatch || listOf(e.title, e.quote, e.richText().text, app.session.workspace.drafts.firstOrNull { it.entry.id == e.id }?.entry?.richText()?.text.orEmpty()).any { it.contains(query, true) })
                    }.map { e -> ExplorerNode(ExplorerItem(cat, "entry", e.id, folder), app.session.workspace.drafts.firstOrNull { it.entry.id == e.id }?.entry?.title ?: e.title) }
                    documents.sortedBy { it.title.lowercase() }.let { if (descending) it.reversed() else it }.forEach { parent.add(DefaultMutableTreeNode(it, false)) }
                }
                children(section, null)
                if (query.isBlank() || section.childCount > 0) root.add(section)
            }
            val research = DefaultMutableTreeNode(ExplorerNode(ExplorerItem(3, "section", ""), "Исследовательские поля"))
            data.researchFields.filter { it.title.contains(query, true) }.sortedBy { it.title.lowercase() }.forEach {
                research.add(DefaultMutableTreeNode(ExplorerNode(ExplorerItem(3, "research", it.id), it.title), false))
            }
            if (query.isBlank() || research.childCount > 0) root.add(research)
            tree.model = object : DefaultTreeModel(root) {
                override fun valueForPathChanged(path: TreePath, value: Any?) {
                    val n = node(path) ?: return
                    app.safe { rename(n, value.toString().trim()) }
                }
            }
            val all = paths()
            all.filter { search.text.isNotBlank() || node(it)?.key in expanded }.forEach { tree.expandPath(it) }
            tree.selectionPaths = all.filter { node(it)?.key in selection }.toTypedArray()
        } finally { rebuilding = false }
    }

    fun renameSelected() { if (nodes().singleOrNull()?.item?.kind in listOf("folder", "pdf", "entry", "research")) tree.startEditingAtPath(tree.selectionPath) }
    private fun rename(n: ExplorerNode, title: String) {
        require(title.isNotBlank()) { "Название не может быть пустым" }
        app.flushEditors()
        app.session.change { s -> when (n.item.kind) {
            "folder" -> if (n.item.category == 0) s.copy(documentFolders = s.documentFolders.map { if (it.id == n.item.id) it.copy(name = title) else it })
                else s.copy(collections = s.collections.map { if (it.id == n.item.id) it.copy(name = title) else it })
            "entry" -> s.copy(entries = s.entries.map { if (it.id == n.item.id) it.copy(title = title) else it })
            "research" -> s.copy(researchFields = s.researchFields.map { if (it.id == n.item.id) it.copy(title = title) else it })
            "pdf" -> s.copy(documents = s.documents.map { if (it.id == n.item.id) it.copy(title = title) else it })
            else -> s
        } }
    }
    private fun context(category: Int? = null): Pair<Int, String?> {
        val n = nodes().lastOrNull()?.item
        val cat = category ?: n?.category ?: app.place.category
        return cat to if (n?.category == cat) (if (n.kind == "folder") n.id else n.parent) else null
    }
    fun createFolder() {
        val (cat, parent) = context()
        require(cat != 3) { "Поля находятся в отдельном разделе без папок" }
        val title = JOptionPane.showInputDialog(app, "Название папки")?.trim()?.takeIf { it.isNotBlank() } ?: return
        val id = newId()
        app.safe { app.session.change { s -> if (cat == 0) s.copy(documentFolders = s.documentFolders + DocumentFolder(id, title, parent))
            else s.copy(collections = s.collections + Collection(id, title, parent, if (cat == 2) CollectionScope.Consp else CollectionScope.Notes)) } }
        reveal(ExplorerItem(cat, "folder", id, parent))
        tree.requestFocusInWindow()
    }
    fun createEntry(kind: EntryKind) {
        val category = if (kind == EntryKind.Consp) 2 else 1
        val (_, folder) = context(category)
        app.selectExplorerPlace(category, folder)
        app.newEntry(kind)
    }
    private fun reveal(item: ExplorerItem) {
        val path = paths().firstOrNull { node(it)?.item == item } ?: return
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }
    private fun move(items: List<ExplorerItem>, category: Int, folder: String?, add: Boolean) {
        app.flushEditors()
        app.session.change { ExplorerOperations.move(it, items, category, folder, add) }
        val destinations = items.map { it.copy(parent = folder) }.toSet()
        val selected = paths().filter { node(it)?.item in destinations }
        tree.selectionPaths = selected.toTypedArray()
        selected.forEach { tree.scrollPathToVisible(it) }
    }
    fun chooseDestination(add: Boolean) {
        val items = selectedItems()
        if (items.isEmpty()) return
        require(items.map { it.category }.distinct().size == 1) { "Выберите объекты одного раздела" }
        val cat = items.first().category
        val root = DefaultMutableTreeNode(Place(cat, null, "Корень"))
        val folders = if (cat == 0) app.session.data.documentFolders.map { Triple(it.id, it.parentId, it.name) }
            else app.session.data.collections.filter { it.scope == if (cat == 2) CollectionScope.Consp else CollectionScope.Notes }.map { Triple(it.id, it.parentId, it.name) }
        fun append(parent: DefaultMutableTreeNode, id: String?) {
            folders.filter { it.second == id }.sortedBy { it.third.lowercase() }.forEach { f -> val n = DefaultMutableTreeNode(Place(cat, f.first, f.third)); parent.add(n); append(n, f.first) }
        }
        append(root, null)
        val picker = JTree(root).apply { rowHeight = 26; setSelectionRow(0); accessibleContext.accessibleName = "Папка назначения" }
        var row = 0
        while (row < picker.rowCount) picker.expandRow(row++)
        val scroll = JScrollPane(picker).apply { preferredSize = Dimension(340, 380) }
        if (JOptionPane.showConfirmDialog(app, scroll, if (add) "Добавить в дополнительную папку" else "Переместить", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val destination = ((picker.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? Place) ?: return
        move(items, cat, destination.id, add)
    }
    private fun removeMemberships() {
        val items = selectedItems().filter { it.kind == "entry" && it.parent != null }
        app.flushEditors()
        app.session.change { s -> s.copy(memberships = s.memberships.filterNot { m -> items.any { it.id == m.entryId && it.parent == m.collectionId } }) }
    }
    fun deleteSelected() {
        val items = selectedItems()
        if (items.isEmpty()) return
        if (!confirm(app, "Удалить выбранное (${items.size})? Удаление поля сохраняет записи. Удаление записи из библиотеки удалит все её карточки, связи, назначения и версии. Содержимое папок будет поднято на уровень выше.")) return
        val pdfIds = items.filter { it.kind == "pdf" }.map { it.id }.toSet()
        require(app.session.data.entries.none { e -> e.sourceBooks().any { it in pdfIds } }) { "Сначала отвяжите записи и конспекты от PDF" }
        val files = app.session.data.documents.filter { it.id in pdfIds }.map { app.session.repository.pdf(it) }
        app.flushEditors()
        app.session.change { s ->
            var next = s
            // Deepest first ensures a selected child is lifted through every selected ancestor.
            items.filter { it.kind == "folder" }.sortedByDescending { item -> FolderTree.depth(item.id, if (item.category == 0) s.documentFolders.map { it.id to it.parentId } else s.collections.map { it.id to it.parentId }) }
                .forEach { next = ExplorerOperations.deleteFolder(next, it.category, it.id) }
            val entryIds = items.filter { it.kind == "entry" }.map { it.id }.toSet()
            val readerIds = next.readerFolders.filter { it.documentId in pdfIds }.map { it.id }.toSet()
            next.copy(researchFields = next.researchFields.filterNot { f -> items.any { it.kind == "research" && it.id == f.id } },
                entries = next.entries.filterNot { it.id in entryIds }, memberships = next.memberships.filterNot { it.entryId in entryIds },
                entryTags = next.entryTags.filterNot { it.entryId in entryIds }, noteVersions = next.noteVersions.filterNot { it.entryId in entryIds },
                documents = next.documents.filterNot { it.id in pdfIds }, documentTags = next.documentTags.filterNot { it.documentId in pdfIds },
                readerFolders = next.readerFolders.filterNot { it.id in readerIds }, readerMemberships = next.readerMemberships.filterNot { it.entryId in entryIds || it.folderId in readerIds },
                bookmarks = next.bookmarks.filterNot { it.documentId in pdfIds })
        }
        files.forEach { it.delete() }
    }
    private fun expandAll(open: Boolean) {
        val all = paths()
        (if (open) all else all.reversed()).forEach { if (open) tree.expandPath(it) else tree.collapsePath(it) }
    }
    fun menu() = JPopupMenu().apply {
        val selected = selectedItems()
        fun item(label: String, enabled: Boolean = true, action: () -> Unit) { add(JMenuItem(label).apply { isEnabled = enabled; addActionListener { app.safe(action) } }) }
        item("Открыть", nodes().isNotEmpty()) { nodes().forEach { open(it) } }
        item("Открыть в другой области", selected.any { it.kind != "folder" }) { nodes().filter { it.item.kind in listOf("entry", "pdf", "research") }.forEach { open(it, true) } }
        addSeparator()
        item("Создать папку / подпапку", context().first != 3) { createFolder() }
        item("Создать заметку здесь", context().first != 2) { createEntry(EntryKind.Note) }
        item("Создать конспект здесь", context().first == 2) { createEntry(EntryKind.Consp) }
        item("Создать исследовательское поле") { app.newResearchField() }
        item("Добавить на исследовательское поле", selected.any { it.kind == "entry" }) { app.addToResearchField(selected.filter { it.kind == "entry" }.map { it.id }) }
        item("Добавить книгу в выбранную папку", context().first == 0) { app.commands["pdf"]?.action?.invoke() }
        addSeparator()
        item("Переименовать   F2", selected.size == 1) { renameSelected() }
        item(DesktopPlatform.shortcutText("Переместить…   Ctrl+M"), selected.isNotEmpty() && selected.none { it.kind == "research" }) { chooseDestination(false) }
        item("Добавить в дополнительную папку…", selected.isNotEmpty() && selected.all { it.kind == "entry" }) { chooseDestination(true) }
        item("Убрать из этой папки", selected.any { it.kind == "entry" && it.parent != null }) { removeMemberships() }
        item("Свойства", selected.size == 1 && selected.first().kind in listOf("entry", "pdf")) { selected.single().let { if (it.kind == "entry") app.entryProperties(it.id) else app.documentProperties(it.id) } }
        item("Экспорт выбранных в Markdown", selected.any { it.kind == "entry" }) { app.exportNotes("md", selected.filter { it.kind == "entry" }.map { it.id }.toSet()) }
        addSeparator()
        item("Раскрыть все ветки") { expandAll(true) }
        item("Свернуть все ветки") { expandAll(false) }
        item("Удалить папки / записи из библиотеки…", selected.isNotEmpty()) { deleteSelected() }
    }
}
