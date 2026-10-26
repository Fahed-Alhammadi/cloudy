import SwiftUI

struct MoreView: View {
    @Environment(Session.self) private var session
    @Environment(\.openURL) private var openURL
    @AppStorage("appLock") private var appLock = false
    @State private var confirmSignOut = false

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
        return "\(v) (\(b))"
    }

    var body: some View {
        NavigationStack {
            List {
                if let user = session.user {
                    Section {
                        HStack(spacing: 14) {
                            Avatar(name: user.name, size: 52)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(user.name).font(.title3.weight(.semibold))
                                Text(verbatim: "\(user.id) · \(URL(string: session.server)?.host ?? session.server)")
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        StorageRow(user: user)
                    }
                }
                // Same entries and order as Android's More screen; the Files tab only shows the folders.
                Section {
                    NavigationLink(value: Route.list(.recent)) { SettingsLabel("Recent", icon: "clock.fill", color: .blue) }
                    NavigationLink(value: Route.list(.favorites)) { SettingsLabel("Favorites", icon: "star.fill", color: .orange) }
                    NavigationLink(value: Route.list(.shared)) { SettingsLabel("Shared with you", icon: "person.2.fill", color: .green) }
                    NavigationLink(value: Route.links) { SettingsLabel("Shared by you", icon: "link", color: .teal) }
                    NavigationLink(value: Route.trash) { SettingsLabel("Trash", icon: "trash.fill", color: .gray) }
                }
                Section {
                    NavigationLink { DevicesView() } label: { SettingsLabel("Devices", icon: "iphone", color: .indigo) }
                    if canUseAppLock {
                        Toggle(isOn: $appLock) { SettingsLabel("App lock", icon: "faceid", color: .green) }
                    }
                    Button {
                        if let u = URL(string: UIApplication.openSettingsURLString) { openURL(u) }
                    } label: {
                        LabeledContent {
                            Text(Locale.current.localizedString(forLanguageCode: Locale.current.language.languageCode?.identifier ?? "en") ?? "")
                        } label: {
                            SettingsLabel("Language", icon: "globe", color: .blue)
                        }
                    }
                    .foregroundStyle(.primary)
                } footer: {
                    Text("App lock asks for Face ID, Touch ID or your passcode when you return to Cloudy. The language is set in the Settings app.")
                }
                Section {
                    if let web = URL(string: session.server) {
                        Link(destination: web) { SettingsLabel("Open Cloudy on the web", icon: "safari.fill", color: .blue).foregroundStyle(.primary) }
                    }
                    Link(destination: privacyPolicyURL) { SettingsLabel("Privacy policy", icon: "hand.raised.fill", color: .purple).foregroundStyle(.primary) }
                    LabeledContent("Version", value: version)
                }
                Section {
                    Button("Sign out", role: .destructive) { confirmSignOut = true }
                }
            }
            .navigationTitle("More")
            .withRoutes()
            .refreshable { await session.refresh() }
            .confirmationDialog("Sign out of Cloudy?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { session.signOut() }
            } message: {
                Text("Files downloaded to this iPhone are removed. Your data stays on the server.")
            }
        }
    }
}

/// A row label with a white symbol on a colored rounded square, like the Settings app.
private struct SettingsLabel: View {
    let title: LocalizedStringKey
    let icon: String
    let color: Color

    init(_ title: LocalizedStringKey, icon: String, color: Color) {
        self.title = title
        self.icon = icon
        self.color = color
    }

    var body: some View {
        Label {
            Text(title)
        } icon: {
            Image(systemName: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 29, height: 29)
                .background(RoundedRectangle(cornerRadius: 7, style: .continuous).fill(color.gradient))
        }
    }
}

private struct StorageRow: View {
    let user: User

    var body: some View {
        let used = user.used ?? 0
        VStack(alignment: .leading, spacing: 6) {
            if let quota = user.quotaBytes, quota > 0 {
                ProgressView(value: min(Double(used) / Double(quota), 1))
                Text("\(formatSize(used)) of \(formatSize(quota)) used").font(.caption).foregroundStyle(.secondary)
            } else {
                Text("\(formatSize(used)) used").font(.caption).foregroundStyle(.secondary)
            }
        }
    }
}

/// Signed-in devices and app passwords; revoking one signs that device out.
struct DevicesView: View {
    @State private var items: [AppPassword]?
    @State private var loadError: String?
    @State private var error: String?
    @State private var revoking: AppPassword?

    var body: some View {
        List {
            Section {
                ForEach(items ?? []) { p in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text(p.name)
                            if p.current == true {
                                Text("This device").font(.caption.bold()).padding(.horizontal, 6).padding(.vertical, 2)
                                    .background(Capsule().fill(Color.accentColor.opacity(0.15))).foregroundStyle(.tint)
                            }
                        }
                        if let d = parseDate(p.lastUsedAt) ?? parseDate(p.createdAt) {
                            Text("Last used \(d.formatted(date: .abbreviated, time: .shortened))").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .swipeActions {
                        if p.current != true {
                            Button("Revoke", role: .destructive) { revoking = p }
                        }
                    }
                }
            } footer: {
                Text("Devices and apps signed in to your account. Revoke one to sign it out, for example a lost phone.")
            }
        }
        .overlay {
            if let loadError, items == nil { ErrorState(message: loadError) { Task { await load() } } }
            else if items == nil { ProgressView() }
        }
        .navigationTitle("Devices")
        .refreshable { await load() }
        .task { await load() }
        .confirmationDialog(Text("Sign out \"\(revoking?.name ?? "")\"?"),
                            isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }), titleVisibility: .visible) {
            Button("Revoke", role: .destructive) {
                if let p = revoking {
                    attempt($error) {
                        try await API.shared.revokeAppPassword(p.id)
                        await load()
                    }
                }
            }
        }
        .errorAlert($error)
    }

    private func load() async {
        do { items = try await API.shared.appPasswords(); loadError = nil } catch { loadError = errorText(error) }
    }
}
