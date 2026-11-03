import Foundation
import CryptoKit
import Security

enum VaultError: Error {
    case keychain(OSStatus)
    case missingKey
    case duplicateKey
    case publicKeyMismatch
}

struct KeychainBlobStore: Sendable {
    let service: String

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account,
         kSecAttrSynchronizable as String: false]
    }

    func insert(_ data: Data, account: String) throws {
        var attributes = query(account)
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        if status == errSecDuplicateItem { throw VaultError.duplicateKey }
        guard status == errSecSuccess else { throw VaultError.keychain(status) }
    }

    func read(account: String) throws -> Data {
        var attributes = query(account)
        attributes[kSecReturnData as String] = true
        attributes[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(attributes as CFDictionary, &result)
        if status == errSecItemNotFound { throw VaultError.missingKey }
        guard status == errSecSuccess, let data = result as? Data else {
            throw VaultError.keychain(status)
        }
        return data
    }
}

struct PublicKeyInfo: Sendable {
    let sec1Hex: String
    let spkiHex: String
}

/// Software P-256 key in Keychain. This is not a Secure Enclave key.
/// Keychain calls run inside the actor, not inside SwiftUI rendering.
actor P256Signer {
    private let store: KeychainBlobStore
    private let account: String

    init(service: String, account: String) {
        self.store = KeychainBlobStore(service: service)
        self.account = account
    }

    /// Explicit onboarding action only. Duplicate aliases are never overwritten.
    func createNew() throws -> PublicKeyInfo {
        let key = P256.Signing.PrivateKey()
        try store.insert(key.rawRepresentation, account: account)
        return info(key)
    }

    /// Explicit migration action. Never use real key material as a bundled resource.
    func importPEM(_ pem: String, expectedSpkiHex: String) throws -> PublicKeyInfo {
        let key = try P256.Signing.PrivateKey(pemRepresentation: pem)
        guard key.publicKey.derRepresentation.lowerHex == expectedSpkiHex.lowercased() else {
            throw VaultError.publicKeyMismatch
        }
        try store.insert(key.rawRepresentation, account: account)
        return info(key)
    }

    func sign(message: String, expectedSpkiHex: String) throws -> String {
        let key = try load()
        guard key.publicKey.derRepresentation.lowerHex == expectedSpkiHex.lowercased() else {
            throw VaultError.publicKeyMismatch
        }
        // Data overload performs SHA-256 internally. Do not pass a hex digest here.
        let signature = try key.signature(for: Data(message.utf8))
        return signature.rawRepresentation.lowerHex // exactly 64 bytes, r || s
    }

    func publicKeyInfo() throws -> PublicKeyInfo { info(try load()) }

    private func load() throws -> P256.Signing.PrivateKey {
        try P256.Signing.PrivateKey(rawRepresentation: store.read(account: account))
    }

    private func info(_ key: P256.Signing.PrivateKey) -> PublicKeyInfo {
        PublicKeyInfo(sec1Hex: key.publicKey.x963Representation.lowerHex,
                      spkiHex: key.publicKey.derRepresentation.lowerHex)
    }
}

extension Data {
    var lowerHex: String { map { String(format: "%02x", $0) }.joined() }
}

func utc14(_ date: Date) -> String {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.calendar = Calendar(identifier: .gregorian)
    formatter.timeZone = TimeZone(secondsFromGMT: 0)
    formatter.dateFormat = "yyyyMMddHHmmss"
    return formatter.string(from: date)
}
