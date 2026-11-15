import Foundation
import NetworkExtension
import VeritasCore

/// Owns the system VPN configuration (NETunnelProviderManager) for the
/// VeritasVPN packet tunnel. The configuration holds no secrets: the profile
/// (keys, endpoints) is in the shared Keychain, see `ProfileVault`.
@MainActor
final class TunnelController: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .invalid

    private let shared: SharedConfiguration
    private var manager: NETunnelProviderManager?
    private var statusObserver: NSObjectProtocol?

    init(shared: SharedConfiguration) {
        self.shared = shared
    }

    deinit {
        if let statusObserver { NotificationCenter.default.removeObserver(statusObserver) }
    }

    private var tunnelBundleIdentifier: String {
        if !shared.tunnelBundleIdentifier.isEmpty { return shared.tunnelBundleIdentifier }
        return (Bundle.main.bundleIdentifier ?? "cloud.veritasvpn.vpn") + ".tunnel"
    }

    /// Finds an existing VeritasVPN configuration without prompting.
    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first {
            ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == tunnelBundleIdentifier
        }
        observe()
    }

    /// Creates or refreshes the configuration. The first save shows the
    /// system "Add VPN Configurations" prompt.
    func install() async throws {
        let target = manager ?? NETunnelProviderManager()
        let proto = (target.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = tunnelBundleIdentifier
        // Shown in Settings → VPN. Never an IP address.
        proto.serverAddress = "VeritasVPN"
        proto.providerConfiguration = ["format": 1]
        proto.disconnectOnSleep = false
        target.protocolConfiguration = proto
        target.localizedDescription = "VeritasVPN"
        target.isEnabled = true
        // No automatic reconnects or network switching without the user.
        target.isOnDemandEnabled = false
        do {
            try await target.saveToPreferences()
            // A fresh load is required before the first start after a save.
            try await target.loadFromPreferences()
        } catch {
            throw Self.configurationError(error)
        }
        manager = target
        observe()
    }

    func start() throws {
        guard let session = manager?.connection as? NETunnelProviderSession else {
            throw TunnelFailure.missingProfile
        }
        do {
            try session.startTunnel(options: nil)
        } catch {
            throw Self.configurationError(error)
        }
    }

    func stop() {
        manager?.connection.stopVPNTunnel()
    }

    /// Byte counters and handshake time from the extension.
    func runtimeStats() async -> RuntimeStats? {
        guard let session = manager?.connection as? NETunnelProviderSession,
              status == .connected || status == .reasserting else { return nil }
        return await withCheckedContinuation { continuation in
            do {
                try session.sendProviderMessage(Data([TunnelMessage.runtimeStats.rawValue])) { data in
                    let stats = data.flatMap { try? JSONDecoder().decode(RuntimeStats.self, from: $0) }
                    continuation.resume(returning: stats)
                }
            } catch {
                continuation.resume(returning: nil)
            }
        }
    }

    /// Why the last attempt ended, as reported by the extension.
    func lastDisconnectError() async -> Error? {
        guard let connection = manager?.connection else { return nil }
        return await withCheckedContinuation { continuation in
            connection.fetchLastDisconnectError { error in
                continuation.resume(returning: error)
            }
        }
    }

    private func observe() {
        if let statusObserver { NotificationCenter.default.removeObserver(statusObserver) }
        guard let connection = manager?.connection else {
            status = .invalid
            return
        }
        status = connection.status
        statusObserver = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange,
            object: connection,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.status = self.manager?.connection.status ?? .invalid
            }
        }
    }

    /// Maps NetworkExtension errors to fixed, descriptive failures.
    static func configurationError(_ error: Error) -> Error {
        let ns = error as NSError
        if ns.domain == NEVPNErrorDomain, ns.code == NEVPNError.Code.configurationReadWriteFailed.rawValue {
            return ConfigurationError.permissionDenied
        }
        return ConfigurationError.saveFailed
    }

    enum ConfigurationError: Error, UserVisibleError {
        case permissionDenied
        case saveFailed

        var userMessage: String {
            switch self {
            case .permissionDenied: return Messages.vpnPermissionDenied
            case .saveFailed: return Messages.vpnConfigurationFailed
            }
        }
    }
}
