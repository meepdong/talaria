package io.github.meepdong.talaria.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The shared screens render the models and send the right actions. */
@OptIn(ExperimentalTestApi::class)
class ScreensTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) { calls += "link $link $deviceName" }
        override fun pairWithCode(code: String, address: String, deviceName: String) {
            calls += "code $code $address $deviceName"
        }
        override fun cancelPairing() { calls += "cancel" }
        override fun testConnection() { calls += "test" }
        override fun reconnectNow() { calls += "reconnect" }
        override fun forgetServer() { calls += "forget" }
    }

    @Test
    fun connectWithLinkAndCode() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaApp(Screen.Connect("Laptop", error = "Pairing was rejected on the bridge"), actions) }
        onNodeWithTag("connect-error").assertTextContains("rejected", substring = true)
        onNodeWithTag("connect").assertIsNotEnabled()
        onNodeWithTag("link").performTextInput("talaria://pair#abc")
        onNodeWithTag("connect").performScrollTo().assertIsEnabled().performClick()

        onNodeWithTag("mode-code").performClick()
        onNodeWithTag("code").performTextInput("ABCD-1234")
        onNodeWithTag("address").performTextInput("vps.tailnet.ts.net")
        onNodeWithTag("connect").performScrollTo().performClick()
        assertEquals(listOf("link talaria://pair#abc Laptop", "code ABCD-1234 vps.tailnet.ts.net Laptop"), actions.calls)
    }

    @Test
    fun confirmShowsCodeAndCountdown() = runComposeUiTest {
        val actions = Recorder()
        setContent {
            TalariaApp(Screen.Confirm("482 913", listOf("🦊", "🌙", "🎸"), listOf("fox", "moon", "guitar"), 103), actions)
        }
        onNodeWithTag("sas-digits").assertTextContains("482 913", substring = true)
        onNodeWithTag("countdown").assertTextContains("Waiting… (1:43)", substring = true)
        onNodeWithText("fox, moon, guitar").assertExists()
        onNodeWithTag("cancel").performClick()
        assertEquals(listOf("cancel"), actions.calls)
    }

    private fun status(failure: String? = null, mustPairAgain: Boolean = false, weak: Boolean = false) = StatusView(
        rows = listOf(
            StatusRow("Network", Health.GOOD, "Tailscale ok (100.64.0.7)"),
            StatusRow("Bridge", if (failure == null) Health.GOOD else Health.BAD,
                if (failure == null) "Connected · 45 ms" else "Disconnected"),
            StatusRow("Meep", Health.GOOD, "Ready"),
        ),
        failure = failure,
        mustPairAgain = mustPairAgain,
        lastConnected = "just now",
        reconnectIn = if (failure != null && !mustPairAgain) "Retrying in 4 s" else null,
        deviceName = "Laptop",
        server = "wss://vps.tailnet.ts.net",
        keyProtection = "No system keyring found, so the key is in a file only your account can read",
        keyWarning = weak,
        test = TestView(running = false, ok = true, message = "Bridge answered in 45 ms"),
        log = listOf("2026-10-03 18:00:00  Connected  (s-1)"),
        overall = Health.GOOD,
        summary = "Connected",
    )

    @Test
    fun statusRowsAndTest() = runComposeUiTest {
        val actions = Recorder()
        setContent { TalariaApp(Screen.Status(status()), actions) }
        onNodeWithTag("row-Bridge").assertTextContains("Connected · 45 ms", substring = true)
        onNodeWithTag("row-Meep").assertTextContains("Ready", substring = true)
        onNodeWithTag("test-result").assertTextContains("Bridge answered in 45 ms", substring = true)
        onNodeWithTag("failure").assertDoesNotExist()
        onNodeWithTag("key-warning").assertDoesNotExist()
        onNodeWithTag("test").performScrollTo().performClick()
        onNodeWithTag("forget").performScrollTo().performClick()
        onNodeWithTag("forget").performScrollTo().performClick()
        assertEquals(listOf("test", "forget"), actions.calls)
    }

    @Test
    fun statusFailures() = runComposeUiTest {
        val actions = Recorder()
        var view by mutableStateOf(status(failure = "Server unreachable", weak = true))
        setContent { TalariaApp(Screen.Status(view), actions) }
        onNodeWithTag("failure").assertExists()
        onNodeWithTag("key-warning").assertExists()
        onNodeWithTag("reconnect-in").assertTextContains("Retrying in 4 s", substring = true)
        onNodeWithTag("reconnect").performClick()

        view = status(failure = "This device was revoked. Pair again", mustPairAgain = true)
        onNodeWithTag("pair-again").performClick()
        assertEquals(listOf("reconnect", "forget"), actions.calls)
    }
}
