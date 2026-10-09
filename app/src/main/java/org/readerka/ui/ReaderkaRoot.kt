package org.readerka.ui

import android.app.Activity
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.readerka.R
import org.readerka.data.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable fun ReaderkaRoot(activity: Activity, vm: LibraryViewModel) {
    val data by vm.library.collectAsStateWithLifecycle()
    val preferences by vm.preferences.collectAsStateWithLifecycle()
    val screen by vm.screen.collectAsStateWithLifecycle()
    val reader by vm.reader.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val openIds by vm.openDocumentIds.collectAsStateWithLifecycle()
    val openDocuments = openIds.mapNotNull { id -> data.documents.firstOrNull { it.id == id } }
    val size = calculateWindowSizeClass(activity)
    val rail = size.widthSizeClass != WindowWidthSizeClass.Compact
    val wide = size.widthSizeClass == WindowWidthSizeClass.Expanded
    val dark = when (preferences.theme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var sidebar by rememberSaveable { mutableStateOf(wide) }
    var notesFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var contentWidth by remember { mutableIntStateOf(0) }
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
    val tabs = listOf("library" to R.string.library, "reader" to R.string.reader, "notes" to R.string.notes, "settings" to R.string.settings)
    val icons = listOf(Icons.Default.Home, Icons.Default.PlayArrow, Icons.Default.Edit, Icons.Default.Settings)
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(fullscreen, screen, dark) {
        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen && screen == "reader") controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    BackHandler(screen != "library" || fullscreen || (!wide && sidebar)) {
        if (!wide && sidebar) sidebar = false else if (fullscreen) fullscreen = false else vm.navigate("library")
    }
    val colorScheme = if (dark) darkColorScheme(
        primary = Color(0xFFB6A1EE), onPrimary = Color(0xFF261A43), secondary = Color(0xFFB9B2C8),
        secondaryContainer = Color(0xFF3C334F), surface = Color(0xFF202024), background = Color(0xFF202024),
        surfaceContainerLow = Color(0xFF19191D), surfaceContainer = Color(0xFF25252B),
        surfaceContainerHigh = Color(0xFF2D2D35), outlineVariant = Color(0xFF36363F))
        else lightColorScheme(primary = Color(0xFF7054AD), secondary = Color(0xFF625A73),
            secondaryContainer = Color(0xFFECE6F6), surface = Color(0xFFFCFBFE), background = Color(0xFFFCFBFE),
            surfaceContainerLow = Color(0xFFF1EFF5), surfaceContainer = Color(0xFFF5F3F8),
            surfaceContainerHigh = Color(0xFFEAE7F0), outlineVariant = Color(0xFFE1DDE9))
    val density = LocalDensity.current
    val immersive = fullscreen && screen == "reader"
    val importPdf: (String?) -> Unit = { folder -> if (busy == null) { importFolder = folder; import.launch(arrayOf("application/pdf")) } }
    val shortcut: (KeyEvent) -> Boolean = { event ->
        val recognized = event.isCtrlPressed && event.keyCode in listOf(KeyEvent.KEYCODE_B, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_TAB)
        if (recognized && event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_B -> sidebar = !sidebar
                KeyEvent.KEYCODE_W -> if (busy == null) reader?.document?.id?.let(vm::closeDocument)
                KeyEvent.KEYCODE_TAB -> if (busy == null && openDocuments.isNotEmpty()) {
                    val index = openDocuments.indexOfFirst { it.id == reader?.document?.id }
                    vm.open(openDocuments[Math.floorMod(index + if (event.isShiftPressed) -1 else 1, openDocuments.size)])
                }
            }
        }
        recognized
    }
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.scale)) {
        MaterialTheme(colorScheme = colorScheme) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                if (!immersive) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        Row(Modifier.fillMaxWidth().height(48.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { sidebar = !sidebar }, modifier = Modifier.testTag("toggle-workspace-sidebar")) { Icon(Icons.Default.Menu, stringResource(R.string.toggle_sidebar), modifier = Modifier.size(21.dp)) }
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                            Text(" / " + stringResource(tabs.firstOrNull { it.first == screen }?.second ?: R.string.library), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { importPdf(null) }) { Icon(Icons.Default.Add, stringResource(R.string.add_pdf), modifier = Modifier.size(21.dp)) }
                        }
                        HorizontalDivider()
                    }
                }
            }, bottomBar = {
                if (!rail && !immersive) NavigationBar {
                    tabs.forEachIndexed { i, tab -> NavigationBarItem(screen == tab.first, { vm.navigate(tab.first) }, icon = { Icon(icons[i], null) }, label = { Text(stringResource(tab.second)) }) }
                }
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (rail && !immersive) Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.width(52.dp).fillMaxHeight().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            tabs.forEachIndexed { i, tab ->
                                IconButton(onClick = { vm.navigate(tab.first) }, modifier = Modifier.padding(vertical = 4.dp)
                                    .background(if (screen == tab.first) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, MaterialTheme.shapes.small)
                                    .testTag("workspace-nav-" + tab.first)) {
                                    Icon(icons[i], stringResource(tab.second), tint = if (screen == tab.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    if (wide && sidebar && !immersive) {
                        WorkspaceSidebar(data, reader?.document?.id, vm, importPdf, {}, Modifier.width(248.dp).fillMaxHeight(), screen == "notes", notesFolder, { notesFolder = it })
                        VerticalDivider()
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Column(Modifier.fillMaxSize()) {
                            if (openDocuments.isNotEmpty() && !immersive) {
                                DocumentTabs(openDocuments, reader?.document?.id?.takeIf { screen == "reader" }, busy == null, { vm.open(it) }, vm::closeDocument)
                            }
                            busy?.let { b ->
                                Text(stringResource(b.label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                                if (b.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                            }
                            Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { contentWidth = it.width }) {
                                when (screen) {
                                    "reader" -> ReaderScreen(reader, data, vm, with(density) { contentWidth.toDp() } >= 820.dp, fullscreen, onFullscreen = { fullscreen = it }, onWorkspaceShortcut = shortcut)
                                    "notes" -> NotesScreen(data, vm, notesFolder, { notesFolder = it }, { sidebar = true }, export)
                                    "settings" -> SettingsScreen(preferences, data, vm, export, { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                                    else -> LibraryScreen(data, vm, false, importPdf, workspace = true)
                                }
                            }
                        }
                        if (!wide && sidebar && !immersive) {
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .30f)).clickable { sidebar = false })
                            WorkspaceSidebar(data, reader?.document?.id, vm, importPdf, { sidebar = false }, Modifier.width(280.dp).fillMaxHeight(), screen == "notes", notesFolder, { notesFolder = it })
                        }
                    }
                }
            }
        }
    }
}
