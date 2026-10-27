import FileProvider
import UniformTypeIdentifiers

/// Cloudy in Finder (NSFileProviderReplicatedExtension): placeholders for every file, contents downloaded when
/// opened, local changes sent to the server. Deletes go to the server's trash. A file changed on both sides is
/// uploaded as "name (conflicted copy <date>).ext", like the desktop sync app.
final class FileProviderExtension: NSObject, NSFileProviderReplicatedExtension {
    required init(domain: NSFileProviderDomain) {
        super.init()
        Account.load()
    }

    func invalidate() {}

    func item(for identifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest,
              completionHandler: @escaping (NSFileProviderItem?, Error?) -> Void) -> Progress {
        run(completionHandler) { try await Remote.item(identifier) }
    }

    func enumerator(for containerItemIdentifier: NSFileProviderItemIdentifier, request: NSFileProviderRequest) throws -> NSFileProviderEnumerator {
        guard Account.load() else { throw NSFileProviderError(.notAuthenticated) }
        return Enumerator(containerItemIdentifier)
    }

    // MARK: download

    func fetchContents(for itemIdentifier: NSFileProviderItemIdentifier, version requestedVersion: NSFileProviderItemVersion?,
                       request: NSFileProviderRequest,
                       completionHandler: @escaping (URL?, NSFileProviderItem?, Error?) -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let task = Task {
            do {
                let item = try await Remote.item(itemIdentifier)
                guard let id = itemIdentifier.nodeId, !item.node.isDir else { throw NSFileProviderError(.noSuchItem) }
                let dest = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
                try await API.shared.download(id, to: dest)
                progress.completedUnitCount = 1
                completionHandler(dest, item, nil)
            } catch {
                completionHandler(nil, nil, providerError(error))
            }
        }
        progress.cancellationHandler = { task.cancel() }
        return progress
    }

    // MARK: local changes → server

    func createItem(basedOn itemTemplate: NSFileProviderItem, fields: NSFileProviderItemFields, contents url: URL?,
                    options: NSFileProviderCreateItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        if itemTemplate.itemIdentifier == .rootContainer { // the system re-imports the root after (re)adding the domain
            return run({ completionHandler($0, [], false, $1) }) { try await Remote.item(.rootContainer) }
        }
        let name = itemTemplate.filename
        let type = itemTemplate.contentType ?? .data
        if isIgnored(name) || type.conforms(to: .symbolicLink) {
            completionHandler(nil, [], false, NSFileProviderError(.excludedFromSync))
            return Progress()
        }
        return run({ completionHandler($0, [], false, $1) }) {
            guard let parentId = itemTemplate.parentItemIdentifier.nodeId else { throw CocoaError(.fileWriteNoPermission) }
            let siblings = try await API.shared.node(parentId).children ?? []
            let existing = siblings.first { $0.name == name }
            if type.conforms(to: .folder) {
                // Same-named folder on the server: use it, the local contents are added to it.
                if let existing, existing.isDir { return try await Remote.item(NSFileProviderItemIdentifier(node: existing.id)) }
                let folder = try await API.shared.mkdir(parentId, name)
                return try await Remote.item(NSFileProviderItemIdentifier(node: folder.id))
            }
            let file = url ?? emptyFile()
            var target = name
            if let existing {
                // After a re-import the file may be ours already; anything else must not overwrite the server copy.
                if options.contains(.mayAlreadyExist), !existing.isDir, existing.bytes == fileSize(file) {
                    return try await Remote.item(NSFileProviderItemIdentifier(node: existing.id))
                }
                target = conflictName(name)
            }
            let id = try await API.shared.upload(parentId, name: target, file: file) { _, _ in }
            return try await Remote.item(NSFileProviderItemIdentifier(node: id))
        }
    }

