import Foundation

enum LocalNetworkAddress {
    /// Interface name prefixes that carry a VPN tunnel (Tailscale uses `utun*` on macOS).
    private static let tunnelPrefixes = ["utun", "tun", "tailscale"]

    /// True when `ip` is inside 100.64.0.0/10, the range Tailscale assigns to
    /// tailnet nodes. Also the carrier-grade NAT range, so it is only treated as
    /// Tailscale when it sits on a tunnel interface.
    static func isTailscaleAddress(_ ip: String) -> Bool {
        let parts = ip.split(separator: ".")
        guard parts.count == 4,
              let first = Int(parts[0]),
              let second = Int(parts[1]) else { return false }
        return first == 100 && (64...127).contains(second)
    }

    /// Returns the local IPv4 address on Wi-Fi/Ethernet (en0/en1) or nil if unavailable.
    static func getLocalIPv4Address() -> String? {
        addresses(matching: { name in name.hasPrefix("en0") || name.hasPrefix("en1") }).first
    }

    /// Returns the Tailscale IPv4 address (100.64.0.0/10 on a tunnel interface),
    /// or nil when Tailscale is not up. Reachable from peers on the same tailnet
    /// even when the two devices sit on different physical networks.
    static func getTailscaleIPv4Address() -> String? {
        addresses(matching: { name in tunnelPrefixes.contains { name.hasPrefix($0) } })
            .first(where: isTailscaleAddress)
    }

    /// All addresses a peer may use to reach this Mac, Tailscale first when it is
    /// up (works on any network, and still takes the direct LAN path when both
    /// devices happen to share one), LAN second as a fallback.
    static func getLocalIPv4Addresses() -> [String] {
        var result: [String] = []
        for address in [getTailscaleIPv4Address(), getLocalIPv4Address()] {
            if let address, !result.contains(address) { result.append(address) }
        }
        return result
    }

    /// The local address a socket should bind to when connecting to `host`.
    ///
    /// For a LAN target this pins the socket to the LAN interface so an active
    /// VPN cannot swallow the route. For a Tailscale target we must NOT bind to
    /// the LAN address, or the packets never enter the tunnel.
    static func sourceIP(for host: String) -> String? {
        isTailscaleAddress(host) ? nil : getLocalIPv4Address()
    }

    /// Enumerates non-loopback IPv4 addresses on interfaces whose name passes `nameFilter`.
    private static func addresses(matching nameFilter: (String) -> Bool) -> [String] {
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0 else { return [] }
        defer { freeifaddrs(ifaddr) }

        var result: [String] = []
        var current = ifaddr
        while let ifa = current {
            defer { current = ifa.pointee.ifa_next }

            let name = String(cString: ifa.pointee.ifa_name)
            guard nameFilter(name) else { continue }

            guard let sockAddr = ifa.pointee.ifa_addr else { continue }
            guard sockAddr.pointee.sa_family == UInt8(AF_INET) else { continue }

            var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            let lookup = getnameinfo(
                sockAddr,
                socklen_t(sockAddr.pointee.sa_len),
                &hostname, socklen_t(hostname.count),
                nil, 0,
                NI_NUMERICHOST
            )
            guard lookup == 0 else { continue }

            let address = String(cString: hostname)
            // Skip loopback
            if address.hasPrefix("127.") { continue }
            result.append(address)
        }
        return result
    }
}
