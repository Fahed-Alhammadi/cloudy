import SwiftUI
import LocalAuthentication

@main
struct CloudyApp: App {
    @State private var session = Session.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(session)
                .environment(Uploads.shared)
        }
    }
}

struct RootView: View {
    @Environment(Session.self) private var session
    @Environment(\.scenePhase) private var phase
    @AppStorage("appLock") private var appLock = false
    @State private var locked = UserDefaults.standard.bool(forKey: "appLock")

    var body: some View {
        ZStack {
            if session.isLoggedIn {
                MainView()
            } else {
                LoginView()
            }
            if locked && session.isLoggedIn {
                LockView(unlock: unlock)
            }
        }
        .onChange(of: phase) { _, p in
            // Lock on leaving the app; the lock screen also hides content in the app switcher.
            // Only prompt when coming back from the background: Face ID itself makes the app inactive.
            if p == .background { wasBackground = true; if appLock { locked = true } }
            if p == .active && wasBackground {
                wasBackground = false
                if locked { unlock() }
            }
        }
        .task { if locked { unlock() } }
    }

    @State private var unlocking = false
    @State private var wasBackground = false

    private func unlock() {
        guard !unlocking else { return }
        let ctx = LAContext()
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) else { locked = false; return }
        unlocking = true
        ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: String(localized: "Unlock Cloudy")) { ok, _ in
            DispatchQueue.main.async {
                unlocking = false
                if ok { locked = false }
            }
        }
    }
}

private struct LockView: View {
    var unlock: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.fill").font(.system(size: 48)).foregroundStyle(.tint)
            Text("Cloudy is locked").font(.title2.bold())
            Button("Unlock", action: unlock).buttonStyle(.borderedProminent)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemBackground))
    }
}

/// Whether this device can use App lock (Face ID, Touch ID or a passcode is set up).
var canUseAppLock: Bool { LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil) }
