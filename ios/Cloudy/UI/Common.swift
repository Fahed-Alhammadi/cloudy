import SwiftUI
import UniformTypeIdentifiers

// MARK: - Formatting

func formatSize(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }

func formatDate(_ d: Date?) -> String {
    guard let d else { return "" }
    return Calendar.current.isDateInToday(d) ? d.formatted(date: .omitted, time: .shortened) : d.formatted(date: .abbreviated, time: .omitted)
}

// MARK: - File types

/// Text files open in the built-in editor (the server keeps the old content as a version).
func isText(_ n: FileNode) -> Bool {
    if n.isDir || n.bytes > 2_000_000 { return false }
    let mime = n.mime ?? ""
    if mime.hasPrefix("text/") { return true }
    let exts: Set = ["txt", "md", "markdown", "json", "xml", "yml", "yaml", "csv", "log", "ini", "conf", "cfg", "toml",
                     "sh", "py", "js", "ts", "css", "html", "htm", "swift", "kt", "java", "c", "h", "cpp", "go", "rs", "sql", "env"]
    return exts.contains(n.ext)
}

func fileIcon(_ n: FileNode) -> (String, Color) {
    if n.isDir { return ("folder.fill", .accentColor) }
    let mime = n.mime ?? ""
    switch true {
    case mime.hasPrefix("image/"): return ("photo", .purple)
    case mime.hasPrefix("video/"): return ("film", .pink)
    case mime.hasPrefix("audio/"): return ("music.note", .orange)
    case mime == "application/pdf" || n.ext == "pdf": return ("doc.richtext", .red)
    case ["zip", "gz", "tgz", "7z", "rar", "tar"].contains(n.ext): return ("doc.zipper", .brown)
    case ["xls", "xlsx", "ods", "numbers"].contains(n.ext): return ("tablecells", .green)
    case ["ppt", "pptx", "odp", "key"].contains(n.ext): return ("rectangle.on.rectangle", .orange)
    case ["doc", "docx", "odt", "rtf", "pages"].contains(n.ext): return ("doc.text", .blue)
    case isText(n): return ("doc.plaintext", .gray)
    default: return ("doc", .gray)
    }
}

struct NodeRow: View {
    let node: FileNode
    var subtitle: String?

    var body: some View {
        let (icon, color) = fileIcon(node)
        HStack(spacing: 12) {
            Image(systemName: icon).font(.title2).foregroundStyle(color).frame(width: 32)
            VStack(alignment: .leading, spacing: 2) {
                Text(node.name).lineLimit(2)
                Text(subtitle ?? defaultSubtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer(minLength: 0)
            if node.isFavorite { Image(systemName: "star.fill").font(.caption).foregroundStyle(.yellow) }
            if node.shared == true { Image(systemName: "person.2.fill").font(.caption).foregroundStyle(.secondary) }
        }
        .contentShape(Rectangle())
    }

    private var defaultSubtitle: String {
        let date = formatDate(node.modified)
        return node.isDir ? date : [formatSize(node.bytes), date].filter { !$0.isEmpty }.joined(separator: " · ")
    }
}

// MARK: - Errors and prompts

extension View {
    /// Shows `error` in an alert and clears it on dismiss.
    func errorAlert(_ error: Binding<String?>) -> some View {
        alert("Something went wrong", isPresented: Binding(get: { error.wrappedValue != nil }, set: { if !$0 { error.wrappedValue = nil } })) {
            Button("OK", role: .cancel) {}
        } message: {
            Text(error.wrappedValue ?? "")
        }
    }

    /// A one-field alert (new folder, rename…). `onSave` gets the trimmed text.
    func textPrompt(_ title: LocalizedStringKey, isPresented: Binding<Bool>, text: Binding<String>, action: LocalizedStringKey,
                    onSave: @escaping (String) -> Void) -> some View {
        alert(title, isPresented: isPresented) {
            TextField("Name", text: text).autocorrectionDisabled()
            Button("Cancel", role: .cancel) {}
            Button(action) {
                let t = text.wrappedValue.trimmingCharacters(in: .whitespacesAndNewlines)
                if !t.isEmpty { onSave(t) }
            }
        }
    }
}

/// Runs an API call, putting any error into `error`.
@MainActor
func attempt(_ message: Binding<String?>, _ work: @escaping () async throws -> Void) {
    Task {
        do { try await work() } catch is CancellationError {} catch { message.wrappedValue = errorText(error) }
    }
}

struct ErrorState: View {
    let message: String
    var retry: () -> Void

    var body: some View {
        ContentUnavailableView {
            Label("Can't load", systemImage: "wifi.exclamationmark")
        } description: {
            Text(message)
        } actions: {
            Button("Try again", action: retry)
        }
    }
}

// MARK: - Live updates

extension View {
    /// Calls `action` when the server reports a change in one of `folders` (or in "shared with me" if `shares`).
    func onLiveChange(folders: Set<Int64> = [], shares: Bool = false, any: Bool = false, _ action: @escaping () -> Void) -> some View {
        onReceive(NotificationCenter.default.publisher(for: .cloudyChange)) { note in
            guard let ev = note.object as? ChangeEvent else { return }
            if any || (shares && ev.shares == true) || !(folders.isDisjoint(with: ev.folders ?? [])) { action() }
        }
    }
}

// MARK: - Sharing files with other apps

/// A file or text that can be handed to the share sheet.
struct ShareItem: Identifiable {
    let id = UUID()
    let items: [Any]
}

struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { UIActivityViewController(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ vc: UIActivityViewController, context: Context) {}
}
