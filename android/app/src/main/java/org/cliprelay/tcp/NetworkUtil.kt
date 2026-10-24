package org.cliprelay.tcp

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtil {
    /**
     * Interface name prefixes that carry a VPN tunnel. Tailscale on Android uses
     * a VpnService interface (typically `tun0`).
     */
    private val TUNNEL_PREFIXES = listOf("tun", "utun", "tailscale")

    /**
     * True when [ip] is inside 100.64.0.0/10, the range Tailscale assigns to
     * tailnet nodes. Note this is also the carrier-grade NAT range, so it is
     * only treated as Tailscale when it sits on a tunnel interface.
     */
    fun isTailscaleAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val first = parts[0].toIntOrNull() ?: return false
        val second = parts[1].toIntOrNull() ?: return false
        return first == 100 && second in 64..127
    }

    /**
     * The LAN (Wi-Fi/Ethernet/cellular) IPv4 address, excluding VPN tunnels.
     * This is the address peers on the same physical network can reach.
     */
    fun getLocalIpAddress(): String? {
        val interfaces = usableInterfaces() ?: return null

        // Prefer wlan/eth interfaces (typical Android Wi-Fi/Ethernet)
        val preferred = interfaces
            .filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress

        if (preferred != null) return preferred

        // Fallback: any non-loopback, non-tunnel IPv4 address (e.g. cellular)
        return interfaces
            .filterNot { isTunnelInterface(it) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    }

    /**
     * The Tailscale IPv4 address (100.64.0.0/10 on a tunnel interface), or null
     * when Tailscale is not up. Reachable from peers on the same tailnet even
     * when the two devices are on different physical networks.
     */
    fun getTailscaleIpAddress(): String? {
        val interfaces = usableInterfaces() ?: return null

        return interfaces
            .filter { isTunnelInterface(it) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .firstOrNull { isTailscaleAddress(it) }
    }

    /**
     * All addresses a peer may use to reach this device, Tailscale first when it
     * is up (works on any network, and still takes the direct LAN path when both
     * devices happen to share one), LAN second as a fallback.
     */
    fun getLocalIpAddresses(): List<String> =
        listOfNotNull(getTailscaleIpAddress(), getLocalIpAddress()).distinct()

    private fun usableInterfaces(): List<NetworkInterface>? =
        NetworkInterface.getNetworkInterfaces()?.asSequence()
            ?.filter { it.isUp && !it.isLoopback }
            ?.toList()

    private fun isTunnelInterface(iface: NetworkInterface): Boolean =
        TUNNEL_PREFIXES.any { iface.name.startsWith(it) }
}
