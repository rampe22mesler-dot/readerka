package org.readerka

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.readerka.data.*
import org.readerka.export.*
import org.readerka.ui.ImportReviewDialog

@OptIn(ExperimentalTestApi::class)
class ImportReviewUiTest {
    @get:Rule val compose = createComposeRule()

    private fun scenario(input: Int) {
        val before =
            LibrarySnapshot(entries = listOf(Entry("same", "На планшете", createdAt = 1000)))
        val incoming = before.copy(entries = listOf(before.entries[0].copy(title = "На ПК")))
        var applied: ImportPolicy? = null
        compose.setContent {
            MaterialTheme {
                ImportReviewDialog(
                    StagedBackup(incoming, File("unused")),
                    before,
                    {},
                    { applied = it },
                )
            }
        }
        fun act(node: SemanticsNodeInteraction) {
            when (input) {
                0 -> node.performTouchInput { click() }
                1 -> {
                    InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
                    val instrumentation=InstrumentationRegistry.getInstrumentation()
                    var focused=false
                    for(i in 0..30){focused=runCatching{node.assertIsFocused();true}.getOrDefault(false);if(focused)break;instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_TAB);compose.waitForIdle()}
                    assertTrue("Tab reaches the requested dialog action",focused)
                    instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)

                }
                else -> node.performMouseInput { click() }
            }
            compose.waitForIdle()
        }
        act(compose.onNodeWithText("Обновить из архива: заменить совпадающие записи"))
        act(compose.onNodeWithText("Запись: На планшете"))
        compose.onNodeWithText("На планшете").assertIsDisplayed()
        compose.onNodeWithText("В архиве").assertIsDisplayed()
        act(compose.onNodeWithText("Назад"))
        act(compose.onNodeWithText("Импортировать"))
        assertEquals(ImportPolicy.UseIncoming, applied)
    }

    @Test fun touchReviewAndExplicitUpdate() = scenario(0)

    @Test fun keyboardReviewAndExplicitUpdate() = scenario(1)

    @Test fun mouseReviewAndExplicitUpdate() = scenario(2)
}
