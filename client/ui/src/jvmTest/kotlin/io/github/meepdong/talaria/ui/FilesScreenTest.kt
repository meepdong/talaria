package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.files.FileEntry
import io.github.meepdong.talaria.files.FileRoot
import io.github.meepdong.talaria.files.FilesState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilesScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun openRoot(id: String) { calls += "root $id" }
        override fun openFile(path: String) { calls += "open $path" }
        override fun filesUp() { calls += "up" }
        override fun searchFiles(query: String) { calls += "search $query" }
        override fun askAboutFile(path: String) { calls += "ask $path" }
    }

    private val now = 1_700_000_100_000L
    private val state = FilesState(
        roots = listOf(FileRoot("inbox", "Sent from Talaria"), FileRoot("workspace", "Hermes workspace")),
        root = "workspace",
        path = "reports",
        entries = listOf(
            FileEntry("2026", "reports/2026", folder = true, size = null, mime = null, modified = 1_700_000_000),
            FileEntry("summary.pdf", "reports/summary.pdf", folder = false, size = 420_000, mime = "application/pdf",
                modified = 1_700_000_000),
        ),
    )

    @Test
    fun mapping() {
        val v = filesView(state, now)
        assertEquals("Hermes workspace / reports", v.location)
        assertTrue(v.canGoUp)
        assertEquals(listOf(false, true), v.roots.map { it.selected })
        assertEquals(listOf("", "PDF"), v.entries.map { it.kind })
        assertTrue(v.entries[0].detail.startsWith("Folder"))
        assertEquals(null, v.message)

        val top = filesView(state.copy(path = "", entries = emptyList()), now)
        assertFalse(top.canGoUp)
        assertEquals("This folder is empty.", top.message)
        assertEquals("Couldn't open it", filesView(state, now, notice = "Couldn't open it").message)
        assertEquals("Hermes workspace / reports · names with \"sum\"", filesView(state.copy(query = "sum"), now).location)
        assertEquals("FILE", kindLabel("README"))
    }

    @Test
    fun browseOpenAndAsk() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaTheme { Box(Modifier.size(900.dp, 700.dp)) { FilesScreen(filesView(state, now), actions) } } }
        onNodeWithTag("files-location").assertTextContains("Hermes workspace / reports")
        onNodeWithTag("root-inbox").performClick()
        onNodeWithTag("file-reports/2026").performClick()
        onNodeWithTag("open-reports/summary.pdf").performClick()
        onNodeWithTag("ask-reports/summary.pdf").performClick()
        onNodeWithTag("files-up").performClick()
        onNodeWithTag("files-query").performTextInput("sum")
        onNodeWithTag("files-search").performClick()
        assertEquals(
            listOf("root inbox", "open reports/2026", "open reports/summary.pdf", "ask reports/summary.pdf", "up", "search sum"),
            actions.calls,
        )
    }

    @Test
    fun openingShowsProgress() = runComposeUiTest {
        setContent { TalariaTheme { FilesScreen(filesView(state, now, opening = "reports/summary.pdf"), Recorder()) } }
        onNodeWithTag("opening").assertExists()
        onNodeWithTag("open-reports/summary.pdf").assertDoesNotExist()
    }
}
