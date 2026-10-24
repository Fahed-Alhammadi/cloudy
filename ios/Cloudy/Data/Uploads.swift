import Foundation
import Observation
import UIKit

/// Uploads in progress, shown at the top of the folder they go to. Each one asks iOS for background
/// time so a short upload finishes after the user leaves the app.
@Observable
final class Uploads {
    static let shared = Uploads()

    struct Job: Identifiable {
        let id = UUID()
        let name: String
        let parentId: Int64
        var sent: Int64 = 0
        var total: Int64 = 0
        var error: String?
        var fraction: Double { total > 0 ? Double(sent) / Double(total) : 0 }
    }

    private(set) var jobs: [Job] = []

    func jobs(in folder: Int64) -> [Job] { jobs.filter { $0.parentId == folder } }

    /// Uploads `file` (a local copy this class may delete afterwards) into `parentId`.
    @MainActor
    func start(_ file: URL, name: String, parentId: Int64, deleteAfter: Bool = true) {
        let job = Job(name: name, parentId: parentId)
        jobs.append(job)
        let bg = BackgroundTask()
        bg.id = UIApplication.shared.beginBackgroundTask(withName: "upload") { UIApplication.shared.endBackgroundTask(bg.id) }
        Task {
            do {
                _ = try await API.shared.upload(parentId, name: name, file: file) { sent, total in
                    DispatchQueue.main.async { self.update(job.id) { $0.sent = sent; $0.total = total } }
                }
                jobs.removeAll { $0.id == job.id }
                NotificationCenter.default.post(name: .cloudyChange, object: ChangeEvent(folders: [parentId]))
            } catch {
                update(job.id) { $0.error = errorText(error) }
            }
            if deleteAfter { try? FileManager.default.removeItem(at: file) }
            UIApplication.shared.endBackgroundTask(bg.id)
        }
    }

    @MainActor
    func dismiss(_ id: UUID) { jobs.removeAll { $0.id == id } }

    private func update(_ id: UUID, _ change: (inout Job) -> Void) {
        if let i = jobs.firstIndex(where: { $0.id == id }) { change(&jobs[i]) }
    }
}

private final class BackgroundTask: @unchecked Sendable {
    var id = UIBackgroundTaskIdentifier.invalid
}
