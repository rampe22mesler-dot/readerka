package org.readerka

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.rules.TestRule

/** Wait for persisted workspace recovery before testing an action on the library. */
fun <R : TestRule, A : ComponentActivity> AndroidComposeTestRule<R, A>.waitForReaderkaReady() {
    waitUntil(60_000) { onAllNodesWithTag("readerka-ready").fetchSemanticsNodes().isNotEmpty() }
}
