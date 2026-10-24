import Foundation

extension Notification.Name {
    /// Posted on the main thread with a `ChangeEvent` as `object`.
    static let cloudyChange = Notification.Name("cloudyChange")
}

/// Live updates: one Server-Sent Events stream while the app is in the foreground. Screens listen for
/// `.cloudyChange` and reload when their folder changed. Duplicate or early events just cause a reload.
enum Live {
    /// Runs until the task is cancelled; reconnects after network errors.
    static func run() async {
        while !Task.isCancelled {
            do {
                try await API.shared.streamEvents { ev in
                    DispatchQueue.main.async { NotificationCenter.default.post(name: .cloudyChange, object: ev) }
                }
            } catch let e as APIError where e.status == 401 || e.status == 404 {
                return // signed out, or a server without live updates
            } catch {
                // network error: retry below
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
        }
    }
}

/// Downloaded copies for preview and sharing, in Caches (the system may purge them).
/// Keyed by node id + etag so a changed file is fetched again.
enum FileCache {
    static var root: URL { FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("files") }

    static func url(for node: FileNode) -> URL {
        let tag = (node.etag ?? "").filter { $0.isLetter || $0.isNumber }
        let name = node.isDir ? node.name + ".zip" : node.name
        return root.appendingPathComponent("\(node.id)-\(tag)").appendingPathComponent(name)
    }

    /// Returns a local copy, downloading it when missing.
    static func fetch(_ node: FileNode) async throws -> URL {
        let dest = url(for: node)
        if FileManager.default.fileExists(atPath: dest.path) { return dest }
        try? FileManager.default.removeItem(at: dest.deletingLastPathComponent())
        try await API.shared.download(node.id, to: dest)
        return dest
    }

    static func clear() { try? FileManager.default.removeItem(at: root) }

    /// A scratch file for uploads/exports in tmp (cleaned by the system).
    static func tempURL(_ name: String) -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(name)
    }
}
