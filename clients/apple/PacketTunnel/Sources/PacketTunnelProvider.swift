import Foundation
import Network
import NetworkExtension
import VeritasCore
import WireGuardKit

/// VeritasVPN packet tunnel (iOS and macOS). Direct WireGuard over UDP via
/// WireGuardKit. Stealth (WireGuard inside TLS) is not built for Apple yet and
/// fails with a "coming soon" sentence instead of falling back on its own.
///
/// Privacy: nothing here logs endpoints, addresses, keys or the user's IP.
final class PacketTunnelProvider: NEPacketTunnelProvider {
    private lazy var adapter = WireGuardAdapter(with: self) { _, _ in
        // wireguard-go log lines can contain endpoints and addresses. They are
        // dropped on purpose; failures surface through TunnelFailure codes.
    }

    private var roamer: EndpointRoamer?

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        let vault = ProfileVault(configuration: SharedConfiguration.fromBundle())
        guard let profile = vault.load() else {
            completionHandler(TunnelFailure.missingProfile.nsError)
            return
        }
        guard profile.transport == .direct else {
            completionHandler(TunnelFailure.stealthComingSoon.nsError)
            return
        }
        // Start on the LAN endpoint when this network is the server's own LAN.
        let initial = EndpointSelector.choose(
            current: profile.endpoint,
            lan: profile.lanEndpoint,
            wan: profile.wanEndpoint,
            underlayIPv4s: InterfaceAddresses.underlayIPv4()
        )
        let configuration: TunnelConfiguration
        do {
            configuration = try WireGuardConfigBuilder.make(profile, endpoint: initial)
        } catch {
            completionHandler(TunnelFailure.invalidConfig.nsError)
            return
        }

        adapter.start(tunnelConfiguration: configuration) { [weak self] error in
            guard let self else { return }
            if let error {
                completionHandler(Self.failure(for: error).nsError)
                return
            }
            let roamer = EndpointRoamer(profile: profile, current: initial, adapter: self.adapter)
            roamer.start()
            self.roamer = roamer
            completionHandler(nil)
        }
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        roamer?.stop()
        roamer = nil
        adapter.stop { _ in
            completionHandler()
        }
    }

    /// Only RuntimeStats JSON leaves the extension, never the UAPI text.
    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        guard let completionHandler else { return }
        guard messageData.first == TunnelMessage.runtimeStats.rawValue else {
            completionHandler(nil)
            return
        }
        adapter.getRuntimeConfiguration { uapi in
            let stats = uapi.map { RuntimeStats.parse(uapi: $0) } ?? RuntimeStats()
            completionHandler(try? JSONEncoder().encode(stats))
        }
    }

    static func failure(for error: WireGuardAdapterError) -> TunnelFailure {
        if case .dnsResolution = error { return .dnsResolution }
        if case .setNetworkSettings = error { return .networkSettings }
        return .backend
    }
}

/// Builds a WireGuardKit configuration from the shared profile, including
/// the preshared key.
enum WireGuardConfigBuilder {
    static func make(_ profile: VPNProfile, endpoint: String? = nil) throws -> TunnelConfiguration {
        guard let privateKey = PrivateKey(base64Key: profile.privateKey),
              let serverKey = PublicKey(base64Key: profile.serverPublicKey) else {
            throw TunnelFailure.invalidConfig
        }
        var interface = InterfaceConfiguration(privateKey: privateKey)
        interface.addresses = try profile.normalizedAddresses.map {
            guard let range = IPAddressRange(from: $0) else { throw TunnelFailure.invalidConfig }
            return range
        }
        interface.dns = try profile.dns.map {
            guard let server = DNSServer(from: $0) else { throw TunnelFailure.invalidConfig }
            return server
        }
        interface.mtu = UInt16(clamping: profile.mtu)

        var peer = PeerConfiguration(publicKey: serverKey)
        if let psk = profile.presharedKey, !psk.isEmpty {
            guard let key = PreSharedKey(base64Key: psk) else { throw TunnelFailure.invalidConfig }
            peer.preSharedKey = key
        }
        peer.allowedIPs = try profile.allowedIPs.map {
            guard let range = IPAddressRange(from: $0) else { throw TunnelFailure.invalidConfig }
            return range
        }
        guard let wgEndpoint = WireGuardKit.Endpoint(from: endpoint ?? profile.endpoint) else {
            throw TunnelFailure.invalidConfig
        }
        peer.endpoint = wgEndpoint
        peer.persistentKeepAlive = UInt16(clamping: profile.persistentKeepalive)
        return TunnelConfiguration(name: "VeritasVPN", interface: interface, peers: [peer])
    }
}
