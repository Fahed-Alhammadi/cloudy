import FileProvider
import Foundation

/// Reads the server for the extension: folder listings, single items and the change walk.
enum Remote {
    static func item(_ id: NSFileProviderItemIdentifier) async throws -> Item {
        if id == .shared { return Item.sharedFolder }
        guard let nodeId = id.nodeId else { throw NSFileProviderError(.noSuchItem) }
        let l = try await API.shared.node(nodeId)
        return Item(l.node, parent: Item.parent(of: l.node), canEdit: l.canEdit)
    }

    /// A folder's etag and children, without touching the index.
    static func fetch(_ container: NSFileProviderItemIdentifier) async throws -> (etag: String, items: [Item]) {
        if container == .shared {
            let nodes = try await API.shared.sharedWithMe()
            let items = nodes.map { Item($0, parent: .shared, canEdit: $0.canEdit ?? false) }
            // No etag for this list: a digest of what it shows (ids, names, contents, permissions).
            let etag = items.map { "\($0.itemIdentifier.rawValue)=\($0.versionKey)=\($0.node.etag ?? "")" }.sorted().joined(separator: ";")
            return (etag, items)
        }
        guard let id = container.nodeId else { throw NSFileProviderError(.noSuchItem) }
        let l = try await API.shared.node(id)
        guard l.node.isDir else { throw NSFileProviderError(.noSuchItem) }
        var items = (l.children ?? []).map { Item($0, parent: container, canEdit: l.canEdit) }
        if container == .rootContainer { items.append(Item.sharedFolder) }
        return (l.node.etag ?? "", items)
    }

    /// Lists a folder for Finder and remembers what was reported.
    static func list(_ container: NSFileProviderItemIdentifier) async throws -> [Item] {
        let (etag, items) = try await fetch(container)
        Index.shared.record(container.rawValue, etag: etag, children: versions(items))
        return items
    }

    private static func versions(_ items: [Item]) -> [String: String] {
        Dictionary(items.map { ($0.itemIdentifier.rawValue, $0.versionKey) }, uniquingKeysWith: { a, _ in a })
    }

    /// Everything that changed since the index was last updated. Only folders whose etag moved are read again; an
    /// unchanged root costs one request. A folder the index doesn't know yet (created in Finder, or the index was
    /// reset) is read once and everything in it reported, so no folder is left without change tracking.
    static func changes() async throws -> (updated: [Item], deleted: [NSFileProviderItemIdentifier]) {
        var updated: [String: Item] = [:]
        var deleted = Set<String>()
        var read = 0

        func walk(_ container: NSFileProviderItemIdentifier) async throws {
            let old = Index.shared.folder(container.rawValue)
            read += 1
            let fetched: (etag: String, items: [Item])
            do {
                fetched = try await fetch(container)
            } catch let e as APIError where e.status == 404 || e.status == 403 {
                // Folder gone (deleted, moved out of reach, share removed): its parent reports the deletion.
                Index.shared.forget([container.rawValue])
                deleted.insert(container.rawValue)
                return
            }
            if let old, fetched.etag == old.etag { return }
            let now = versions(fetched.items)
            for item in fetched.items {
                let id = item.itemIdentifier.rawValue
                if old?.children[id] != item.versionKey { updated[id] = item }
            }
            if let old { deleted.formUnion(old.children.keys.filter { now[$0] == nil }) }
            Index.shared.record(container.rawValue, etag: fetched.etag, children: now)
            for item in fetched.items where item.node.isDir {
                let id = item.itemIdentifier
                // The listing carries each child folder's etag, so unchanged folders aren't read again.
                if id != .shared, Index.shared.folder(id.rawValue)?.etag != (item.node.etag ?? "") {
                    try await walk(id)
                }
            }
        }

        try await walk(.rootContainer)
        // Shares from others don't touch this user's etags, so "Shared with me" is compared on every pass.
        try await walk(.shared)
        // A node moved between two listed folders shows up as updated in its new place, not deleted.
        deleted.subtract(updated.keys)
        Index.shared.forget(deleted)
        log.info("Changes: read \(read) folders, \(updated.count) updated, \(deleted.count) deleted")
        return (Array(updated.values), deleted.map { NSFileProviderItemIdentifier($0) })
    }
}

/// Server and network errors as File Provider errors, so Finder shows the right state.
func providerError(_ error: Error) -> Error {
    if let e = error as? APIError {
        switch e.status {
        case 401: return NSFileProviderError(.notAuthenticated)
        case 404: return NSFileProviderError(.noSuchItem)
        case 403: return CocoaError(.fileWriteNoPermission)
        case 409: return NSFileProviderError(.filenameCollision)
        case 413, 507: return NSFileProviderError(.insufficientQuota)
        default: return NSFileProviderError(.serverUnreachable)
        }
    }
    if error is URLError { return NSFileProviderError(.serverUnreachable) }
    return error
}
