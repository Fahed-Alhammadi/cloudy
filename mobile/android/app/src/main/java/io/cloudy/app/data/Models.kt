package io.cloudy.app.data

import kotlinx.serialization.Serializable

@Serializable
data class User(
    val id: String,
    val displayName: String = "",
    val email: String = "",
    val isAdmin: Boolean = false,
    val quotaBytes: Long? = null,
    val used: Long = 0,
    val rootId: Long = 0,
)

@Serializable
data class TokenResponse(val token: String, val user: User)

@Serializable
data class FileNode(
    val id: Long,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val mime: String = "",
    val etag: String = "",
    val mtime: String = "",
    val owner: String = "",
    val parentId: Long? = null,
    val favorite: Boolean = false,
    val shared: Boolean = false,
    // Present on "shared with you" and search results.
    val canEdit: Boolean? = null,
    val ownerName: String? = null,
    val shareId: Long? = null,
    val shareType: String? = null,
    val path: String? = null,
)

@Serializable
data class Crumb(val id: Long, val name: String)

@Serializable
data class NodeListing(
    val node: FileNode,
    val perm: String,
    val breadcrumb: List<Crumb>,
    val children: List<FileNode>? = null,
)

@Serializable
data class TrashItem(
    val id: Long,
    val name: String,
    val isDir: Boolean,
    val size: Long = 0,
    val mime: String = "",
    val deletedAt: String = "",
    val originalPath: String? = null,
)

@Serializable
data class Share(
    val id: Long,
    val nodeId: Long,
    val type: String,
    val shareWith: String? = null,
    val canEdit: Boolean = false,
    val token: String? = null,
    val url: String? = null,
    val hasPassword: Boolean = false,
    val expiresAt: String? = null,
    val node: FileNode? = null,
)

@Serializable
data class UploadSession(val id: String, val chunkSize: Long, val received: Long = 0, val size: Long = 0)

@Serializable
data class UploadedNode(val id: Long, val name: String, val size: Long)

@Serializable
data class ChunkResult(val received: Long, val done: Boolean, val node: UploadedNode? = null)

@Serializable
data class Version(val id: Long, val size: Long, val mtime: String)

@Serializable
data class DavCollection(val id: Long, val uri: String, val name: String, val color: String = "")

@Serializable
data class CalEvent(
    val calendarId: Long,
    val uri: String,
    val uid: String = "",
    val summary: String = "",
    val location: String = "",
    val description: String = "",
    val start: String,
    val end: String,
    val allDay: Boolean = false,
    val recurring: Boolean = false,
    val rrule: String = "",
    val masterStart: String = "",
    val masterEnd: String = "",
)

@Serializable
data class EventInput(
    val summary: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
    val location: String,
    val description: String,
    val rrule: String,
)

@Serializable
data class Contact(
    val uri: String = "",
    val uid: String = "",
    val fn: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val emails: List<String> = emptyList(),
    val phones: List<String> = emptyList(),
    val org: String = "",
    val title: String = "",
    val address: String = "",
    val birthday: String = "",
    val note: String = "",
)

@Serializable
data class AppRelease(
    val versionName: String,
    val notes: String = "",
    val size: Long = 0,
    /** SHA-256 GitHub reports for the asset, when available. */
    val sha256: String? = null,
    /** Absolute download URL of the APK. */
    val url: String,
)

/** GitHub `GET /repos/{repo}/releases/latest`, only the fields we use. */
@Serializable
data class GithubRelease(val tag_name: String, val body: String? = null, val assets: List<GithubAsset> = emptyList())

@Serializable
data class GithubAsset(val name: String, val size: Long = 0, val browser_download_url: String, val digest: String? = null)

@Serializable
data class Sharees(val users: List<Sharee> = emptyList(), val groups: List<ShareeGroup> = emptyList())

@Serializable
data class Sharee(val id: String, val displayName: String = "")

@Serializable
data class ShareeGroup(val id: String)

/** Live update from `GET /api/events`: folders whose listing changed; `shares` = shared-with-me changed. */
@Serializable
data class ChangeEvent(val folders: List<Long> = emptyList(), val shares: Boolean = false)

/** `GET /api/office`: extensions the server can show as PDF previews (`/api/nodes/{id}/preview`). */
@Serializable
data class OfficeInfo(val enabled: Boolean = false, val extensions: List<String> = emptyList())

/** `POST /api/addressbooks/{id}/import` result. */
@Serializable
data class ImportResult(
    val format: String = "",
    val imported: Int = 0,
    val updated: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val errors: List<String> = emptyList(),
)
