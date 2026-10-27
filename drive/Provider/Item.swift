import FileProvider
import UniformTypeIdentifiers

extension NSFileProviderItemIdentifier {
    /// Virtual folder at the root listing the files others shared with this user.
    static let shared = NSFileProviderItemIdentifier("shared")

    init(node id: Int64) { self = id == Account.rootId ? .rootContainer : NSFileProviderItemIdentifier(String(id)) }

    /// Server node id; nil for the virtual "Shared with me" folder (and the working set / trash).
    var nodeId: Int64? { self == .rootContainer ? Account.rootId : Int64(rawValue) }
}

/// A Cloudy node as macOS sees it. Versions: a file's content version is its etag; a folder's etag changes with
/// every edit below it, so folders only carry name + parent.
final class Item: NSObject, NSFileProviderItem {
    let node: FileNode
    let itemIdentifier: NSFileProviderItemIdentifier
    let parentItemIdentifier: NSFileProviderItemIdentifier
    let canEdit: Bool

    init(_ node: FileNode, parent: NSFileProviderItemIdentifier, canEdit: Bool) {
        self.node = node
        itemIdentifier = NSFileProviderItemIdentifier(node: node.id)
        parentItemIdentifier = itemIdentifier == .rootContainer ? .rootContainer : parent
        self.canEdit = canEdit
    }

    private init(virtual id: NSFileProviderItemIdentifier, name: String) {
        node = FileNode(id: -1, name: name, isDir: true)
        itemIdentifier = id
        parentItemIdentifier = .rootContainer
        canEdit = false
    }

    static var sharedFolder: Item { Item(virtual: .shared, name: String(localized: "Shared with me")) }

    /// Where a node sits in Finder: share roots under "Shared with me", everything else under its server parent.
    static func parent(of node: FileNode) -> NSFileProviderItemIdentifier {
        let id = NSFileProviderItemIdentifier(node: node.id).rawValue
        if Index.shared.folder(NSFileProviderItemIdentifier.shared.rawValue)?.children[id] != nil { return .shared }
        guard let p = node.parentId else { return .rootContainer }
        return NSFileProviderItemIdentifier(node: p)
    }

    var filename: String { itemIdentifier == .rootContainer || node.name.isEmpty ? "Cloudy" : node.name }
    var contentType: UTType { node.isDir ? .folder : UTType(filenameExtension: node.ext) ?? .data }
    var documentSize: NSNumber? { node.isDir ? nil : NSNumber(value: node.bytes) }
    var contentModificationDate: Date? { node.modified }
    var creationDate: Date? { node.modified }

    var capabilities: NSFileProviderItemCapabilities {
        var c: NSFileProviderItemCapabilities = [.allowsReading, .allowsContentEnumerating]
        guard canEdit else { return c }
        c.formUnion([.allowsWriting, .allowsAddingSubItems])
        // Share roots and the root itself stay where they are.
        if itemIdentifier != .rootContainer && parentItemIdentifier != .shared {
            c.formUnion([.allowsRenaming, .allowsReparenting, .allowsDeleting])
        }
        return c
    }

    private var contentVersion: String { node.isDir ? "dir" : node.etag ?? "" }
    private var metadataVersion: String { "\(node.name)|\(parentItemIdentifier.rawValue)|\(canEdit)|\(contentVersion)" }
    var itemVersion: NSFileProviderItemVersion {
        NSFileProviderItemVersion(contentVersion: Data(contentVersion.utf8), metadataVersion: Data(metadataVersion.utf8))
    }

    /// Changes whenever macOS needs to hear about this item again (stored in `Index`).
    var versionKey: String { metadataVersion }
}
