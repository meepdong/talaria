package io.github.meepdong.talaria.session

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/** The Network row: is a private network (Tailscale, WireGuard, another VPN) up? */
data class NetworkStatus(val kind: Kind, val detail: String) {
    enum class Kind { TAILSCALE, VPN, NONE }

    val ok: Boolean get() = kind != Kind.NONE
}

object NetworkCheck {
    private val VPN_NAMES = Regex("^(tailscale|wg|tun|utun|ppp|ipsec|zt|nordlynx|proton)", RegexOption.IGNORE_CASE)

    /** A Tailscale address (100.64.0.0/10) wins; otherwise any VPN-looking interface counts. */
    fun check(interfaces: List<Pair<String, List<ByteArray>>> = systemInterfaces()): NetworkStatus {
        for ((name, addresses) in interfaces) {
            val ts = addresses.firstOrNull { it.size == 4 && (it[0].toInt() and 0xff) == 100 && (it[1].toInt() and 0xc0) == 64 }
            if (ts != null) {
                return NetworkStatus(NetworkStatus.Kind.TAILSCALE,
                    "Tailscale ok (${ts.joinToString(".") { (it.toInt() and 0xff).toString() }})")
            }
        }
        interfaces.firstOrNull { (name, addresses) -> VPN_NAMES.containsMatchIn(name) && addresses.isNotEmpty() }?.let {
            return NetworkStatus(NetworkStatus.Kind.VPN, "VPN ${it.first} is up")
        }
        return NetworkStatus(NetworkStatus.Kind.NONE, "No private network. Turn on Tailscale")
    }

    private fun systemInterfaces(): List<Pair<String, List<ByteArray>>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .map { nif ->
                val label = listOf(nif.name, nif.displayName.orEmpty()).joinToString(" ")
                label to nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.address }
            }
    } catch (e: SocketException) {
        emptyList()
    }
}
