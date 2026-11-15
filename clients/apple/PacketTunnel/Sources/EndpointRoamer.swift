import Foundation
import Network
import VeritasCore
import WireGuardKit

/// LAN / WAN endpoint handling after a network change, like Android's
/// EndpointSelector: try the preferred endpoint for the new network, keep
/// whichever completes a fresh handshake. It never changes transport
/// (Direct stays Direct) and never picks a network for the user.
final class EndpointRoamer {
    private let profile: VPNProfile
    private weak var adapter: WireGuardAdapter?
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "cloud.veritasvpn.tunnel.roamer")
    private var current: String
    private var generation = 0

    static let handshakeWait: TimeInterval = 5

    init(profile: VPNProfile, current: String, adapter: WireGuardAdapter) {
        self.profile = profile
        self.current = current
        self.adapter = adapter
    }

    func start() {
        // Nothing to roam between without both a LAN and a WAN endpoint.
        guard profile.lanEndpoint?.isEmpty == false || profile.wanEndpoint?.isEmpty == false else { return }
        monitor.pathUpdateHandler = { [weak self] path in
            guard path.status == .satisfied else { return }
            self?.pathChanged()
        }
        monitor.start(queue: queue)
    }

    func stop() {
        monitor.cancel()
        queue.async { self.generation += 1 }
    }

    private func pathChanged() {
        generation += 1
        let run = generation
        let order = EndpointSelector.roamOrder(
            current: current,
            lan: profile.lanEndpoint,
            wan: profile.wanEndpoint,
            underlayIPv4s: InterfaceAddresses.underlayIPv4()
        )
        // Let WireGuardAdapter finish its own path update first.
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            self?.tryEndpoints(order, index: 0, run: run)
        }
    }

    private func tryEndpoints(_ order: [String], index: Int, run: Int) {
        guard run == generation, index < order.count, let adapter else { return }
        let candidate = order[index]
        let started = Date().timeIntervalSince1970
        if candidate != current {
            guard let configuration = try? WireGuardConfigBuilder.make(profile, endpoint: candidate) else {
                tryEndpoints(order, index: index + 1, run: run)
                return
            }
            // replace_peers in the update forces a new handshake.
            adapter.update(tunnelConfiguration: configuration) { _ in }
        }
        queue.asyncAfter(deadline: .now() + Self.handshakeWait) { [weak self] in
            guard let self, run == self.generation else { return }
            adapter.getRuntimeConfiguration { [weak self] uapi in
                guard let self else { return }
                let stats = uapi.map { RuntimeStats.parse(uapi: $0) } ?? RuntimeStats()
                self.queue.async {
                    guard run == self.generation else { return }
                    if stats.lastHandshakeEpoch >= started - 1 {
                        self.current = candidate
                    } else {
                        self.tryEndpoints(order, index: index + 1, run: run)
                    }
                }
            }
        }
    }
}

/// Underlay IPv4 addresses of the active Wi-Fi / Ethernet / cellular
/// interfaces, used only to compare against the server's LAN /24. They are
/// never logged or sent anywhere.
enum InterfaceAddresses {
    static func underlayIPv4() -> [String] {
        var result: [String] = []
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return [] }
        defer { freeifaddrs(head) }
        var cursor: UnsafeMutablePointer<ifaddrs>? = first
        while let entry = cursor {
            defer { cursor = entry.pointee.ifa_next }
            let flags = Int32(entry.pointee.ifa_flags)
            guard flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0,
                  let address = entry.pointee.ifa_addr, address.pointee.sa_family == UInt8(AF_INET) else { continue }
            let name = String(cString: entry.pointee.ifa_name)
            // Skip our own tunnel and other virtual interfaces.
            guard name.hasPrefix("en") || name.hasPrefix("pdp_ip") || name.hasPrefix("bridge") else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(address, socklen_t(address.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                result.append(String(cString: host))
            }
        }
        return result
    }
}