    func modifyItem(_ item: NSFileProviderItem, baseVersion version: NSFileProviderItemVersion, changedFields: NSFileProviderItemFields,
                    contents newContents: URL?, options: NSFileProviderModifyItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (NSFileProviderItem?, NSFileProviderItemFields, Bool, Error?) -> Void) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let task = Task {
            do {
                guard let id = item.itemIdentifier.nodeId, item.itemIdentifier != .rootContainer else {
                    throw CocoaError(.fileWriteNoPermission)
                }
                // Contents first: a rename also changes the etag the conflict check compares.
                if changedFields.contains(.contents), let newContents {
                    let current = try await API.shared.node(id).node
                    guard let parentId = current.parentId else { throw CocoaError(.fileWriteNoPermission) }
                    if current.etag != String(decoding: version.contentVersion, as: UTF8.self) {
                        // Changed on the server since this Mac last had it: keep both, take the server's version here.
                        _ = try await API.shared.upload(parentId, name: conflictName(item.filename), file: newContents) { _, _ in }
                        progress.completedUnitCount = 1
                        completionHandler(try await Remote.item(item.itemIdentifier), [], true, nil)
                        return
                    }
                    _ = try await API.shared.upload(parentId, name: current.name, file: newContents) { _, _ in }
                }
                var patch: [String: Any?] = [:]
                if changedFields.contains(.filename) { patch["name"] = item.filename }
                if changedFields.contains(.parentItemIdentifier) {
                    guard let parentId = item.parentItemIdentifier.nodeId else { throw CocoaError(.fileWriteNoPermission) }
                    patch["parentId"] = parentId
                }
                if !patch.isEmpty { try await API.shared.sendJSON("PATCH", "/api/nodes/\(id)", patch) }
                progress.completedUnitCount = 1
                // Dates, tags and other Finder metadata stay local.
                completionHandler(try await Remote.item(item.itemIdentifier), [], false, nil)
            } catch {
                completionHandler(nil, [], false, providerError(error))
            }
        }
        progress.cancellationHandler = { task.cancel() }
        return progress
    }

    func deleteItem(identifier: NSFileProviderItemIdentifier, baseVersion version: NSFileProviderItemVersion,
                    options: NSFileProviderDeleteItemOptions = [], request: NSFileProviderRequest,
                    completionHandler: @escaping (Error?) -> Void) -> Progress {
        run({ _, error in completionHandler(error) }) {
            guard let id = identifier.nodeId, identifier != .rootContainer else { throw CocoaError(.fileWriteNoPermission) }
            do {
                try await API.shared.delete(id)
            } catch let e as APIError where e.status == 404 {
                // already gone
            }
        }
    }

    // MARK: helpers

    /// Runs `work` and reports its result (or File Provider error) to `done`.
    private func run<T>(_ done: @escaping (T?, Error?) -> Void, _ work: @escaping () async throws -> T) -> Progress {
        let progress = Progress(totalUnitCount: 1)
        let task = Task {
            do {
                let value = try await work()
                progress.completedUnitCount = 1
                done(value, nil)
            } catch {
                done(nil, providerError(error))
            }
        }
        progress.cancellationHandler = { task.cancel() }
        return progress
    }
}

private func fileSize(_ url: URL) -> Int64 {
    ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size]) as? NSNumber)?.int64Value ?? -1
}

private func emptyFile() -> URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    FileManager.default.createFile(atPath: url.path, contents: Data())
    return url
}

/// System and editor files that stay on this Mac (same list as the desktop app's sync.ts).
func isIgnored(_ name: String) -> Bool {
    name.range(of: #"^(\.DS_Store|\._.*|Thumbs\.db|desktop\.ini|\.directory|Icon\r|~\$.*|\.~lock\..*#|.*\.swp|\.cloudy-tmp-.*)$"#,
               options: [.regularExpression, .caseInsensitive]) != nil
}

/// "report (conflicted copy 2026-10-27 153000).pdf", like the desktop app.
func conflictName(_ name: String) -> String {
    let f = DateFormatter()
    f.locale = Locale(identifier: "en_US_POSIX")
    f.dateFormat = "yyyy-MM-dd HHmmss"
    let ext = (name as NSString).pathExtension
    let stem = ext.isEmpty ? name : String(name.dropLast(ext.count + 1))
    let label = String(localized: "conflicted copy")
    return "\(stem) (\(label) \(f.string(from: Date())))" + (ext.isEmpty ? "" : ".\(ext)")
}
