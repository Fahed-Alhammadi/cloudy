import Foundation
import Observation
import Security

/// Server address and signed-in user (UserDefaults); the device token lives in the Keychain.
@Observable
final class Session {
    static let shared = Session()

    private(set) var server: String
    private(set) var user: User?
    var isLoggedIn: Bool { user != nil && API.shared.token != nil }

    private let defaults = UserDefaults.standard

    private init() {
        server = defaults.string(forKey: "server") ?? ""
        user = defaults.data(forKey: "user").flatMap { try? JSONDecoder().decode(User.self, from: $0) }
        API.shared.server = server
        API.shared.token = Keychain.get("token")
        #if DEBUG
        // Simulator testing/screenshots: `SIMCTL_CHILD_CLOUDY_SERVER=… SIMCTL_CHILD_CLOUDY_TOKEN=… xcrun simctl launch …`
        let env = ProcessInfo.processInfo.environment
        if let s = env["CLOUDY_SERVER"], let t = env["CLOUDY_TOKEN"] {
            server = s
            API.shared.server = s
            API.shared.token = t
            user = User(id: "")
            Task { await refresh() }
        }
        #endif
        if API.shared.token == nil { user = nil }
        API.shared.onUnauthorized = { [weak self] in self?.signOut(revoke: false) }
    }

    var rootId: Int64 { user?.rootId ?? 0 }

    func signIn(server: String, token: String, user: User) {
        self.server = server
        defaults.set(server, forKey: "server")
        Keychain.set("token", token)
        API.shared.server = server
        API.shared.token = token
        update(user)
    }

    func update(_ user: User) {
        defaults.set(try? JSONEncoder().encode(user), forKey: "user")
        self.user = user
    }

    func refresh() async {
        if let me = try? await API.shared.me() { await MainActor.run { update(me) } }
    }

    /// `revoke`: also invalidate the token on the server (not when the server already rejected it).
    func signOut(revoke: Bool = true) {
        guard user != nil else { return }
        if revoke { Task { await API.shared.logout(); Keychain.delete("token") } } else { Keychain.delete("token") }
        API.shared.token = nil
        defaults.removeObject(forKey: "user")
        user = nil
        FileCache.clear()
    }
}

/// Minimal generic-password Keychain wrapper (this device only, readable after first unlock).
enum Keychain {
    private static func query(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "io.cloudy.app", kSecAttrAccount as String: key]
    }

    static func get(_ key: String) -> String? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func set(_ key: String, _ value: String) {
        delete(key)
        var q = query(key)
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete(_ key: String) { SecItemDelete(query(key) as CFDictionary) }
}
