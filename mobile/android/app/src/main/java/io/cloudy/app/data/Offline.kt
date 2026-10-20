package io.cloudy.app.data

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Keeps pinned files and folders on the device.
 * Layout under filesDir/offline:
 *   files/<nodeId>/<name>     downloaded content
 *   dirs/<nodeId>.json        cached folder listing (for browsing without network)
 *   state.json                pinned ids + etag of each downloaded file
 */
class Offline(context: Context, private val api: Api) {
    private val root = File(context.filesDir, "offline")
    private val json = Json { ignoreUnknownKeys = true }

    @kotlinx.serialization.Serializable
    private data class State(val pinned: Set<Long> = emptySet(), val etags: Map<Long, String> = emptyMap())

    private val stateFile = File(root, "state.json")
    private var state: State = runCatching { json.decodeFromString<State>(stateFile.readText()) }.getOrDefault(State())

    private fun save() {
        root.mkdirs()
        stateFile.writeText(json.encodeToString(state))
    }

    fun isPinned(id: Long) = id in state.pinned
    val pinned: Set<Long> get() = state.pinned

    fun localFile(node: FileNode): File? =
        File(root, "files/${node.id}/${node.name}").takeIf { it.exists() && state.etags[node.id] == node.etag }

    /** Any downloaded copy, even if outdated (used when offline). */
    fun anyLocalFile(id: Long): File? = File(root, "files/$id").listFiles()?.firstOrNull()

    fun hasCachedListing(id: Long): Boolean = File(root, "dirs/$id.json").exists()

    fun cachedListing(id: Long): NodeListing? =
        runCatching { json.decodeFromString<NodeListing>(File(root, "dirs/$id.json").readText()) }.getOrNull()

    fun cacheListing(listing: NodeListing) {
        File(root, "dirs").mkdirs()
        File(root, "dirs/${listing.node.id}.json").writeText(json.encodeToString(listing))
    }

    suspend fun pin(node: FileNode) {
        state = state.copy(pinned = state.pinned + node.id)
        save()
        sync()
    }

    fun unpin(id: Long) {
        state = state.copy(pinned = state.pinned - id)
        save()
        cleanup()
    }

    /** Downloads everything pinned that changed on the server. Returns the number of files fetched. */
    suspend fun sync(onProgress: (String) -> Unit = {}): Int {
        var fetched = 0
        val keep = mutableSetOf<Long>()
        suspend fun visit(id: Long) {
            val listing = api.node(id)
            val n = listing.node
            keep += n.id
            if (n.isDir) {
                cacheListing(listing)
                for (c in listing.children.orEmpty()) {
                    if (c.isDir) visit(c.id) else fetchFile(c, keep)?.let { fetched++; onProgress(c.name) }
                }
            } else if (fetchFile(n, keep) != null) {
                fetched++
                onProgress(n.name)
            }
        }
        for (id in state.pinned) {
            try {
                visit(id)
            } catch (e: ApiException) {
                if (e.status == 404) state = state.copy(pinned = state.pinned - id)
            }
        }
        save()
        cleanup(keep)
        return fetched
    }

    private suspend fun fetchFile(n: FileNode, keep: MutableSet<Long>): File? {
        keep += n.id
        if (localFile(n) != null) return null
        val dir = File(root, "files/${n.id}")
        dir.deleteRecursively()
        val dest = File(dir, n.name)
        api.download(n.id, dest)
        state = state.copy(etags = state.etags + (n.id to n.etag))
        return dest
    }

    /** Deletes content no longer reachable from a pinned item. */
    private fun cleanup(keep: Set<Long>? = null) {
        val live = keep ?: return run {
            if (state.pinned.isEmpty()) {
                File(root, "files").deleteRecursively()
                File(root, "dirs").deleteRecursively()
                state = State()
                save()
            }
        }
        File(root, "files").listFiles()?.forEach { f -> if (f.name.toLongOrNull() !in live) f.deleteRecursively() }
        File(root, "dirs").listFiles()?.forEach { f -> if (f.nameWithoutExtension.toLongOrNull() !in live) f.delete() }
        state = state.copy(etags = state.etags.filterKeys { it in live })
        save()
    }

    fun usedBytes(): Long = File(root, "files").walkBottomUp().filter { it.isFile }.sumOf { it.length() }
}
