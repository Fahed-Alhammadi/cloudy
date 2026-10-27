import FileProvider
import Foundation
import os

/// Console.app: subsystem io.cloudy.drive (menu bar app and extension).
let log = Logger(subsystem: "io.cloudy.drive", category: Bundle.main.bundleURL.pathExtension == "appex" ? "provider" : "app")

/// The signed-in account, shared by the menu bar app and the File Provider extension through the App Group:
/// server + user in the group's UserDefaults, the device token in a file only these two can read (macOS keeps other
/// apps out of a group container; a shared Keychain item would need a provisioning profile).
enum Account {
    /// `<TeamID>.io.cloudy.drive`, from Info.plist (`CloudyAppGroup`).
    static let group = Bundle.main.object(forInfoDictionaryKey: "CloudyAppGroup") as? String ?? "io.cloudy.drive"
    static let defaults = UserDefaults(suiteName: group) ?? .standard
    /// The one Finder location ("Cloudy" in the sidebar).
    static let domain: NSFileProviderDomain = {
        let d = NSFileProviderDomain(identifier: NSFileProviderDomainIdentifier("cloudy"), displayName: "Cloudy")
        d.supportsSyncingTrash = false // Finder's Move to Trash deletes on the server, which has its own trash
        return d
    }()

    static var server: String { defaults.string(forKey: "server") ?? "" }
    static var user: User? { defaults.data(forKey: "user").flatMap { try? JSONDecoder().decode(User.self, from: $0) } }
    static var token: String? {
        (try? String(contentsOf: tokenFile, encoding: .utf8)).flatMap { $0.isEmpty ? nil : $0 }
    }
    private static var tokenFile: URL { container.appendingPathComponent("token") }
    static var rootId: Int64 { user?.rootId ?? 0 }

    /// Points `API.shared` at the stored account. Returns false when signed out.
    @discardableResult
    static func load() -> Bool {
        API.shared.server = server
        API.shared.token = token
        return user != nil && API.shared.token != nil
    }

    static func save(server: String, token: String, user: User) {
        defaults.set(server, forKey: "server")
        do {
            try FileManager.default.createDirectory(at: container, withIntermediateDirectories: true)
            try Data(token.utf8).write(to: tokenFile, options: [.atomic, .completeFileProtection])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: tokenFile.path)
        } catch {
            log.error("Saving the token failed: \(error, privacy: .public)")
        }
        update(user)
        load()
    }

    static func update(_ user: User) { defaults.set(try? JSONEncoder().encode(user), forKey: "user") }

    static func clear() {
        defaults.removeObject(forKey: "user")
        try? FileManager.default.removeItem(at: tokenFile)
        API.shared.token = nil
        Index.reset()
    }

    /// Shared container (extension state, see `Index`).
    static var container: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: group)
            ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }
}
