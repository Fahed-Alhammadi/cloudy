import AppKit
import SwiftUI

/// Menu bar app (no Dock icon): signs in, adds "Cloudy" to Finder's sidebar and keeps it up to date. The files
/// themselves are handled by the File Provider extension (Provider/).
@main
struct DriveApp: App {
    @NSApplicationDelegateAdaptor private var delegate: AppDelegate
    @State private var model = AppModel.shared

    var body: some Scene {
        MenuBarExtra {
            MenuContent(model: model)
        } label: {
            Image("MenuIcon")
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        MainActor.assumeIsolated { AppModel.shared.start() }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        MainActor.assumeIsolated { AppModel.shared.reopened() }
        return false
    }
}

private struct MenuContent: View {
    @Bindable var model: AppModel

    var body: some View {
        if let user = model.user {
            Text("Signed in as \(user.name)")
            Text(verbatim: model.serverHost)
            if let problem = model.problem { Text(problem) }
            Divider()
            if !model.finderEnabled {
                Text("Cloudy is switched off in Finder")
                Button("Switch On in System Settings…") { model.openSettings() }
            }
            Button("Open Cloudy in Finder") { model.openInFinder() }
            Button("Open Cloudy on the web") {
                if let u = URL(string: model.server) { NSWorkspace.shared.open(u) }
            }
            Divider()
            Toggle("Open at login", isOn: $model.opensAtLogin)
            Button("Sign out") { model.signOut() }
        } else {
            if let problem = model.problem { Text(problem) }
            Button("Sign in…") { SignInWindow.show() }
        }
        Divider()
        if let update = model.update {
            Button("Update to Cloudy Drive \(update.version)…") { Task { await model.installUpdate() } }
                .disabled(model.updating)
        }
        Button("Check for Updates…") { Task { await model.checkForUpdateNow() } }
            .disabled(model.updating)
        Divider()
        Button("Quit Cloudy Drive") { NSApp.terminate(nil) }
            .keyboardShortcut("q")
            .task { await model.checkEnabled() } // each time the menu opens
    }
}
