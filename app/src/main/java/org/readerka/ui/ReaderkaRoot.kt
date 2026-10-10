package org.readerka.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.readerka.MainActivity
import org.readerka.data.*

@Composable fun ReaderkaRoot(activity: Activity, vm: LibraryViewModel) {
    val data by vm.library.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val readers by vm.readers.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val workspace by vm.workspace.collectAsStateWithLifecycle()
    var commands by remember { mutableStateOf(false) }
    var sourcesOnly by remember { mutableStateOf(false) }
    var draftsOpen by remember { mutableStateOf(false) }
    var layoutOpen by remember { mutableStateOf(false) }
    var drawer by remember { mutableStateOf(false) }
    val narrowWindow = LocalConfiguration.current.screenWidthDp < 1000
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var notesFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var importFolder by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.import(uris, importFolder) }
    val zip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(vm::export) }
    val markdown = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let(vm::export) }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> uri?.let(vm::export) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::restore) }
    val export: (ExportKind, Set<String>?) -> Unit = { kind, ids ->
        if (busy == null) {
            vm.pendingExport = kind; vm.exportIds = ids
            when (kind) { ExportKind.Obsidian -> zip.launch("Readerka-Obsidian.zip"); ExportKind.Backup -> zip.launch("Readerka-backup.zip"); ExportKind.Markdown -> markdown.launch("Readerka-notes.md"); ExportKind.Csv -> csv.launch("Readerka-notes.csv") }
        }
    }
    val importPdf: (String?) -> Unit = { folder -> if (busy == null) { importFolder = folder; import.launch(arrayOf("application/pdf")) } }
    val tabs = listOf("library" to "Библиотека", "reader" to "Чтение", "notes" to "Заметки и цитаты", "settings" to "Настройки")
    val icons = listOf(Outline.Folder, Outline.Book, Outline.Note, Outline.Settings)
    val dark = when (preferences.theme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFB7A2ED), onPrimary = Color(0xFF281C40), secondaryContainer = Color(0xFF343044),
        surface = Color(0xFF222226), background = Color(0xFF222226), surfaceContainerLow = Color(0xFF1B1B1F), surfaceContainer = Color(0xFF29292F), surfaceContainerHigh = Color(0xFF313137), outlineVariant = Color(0xFF39393F), onSurface = Color(0xFFE4E3E8), onSurfaceVariant = Color(0xFFB4B1BE))
        else lightColorScheme(primary = Color(0xFF7453B4), onPrimary = Color.White, secondaryContainer = Color(0xFFECE6F7), surface = Color(0xFFFCFCFD), background = Color(0xFFFCFCFD), surfaceContainerLow = Color(0xFFF2F2F5), surfaceContainer = Color(0xFFF5F4F8), surfaceContainerHigh = Color(0xFFEAE9EF), outlineVariant = Color(0xFFDFDEE5), onSurface = Color(0xFF29272F), onSurfaceVariant = Color(0xFF696574))
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(fullscreen, dark) {
        activity.window.navigationBarColor = colors.surfaceContainerLow.toArgb()
        activity.window.statusBarColor = colors.surfaceContainerLow.toArgb()
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val focusManager = LocalFocusManager.current
    val selected = workspace.panes[workspace.activePane].selected
    val reader = readers[workspace.activePane].takeIf { selected?.kind == WorkspaceKind.Pdf }
    val shortcut: (KeyEvent) -> Boolean = { event ->
        val historyKey = event.isAltPressed && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT)
        val recognized = historyKey || (event.isCtrlPressed && event.keyCode in listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_O, KeyEvent.KEYCODE_N, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_F))
        if (recognized && event.action == KeyEvent.ACTION_DOWN) {
            when {
                historyKey -> if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) vm.historyBack() else vm.historyForward()
                event.keyCode == KeyEvent.KEYCODE_F -> if (reader != null) vm.requestReaderTool(ReaderTool.Search)
                event.keyCode == KeyEvent.KEYCODE_P -> { commands = true; sourcesOnly = false }
                event.keyCode == KeyEvent.KEYCODE_O -> { commands = true; sourcesOnly = true }
                event.keyCode == KeyEvent.KEYCODE_N -> vm.newNote(reader?.document?.id, notesFolder.takeIf { screen == "notes" })
                event.keyCode in listOf(KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_ENTER) -> vm.saveEditor(selected?.documentId?.takeIf { selected.kind == WorkspaceKind.Note })
                event.keyCode == KeyEvent.KEYCODE_B -> if (narrowWindow) drawer = !drawer else vm.layout(sidebarVisible = !workspace.sidebarVisible)
                event.keyCode == KeyEvent.KEYCODE_W -> selected?.let { vm.closeTab(workspace.activePane, it.id) }
                event.keyCode == KeyEvent.KEYCODE_TAB -> vm.nextTab(if (event.isShiftPressed) -1 else 1)
            }
        }
        if (!recognized && event.keyCode == KeyEvent.KEYCODE_TAB && !event.isCtrlPressed && !commands && !layoutOpen && !draftsOpen) {
            if (event.action == KeyEvent.ACTION_DOWN) focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
            true
        } else recognized || (screen == "reader" && !commands && !draftsOpen && !layoutOpen && reader != null && vm.readerKeyHandler?.invoke(event) == true)
    }
    val currentShortcut by rememberUpdatedState(shortcut)
    DisposableEffect(activity) { (activity as? MainActivity)?.workspaceShortcut = { currentShortcut(it) }; onDispose { (activity as? MainActivity)?.workspaceShortcut = null } }
    BackHandler(screen != "library" || fullscreen) { if (fullscreen) fullscreen = false else if (selected?.kind == WorkspaceKind.Note && workspace.editor != null) vm.closeEditor() else vm.navigate("library") }
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.scale), LocalCompact provides preferences.compact) {
      MaterialTheme(colorScheme = colors, shapes = Shapes(small = androidx.compose.foundation.shape.RoundedCornerShape(6.dp), medium = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            val windowWidth = maxWidth
            val wide = windowWidth >= 1000.dp
            BackHandler(drawer) { drawer = false }
            val toggleSidebar = { if (wide) vm.layout(sidebarVisible = !workspace.sidebarVisible) else drawer = !drawer }
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                if (!fullscreen) Surface(color = colors.surfaceContainerLow) {
                    Column(Modifier.statusBarsPadding()) {
                        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                            WorkspaceAction(Outline.Menu, "Проводник · Ctrl+B", toggleSidebar, Modifier.testTag("toggle-workspace-sidebar"))
                            Text("Readerka", style = MaterialTheme.typography.titleSmall, color = colors.primary, modifier = Modifier.weight(1f))
                            if (windowWidth > 600.dp) {
                                WorkspaceAction(Outline.Left, "Назад · Alt+←", vm::historyBack, enabled = workspace.history.canBack)
                                WorkspaceAction(Outline.Right, "Вперёд · Alt+→", vm::historyForward, enabled = workspace.history.canForward)
                            }
                            WorkspaceAction(Outline.Search, "Команды · Ctrl+P", { commands = true; sourcesOnly = false }, Modifier.testTag("quick-commands"))
                            WorkspaceAction(Outline.Edit, "Черновики (${workspace.drafts.size})", { draftsOpen = true }, Modifier.testTag("open-drafts"))
                            WorkspaceAction(Outline.Split, "Области и размеры", { layoutOpen = true }, Modifier.testTag("workspace-layout"), selected = workspace.split)
                            WorkspaceAction(Outline.Plus, "Добавить PDF", { importPdf(null) })
                        }
                        HorizontalDivider()
                    }
                }
            }, bottomBar = {
                if (windowWidth < 600.dp && !fullscreen && !imeVisible) Row(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalArrangement = Arrangement.SpaceEvenly) { tabs.forEachIndexed { i, tab -> WorkspaceAction(icons[i], tab.second, { vm.navigate(tab.first) }, selected = screen == tab.first) } }
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (windowWidth >= 600.dp && !fullscreen) Surface(color = colors.surfaceContainerLow) {
                        Column(Modifier.width(52.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) { tabs.forEachIndexed { i, tab -> WorkspaceAction(icons[i], tab.second, { vm.navigate(tab.first) }, Modifier.testTag("workspace-nav-${tab.first}"), selected = screen == tab.first) } }
                    }
                    if (wide && workspace.sidebarVisible && !fullscreen) {
                        WorkspaceSidebar(data, reader?.document?.id, vm, importPdf, {}, Modifier.width(workspace.sidebarWidth.dp).fillMaxHeight(), screen == "notes", notesFolder, { notesFolder = it })
                        ResizeHandle("resize-sidebar", { vm.layout(sidebarWidth = vm.workspace.value.sidebarWidth + it) })
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            busy?.let { Text(activity.getString(it.label), style = MaterialTheme.typography.labelMedium); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                            when (screen) {
                                "reader" -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                    val horizontal = maxWidth >= 700.dp
                                    val available = (if (horizontal) maxWidth else maxHeight).value - 48f
                                    if (!workspace.split || (imeVisible && !horizontal)) {
                                        val visiblePane = if (workspace.split) workspace.activePane else 0
                                        WorkspacePaneContent(visiblePane, data, vm, readers[visiblePane], workspace, fullscreen, { fullscreen = it }, !commands && !draftsOpen && !layoutOpen)
                                    }
                                    else if (horizontal) Row(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(workspace.splitFraction).fillMaxHeight()) { WorkspacePaneContent(0, data, vm, readers[0], workspace, fullscreen, { fullscreen = it }, !commands && !draftsOpen && !layoutOpen) }
                                        ResizeHandle("resize-split", { vm.layout(fraction = vm.workspace.value.splitFraction + it / available.coerceAtLeast(1f)) })
                                        Box(Modifier.weight(1f - workspace.splitFraction).fillMaxHeight()) { WorkspacePaneContent(1, data, vm, readers[1], workspace, fullscreen, { fullscreen = it }, !commands && !draftsOpen && !layoutOpen) }
                                    } else Column(Modifier.fillMaxSize()) {
                                        Box(Modifier.weight(workspace.splitFraction).fillMaxWidth()) { WorkspacePaneContent(0, data, vm, readers[0], workspace, fullscreen, { fullscreen = it }, !commands && !draftsOpen && !layoutOpen) }
                                        ResizeHandle("resize-split", { vm.layout(fraction = vm.workspace.value.splitFraction + it / available.coerceAtLeast(1f)) }, vertical = false)
                                        Box(Modifier.weight(1f - workspace.splitFraction).fillMaxWidth()) { WorkspacePaneContent(1, data, vm, readers[1], workspace, fullscreen, { fullscreen = it }, !commands && !draftsOpen && !layoutOpen) }
                                    }
                                }
                                "notes" -> NotesScreen(data, vm, notesFolder, { notesFolder = it }, { if (wide) vm.layout(sidebarVisible = true) else drawer = true }, export)
                                "settings" -> SettingsScreen(preferences, data, vm, export, { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                                else -> LibraryScreen(data, vm, false, importPdf, workspace = true)
                            }
                        }
                        if (!wide && drawer) ModalNavigationDrawer(drawerState = rememberDrawerState(DrawerValue.Open), drawerContent = {
                            ModalDrawerSheet(Modifier.widthIn(max = 320.dp)) {
                                TextButton(onClick = { drawer = false }) { Text("Закрыть проводник") }
                                WorkspaceSidebar(data, reader?.document?.id, vm, importPdf, { drawer = false }, Modifier.fillMaxSize(), screen == "notes", notesFolder, { notesFolder = it })
                            }
                        }, gesturesEnabled = true) { }
                    }
                }
            }
            if (layoutOpen) AlertDialog(onDismissRequest = { layoutOpen = false }, title = { Text("Рабочее пространство") }, text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Text("Две области", Modifier.weight(1f)); Switch(workspace.split, { vm.layout(split = it); vm.navigate("reader") }) }
                    Text("Пропорции областей", style = MaterialTheme.typography.labelLarge)
                    Row { listOf(.33f to "⅓ : ⅔", .5f to "½ : ½", .67f to "⅔ : ⅓").forEach { (value, label) -> TextButton(onClick = { vm.layout(fraction = value) }) { Text(label) } } }
                    Slider(workspace.splitFraction, { vm.layout(fraction = it) }, valueRange = .3f.. .7f)
                    Text("Проводник · ${workspace.sidebarWidth.toInt()} dp")
                    Slider(workspace.sidebarWidth, { vm.layout(sidebarWidth = it) }, valueRange = 200f..400f)
                    Text("Панель PDF · ${workspace.structureWidth.toInt()} dp")
                    Slider(workspace.structureWidth, { vm.layout(structureWidth = it) }, valueRange = 220f..400f)
                    TextButton(onClick = { vm.layout(fraction = .5f, sidebarWidth = 260f, structureWidth = 280f) }) { Text("Стандартные размеры") }
                    Text("Границы с ручками можно перемещать касанием. На узком экране области располагаются одна над другой.", style = MaterialTheme.typography.bodySmall)
                }
            }, confirmButton = { TextButton(onClick = { layoutOpen = false }) { Text("Готово") } })
            if (draftsOpen) DraftList(workspace.drafts, data, vm) { draftsOpen = false }
            if (commands) QuickCommands(buildList {
                if (!sourcesOnly) {
                    add(QuickCommand("new-note", "Создать заметку", "Ctrl+N") { vm.newNote(reader?.document?.id) })
                    add(QuickCommand("save-note", "Сохранить заметку", "Ctrl+S") { vm.saveEditor(selected?.documentId?.takeIf { selected.kind == WorkspaceKind.Note }) })
                    add(QuickCommand("drafts", "Открыть черновики") { draftsOpen = true })
                    add(QuickCommand("library", "Открыть библиотеку") { vm.navigate("library") })
                    add(QuickCommand("notes", "Каталог заметок") { vm.navigate("notes") })
                    add(QuickCommand("settings", "Настройки") { vm.navigate("settings") })
                    add(QuickCommand("layout", "Области и размеры") { layoutOpen = true })
                    add(QuickCommand("sidebar", "Показать или скрыть проводник", "Ctrl+B") { toggleSidebar() })
                    selected?.let { tab -> add(QuickCommand("right", "Открыть справа") { vm.openRight(tab) }); add(QuickCommand("pin", "Закрепить или открепить вкладку") { vm.pinTab(workspace.activePane, tab.id) }) }
                    if (reader != null) {
                        add(QuickCommand("pdf-search", "Поиск в PDF", "Ctrl+F") { vm.requestReaderTool(ReaderTool.Search) })
                        add(QuickCommand("pdf-quotes", "Цитаты текущего PDF") { vm.requestReaderTool(ReaderTool.Quotes) })
                        add(QuickCommand("pdf-bookmarks", "Закладки текущего PDF") { vm.requestReaderTool(ReaderTool.Bookmarks) })
                        add(QuickCommand("pdf-page", "Перейти к странице PDF") { vm.requestReaderTool(ReaderTool.Page) })
                    }
                    workspace.drafts.forEach { draft -> add(QuickCommand("draft-${draft.entry.id}", draft.entry.title.ifBlank { "Без названия" }, "Черновик") { vm.resumeDraft(draft) }) }
                    data.entries.forEach { entry -> add(QuickCommand("note-${entry.id}", entry.title, "Заметка · ${entry.comment.take(100)}") { vm.openNote(entry) }) }
                }
                data.documents.sortedByDescending { it.lastOpenedAt }.forEach { document -> add(QuickCommand("source-${document.id}", document.title, "PDF · ${document.authors}") { vm.open(document) }) }
            }) { commands = false }
        }
      }
    }
}

