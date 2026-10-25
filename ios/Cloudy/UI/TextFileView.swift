import SwiftUI

/// Views and edits a text file; Save uploads the new content (the server keeps the old one as a version).
struct TextFileView: View {
    let node: FileNode
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""
    @State private var saved: String?
    @State private var loadError: String?
    @State private var error: String?
    @State private var saving = false
    @State private var confirmDiscard = false
    @State private var actions = FileActions()

    private var dirty: Bool { saved != nil && text != saved }

    var body: some View {
        Group {
            if saved != nil {
                TextEditor(text: $text)
                    .font(.system(.body, design: .monospaced))
                    .autocorrectionDisabled()
                    .textInputAutocapitalization(.never)
                    .padding(.horizontal, 8)
            } else if let loadError {
                ErrorState(message: loadError) { Task { await load() } }
            } else {
                ProgressView()
            }
        }
        .navigationTitle(node.name)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(dirty)
        .toolbar {
            if dirty {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { confirmDiscard = true }
                }
            }
            ToolbarItem(placement: .confirmationAction) {
                if saving {
                    ProgressView()
                } else if dirty {
                    Button("Save", action: save).bold()
                } else {
                    Menu { actions.menu(for: node) } label: { Label("Actions", systemImage: "ellipsis.circle") }
                }
            }
        }
        .confirmationDialog("Discard changes?", isPresented: $confirmDiscard, titleVisibility: .visible) {
            Button("Discard", role: .destructive) { dismiss() }
        }
        .interactiveDismissDisabled(dirty)
        .task { await load() }
        .fileActions(actions)
        #if DEBUG
        .onReceive(NotificationCenter.default.publisher(for: .tour)) { note in
            guard case .type(let s) = note.object as? Screenshots.Step else { return }
            Task { for ch in s { text.append(ch); try? await Task.sleep(nanoseconds: 40_000_000) } }
        }
        #endif
        .errorAlert($error)
    }

    private func load() async {
        guard saved == nil else { return }
        do {
            let t = try await API.shared.downloadText(node.id)
            text = t
            saved = t
            loadError = nil
        } catch {
            loadError = errorText(error)
        }
    }

    private func save() {
        saving = true
        let content = text
        Task {
            do {
                try await API.shared.saveText(node.id, content)
                saved = content
            } catch {
                self.error = errorText(error)
            }
            saving = false
        }
    }
}
