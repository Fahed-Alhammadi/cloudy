import SwiftUI
import UniformTypeIdentifiers

struct ContactsView: View {
    @State private var books: [DavCollection]?
    @AppStorage("addressBook") private var bookId: Int = 0
    @State private var contacts: [Contact]?
    @State private var loadError: String?
    @State private var error: String?
    @State private var query = ""
    @State private var editing: Contact?
    @State private var importing = false
    @State private var importResult: String?
    @State private var shareItem: ShareItem?

    private var book: Int64 { Int64(bookId) }

    private var filtered: [Contact] {
        let all = (contacts ?? []).sorted { $0.displayName.localizedCaseInsensitiveCompare($1.displayName) == .orderedAscending }
        let q = query.trimmingCharacters(in: .whitespaces)
        guard !q.isEmpty else { return all }
        return all.filter { c in
            ([c.displayName, c.org] + c.emails + c.phones).contains { $0.localizedCaseInsensitiveContains(q) }
        }
    }

    var body: some View {
        NavigationStack {
            List(filtered) { c in
                NavigationLink(value: c) {
                    HStack(spacing: 12) {
                        Initials(name: c.displayName)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(c.displayName)
                            if !c.org.isEmpty { Text(c.org).font(.caption).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            .overlay {
                if let loadError, contacts == nil { ErrorState(message: loadError) { Task { await load() } } }
                else if contacts == nil { ProgressView() }
                else if contacts!.isEmpty { ContentUnavailableView("No contacts", systemImage: "person.crop.circle") }
                else if filtered.isEmpty { ContentUnavailableView.search(text: query) }
            }
            .searchable(text: $query)
            .navigationTitle(books?.first { $0.id == book }?.name ?? String(localized: "Contacts"))
            .navigationDestination(for: Contact.self) { c in
                ContactDetail(contact: c, bookId: book) { Task { await load() } }
            }
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { editing = Contact() } label: { Label("New contact", systemImage: "plus") }
                        .disabled(book == 0)
                }
                ToolbarItem(placement: .secondaryAction) {
                    Menu {
                        if let books, books.count > 1 {
                            Picker("Address book", selection: $bookId) {
                                ForEach(books) { b in Text(b.name).tag(Int(b.id)) }
                            }
                        }
                        Button { importing = true } label: { Label("Import (.vcf, .csv)", systemImage: "square.and.arrow.down") }
                        Button(action: export) { Label("Export (.vcf)", systemImage: "square.and.arrow.up") }
                    } label: {
                        Label("Address book options", systemImage: "ellipsis.circle")
                    }
                    .disabled(book == 0)
                }
            }
            .refreshable { await load() }
            .task(id: bookId) { await load() }
            .onLiveChange(any: true) { Task { await load() } }
            .sheet(item: $editing) { c in ContactEditor(contact: c, bookId: book) { Task { await load() } } }
            .sheet(item: $shareItem) { ActivityView(items: $0.items).presentationDetents([.medium, .large]) }
            .fileImporter(isPresented: $importing, allowedContentTypes: [.vCard, .commaSeparatedText, .plainText]) { result in
                if case .success(let url) = result { importFile(url) }
            }
            .alert("Import finished", isPresented: Binding(get: { importResult != nil }, set: { if !$0 { importResult = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(importResult ?? "")
            }
            .errorAlert($error)
        }
    }

    private func load() async {
        do {
            let b = try await API.shared.addressBooks()
            books = b
            if !b.contains(where: { $0.id == book }) { bookId = Int(b.first?.id ?? 0) }
            contacts = book == 0 ? [] : try await API.shared.contacts(book)
            loadError = nil
        } catch is CancellationError {
        } catch let e as URLError where e.code == .cancelled {
        } catch {
            loadError = errorText(error)
        }
    }

    private func importFile(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { error = String(localized: "Can't read the file"); return }
        attempt($error) {
            let r = try await API.shared.importContacts(book, data)
            importResult = String(localized: "Imported: \(r.imported ?? 0) · Updated: \(r.updated ?? 0) · Duplicates skipped: \(r.duplicates ?? 0)")
            await load()
        }
    }

    private func export() {
        let name = (books?.first { $0.id == book }?.name ?? "contacts") + ".vcf"
        attempt($error) {
            let data = try await API.shared.exportContacts(book)
            let url = FileCache.tempURL(name)
            try data.write(to: url)
            shareItem = ShareItem(items: [url])
        }
    }
}

private struct Initials: View {
    let name: String

    var body: some View {
        let letters = name.split(separator: " ").prefix(2).compactMap(\.first).map(String.init).joined().uppercased()
        Text(letters.isEmpty ? "?" : letters)
            .font(.subheadline.bold()).foregroundStyle(.white)
            .frame(width: 36, height: 36)
            .background(Circle().fill(Color.accentColor.gradient))
    }
}

struct ContactDetail: View {
    @State var contact: Contact
    let bookId: Int64
    let onChange: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var editing = false
    @State private var confirmDelete = false
    @State private var error: String?

    var body: some View {
        List {
            Section {
                VStack(spacing: 8) {
                    Initials(name: contact.displayName).scaleEffect(2).frame(height: 80)
                    Text(contact.displayName).font(.title2.bold())
                    let job = [contact.title, contact.org].filter { !$0.isEmpty }.joined(separator: " · ")
                    if !job.isEmpty { Text(job).foregroundStyle(.secondary) }
                }
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
            }
            if !contact.phones.isEmpty {
                Section("Phone") {
                    ForEach(contact.phones, id: \.self) { p in
                        Link(destination: URL(string: "tel:" + p.filter { "+0123456789".contains($0) }) ?? URL(string: "tel:")!) {
                            Label(p, systemImage: "phone")
                        }
                    }
                }
            }
            if !contact.emails.isEmpty {
                Section("Email") {
                    ForEach(contact.emails, id: \.self) { e in
                        Link(destination: URL(string: "mailto:" + e) ?? URL(string: "mailto:")!) { Label(e, systemImage: "envelope") }
                    }
                }
            }
            if !contact.address.isEmpty {
                Section("Address") { Text(contact.address).textSelection(.enabled) }
            }
            if let b = dayFormatter.date(from: contact.birthday) {
                Section("Birthday") { Text(b.formatted(date: .long, time: .omitted)) }
            }
            if !contact.note.isEmpty {
                Section("Notes") { Text(contact.note).textSelection(.enabled) }
            }
            Section {
                Button("Delete contact", role: .destructive) { confirmDelete = true }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { Button("Edit") { editing = true } }
        .sheet(isPresented: $editing) {
            ContactEditor(contact: contact, bookId: bookId) {
                onChange()
                Task {
                    if let fresh = try? await API.shared.contacts(bookId).first(where: { $0.uri == contact.uri }) { contact = fresh }
                }
            }
        }
        .confirmationDialog("Delete this contact?", isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("Delete", role: .destructive) {
                attempt($error) {
                    try await API.shared.deleteContact(bookId, uri: contact.uri)
                    onChange()
                    dismiss()
                }
            }
        }
        .errorAlert($error)
    }
}

struct ContactEditor: View {
    let bookId: Int64
    let onSaved: () -> Void
    @State private var c: Contact
    @State private var hasBirthday: Bool
    @State private var birthday: Date
    @State private var saving = false
    @State private var error: String?
    @Environment(\.dismiss) private var dismiss

    init(contact: Contact, bookId: Int64, onSaved: @escaping () -> Void) {
        self.bookId = bookId
        self.onSaved = onSaved
        var c = contact
        if c.emails.isEmpty { c.emails = [""] }
        if c.phones.isEmpty { c.phones = [""] }
        _c = State(initialValue: c)
        let b = dayFormatter.date(from: contact.birthday)
        _hasBirthday = State(initialValue: b != nil)
        _birthday = State(initialValue: b ?? Calendar.current.date(byAdding: .year, value: -30, to: Date())!)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("First name", text: $c.givenName).textContentType(.givenName)
                    TextField("Last name", text: $c.familyName).textContentType(.familyName)
                    TextField("Company", text: $c.org).textContentType(.organizationName)
                    TextField("Job title", text: $c.title).textContentType(.jobTitle)
                }
                Section("Phone") {
                    ForEach(c.phones.indices, id: \.self) { i in
                        TextField("Phone", text: $c.phones[i]).keyboardType(.phonePad).textContentType(.telephoneNumber)
                    }
                    .onDelete { c.phones.remove(atOffsets: $0) }
                    Button { c.phones.append("") } label: { Label("Add phone", systemImage: "plus.circle") }
                }
                Section("Email") {
                    ForEach(c.emails.indices, id: \.self) { i in
                        TextField("Email", text: $c.emails[i]).keyboardType(.emailAddress).textContentType(.emailAddress)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                    .onDelete { c.emails.remove(atOffsets: $0) }
                    Button { c.emails.append("") } label: { Label("Add email", systemImage: "plus.circle") }
                }
                Section {
                    TextField("Address", text: $c.address, axis: .vertical).lineLimit(2...5).textContentType(.fullStreetAddress)
                    Toggle("Birthday", isOn: $hasBirthday.animation())
                    if hasBirthday {
                        DatePicker("Birthday", selection: $birthday, in: ...Date(), displayedComponents: .date)
                    }
                }
                Section("Notes") {
                    TextField("Notes", text: $c.note, axis: .vertical).lineLimit(3...10)
                }
            }
            .navigationTitle(c.uri.isEmpty ? "New contact" : "Edit contact")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if saving { ProgressView() } else { Button("Save", action: save).bold().disabled(isEmpty) }
                }
            }
            .interactiveDismissDisabled(saving)
            .errorAlert($error)
        }
    }

    private var isEmpty: Bool {
        ([c.givenName, c.familyName, c.org] + c.emails + c.phones).allSatisfy { $0.trimmingCharacters(in: .whitespaces).isEmpty }
    }

    private func save() {
        var out = c
        out.emails = c.emails.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        out.phones = c.phones.map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        out.birthday = hasBirthday ? dayFormatter.string(from: birthday) : ""
        saving = true
        Task {
            do {
                try await API.shared.saveContact(bookId, out)
                onSaved()
                dismiss()
            } catch {
                self.error = errorText(error)
            }
            saving = false
        }
    }
}
