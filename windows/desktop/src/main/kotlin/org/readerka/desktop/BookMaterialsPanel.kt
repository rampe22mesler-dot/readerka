package org.readerka.desktop

import java.awt.*
import java.awt.datatransfer.*
import java.awt.event.*
import javax.swing.*
import javax.swing.event.*
import javax.swing.tree.*
import org.readerka.data.*

internal data class BookMaterialNode(val value: Any, val source: String? = null) {
    val key get() = when (value) {
        is ReaderFolder -> "folder:${value.id}"
        is Entry -> "entry:${value.id}:$source"
        is Bookmark -> "bookmark:${value.id}"
        else -> "root"
    }
    val item get() = when (value) {
        is ReaderFolder -> ReaderItem(ReaderItemKind.Folder, value.id)
        is Entry -> ReaderItem(ReaderItemKind.Quote, value.id)
        is Bookmark -> ReaderItem(ReaderItemKind.Bookmark, value.id)
        else -> null
    }
    override fun toString() = when (value) {
        is ReaderFolder -> value.name
        is Entry -> value.title + (value.anchor?.let { " · стр. ${it.page + 1}" } ?: "")
        is Bookmark -> "${value.title} · стр. ${value.page + 1}"
        else -> value.toString()
    }
}

private data class ReaderDrag(val book: String, val kind: ReaderFolderKind, val items: List<ReaderOccurrence>)
private val readerDragFlavor = DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "Материалы PDF")

