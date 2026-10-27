import Foundation
import NetFS

/// Cloudy as a network drive (menu "Mount as a drive"): macOS's own WebDAV client mounts the server's
/// `/dav/files/<user>/`, which then shows under Locations and on the desktop. The password is the device token,
/// an app password, which DAV accepts. macOS names the volume after the last path part, the user id.
enum DriveMount {
    private static let pathKey = "drivePath"

    /// Where the drive is mounted, if it still is.
    static var mounted: URL? {
        guard let path = UserDefaults.standard.string(forKey: pathKey) else { return nil }
        let volumes = FileManager.default.mountedVolumeURLs(includingResourceValuesForKeys: nil, options: []) ?? []
        return volumes.first { $0.standardizedFileURL.path == URL(fileURLWithPath: path).standardizedFileURL.path }
    }

    static func mount(server: String, user: String, token: String) async throws -> URL {
        if let url = mounted { return url }
        guard let id = user.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
              let url = URL(string: "\(server)/dav/files/\(id)/") else { throw MountError(EINVAL) }
        let path = try await Task.detached { () throws -> String in
            let options = NSMutableDictionary()
            options[kNAUIOptionKey as String] = kNAUIOptionNoUI as String // no macOS login dialog
            var points: Unmanaged<CFArray>?
            let status = NetFSMountURLSync(url as CFURL, nil, user as CFString, token as CFString, options, nil, &points)
            guard status == 0, let path = (points?.takeRetainedValue() as? [String])?.first else { throw MountError(status) }
            return path
        }.value
        UserDefaults.standard.set(path, forKey: pathKey)
        return URL(fileURLWithPath: path)
    }

    static func unmount() async {
        guard let url = mounted else { return }
        do {
            try await FileManager.default.unmountVolume(at: url)
        } catch {
            log.error("Ejecting the drive failed: \(error, privacy: .public)")
        }
    }
}

struct MountError: LocalizedError {
    let code: Int32
    init(_ code: Int32) { self.code = code }
    var errorDescription: String? { String(localized: "Could not mount the drive (error \(Int(code)))") }
}
