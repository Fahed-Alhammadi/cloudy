import Foundation

/// What the extension last reported to macOS, per folder the system has listed: the folder's etag and a version
/// key per child. Change enumeration walks down from the root and re-reads only folders whose etag moved (etags
/// change all the way up the tree on every edit), like the desktop app's folder-etag listing cache.
/// Kept as JSON in the App Group container; the menu bar app only resets it on sign out.
final class Index: @unchecked Sendable {
    struct Folder: Codable {
        var etag: String
        /// child item identifier → version key (`Item.versionKey`)
        var children: [String: String]
    }

    private struct State: Codable {
        var session = UUID().uuidString
        var counter = 0
        var folders: [String: Folder] = [:]
    }

    static let shared = Index()
    private static var file: URL { Account.container.appendingPathComponent("index.json") }

    private let lock = NSLock()
    private var state: State

    private init() {
        state = (try? Data(contentsOf: Index.file)).flatMap { try? JSONDecoder().decode(State.self, from: $0) } ?? State()
    }

    static func reset() {
        try? FileManager.default.removeItem(at: file)
        shared.lock.withLock { shared.state = State() }
    }

    func folder(_ id: String) -> Folder? { lock.withLock { state.folders[id] } }

    func record(_ id: String, etag: String, children: [String: String]) {
        lock.withLock {
            state.folders[id] = Folder(etag: etag, children: children)
            save()
        }
    }

    /// Drops folders (and everything known below them) that no longer exist.
    func forget(_ ids: some Sequence<String>) {
        lock.withLock {
            var queue = Array(ids)
            while let id = queue.popLast() {
                if let f = state.folders.removeValue(forKey: id) { queue.append(contentsOf: f.children.keys) }
            }
            save()
        }
    }

    /// Anchor = session + counter. A different session (index lost or reset) means the anchor expired.
    var anchor: Data { lock.withLock { Data("\(state.session):\(state.counter)".utf8) } }

    func isCurrent(_ anchor: Data) -> Bool {
        let s = String(decoding: anchor, as: UTF8.self)
        return lock.withLock { s.hasPrefix(state.session + ":") }
    }

    func bump() -> Data {
        lock.withLock {
            state.counter += 1
            save()
            return Data("\(state.session):\(state.counter)".utf8)
        }
    }

    private func save() {
        try? FileManager.default.createDirectory(at: Account.container, withIntermediateDirectories: true)
        try? JSONEncoder().encode(state).write(to: Index.file, options: .atomic)
    }
}
