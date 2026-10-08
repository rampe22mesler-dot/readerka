package org.readerka.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.windowsizeclass.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
    val size = calculateWindowSizeClass(activity)
    val rail = size.widthSizeClass != WindowWidthSizeClass.Compact
    val wide = size.widthSizeClass == WindowWidthSizeClass.Expanded
    val dark = when (preferences.theme) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
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
    BackHandler(screen != "library" || fullscreen) { if (fullscreen) fullscreen = false else vm.navigate("library") }
    val colorScheme = if (dark) darkColorScheme(primary = Color(0xFFAFCDA5), secondary = Color(0xFFC1C9B9), surface = Color(0xFF171B16))
        else lightColorScheme(primary = Color(0xFF4B6343), secondary = Color(0xFF5B6654), surface = Color(0xFFFAFAF4))
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.scale)) {
        MaterialTheme(colorScheme = colorScheme) {
            Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
                if (!(fullscreen && screen == "reader")) TopAppBar(title = { Text(stringResource(R.string.app_name)) })
            }, bottomBar = {
                if (!rail && !(fullscreen && screen == "reader")) NavigationBar {
                    tabs.forEachIndexed { i, tab -> NavigationBarItem(screen == tab.first, { vm.navigate(tab.first) }, icon = { Icon(icons[i], null) }, label = { Text(stringResource(tab.second)) }) }
                }
            }) { padding ->
                Row(Modifier.fillMaxSize().padding(padding)) {
                    if (rail && !(fullscreen && screen == "reader")) NavigationRail {
                        tabs.forEachIndexed { i, tab -> NavigationRailItem(screen == tab.first, { vm.navigate(tab.first) }, icon = { Icon(icons[i], null) }, label = { Text(stringResource(tab.second)) }) }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        busy?.let { b ->
                            Text(stringResource(b.label), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                            if (b.progress == null) LinearProgressIndicator(Modifier.fillMaxWidth()) else LinearProgressIndicator(progress = { b.progress }, modifier = Modifier.fillMaxWidth())
                        }
                        Box(Modifier.weight(1f)) {
                            when (screen) {
                                "reader" -> ReaderScreen(reader, data, vm, rail, fullscreen) { fullscreen = it }
                                "notes" -> NotesScreen(data, vm, wide, export)
                                "settings" -> SettingsScreen(preferences, data, vm, export, { restore.launch(arrayOf("application/zip", "application/octet-stream")) })
                                else -> LibraryScreen(data, vm, wide) { folder -> if (busy == null) { importFolder = folder; import.launch(arrayOf("application/pdf")) } }
                            }
                        }
                    }
                }
            }
        }
    }
}
