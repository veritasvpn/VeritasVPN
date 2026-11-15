import Foundation
#if canImport(Security)
import Security
#endif

/// The signed-in account. Tokens live in the Keychain on device.
public struct StoredSession: Codable, Equatable, Sendable {
    public var accountId: String
    public var email: String?
    public var isAnonymous: Bool
    public var accessToken: String
    public var refreshToken: String

    public init(accountId: String, email: String?, isAnonymous: Bool, accessToken: String, refreshToken: String) {
        self.accountId = accountId
        self.email = email
        self.isAnonymous = isAnonymous
        self.accessToken = accessToken
        self.refreshToken = refreshToken
    }
}

public protocol CredentialStore: AnyObject, Sendable {
    func load() -> StoredSession?
    func save(_ session: StoredSession)
    func clear()
}

/// Test and preview store.
public final class InMemoryCredentialStore: CredentialStore, @unchecked Sendable {
    private let lock = NSLock()
    private var session: StoredSession?
    public private(set) var clearCount = 0

    public init(_ session: StoredSession? = nil) { self.session = session }

    public func load() -> StoredSession? { lock.lock(); defer { lock.unlock() }; return session }
    public func save(_ session: StoredSession) { lock.lock(); self.session = session; lock.unlock() }
    public func clear() { lock.lock(); session = nil; clearCount += 1; lock.unlock() }
}

#if canImport(Security)
/// Generic-password Keychain item holding one Codable value.
public struct KeychainItem: Sendable {
    public let service: String
    public let account: String
    /// Shared access group (`TEAMID.cloud.veritasvpn.vpn.shared`) or nil for
    /// the app's default group.
    public let accessGroup: String?

    public init(service: String, account: String, accessGroup: String? = nil) {
        self.service = service
        self.account = account
        self.accessGroup = accessGroup
    }

    private func baseQuery() -> [String: Any] {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if let accessGroup, !accessGroup.isEmpty, !accessGroup.hasPrefix("$(") {
            query[kSecAttrAccessGroup as String] = accessGroup
        }
        #if os(macOS)
        // The data protection keychain honours access groups on macOS too.
        query[kSecUseDataProtectionKeychain as String] = true
        #endif
        return query
    }

    public func read() -> Data? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    @discardableResult
    public func write(_ data: Data) -> Bool {
        let query = baseQuery()
        // Readable after first unlock so the tunnel can restart while locked.
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecSuccess { return true }
        if status != errSecItemNotFound { SecItemDelete(query as CFDictionary) }
        var insert = query
        for (key, value) in attributes { insert[key] = value }
        return SecItemAdd(insert as CFDictionary, nil) == errSecSuccess
    }

    public func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }

    public func readValue<T: Decodable>(_ type: T.Type) -> T? {
        guard let data = read() else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    @discardableResult
    public func writeValue<T: Encodable>(_ value: T) -> Bool {
        guard let data = try? JSONEncoder().encode(value) else { return false }
        return write(data)
    }
}

public final class KeychainCredentialStore: CredentialStore, @unchecked Sendable {
    private let item: KeychainItem
    private let lock = NSLock()

    public init(item: KeychainItem = KeychainItem(service: "cloud.veritasvpn.auth", account: "session")) {
        self.item = item
    }

    public func load() -> StoredSession? {
        lock.lock(); defer { lock.unlock() }
        return item.readValue(StoredSession.self)
    }

    public func save(_ session: StoredSession) {
        lock.lock(); defer { lock.unlock() }
        item.writeValue(session)
    }

    public func clear() {
        lock.lock(); defer { lock.unlock() }
        item.delete()
    }
}
#endif
