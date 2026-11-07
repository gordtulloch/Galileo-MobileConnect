import Foundation
import Security

/// What is kept about one paired Master. Lives only in a `SecureStore` (`MAPP-CON-040`).
public struct MasterCredentials: Codable, Sendable, Equatable, Identifiable {
    public var id: String { fingerprint }
    public var name: String
    public var address: MasterAddress
    public var token: String
    /// The pin: lower-case hex SHA-256 of the Master's certificate.
    public var fingerprint: String

    public init(name: String, address: MasterAddress, token: String, fingerprint: String) {
        self.name = name
        self.address = address
        self.token = token
        self.fingerprint = fingerprint
    }
}

/// Secrets are held by the platform, not the core (ADR-APP-007, `S-SEC`).
public protocol SecureStore: Sendable {
    func all() throws -> [MasterCredentials]
    func save(_ credentials: MasterCredentials) throws
    func delete(id: String) throws
}

public final class InMemorySecureStore: SecureStore {
    private let items = Locked<[String: MasterCredentials]>([:])
    public init() {}
    public func all() -> [MasterCredentials] { items.current.values.sorted { $0.name < $1.name } }
    public func save(_ credentials: MasterCredentials) { items.withLock { $0[credentials.id] = credentials } }
    public func delete(id: String) { items.withLock { _ = $0.removeValue(forKey: id) } }
}

/// One generic-password item per Master, usable after first unlock so the app can reconnect after a reboot,
/// and bound to this device: never synchronised, never in a backup (iOS SDD 3.3).
public struct KeychainSecureStore: SecureStore {
    public struct Failure: Error, Equatable { public let status: OSStatus }

    private let service: String

    public init(service: String = "com.gordtulloch.galileo.mobileconnect.masters") {
        self.service = service
    }

    private var base: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrSynchronizable as String: false,
            kSecUseDataProtectionKeychain as String: true,
        ]
    }

    public func all() throws -> [MasterCredentials] {
        var query = base
        query[kSecMatchLimit as String] = kSecMatchLimitAll
        query[kSecReturnData as String] = true
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return [] }
        guard status == errSecSuccess else { throw Failure(status: status) }
        return ((result as? [Data]) ?? [])
            .compactMap { try? JSONDecoder().decode(MasterCredentials.self, from: $0) }
            .sorted { $0.name < $1.name }
    }

    public func save(_ credentials: MasterCredentials) throws {
        let data = try JSONEncoder().encode(credentials)
        var query = base
        query[kSecAttrAccount as String] = credentials.id
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if status == errSecItemNotFound {
            query.merge(update) { _, new in new }
            status = SecItemAdd(query as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw Failure(status: status) }
    }

    public func delete(id: String) throws {
        var query = base
        query[kSecAttrAccount as String] = id
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Failure(status: status) }
    }
}
