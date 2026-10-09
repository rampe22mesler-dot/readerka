package org.readerka

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.readerka.data.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FolderWorkspaceUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val dao get() = ApplicationProvider.getApplicationContext<ReaderkaApp>().database.dao()
    private fun sidebar(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-sidebar")))
    private fun waitSidebar(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodes(hasTestTag(tag) and hasAnyAncestor(hasTestTag("workspace-sidebar"))).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun dialog(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(isDialog()))

    private fun savePreview() {
        compose.waitForIdle()
        val bitmap = compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
        }
        val copied = java.util.concurrent.CountDownLatch(1)
        val result = java.util.concurrent.atomic.AtomicInteger(-1)
        compose.runOnUiThread {
            android.view.PixelCopy.request(compose.activity.window, bitmap, { code -> result.set(code); copied.countDown() }, android.os.Handler(android.os.Looper.getMainLooper()))
        }
        assertTrue(copied.await(15, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(android.view.PixelCopy.SUCCESS, result.get())
        val app = ApplicationProvider.getApplicationContext<ReaderkaApp>()
        val file = java.io.File(app.filesDir, "ui-previews/readerka-0.4-folder-workspace.png").apply { parentFile!!.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    @Test fun foldersCanBeCreatedWithoutQuoteAndMultiMembershipSurvivesRecreation() {
        val token = UUID.randomUUID().toString().take(8)
        val rootName = "0 Philosophy $token"
        val childName = "Epistemology $token"
        val otherName = "1 Logic $token"
        val title = "One canonical note $token"
        try {
            compose.onNodeWithContentDescription("Заметки и цитаты").performClick()
            compose.onNodeWithContentDescription("Папки заметок").performClick()
            sidebar("create-root-folder").performClick()
            compose.onNodeWithText("Название").performTextInput(rootName)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allCollections().any { it.name == rootName } } }
            val root = runBlocking { dao.allCollections().first { it.name == rootName } }
            waitSidebar("folder-menu-" + root.id)
            sidebar("folder-menu-" + root.id).performClick()
            compose.onNodeWithText("Создать подпапку").performClick()
            compose.onNodeWithText("Название").performTextInput(childName)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allCollections().any { it.name == childName } } }
            val child = runBlocking { dao.allCollections().first { it.name == childName } }
            assertEquals(root.id, child.parentId)
            waitSidebar("select-folder-" + child.id)
            sidebar("select-folder-" + child.id).assertIsDisplayed()
            sidebar("expand-folder-" + root.id).performClick()
            sidebar("select-folder-" + child.id).assertDoesNotExist()
            sidebar("folder-search").performTextInput(childName)
            sidebar("select-folder-" + child.id).assertIsDisplayed()
            sidebar("select-folder-" + root.id).assertIsDisplayed()
            sidebar("folder-search").performTextClearance()
            sidebar("create-root-folder").performClick()
            compose.onNodeWithText("Название").performTextInput(otherName)
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allCollections().any { it.name == otherName } } }
            val other = runBlocking { dao.allCollections().first { it.name == otherName } }
            assertNull(other.parentId)
            waitSidebar("select-folder-" + other.id)
            compose.onNodeWithText("Создать заметку").performClick()
            compose.onNodeWithTag("entry-title").performTextInput(title)
            compose.onNodeWithTag("entry-comment").performTextInput("Shared between two branches")
            compose.onNodeWithText("Папки заметок", substring = true).performScrollTo().performClick()
            dialog("expand-folder-" + root.id).performClick()
            dialog("select-folder-" + child.id).performClick()
            dialog("select-folder-" + other.id).performClick()
            compose.onNodeWithText("Применить").performClick()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allEntries().any { it.title == title } } }
            val entry = runBlocking { dao.allEntries().single { it.title == title } }
            compose.waitUntil(30_000) { runBlocking { dao.allMemberships().count { it.entryId == entry.id } == 2 } }
            sidebar("select-folder-" + root.id).performClick()
            compose.onNodeWithText(title).assertIsDisplayed()
            savePreview()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(title).assertIsDisplayed()
            assertEquals(setOf(child.id, other.id), runBlocking { dao.allMemberships().filter { it.entryId == entry.id }.map { it.collectionId }.toSet() })
            // Renaming/reparenting does not copy the canonical entry or remove its memberships.
            sidebar("folder-menu-" + child.id).performClick()
            compose.onNodeWithText("Редактировать папку").performClick()
            compose.onNodeWithText("Название").performTextClearance()
            compose.onNodeWithText("Название").performTextInput("Renamed $token")
            compose.onNodeWithText("Родительская папка: $rootName").performClick()
            dialog("select-folder-" + other.id).performClick()
            compose.onNodeWithText("Сохранить").performClick()
            compose.waitUntil(30_000) { runBlocking { dao.allCollections().first { it.id == child.id }.parentId == other.id } }
            assertEquals(1, runBlocking { dao.allEntries().count { it.id == entry.id } })
            assertEquals(2, runBlocking { dao.allMemberships().count { it.entryId == entry.id } })
        } finally {
            runBlocking {
                dao.deleteEntries(dao.allEntries().filter { it.title == title }.map { it.id })
                dao.allCollections().filter { it.name.endsWith(token) }.forEach { dao.deleteCollection(it.id) }
            }
        }
    }

    @Test fun largeSourceFilterShowsRecentFirstAndSearchesLongTitlesAndAuthors() {
        val token = UUID.randomUUID().toString().take(8)
        val docs = (0..79).map { n -> Document("filter-$token-$n", "Source $n " + "long research title ".repeat(20), "test.pdf", "unused.pdf",
            authors = "Author $n $token", lastOpenedAt = if (n == 79) 100000 else 0, pageCount = 1) }
        val entries = listOf(Entry("recent-$token", "Recent quote $token", documentId = docs.last().id), Entry("old-$token", "Older quote $token", documentId = docs.first().id))
        try {
            runBlocking { docs.forEach { dao.putDocument(it) }; entries.forEach { dao.putEntry(it) } }
            compose.onNodeWithContentDescription("Заметки и цитаты").performClick()
            compose.onNodeWithText("Фильтры").performClick()
            compose.onNodeWithText("Источник: Все").performScrollTo().performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("search-option-" + docs.last().id).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Недавно открытые").assertIsDisplayed()
            compose.onNodeWithTag("search-option-" + docs.last().id).assertIsDisplayed()
            val height = compose.onNodeWithTag("search-option-" + docs.last().id).fetchSemanticsNode().boundsInRoot.height
            assertTrue("A long source must not occupy the whole picker: $height", height < 150f)
            compose.onNodeWithTag("option-search").performTextInput("Author 0 $token")
            compose.onNodeWithTag("search-option-" + docs.first().id).assertIsDisplayed().performClick()
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithText("Older quote $token").assertIsDisplayed()
            compose.onNodeWithText("Recent quote $token").assertDoesNotExist()
            compose.onNodeWithText("Фильтры").performClick()
            compose.onNodeWithText("Сбросить фильтры").performScrollTo().performClick()
            compose.onNodeWithText("Закрыть").performClick()
            compose.onNodeWithText("Recent quote $token").assertIsDisplayed()
        } finally { runBlocking { dao.deleteEntries(entries.map { it.id }); docs.forEach { dao.deleteDocument(it.id) } } }
    }
}