class BookMaterialsPanel(val app: DesktopWindow, val pdf: PdfPanel, val bookId: String,
    private val onClose: () -> Unit) : JPanel(BorderLayout()) {
    private val tabs = MotionTabbedPane()
    private val materials = (0..2).map { MaterialTree(it) }
    val selectedTab get() = tabs.selectedIndex
    fun selectTab(index: Int) { tabs.selectedIndex = index.coerceIn(0, 2) }
    fun refresh() { materials.forEach { it.refresh() } }
    fun selectBookmark(id: String) { materials[1].selectBookmark(id) }

    init {
        name = "readerka-sidebar"
        getAccessibleContext().accessibleName = "Материалы книги"
        background = sidebarColor()
        preferredSize = Dimension(320, 400)
        minimumSize = Dimension(220, 100)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = BorderFactory.createEmptyBorder(2, 8, 2, 4)
            add(JLabel("Материалы книги"), BorderLayout.CENTER)
            add(iconButton("close", "Скрыть материалы книги", onClose), BorderLayout.EAST)
        }, BorderLayout.NORTH)
        listOf("Цитаты", "Закладки", "Конспекты").forEachIndexed { index, title -> tabs.addTab(title, materials[index]) }
        add(tabs, BorderLayout.CENTER)
    }

    private inner class MaterialTree(val kind: Int) : JPanel(BorderLayout()) {
        private val caption = when (kind) { 0 -> "Цитаты книги"; 1 -> "Закладки книги"; else -> "Конспекты книги" }
        private val tree = JTree()
        private var rebuilding = false
        private val preference = "pdf-material-folders:$bookId:$kind"
        private val expanded = app.session.repository.preference(preference).orEmpty().split('\n').filter { it.isNotEmpty() }.toMutableSet()

        init {
            tree.accessibleContext.accessibleName = caption
            tree.isRootVisible = true
            tree.showsRootHandles = true
            tree.rowHeight = 26
            tree.background = sidebarColor()
            tree.putClientProperty("readerka.sidebar", true)
            tree.selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            tree.cellRenderer = object : DefaultTreeCellRenderer() {
                override fun getTreeCellRendererComponent(t: JTree, value: Any?, selected: Boolean,
                    expanded: Boolean, leaf: Boolean, row: Int, focus: Boolean): Component {
                    super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus)
                    val node = (value as? DefaultMutableTreeNode)?.userObject as? BookMaterialNode
                    icon = LineIcon(when (node?.value) { is ReaderFolder, is String -> "folder"; is Bookmark -> "bookmark"; else -> "note" })
                    return this
                }
            }
            tree.addTreeExpansionListener(object : TreeExpansionListener {
                override fun treeExpanded(e: TreeExpansionEvent) = changed(e, true)
                override fun treeCollapsed(e: TreeExpansionEvent) = changed(e, false)
                private fun changed(e: TreeExpansionEvent, open: Boolean) {
                    if (rebuilding) return
                    val id = (node(e.path)?.value as? ReaderFolder)?.id ?: return
                    if (open) expanded += id else expanded -= id
                    app.session.repository.persistPreference(preference, expanded.joinToString("\n"))
                    if (open) {
                        rebuilding = true
                        try { paths().filter { path -> e.path.isDescendant(path) && tree.isExpanded(path.parentPath) &&
                            (node(path)?.value as? ReaderFolder)?.id in expanded }.forEach { tree.expandPath(it) }
                        } finally { rebuilding = false }
                    }
                }
            })
            tree.addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e) && selectedValue().let { it is Entry || it is Bookmark }) open()
                }
                override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
                override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) popup(e) }
                private fun popup(e: MouseEvent) {
                    tree.getPathForLocation(e.x, e.y)?.let { if (!tree.isPathSelected(it)) tree.selectionPath = it }
                    menu().show(tree, e.x, e.y)
                }
            })
            tree.inputMap.put(KeyStroke.getKeyStroke("ENTER"), "open")
            tree.actionMap.put("open", object : AbstractAction() {
                override fun actionPerformed(e: ActionEvent) { open() }
            })
            if (kind != 2) installDragAndDrop()
            add(compactBar(
                iconButton("collapse", "Свернуть папки материалов") {
                    for (row in tree.rowCount - 1 downTo 1) tree.collapseRow(row)
                },
                iconButton("read", "Открыть выбранный материал") { open() },
                iconButton("note-add", when (kind) {
                    0 -> "Создать цитату из выделения PDF"; 1 -> "Добавить закладку"; else -> "Добавить конспект"
                }) {
                    when (kind) {
                        0 -> pdf.quote(contextFolder())
                        1 -> pdf.bookmark(contextFolder())
                        else -> app.newEntry(EntryKind.Consp, bookId)
                    }
                },
                iconButton("folder-add", "Создать папку материалов") { newFolder() }.apply { isEnabled = kind != 2 },
                iconButton("settings", "Свойства выбранного материала") { editSelected() },
                iconButton("more", "Действия с материалом") { menu().show(tree, 20, 20) }
            ), BorderLayout.NORTH)
            add(JScrollPane(tree), BorderLayout.CENTER)
            refresh()
        }

        private fun folderKind() = if (kind == 0) ReaderFolderKind.Quotes else ReaderFolderKind.Bookmarks
        private fun node(path: TreePath?) = (path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? BookMaterialNode
        private fun nodes() = tree.selectionPaths.orEmpty().mapNotNull(::node)
        private fun selectedValue() = node(tree.selectionPath)?.value
        private fun occurrences() = nodes().mapNotNull { n -> n.item?.let { ReaderOccurrence(it, n.source) } }
        private fun contextFolder() = node(tree.selectionPath)?.let { (it.value as? ReaderFolder)?.id ?: it.source }
        private fun paths() = (tree.model.root as DefaultMutableTreeNode).preorderEnumeration().toList()
            .map { TreePath((it as DefaultMutableTreeNode).path) }

        fun refresh() {
            val selection = nodes().map { it.key }.toSet()
            val data = app.session.data
            val root = DefaultMutableTreeNode(BookMaterialNode(caption))
            val folders = data.readerFolders.filter { it.documentId == bookId && it.kind == folderKind() }
            fun append(parent: DefaultMutableTreeNode, folder: String?) {
                folders.filter { it.parentId == folder }.sortedBy { it.name.lowercase() }.forEach { f ->
                    val child = DefaultMutableTreeNode(BookMaterialNode(f, folder))
                    parent.add(child); append(child, f.id)
                }
                if (kind == 0) data.entries.filter { e -> e.documentId == bookId && e.kind == EntryKind.Quote &&
                    (if (folder == null) data.readerMemberships.none { it.entryId == e.id }
                    else ReaderMembership(folder, e.id) in data.readerMemberships)
                }.sortedWith(compareBy<Entry> { it.anchor?.page ?: Int.MAX_VALUE }.thenBy { it.title.lowercase() })
                    .forEach { parent.add(DefaultMutableTreeNode(BookMaterialNode(it, folder), false)) }
                else data.bookmarks.filter { it.documentId == bookId && it.folderId == folder }.sortedBy { it.page }
                    .forEach { parent.add(DefaultMutableTreeNode(BookMaterialNode(it, folder), false)) }
            }
            if (kind == 2) data.entries.filter { it.kind == EntryKind.Consp && bookId in it.sourceBooks() }
                .forEach { root.add(DefaultMutableTreeNode(BookMaterialNode(it), false)) }
            else append(root, null)
            rebuilding = true
            try {
                tree.model = DefaultTreeModel(root)
                tree.expandPath(TreePath(root.path))
                paths().forEach { path ->
                    if ((node(path)?.value as? ReaderFolder)?.id in expanded && tree.isExpanded(path.parentPath)) tree.expandPath(path)
                }
                tree.selectionPaths = paths().filter { node(it)?.key in selection }.toTypedArray()
            } finally { rebuilding = false }
        }

        fun selectBookmark(id: String) {
            val path = paths().firstOrNull { (node(it)?.value as? Bookmark)?.id == id } ?: return
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }

        private fun open() {
            when (val value = selectedValue()) {
                is ReaderFolder, is String -> tree.selectionPath?.let {
                    if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it)
                }
                is Entry -> app.openEntry(value.id, right = true)
                is Bookmark -> pdf.go(value.page, value.offset)
            }
        }

        private fun installDragAndDrop() {
            tree.dragEnabled = !GraphicsEnvironment.isHeadless()
            tree.dropMode = DropMode.ON_OR_INSERT
            tree.toolTipText = "Перетащите в папку; Ctrl + перетаскивание добавляет цитату в ещё одну папку"
            tree.transferHandler = object : TransferHandler() {
                override fun getSourceActions(c: JComponent) = if (occurrences().all { it.item.kind == ReaderItemKind.Quote }) COPY_OR_MOVE else MOVE
                override fun createTransferable(c: JComponent): Transferable? {
                    val items = occurrences().takeIf { it.isNotEmpty() } ?: return null
                    val drag = ReaderDrag(bookId, folderKind(), items)
                    return object : Transferable {
                        override fun getTransferDataFlavors() = if (items.all { it.item.kind == ReaderItemKind.Quote }) arrayOf(readerDragFlavor, researchEntryFlavor) else arrayOf(readerDragFlavor)
                        override fun isDataFlavorSupported(f: DataFlavor) = f in getTransferDataFlavors()
                        override fun getTransferData(f: DataFlavor): Any {
                            if (f == researchEntryFlavor && isDataFlavorSupported(f)) return items.map { it.item.id }
                            if (f != readerDragFlavor) throw UnsupportedFlavorException(f)
                            return drag
                        }
                    }
                }
                private fun result(s: TransferSupport): LibrarySnapshot? {
                    if (!s.isDrop || !s.isDataFlavorSupported(readerDragFlavor)) return null
                    val drag = s.transferable.getTransferData(readerDragFlavor) as? ReaderDrag ?: return null
                    if (drag.book != bookId || drag.kind != folderKind()) return null
                    val target = node((s.dropLocation as? JTree.DropLocation)?.path) ?: return null
                    if (target.value !is ReaderFolder && target.value !is String) return null
                    return ReaderOperations.moveOccurrences(app.session.data, bookId, folderKind(), drag.items,
                        (target.value as? ReaderFolder)?.id, s.dropAction == COPY)
                }
                override fun canImport(s: TransferSupport) = runCatching { result(s) != null }.getOrDefault(false)
                override fun importData(s: TransferSupport): Boolean {
                    val next = runCatching { result(s) }.getOrNull() ?: return false
                    val target = node((s.dropLocation as JTree.DropLocation).path)?.key
                    var saved = false
                    app.safe {
                        app.session.change { next }
                        paths().firstOrNull { node(it)?.key == target }?.let { tree.expandPath(it) }
                        saved = true
                    }
                    return saved
                }
            }
        }

        private fun newFolder() {
            if (kind == 2) return
            app.safe {
                val name = JOptionPane.showInputDialog(this, "Название папки")?.trim()?.takeIf { it.isNotEmpty() } ?: return@safe
                val parent = contextFolder()
                val folder = ReaderFolder(newId(), bookId, folderKind(), name, parent)
                app.session.change { it.copy(readerFolders = it.readerFolders + folder) }
                paths().firstOrNull { (node(it)?.value as? ReaderFolder)?.id == folder.id }?.let {
                    tree.selectionPath = it; tree.scrollPathToVisible(it)
                }
            }
        }

        private fun chooseDestination(additional: Boolean) {
            if (kind == 2) { (selectedValue() as? Entry)?.let { app.entryProperties(it.id) }; return }
            val items = occurrences().takeIf { it.isNotEmpty() } ?: return
            val data = app.session.data
            val folders = data.readerFolders.filter { it.documentId == bookId && it.kind == folderKind() }
            val choices = (listOf(Place(-1, null, "Без папки")) + folders.map { f ->
                val path = generateSequence(f) { current -> folders.firstOrNull { it.id == current.parentId } }
                    .toList().asReversed().joinToString(" / ") { it.name }
                Place(-1, f.id, "$path · ${f.id.take(8)}")
            }).filter { place -> runCatching {
                ReaderOperations.moveOccurrences(data, bookId, folderKind(), items, place.id, additional)
            }.isSuccess }
            if (choices.isEmpty()) { JOptionPane.showMessageDialog(app, "Нет доступной папки для выбранных материалов"); return }
            val chosen = JOptionPane.showInputDialog(app, "Папка", if (additional) "Добавить также в папку" else "Переместить",
                JOptionPane.PLAIN_MESSAGE, null, choices.toTypedArray(), choices[0]) as? Place ?: return
            app.session.change { ReaderOperations.moveOccurrences(it, bookId, folderKind(), items, chosen.id, additional) }
        }

        // Existing property and deletion dialogs are shared with the tree actions below.
        private fun editSelected() {
            app.safe {
                when (val v = selectedValue()) {
                    is Entry -> app.entryProperties(v.id)
                    is ReaderFolder -> {
                        val name =
                            JOptionPane.showInputDialog(app, "Название", v.name) ?: return@safe
                        app.session.change { s ->
                            s.copy(
                                readerFolders =
                                    s.readerFolders.map {
                                        if (it.id == v.id) it.copy(name = name) else it
                                    }
                            )
                        }
                    }
                    is Bookmark -> {
                        val title = JTextField(v.title)
                        val comment = JTextArea(v.comment, 4, 30)
                        val page =
                            JSpinner(
                                SpinnerNumberModel(
                                    v.page + 1,
                                    1,
                                    app.session.data.documents
                                        .first { it.id == bookId }
                                        .pageCount,
                                    1,
                                )
                            )
                        val bookmarks =
                            listOf<String?>(null) +
                                app.session.data.bookmarks
                                    .filter { it.documentId == bookId && it.id != v.id }
                                    .map { it.id }
                        val parents =
                            JComboBox(
                                    (listOf("Без родителя") +
                                            bookmarks.drop(1).map { id ->
                                                app.session.data.bookmarks
                                                    .first { it.id == id }
                                                    .title
                                            })
                                        .toTypedArray()
                                )
                                .apply {
                                    selectedIndex =
                                        bookmarks.indexOf(v.parentId).coerceAtLeast(0)
                                }
                        if (
                            JOptionPane.showConfirmDialog(
                                app,
                                fieldPanel(
                                    "Название" to title,
                                    "Комментарий" to JScrollPane(comment),
                                    "Страница" to page,
                                    "Родитель" to parents,
                                ),
                                "Закладка",
                                JOptionPane.OK_CANCEL_OPTION,
                            ) == JOptionPane.OK_OPTION
                        )
                            app.session.change { s ->
                                s.copy(
                                    bookmarks =
                                        s.bookmarks.map {
                                            if (it.id == v.id)
                                                it.copy(
                                                    title = title.text,
                                                    comment = comment.text,
                                                    page = (page.value as Int) - 1,
                                                    parentId = bookmarks[parents.selectedIndex],
                                                )
                                            else it
                                        }
                                )
                            }
                    }
                }
                refresh()
            }
        }

        private fun menu() = JPopupMenu().apply {
            fun item(label: String, action: () -> Unit) { add(JMenuItem(label).apply { addActionListener { app.safe(action) } }) }
            item("Открыть источник") {
                when (val value = selectedValue()) {
                    is Entry -> value.anchor?.let { pdf.go(it.page, ((it.rects.firstOrNull()?.top ?: 0f) * 10000).toInt()) }
                    is Bookmark -> pdf.go(value.page, value.offset)
                }
            }
            item("Изменить") { editSelected() }
            item("Добавить на исследовательское поле") {
                val ids = nodes().mapNotNull { (it.value as? Entry)?.id }
                if (ids.isNotEmpty()) app.addToResearchField(ids)
            }
            item("Переместить в папку") { chooseDestination(false) }
            if (kind == 0) {
                item("Добавить также в папку") { chooseDestination(true) }
                item("Убрать из текущей папки") {
                    val items = occurrences().filter { it.item.kind == ReaderItemKind.Quote && it.source != null }
                    if (items.isNotEmpty()) app.session.change { ReaderOperations.moveOccurrences(it, bookId, folderKind(), items, null) }
                }
            }
            item("Удалить") {
                val v = selectedValue() ?: return@item
                if (!confirm(app, "Удалить выбранное?")) return@item
                when (v) {
                    is Entry -> app.session.deleteEntry(v.id)
                    is Bookmark ->
                        app.session.change { s ->
                            s.copy(
                                bookmarks =
                                    s.bookmarks
                                        .filterNot { it.id == v.id }
                                        .map {
                                            if (it.parentId == v.id) it.copy(parentId = null)
                                            else it
                                        }
                            )
                        }
                    is ReaderFolder ->
                        app.session.change { s ->
                            s.copy(
                                readerFolders =
                                    s.readerFolders
                                        .filterNot { it.id == v.id }
                                        .map {
                                            if (it.parentId == v.id) it.copy(parentId = null)
                                            else it
                                        },
                                readerMemberships =
                                    s.readerMemberships.filterNot { it.folderId == v.id },
                                bookmarks =
                                    s.bookmarks.map {
                                        if (it.folderId == v.id) it.copy(folderId = null)
                                        else it
                                    },
                            )
                        }
                }
            }
        }
    }
}
