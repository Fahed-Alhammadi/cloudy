import AppKit
import SwiftUI

/// The sign-in window. An AppKit window, so the menu bar app can open it at launch and from the menu.
@MainActor
enum SignInWindow {
    private static var window: NSWindow?

    static func show() {
        if window == nil {
            let w = NSWindow(contentViewController: NSHostingController(rootView: SignInView { close() }))
            w.title = String(localized: "Sign in to Cloudy")
            w.styleMask = [.titled, .closable]
            w.isReleasedWhenClosed = false
            w.center()
            window = w
        }
        NSApp.activate(ignoringOtherApps: true)
        window?.makeKeyAndOrderFront(nil)
    }

    static func close() {
        window?.close()
        window = nil
    }
}

struct SignInView: View {
    var done: () -> Void

    @State private var server = Account.server
    @State private var user = ""
    @State private var password = ""
    @State private var code = ""
    @State private var needsCode = false
    @State private var busy = false
    @State private var error: String?

    var body: some View {
        VStack(spacing: 16) {
            VStack(spacing: 6) {
                Image(nsImage: NSApp.applicationIconImage).resizable().frame(width: 64, height: 64)
                Text("Cloudy Drive").font(.title2.bold())
                Text("Your Cloudy files in Finder. They take no space on this Mac until you open them.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Form {
                TextField("Server address", text: $server, prompt: Text(verbatim: "https://cloud.example.com"))
                    .textContentType(.URL)
                TextField("Username", text: $user).textContentType(.username)
                SecureField("Password", text: $password).textContentType(.password)
                if needsCode {
                    TextField("Two-factor code", text: $code).textContentType(.oneTimeCode)
                }
            }
            .disabled(busy)
            if let error {
                Label(error, systemImage: "exclamationmark.triangle").foregroundStyle(.red)
                    .fixedSize(horizontal: false, vertical: true)
            }
            HStack {
                Button("Forgot password?") {
                    if let u = URL(string: normalized(server) + "/#/forgot") { NSWorkspace.shared.open(u) }
                }
                .buttonStyle(.link)
                .disabled(server.isEmpty)
                Spacer()
                if busy { ProgressView().controlSize(.small) }
                Button("Sign in", action: signIn)
                    .keyboardShortcut(.defaultAction)
                    .disabled(busy || server.isEmpty || user.isEmpty || password.isEmpty)
            }
            Text("Cloudy doesn't collect any data. Everything stays between this app and the server you sign in to.")
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(24)
        .frame(width: 400)
    }

    private func normalized(_ s: String) -> String {
        var s = s.trimmingCharacters(in: .whitespacesAndNewlines)
        while s.hasSuffix("/") { s.removeLast() }
        if !s.isEmpty && !s.contains("://") { s = "https://" + s }
        return s
    }

    private func signIn() {
        guard !busy, !server.isEmpty, !user.isEmpty, !password.isEmpty else { return }
        let address = normalized(server)
        server = address
        busy = true
        error = nil
        Task {
            do {
                let r = try await API.shared.login(server: address, user: user.trimmingCharacters(in: .whitespaces),
                                                   password: password, code: needsCode ? code : nil)
                await AppModel.shared.signIn(server: address, token: r.token, user: r.user)
                done()
            } catch let e as APIError where e.twoFactor {
                if needsCode { error = e.message }
                needsCode = true
            } catch {
                self.error = errorText(error)
            }
            busy = false
        }
    }
}