@Composable private fun WorkspacePaneContent(pane: Int, data: LibrarySnapshot, vm: LibraryViewModel, session: ReaderSession?, state: WorkspaceState, fullscreen: Boolean, onFullscreen: (Boolean) -> Unit, keys: Boolean) {
    val model = state.panes[pane]
    val tab = model.selected
    val active = state.activePane == pane
    BoxWithConstraints(Modifier.fillMaxSize().testTag("workspace-pane-$pane").onFocusChanged { if (it.hasFocus) vm.focusPane(pane) }.focusGroup().pointerInput(pane) {
        awaitPointerEventScope { while (true) { val event = awaitPointerEvent(PointerEventPass.Initial); if (event.changes.any { it.pressed && !it.previousPressed }) vm.focusPane(pane) } }
    }) {
        val wide = maxWidth >= 820.dp
        Column(Modifier.fillMaxSize()) {
            if (!fullscreen) WorkspaceTabs(model, pane, data, state.drafts, active, vm)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab?.kind) {
                    WorkspaceKind.Pdf -> if (session?.tabId == tab.id) ReaderScreen(session, data, vm, wide, fullscreen, onFullscreen, allowPageKeys = keys && active) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    WorkspaceKind.Note -> key(tab.id) { NoteDocument(tab.documentId, data, vm, pane) }
                    null -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Text("Область ${pane + 1}", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { vm.focusPane(pane); vm.navigate("library") }) { Text("Открыть PDF") }; TextButton(onClick = { vm.focusPane(pane); vm.newNote() }) { Text("Создать заметку") } }
                }
            }
        }
    }
}
