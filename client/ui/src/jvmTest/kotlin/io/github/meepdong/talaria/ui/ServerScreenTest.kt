package io.github.meepdong.talaria.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.runComposeUiTest
import io.github.meepdong.talaria.ops.OpOutcome
import io.github.meepdong.talaria.ops.OpsApproval
import io.github.meepdong.talaria.ops.OpsState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ServerScreenTest {
    private class Recorder : TalariaActions {
        val calls = mutableListOf<String>()
        override fun pairWithLink(link: String, deviceName: String) {}
        override fun pairWithCode(code: String, address: String, deviceName: String) {}
        override fun cancelPairing() {}
        override fun testConnection() {}
        override fun reconnectNow() {}
        override fun forgetServer() {}
        override fun showChats() { calls += "back" }
        override fun refreshServer() { calls += "refresh" }
        override fun serverRun(op: String, params: Map<String, String>) {
            calls += (listOf(op) + params.entries.sortedBy { it.key }.map { "${it.key}=${it.value}" }).joinToString(" ")
        }
        override fun opsApprove(requestId: String, choice: String) { calls += "approve $requestId $choice" }
        override fun opsDismiss(requestId: String) { calls += "dismiss $requestId" }
    }

    private fun outcome(op: String, summary: String, data: Any? = null, output: String = "") =
        OpOutcome(op, true, 0, summary, output, data, 1)

    private val state = OpsState(
        reads = mapOf(
            "system.overview" to outcome("system.overview", "up 47 h, disk 13% used, 0 updates",
                mapOf("mem_total" to 8_000_000_000L, "mem_available" to 5_000_000_000L, "disk_total" to 100_000_000_000L,
                      "disk_free" to 87_000_000_000L, "load" to listOf(0.1, 0.2, 0.3), "kernel" to "6.8.0-146-generic",
                      "failed_units" to emptyList<String>())),
            "services.list" to outcome("services.list", "all active",
                listOf(mapOf("service" to "docker", "state" to "active"), mapOf("service" to "ssh", "state" to "active"))),
            "docker.ps" to outcome("docker.ps", "1 container", listOf(mapOf("name" to "hermes-abc", "status" to "Up 2 days"))),
            "bridge.version" to outcome("bridge.version", "up to date", mapOf("head" to "abc", "behind" to 0)),
        ),
        pending = listOf(OpsApproval("op-1", "system.reboot", "{}", 2, "Reboot the server now", "agent:hermes", 9_999_999_999)),
    )

    @Test
    fun showsTheServerAndAsksBeforeChanging() = runComposeUiTest {
        val actions = Recorder()
        val view = serverView(state.copy(pending = emptyList()), "D1")
        assertEquals(listOf("Memory" to "5.0 GB free of 8.0 GB", "Disk" to "87.0 GB free of 100.0 GB",
            "Load" to "0.10  0.20  0.30", "Kernel" to "6.8.0-146-generic"), view.overviewRows)
        var v by mutableStateOf(view)
        setContent { TalariaTheme { ServerScreen(v, actions) } }
        onNodeWithTag("ops-floating").assertDoesNotExist()

        onNodeWithTag("server-overview").assertTextContains("up 47 h", substring = true)
        onNodeWithTag("restart-docker").performScrollTo().performClick()
        onNodeWithTag("logs-ssh").performScrollTo().performClick()
        onNodeWithTag("restart-container-hermes-abc").performScrollTo().performClick()
        onNodeWithTag("bridge-update").performScrollTo().assertIsNotEnabled()  // already up to date
        onNodeWithTag("apt-upgrade").performScrollTo().performClick()
        onNodeWithTag("reboot").performScrollTo().performClick()
        onNodeWithTag("reboot-ask").performClick()
        assertEquals(listOf("service.restart service=docker", "service.logs lines=100 service=ssh",
            "docker.restart container=hermes-abc", "apt.upgrade", "system.reboot"), actions.calls)

        // an approval floats above the page; a tier 2 one asks again before it's allowed
        actions.calls.clear()
        v = serverView(state, "D1")
        onNodeWithTag("ops-floating").assertExists()
        onNodeWithTag("ops-summary").assertTextContains("Reboot the server now")
        onNodeWithTag("ops-allow").performClick()
        assertEquals(emptyList(), actions.calls)
        onNodeWithTag("ops-confirm").performClick()
        assertEquals(listOf("approve op-1 once"), actions.calls)
    }

    @Test
    fun mapsWhoAskedAndWhat() {
        assertEquals("Hermes", opsFrom("agent:hermes", "D1"))
        assertEquals("this device", opsFrom("device:D1", "D1"))
        assertEquals("another device", opsFrom("device:D2", "D1"))
        assertEquals("service: docker\nlines: 100", paramsText("""{"service":"docker","lines":100}"""))
        assertEquals("", paramsText("{}"))
        assertEquals("Reboot the server now", opsApprovals(state, "D1").single().summary)
    }

    @Test
    fun saysWhenTheServerHasNoOperations() = runComposeUiTest {
        setContent { TalariaTheme { ServerScreen(ServerView(available = false), Recorder()) } }
        onNodeWithTag("server-unavailable").assertTextContains("talaria-ops", substring = true)
    }
}
