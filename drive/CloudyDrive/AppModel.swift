import AppKit
import FileProvider
import Observation
import ServiceManagement

/// State of the menu bar app: the account, the Finder domain, live updates and the update check.
@MainActor
@Observable
final class AppModel {
    static let shared = AppModel()

    private(set) var user: User?
    private(set) var server = Account.server
    /// Last problem worth showing in the menu (server unreachable, signed out by the server).
    private(set) var problem: String?
    /// False while the user hasn't switched Cloudy on under System Settings → Login Items & Extensions → File Providers
    /// (new File Providers start switched off on current macOS).
    private(set) var finderEnabled = true
    /// Download page of a newer `drive-v*` release.
    private(set) var update: (version: String, url: URL)?

    private var live: Task<Void, Never>?
    private var timer: Timer?

    var signedIn: Bool { user != nil }
    var serverHost: String { URL(string: server)?.host ?? server }

    private init() {
        API.shared.onUnauthorized = { [weak self] in
            Task { @MainActor in self?.signedOutByServer() }
        }
        NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in AppModel.shared.signalChanges() }
        }
    }

    func start() {
        #if DEBUG
        // Testing without the sign-in window: `open --env CLOUDY_SERVER=… --env CLOUDY_USER=… --env CLOUDY_PASSWORD=… <app>`
        // CLOUDY_RESET=1 first removes the Finder domain and the stored account (alone: just signs out).
        let env = ProcessInfo.processInfo.environment
        let reset = env["CLOUDY_RESET"] != nil
        let login = env["CLOUDY_SERVER"].flatMap { s in env["CLOUDY_USER"].flatMap { u in env["CLOUDY_PASSWORD"].map { (s, u, $0) } } }
        if reset || (login != nil && !Account.load()) {
            Task {
                if reset {
                    _ = try? await NSFileProviderManager.remove(Account.domain, mode: .removeAll)
                    Account.clear()
                }
                if let (s, u, p) = login, let r = try? await API.shared.login(server: s, user: u, password: p, code: nil) {
                    await signIn(server: s, token: r.token, user: r.user)
                }
            }
            return
        }
        #endif
        if Account.load() {
            user = Account.user
            connect()
        } else {
            SignInWindow.show()
        }
        Task { await checkForUpdate() }
        Timer.scheduledTimer(withTimeInterval: 24 * 3600, repeats: true) { _ in
            Task { await AppModel.shared.checkForUpdate() }
        }
    }

    func signIn(server: String, token: String, user: User) async {
        Account.save(server: server, token: token, user: user)
        self.server = server
        self.user = user
        problem = nil
        if SMAppService.mainApp.status != .enabled { try? SMAppService.mainApp.register() }
        connect()
        if await checkEnabled() { openInFinder() } else { openSettings() }
    }

    func signOut() {
        let revoke = API.shared.token != nil
        Task {
            if revoke { await API.shared.logout() }
            // Keeps files with changes not uploaded yet (macOS moves them to a folder it names).
            _ = try? await NSFileProviderManager.remove(Account.domain, mode: .preserveDirtyUserData)
            Account.clear()
        }
        disconnect()
        user = nil
        problem = nil
    }

    private func signedOutByServer() {
        guard user != nil else { return }
        signOut()
        problem = String(localized: "You were signed out. Sign in again to keep using Cloudy in Finder.")
        SignInWindow.show()
    }

    // MARK: Finder domain + live updates

    private func connect() {
        Task {
            do {
                try await NSFileProviderManager.add(Account.domain)
            } catch {
                log.error("Adding the Finder domain failed: \(error, privacy: .public)")
                problem = error.localizedDescription
            }
            await checkEnabled()
            if let me = try? await API.shared.me() {
                Account.update(me)
                user = me
            }
        }
        live?.cancel()
        live = Task { await runLive() }
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 300, repeats: true) { _ in
            Task { @MainActor in AppModel.shared.signalChanges() }
        }
    }

    private func disconnect() {
        live?.cancel()
        live = nil
        timer?.invalidate()
        timer = nil
    }

    /// One SSE stream while signed in; each event asks macOS to fetch changes (it coalesces repeated signals).
    private func runLive() async {
        while !Task.isCancelled {
            do {
                try await API.shared.streamEvents { _ in
                    Task { @MainActor in AppModel.shared.signalChanges() }
                }
                problem = nil
            } catch let e as APIError where e.status == 401 {
                return
            } catch {
                if !Task.isCancelled { problem = errorText(error) }
            }
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            signalChanges() // catch up on anything missed while disconnected
        }
    }

    /// Reads whether the user switched the Finder location on; called on launch, sign in and when the menu opens.
    @discardableResult
    func checkEnabled() async -> Bool {
        guard signedIn, let domains = try? await NSFileProviderManager.domains(),
              let domain = domains.first(where: { $0.identifier == Account.domain.identifier }) else { return finderEnabled }
        finderEnabled = domain.userEnabled
        return finderEnabled
    }

    func openSettings() {
        if let u = URL(string: "x-apple.systempreferences:com.apple.LoginItems-Settings.extension") { NSWorkspace.shared.open(u) }
    }

    func signalChanges() {
        guard signedIn, let manager = NSFileProviderManager(for: Account.domain) else { return }
        manager.signalEnumerator(for: .workingSet) { _ in }
    }

    func openInFinder() {
        guard let manager = NSFileProviderManager(for: Account.domain) else { return }
        manager.getUserVisibleURL(for: .rootContainer) { url, _ in
            if let url { DispatchQueue.main.async { NSWorkspace.shared.open(url) } }
        }
    }

    // MARK: launch at login

    var opensAtLogin: Bool {
        get { SMAppService.mainApp.status == .enabled }
        set {
            if newValue { try? SMAppService.mainApp.register() } else { try? SMAppService.mainApp.unregister() }
        }
    }

    // MARK: updates (newest drive-v* GitHub release; this repository also has server, app and desktop releases)

    private struct Release: Decodable {
        var tag_name: String
        var html_url: String
        var draft: Bool
        var prerelease: Bool
    }

    func checkForUpdate() async {
        guard let url = URL(string: "https://api.github.com/repos/Fahed-Alhammadi/cloudy/releases?per_page=50"),
              let (data, _) = try? await URLSession.shared.data(from: url),
              let releases = try? JSONDecoder().decode([Release].self, from: data) else { return }
        let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
        let newest = releases
            .filter { !$0.draft && !$0.prerelease && $0.tag_name.hasPrefix("drive-v") }
            .map { (version: String($0.tag_name.dropFirst(7)), url: $0.html_url) }
            .max { $0.version.compare($1.version, options: .numeric) == .orderedAscending }
        if let newest, newest.version.compare(current, options: .numeric) == .orderedDescending, let u = URL(string: newest.url) {
            update = (newest.version, u)
        }
    }
}
