import Combine
import Foundation
import NetworkExtension
import SwiftUI
import VeritasCore

/// App state and flows. Mirrors Android's MainViewModel: one-tap connect,
/// plan check, Veritas Shield, sign-out rules. Every message it publishes is
/// a descriptive English sentence (a localization key), never a raw code.
@MainActor
final class AppModel: ObservableObject {
    enum Screen: Equatable { case launching, auth, home }

    // Session
    @Published var screen: Screen = .launching
    @Published private(set) var session: StoredSession?
    /// A background call was rejected: offer "Sign in again", never sign out.
    @Published private(set) var signInAgain = false
    @Published var authNotice: String?
    /// Shown once after an anonymous account is created.
    @Published var revealAccountId: String?

    // Plan
    @Published private(set) var billing: BillingStatus?
    @Published private(set) var billingReady = false
    @Published private(set) var billingError: String?

    // Connection
    @Published private(set) var connecting = false
    @Published private(set) var connected = false
    @Published private(set) var reconnecting = false
    @Published private(set) var handshakeOK = false
    @Published private(set) var stats = RuntimeStats()
    @Published private(set) var connectedSince: Date?
    @Published private(set) var shieldBlocks: ShieldBlocks?
    @Published var statusMessage: String?
    @Published var showDisclosure = false

    // Settings (persisted)
    @Published var stealthMode: StealthMode {
        didSet { defaults.set(stealthMode.rawValue, forKey: Keys.stealth) }
    }
    @Published var shield: ShieldPolicy {
        didSet {
            guard shield != oldValue else { return }
            persist(shield, key: Keys.shield)
            pushShield()
        }
    }
    @Published var excludeLAN: Bool {
        didSet { defaults.set(excludeLAN, forKey: Keys.excludeLAN) }
    }
    @Published private(set) var disclosureAccepted: Bool

    let api: APIClient
    let sessionManager: SessionManager
    let auth: AuthService
    let peers: PeerService
    let tunnel: TunnelController
    let store: SubscriptionStore

    private let vault: ProfileVault
    private let defaults: UserDefaults
    private var connectTask: Task<Void, Never>?
    private var pollTask: Task<Void, Never>?
    private var watchdogTask: Task<Void, Never>?
    private var statusCancellable: AnyCancellable?
    private var currentPeerId: String?
    private var userWantsConnection = false
    private var previousStatus: NEVPNStatus = .invalid
    private var bootstrapped = false

    static let firstConnectTimeout: TimeInterval = 25
    /// WireGuard rekeys every 2 minutes; a handshake older than this means
    /// the tunnel is not carrying traffic right now.
    static let staleHandshake: TimeInterval = 200

    private enum Keys {
        static let stealth = "veritas.stealthMode"
        static let shield = "veritas.shield"
        static let excludeLAN = "veritas.excludeLAN"
        static let disclosure = "veritas.vpnDisclosureAccepted"
        static let deviceId = "veritas.deviceId"
        static let premiumCache = "veritas.premiumCache."
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        let shared = SharedConfiguration.fromBundle()
        let api = APIClient(baseURL: APIClient.productionBaseURL, clientName: Platform.clientName)
        let manager = SessionManager(store: KeychainCredentialStore(), api: api)
        self.api = api
        self.sessionManager = manager
        self.auth = AuthService(api: api, session: manager)
        self.peers = PeerService(api: AuthorizedAPI(api: api, session: manager))
        self.tunnel = TunnelController(shared: shared)
        self.vault = ProfileVault(configuration: shared)
        self.store = SubscriptionStore()
        self.stealthMode = StealthMode(rawValue: defaults.string(forKey: Keys.stealth) ?? "") ?? .auto
        self.shield = Self.restore(ShieldPolicy.self, key: Keys.shield, defaults: defaults) ?? ShieldPolicy()
        self.excludeLAN = defaults.object(forKey: Keys.excludeLAN) as? Bool ?? true
        self.disclosureAccepted = defaults.bool(forKey: Keys.disclosure)
    }

    // MARK: - Derived state

    var isPremium: Bool { billing?.isPremium ?? false }

    var heroPhase: HeroPhase {
        HeroLogic.phase(
            connected: connected, connecting: connecting, reconnecting: reconnecting,
            handshakeOK: handshakeOK, billingReady: billingReady, isPremium: isPremium
        )
    }

    var heroAction: HeroAction {
        HeroLogic.mainAction(
            connecting: connecting, billingReady: billingReady, connected: connected,
            signInAgain: signInAgain, isPremium: isPremium, reconnecting: reconnecting
        )
    }

    var deviceId: String {
        if let id = defaults.string(forKey: Keys.deviceId), !id.isEmpty { return id }
        // Random install id, not a hardware identifier.
        let id = UUID().uuidString.lowercased()
        defaults.set(id, forKey: Keys.deviceId)
        return id
    }

