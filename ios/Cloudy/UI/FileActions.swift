import SwiftUI
import QuickLook

/// Per-screen state for acting on files: open (Quick Look), send to other apps, rename, move/copy,
/// favorite, share and delete. Attach with `.fileActions(actions)`; rows use `menu(for:)`.
@Observable
final class FileActions {
    struct Transfer: Identifiable {
        let id: Int64
        let node: FileNode
        let copy: Bool
    }

    var renaming: FileNode?
    var renameText = ""
    var transferring: Transfer?
    var sharing: FileNode?
    var deleting: FileNode?
    var downloading: FileNode?
    var previewURL: URL?
    var shareItem: ShareItem?
    var error: String?
    /// Called after a change so the screen reloads (live updates usually do it too).
    var changed: () -> Void = {}
    private var task: Task<Void, Never>?

    /// Downloads (or reuses the cached copy) and shows the file in Quick Look: images, video, audio,
    /// PDF and office documents all preview natively.
    @MainActor
    func open(_ node: FileNode) { fetch(node) { self.previewURL = $0 } }

    /// Hands the file to the share sheet (Save to Files, Mail, Messages, other apps…). Folders go as ZIP.
    @MainActor
    func export(_ node: FileNode) { fetch(node) { self.shareItem = ShareItem(items: [$0]) } }

    @MainActor
    private func fetch(_ node: FileNode, then: @escaping (URL) -> Void) {
        task?.cancel()
        downloading = node
        task = Task {
            do {
                let url = try await FileCache.fetch(node)
                if !Task.isCancelled { then(url) }
            } catch is CancellationError {
            } catch let e as URLError where e.code == .cancelled {
            } catch {
                self.error = errorText(error)
            }
            downloading = nil
        }
    }

    @MainActor
    func cancelDownload() {
        task?.cancel()
        downloading = nil
    }

    @MainActor
    func run(_ work: @escaping () async throws -> Void) {
        Task {
            do {
                try await work()
                changed()
            } catch {
                self.error = errorText(error)
            }
        }
    }

    /// Context-menu / swipe actions for a row.
    @MainActor @ViewBuilder
    func menu(for node: FileNode) -> some View {
        if !node.isDir {
            Button { self.open(node) } label: { Label("Open", systemImage: "eye") }
        }
        Button { self.export(node) } label: { Label(node.isDir ? "Send as ZIP" : "Send to…", systemImage: "square.and.arrow.up") }
        Button { self.sharing = node } label: { Label("Share", systemImage: "person.crop.circle.badge.plus") }
        Button {
            self.run { try await API.shared.setFavorite(node.id, !node.isFavorite) }
        } label: {
            node.isFavorite ? Label("Remove from favorites", systemImage: "star.slash") : Label("Add to favorites", systemImage: "star")
        }
        Divider()
        Button { self.renameText = node.name; self.renaming = node } label: { Label("Rename", systemImage: "pencil") }
        Button { self.transferring = Transfer(id: node.id, node: node, copy: false) } label: { Label("Move", systemImage: "folder") }
        Button { self.transferring = Transfer(id: node.id, node: node, copy: true) } label: { Label("Copy", systemImage: "doc.on.doc") }
        Divider()
        Button(role: .destructive) { self.deleting = node } label: { Label("Delete", systemImage: "trash") }
    }
}

private struct FileActionsModifier: ViewModifier {
    @Bindable var actions: FileActions

    func body(content: Content) -> some View {
        content
            .quickLookPreview($actions.previewURL)
            .sheet(item: $actions.shareItem) { ActivityView(items: $0.items).presentationDetents([.medium, .large]) }
            .sheet(item: $actions.sharing) { ShareSheetView(node: $0) }
            .sheet(item: $actions.transferring) { t in
                FolderPicker(title: t.copy ? "Copy to" : "Move to", action: t.copy ? "Copy here" : "Move here", excluding: t.node.id) { target in
                    actions.run {
                        if t.copy { try await API.shared.copy(t.node.id, to: target) } else { try await API.shared.move(t.node.id, to: target) }
                    }
                }
            }
            .alert("Rename", isPresented: Binding(get: { actions.renaming != nil }, set: { if !$0 { actions.renaming = nil } })) {
                TextField("Name", text: $actions.renameText).autocorrectionDisabled()
                Button("Cancel", role: .cancel) {}
                Button("Rename") {
                    guard let n = actions.renaming else { return }
                    let name = actions.renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !name.isEmpty && name != n.name { actions.run { try await API.shared.rename(n.id, name) } }
                }
            }
            .confirmationDialog(Text("Move \"\(actions.deleting?.name ?? "")\" to the trash?"),
                                isPresented: Binding(get: { actions.deleting != nil }, set: { if !$0 { actions.deleting = nil } }),
                                titleVisibility: .visible) {
                Button("Move to trash", role: .destructive) {
                    if let n = actions.deleting { actions.run { try await API.shared.delete(n.id) } }
                }
            }
            .overlay {
                if let n = actions.downloading {
                    VStack(spacing: 14) {
                        ProgressView()
                        Text("Downloading \(n.name)…").font(.callout).multilineTextAlignment(.center)
                        Button("Cancel", role: .cancel) { actions.cancelDownload() }
                    }
                    .padding(24)
                    .frame(maxWidth: 280)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18))
                }
            }
            .errorAlert($actions.error)
    }
}

extension View {
    func fileActions(_ actions: FileActions) -> some View { modifier(FileActionsModifier(actions: actions)) }
}

extension FileNode {
    /// Where tapping the row goes: folders and text files push a screen; other files open in Quick Look.
    var route: Route? {
        if isDir { return .folder(id, name) }
        if isText(self) { return .text(self) }
        return nil
    }
}

/// A row that navigates or opens the file, with the actions menu on long press and swipe.
struct FileRow: View {
    let node: FileNode
    let actions: FileActions
    var subtitle: String?

    var body: some View {
        Group {
            if let route = node.route {
                NavigationLink(value: route) { NodeRow(node: node, subtitle: subtitle) }
            } else {
                Button { actions.open(node) } label: { NodeRow(node: node, subtitle: subtitle) }
                    .foregroundStyle(.primary)
            }
        }
        .contextMenu { actions.menu(for: node) }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) { actions.deleting = node } label: { Label("Delete", systemImage: "trash") }
            Button { actions.sharing = node } label: { Label("Share", systemImage: "person.crop.circle.badge.plus") }.tint(.accentColor)
        }
    }
}
