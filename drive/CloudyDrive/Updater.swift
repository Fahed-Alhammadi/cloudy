import AppKit
import CryptoKit
import Security

/// A newer `drive-v*` GitHub release and its `.dmg`.
struct Release {
    var version: String
    var page: URL
    var dmg: URL?
    /// Lowercase hex SHA-256 of the `.dmg`, as GitHub reports it.
    var sha256: String?
}

/// Finds the newest `drive-v*` release (this repository also has server, app and desktop releases), then downloads,
/// verifies and swaps in the new app and relaunches. Same approach as the RemoteSSH Mac updater.
@MainActor
enum Updater {
    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    /// The newest release if it is newer than this app, nil when up to date.
    static func newer() async throws -> Release? {
        struct Asset: Decodable { var name: String; var browser_download_url: URL; var digest: String? }
        struct Item: Decodable { var tag_name: String; var html_url: URL; var draft: Bool; var prerelease: Bool; var assets: [Asset] }
        var request = URLRequest(url: URL(string: "https://api.github.com/repos/Fahed-Alhammadi/cloudy/releases?per_page=50")!)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.timeoutInterval = 20
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw UpdateError(String(localized: "Download failed (HTTP \(status))")) }
        let newest = try JSONDecoder().decode([Item].self, from: data)
            .filter { !$0.draft && !$0.prerelease && $0.tag_name.hasPrefix("drive-v") }
            .max { $0.tag_name.compare($1.tag_name, options: .numeric) == .orderedAscending }
        guard let newest else { return nil }
        let version = String(newest.tag_name.dropFirst(7))
        guard version.compare(currentVersion, options: .numeric) == .orderedDescending else { return nil }
        let asset = newest.assets.first { $0.name.hasSuffix(".dmg") }
        let digest = asset?.digest.flatMap { $0.hasPrefix("sha256:") ? String($0.dropFirst(7)).lowercased() : nil }
        return Release(version: version, page: newest.html_url, dmg: asset?.browser_download_url, sha256: digest)
    }

    /// Asks, then installs and relaunches. Without a `.dmg`, or when the app can't replace itself, offers the release page.
    static func offer(_ release: Release, ask: Bool = true) async {
        let app = Bundle.main.bundleURL
        let title = String(localized: "Cloudy Drive \(release.version) is available")
        guard let dmg = release.dmg, let sha256 = release.sha256, installBlocker(app) == nil else {
            let note = installBlocker(app) ?? String(localized: "You have version \(currentVersion). Download it from GitHub.")
            if confirm(title, note, buttons: [String(localized: "Open Download Page"), String(localized: "Later")]) {
                NSWorkspace.shared.open(release.page)
            }
            return
        }
        if ask {
            guard confirm(title, String(localized: "You have version \(currentVersion). Download and install it now? Cloudy Drive will relaunch."),
                          buttons: [String(localized: "Install and Relaunch"), String(localized: "Later")]) else { return }
        }
        do {
            let newApp = try await fetch(dmg, sha256: sha256)
            try relaunch(replacing: app, with: newApp)
        } catch {
            log.error("Update failed: \(error, privacy: .public)")
            alert(String(localized: "Could not update"), error.localizedDescription, style: .warning)
        }
    }

    /// The "Check for Updates…" menu item: also reports "up to date" and errors.
    static func checkNow() async -> Release? {
        do {
            if let release = try await newer() {
                await offer(release)
                return release
            }
            alert(String(localized: "You're up to date"), String(localized: "Cloudy Drive \(currentVersion) is the latest version."))
        } catch {
            alert(String(localized: "Could not update"), error.localizedDescription, style: .warning)
        }
        return nil
    }

    /// Why the running app can't be replaced in place, or nil when it can.
    private static func installBlocker(_ app: URL) -> String? {
        if app.path.contains("/AppTranslocation/") {
            return String(localized: "You have version \(currentVersion). Move Cloudy Drive to the Applications folder to install updates.")
        }
        if !FileManager.default.isWritableFile(atPath: app.deletingLastPathComponent().path) {
            return String(localized: "You have version \(currentVersion). Cloudy Drive can't replace itself in this folder; download the new version instead.")
        }
        return nil
    }

    /// Downloads the `.dmg`, checks its checksum, copies the app out of it and checks the bundle ID and code signature.
    /// When this app is signed by a team, the update must be signed by the same team.
    private static func fetch(_ url: URL, sha256: String) async throws -> URL {
        let (file, response) = try await URLSession.shared.download(from: url)
        defer { try? FileManager.default.removeItem(at: file) }
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw UpdateError(String(localized: "Download failed (HTTP \(status))")) }
        let digest = SHA256.hash(data: try Data(contentsOf: file, options: .mappedIfSafe))
        guard digest.map({ String(format: "%02x", $0) }).joined() == sha256 else {
            throw UpdateError(String(localized: "The download is damaged (checksum mismatch)."))
        }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("CloudyDrive-update-\(UUID().uuidString)")
        let mount = dir.appendingPathComponent("mount")
        let newApp = dir.appendingPathComponent(Bundle.main.bundleURL.lastPathComponent)
        do {
            try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
            try run("/usr/bin/hdiutil", ["attach", file.path, "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.path])
            defer { try? run("/usr/bin/hdiutil", ["detach", mount.path, "-force"]) }
            try run("/usr/bin/ditto", [mount.appendingPathComponent("Cloudy Drive.app").path, newApp.path])
            guard Bundle(url: newApp)?.bundleIdentifier == Bundle.main.bundleIdentifier else {
                throw UpdateError(String(localized: "The download does not contain Cloudy Drive."))
            }
            try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", newApp.path])
            if let team = teamIdentifier(of: Bundle.main.bundleURL), !isSigned(newApp, byTeam: team) {
                throw UpdateError(String(localized: "The download is not signed by the Cloudy Drive developer."))
            }
            return newApp
        } catch {
            try? FileManager.default.removeItem(at: dir)
            throw error
        }
    }

    /// A detached shell waits for this process to quit, swaps the bundles (restoring on failure), stops the old
    /// File Provider extension (macOS starts the new one when Finder next needs it) and reopens the app.
    private static func relaunch(replacing app: URL, with newApp: URL) throws {
        let script = """
        while kill -0 "$1" 2>/dev/null; do sleep 0.2; done
        rm -rf "$2.old"
        if mv "$2" "$2.old" && mv "$3" "$2"; then rm -rf "$2.old"; else mv "$2.old" "$2"; fi
        rm -rf "$(dirname "$3")"
        pkill -f "$2/Contents/PlugIns/Provider.appex/" 2>/dev/null
        xattr -dr com.apple.quarantine "$2" 2>/dev/null
        open "$2"
        """
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = ["-c", script, "sh", String(ProcessInfo.processInfo.processIdentifier), app.path, newApp.path]
        try process.run()
        NSApp.terminate(nil)
    }

    private static func run(_ tool: String, _ arguments: [String]) throws {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else {
            log.error("\(tool, privacy: .public) failed with status \(process.terminationStatus)")
            throw UpdateError(String(localized: "Could not verify the downloaded app."))
        }
    }

    /// The Team ID in an app's signature, or nil for ad-hoc and unsigned builds.
    private static func teamIdentifier(of app: URL) -> String? {
        var code: SecStaticCode?
        var info: CFDictionary?
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code,
              SecCodeCopySigningInformation(code, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess
        else { return nil }
        return (info as? [String: Any])?[kSecCodeInfoTeamIdentifier as String] as? String
    }

    /// Whether `app` has a valid signature from an Apple-issued certificate of `team`.
    private static func isSigned(_ app: URL, byTeam team: String) -> Bool {
        var code: SecStaticCode?
        var requirement: SecRequirement?
        let text = "anchor apple generic and certificate leaf[subject.OU] = \"\(team)\"" as CFString
        guard SecStaticCodeCreateWithPath(app as CFURL, [], &code) == errSecSuccess, let code,
              SecRequirementCreateWithString(text, [], &requirement) == errSecSuccess else { return false }
        let flags = SecCSFlags(rawValue: kSecCSCheckAllArchitectures | kSecCSStrictValidate | kSecCSCheckNestedCode)
        return SecStaticCodeCheckValidity(code, flags, requirement) == errSecSuccess
    }

    /// A menu bar app has no window in front, so it activates itself before showing an alert.
    private static func confirm(_ title: String, _ message: String, buttons: [String]) -> Bool {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = title
        alert.informativeText = message
        buttons.forEach { alert.addButton(withTitle: $0) }
        return alert.runModal() == .alertFirstButtonReturn
    }

    private static func alert(_ title: String, _ message: String, style: NSAlert.Style = .informational) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.alertStyle = style
        alert.messageText = title
        alert.informativeText = message
        alert.runModal()
    }
}

struct UpdateError: LocalizedError {
    let errorDescription: String?
    init(_ message: String) { errorDescription = message }
}