    // MARK: - Lifecycle

    func bootstrap() async {
        guard !bootstrapped else { return }
        bootstrapped = true
        await sessionManager.setSignedOutHandler { [weak self] in
            Task { @MainActor [weak self] in self?.sessionEnded() }
        }
        statusCancellable = tunnel.$status.sink { [weak self] status in
            Task { @MainActor [weak self] in self?.handle(status: status) }
        }
        await tunnel.load()
        session = await sessionManager.currentSession()
        if session == nil {
            screen = .auth
            return
        }
        screen = .home
        // A tunnel left running from an earlier launch stays connected.
        if let profile = vault.load(), tunnel.status == .connected || tunnel.status == .reasserting {
            currentPeerId = profile.peerId
            userWantsConnection = true
            connected = true
            connectedSince = Date()
            startPolling()
        }
        await refreshAccount()
    }

    /// Called when the app returns to the foreground.
    func becameActive() async {
        guard screen == .home else { return }
        await refreshAccount()
    }

    func refreshAccount() async {
        if let updated = await auth.refreshStoredEmail() { session = updated }
        await refreshBilling()
    }

    func refreshBilling() async {
        guard session != nil else { return }
        do {
            let status = try await peers.billingStatus(background: true)
            billing = status
            billingReady = true
            billingError = nil
            signInAgain = false
            if let id = session?.accountId { defaults.set(status.isPremium, forKey: Keys.premiumCache + id) }
        } catch APIError.backgroundAuth {
            signInAgain = true
            billingReady = true
        } catch {
            // Use the last known plan for this account so Connect stays one tap.
            if let id = session?.accountId, let cached = defaults.object(forKey: Keys.premiumCache + id) as? Bool {
                billing = billing ?? BillingStatus(isPremium: cached)
                billingReady = true
            } else {
                billingReady = false
            }
            billingError = ErrorText.message(for: error)
        }
    }

    // MARK: - Auth

    func signedIn(_ newSession: StoredSession, revealAccountId: Bool = false) {
        session = newSession
        signInAgain = false
        authNotice = nil
        billing = nil
        billingReady = false
        billingError = nil
        if revealAccountId { self.revealAccountId = newSession.accountId }
        screen = .home
        Task { await refreshAccount() }
    }

    /// Foreground refresh was rejected: the session is really over.
    private func sessionEnded() {
        tearDownConnection(deletePeer: false)
        session = nil
        billing = nil
        billingReady = false
        signInAgain = false
        authNotice = Messages.sessionExpired
        screen = .auth
    }

    /// "Sign in again" from the hero or banner: return to sign-in without
    /// waiting for a server answer.
    func requestSignInAgain() {
        signOutThisDevice()
    }

    func signOutThisDevice() {
        tearDownConnection(deletePeer: true)
        Task {
            await sessionManager.signOut()
        }
        session = nil
        billing = nil
        billingReady = false
        signInAgain = false
        screen = .auth
    }

    /// Returns a descriptive error sentence, or nil on success.
    func signOutAllDevices() async -> String? {
        do {
            tearDownConnection(deletePeer: true)
            try await auth.logoutAllSessions()
            session = nil
            billing = nil
            billingReady = false
            screen = .auth
            return nil
        } catch {
            return ErrorText.message(for: error)
        }
    }

    func deleteAccount(password: String, turnstileToken: String) async throws {
        try await auth.deleteAccount(password: password, turnstileToken: turnstileToken)
        tearDownConnection(deletePeer: false)
        session = nil
        billing = nil
        billingReady = false
        screen = .auth
    }

    // MARK: - Connect

    func performHeroAction() {
        switch heroAction {
        case .signIn: requestSignInAgain()
        case .disconnect: disconnect()
        case .connect: connect()
        case .plans: statusMessage = nil
        case .none:
            if billingError != nil { Task { await refreshBilling() } }
        }
    }

    func acceptDisclosure() {
        disclosureAccepted = true
        defaults.set(true, forKey: Keys.disclosure)
        showDisclosure = false
        connect()
    }

    func connect() {
        guard !connecting, !connected else { return }
        if !disclosureAccepted {
            showDisclosure = true
            return
        }
        if stealthMode == .stealthAlways {
            // No silent fallback to Direct: the user picks the transport.
            statusMessage = Messages.stealthComingSoon
            return
        }
        statusMessage = nil
        connecting = true
        handshakeOK = false
        userWantsConnection = true
        connectTask?.cancel()
        connectTask = Task { [weak self] in
            await self?.runConnect()
        }
    }

