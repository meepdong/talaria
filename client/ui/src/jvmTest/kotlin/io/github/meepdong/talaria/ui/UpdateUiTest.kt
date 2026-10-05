package io.github.meepdong.talaria.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import io.github.meepdong.talaria.chat.ChatState
import io.github.meepdong.talaria.updates.AppRelease
import io.github.meepdong.talaria.updates.UpdateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UpdateUiTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun checkForUpdates() { calls += "check" }
        override fun installUpdate() { calls += "install" }
    }

    private val now = 1_791_028_800_000L
    private val status = StatusView(rows = emptyList(), lastConnected = "", deviceName = "Phone", server = "", keyProtection = "",
        overall = Health.GOOD, summary = "Connected")
    private val view = chatView(ChatState(), false, true, status, now)
    private val newer = UpdateState(channel = "beta", available = true,
        release = AppRelease("0.2.0-beta.2", 20002, 1000, "0".repeat(64), "Faster sync."))

    @Test
    fun versionCodes() {
        assertEquals(20003L, versionCodeOf("0.2.0-beta.3"))
        assertEquals(20099L, versionCodeOf("0.2.0"))
        assertEquals(1_000_001L, versionCodeOf("1.0.0-beta.1"))
        for (bad in listOf("0.2", "0.2.0-rc.1", "0.100.0", "0.2.0-beta.99", "0.2.0-beta.0")) assertNull(versionCodeOf(bad), bad)
        assertTrue(versionCodeOf(TALARIA_VERSION) != null, "this build's version is a release version")
    }

    @Test
    fun mapping() {
        val menu = menuView(view, status, version = "0.2.0-beta.1")
        assertFalse(menu.withUpdate(newer, canInstall = false).canUpdate, "no installer (desktop): no update controls")
        assertFalse(menu.withUpdate(newer.copy(supported = false), canInstall = true).canUpdate, "an older bridge")
        val m = menu.withUpdate(newer, canInstall = true)
        assertTrue(m.canUpdate && m.updateAvailable)
        assertEquals("0.2.0-beta.2", m.updateVersion)
        assertEquals("Faster sync.", m.updateNotes)
        assertEquals("Downloading 40%", menu.withUpdate(newer.copy(progress = 0.4f), true).updateStatus)
        assertEquals("Up to date", menu.withUpdate(UpdateState(upToDate = true), true).updateStatus)
        assertNull(updateBanner(UpdateState(release = newer.release, available = false)))
        assertEquals(UpdateBanner("0.2.0-beta.2", "Faster sync.", "Downloading 40%", busy = true),
            updateBanner(newer.copy(progress = 0.4f)))
    }

    @Test
    fun homeOffersTheUpdateAndTheMenuChecks() = runComposeUiTest {
        val actions = Recorder()
        val menu = menuView(view, status, version = "0.2.0-beta.1")
        var screen by mutableStateOf(Screen.Chat(view, status, tab = Tab.HOME, tabs = TalariaController.TABS,
            home = homeView(view, now).copy(update = updateBanner(newer)), menu = menu.withUpdate(newer, true)))
        setContent { TalariaTheme { Box(Modifier.size(500.dp, 1600.dp)) { MainScreen(screen, actions) } } }
        onNodeWithTag("update-banner").assertExists()
        onNodeWithTag("update-install").performClick()
        assertEquals(listOf("install"), actions.calls)

        val downloading = newer.copy(progress = 0.4f)
        screen = screen.copy(home = screen.home.copy(update = updateBanner(downloading)))
        onNodeWithTag("update-install").assertIsNotEnabled()
        onNodeWithTag("update-status").assertTextEquals("Downloading 40%")

        screen = screen.copy(home = screen.home.copy(update = null), menuOpen = true,
            menu = menu.withUpdate(UpdateState(channel = "beta"), canInstall = true))
        onNodeWithTag("update-banner").assertDoesNotExist()
        onNodeWithTag("check-update").performScrollTo().performClick()
        assertEquals(listOf("install", "check"), actions.calls)

        screen = screen.copy(menu = menu)  // desktop: nothing to check
        onNodeWithTag("check-update").assertDoesNotExist()
    }
}