    private func runConnect() async {
        do {
            let keys = WireGuardKeyPair()
            let peer = try await peers.createPeer(
                publicKey: keys.publicKey,
                deviceId: deviceId,
                metadata: Platform.deviceMetadata(),
                shield: shield
            )
            currentPeerId = peer.peerId
            try Task.checkCancellation()
            let profile = try VPNProfile.make(peer: peer, privateKey: keys.privateKey, excludeLAN: excludeLAN)
            guard vault.save(profile) else { throw TunnelController.ConfigurationError.saveFailed }
            try await tunnel.install()
            try Task.checkCancellation()
            try tunnel.start()
            startWatchdog()
        } catch is CancellationError {
            return
        } catch {
            failConnect(message: ErrorText.connectMessage(for: error, wantedStealth: false))
        }
    }

    func disconnect() {
        userWantsConnection = false
        statusMessage = nil
        tearDownConnection(deletePeer: true)
    }

    private func failConnect(message: String) {
        userWantsConnection = false
        tearDownConnection(deletePeer: true)
        statusMessage = message
    }

    private func tearDownConnection(deletePeer: Bool) {
        connectTask?.cancel()
        connectTask = nil
        watchdogTask?.cancel()
        watchdogTask = nil
        pollTask?.cancel()
        pollTask = nil
        tunnel.stop()
        let peerId = currentPeerId
        currentPeerId = nil
        connecting = false
        connected = false
        reconnecting = false
        handshakeOK = false
        connectedSince = nil
        stats = RuntimeStats()
        shieldBlocks = nil
        let vault = self.vault
        let peers = self.peers
        Task {
            // Let the tunnel stop, then forget this connection's keys. A newer
            // profile written by a quick reconnect is left alone.
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            if let stored = vault.load(), stored.peerId == peerId {
                vault.clear()
            }
            if deletePeer, let peerId { await peers.deletePeer(id: peerId) }
        }
    }

    /// First connect gives up after 25 s without a handshake, like Android.
    private func startWatchdog() {
        watchdogTask?.cancel()
        watchdogTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(Self.firstConnectTimeout * 1_000_000_000))
            guard !Task.isCancelled, let self else { return }
            if self.connecting && !self.connected {
                self.failConnect(message: Messages.handshakeTimeout)
            }
        }
    }

    private func handle(status: NEVPNStatus) {
        defer { previousStatus = status }
        switch status {
        case .connecting:
            if connected { reconnecting = true }
        case .connected:
            reconnecting = false
            startPolling()
        case .reasserting:
            if connected { reconnecting = true }
        case .disconnected, .invalid:
            guard userWantsConnection, previousStatus != .disconnected, previousStatus != .invalid else { return }
            // The tunnel ended without the user asking: explain why.
            userWantsConnection = false
            Task { [weak self] in
                guard let self else { return }
                let reason = await self.tunnel.lastDisconnectError()
                let message = reason.map { ErrorText.connectMessage(for: $0, wantedStealth: false) }
                self.failConnect(message: message ?? Messages.tunnelStartFailed)
            }
        case .disconnecting:
            break
        @unknown default:
            break
        }
    }

    // MARK: - Live stats and Shield counts

    private func startPolling() {
        guard pollTask == nil else { return }
        pollTask = Task { [weak self] in
            var tick = 0
            while !Task.isCancelled {
                guard let self else { return }
                await self.pollStats()
                if tick % 30 == 0 { await self.pollShield() }
                tick += 1
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func pollStats() async {
        guard let latest = await tunnel.runtimeStats() else { return }
        stats = latest
        let age = Date().timeIntervalSince1970 - latest.lastHandshakeEpoch
        let fresh = latest.hasHandshake && age < Self.staleHandshake
        handshakeOK = fresh
        if fresh && connecting {
            connecting = false
            connected = true
            connectedSince = Date()
            watchdogTask?.cancel()
            watchdogTask = nil
        }
    }

    private func pollShield() async {
        guard connected, let peerId = currentPeerId else { return }
        if let info = try? await peers.peerInfo(id: peerId), let blocks = info.shieldBlocks {
            shieldBlocks = blocks
        }
    }

    private func pushShield() {
        guard connected, let peerId = currentPeerId else { return }
        let policy = shield
        Task { [weak self] in
            guard let self else { return }
            do {
                try await self.peers.updateShield(peerId: peerId, policy: policy)
            } catch {
                self.statusMessage = "Couldn't update Veritas Shield. Your change applies the next time you connect."
            }
        }
    }

    // MARK: - Persistence helpers

    private func persist<T: Encodable>(_ value: T, key: String) {
        if let data = try? JSONEncoder().encode(value) { defaults.set(data, forKey: key) }
    }

    private static func restore<T: Decodable>(_ type: T.Type, key: String, defaults: UserDefaults) -> T? {
        guard let data = defaults.data(forKey: key) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }
}
