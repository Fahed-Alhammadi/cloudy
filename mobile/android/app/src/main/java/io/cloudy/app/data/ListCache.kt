package io.cloudy.app.data

/**
 * In-memory cache of recently shown listings, so screens appear instantly (also when going back) and are
 * refreshed in the background. Also remembers nodes seen in listings, so a folder's title shows before
 * its contents arrive. Cleared on sign-out.
 */
object ListCache {
    private const val MAX_FOLDERS = 100
    private val folders = object : LinkedHashMap<Long, NodeListing>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, NodeListing>?) = size > MAX_FOLDERS
    }
    private val nodes = HashMap<Long, FileNode>()
    private val lists = HashMap<String, List<FileNode>>()

    @Synchronized fun folder(id: Long): NodeListing? = folders[id]

    @Synchronized fun putFolder(listing: NodeListing) {
        folders[listing.node.id] = listing
        nodes[listing.node.id] = listing.node
        listing.children?.forEach { nodes[it.id] = it }
        if (nodes.size > 5_000) nodes.clear()
    }

    @Synchronized fun node(id: Long): FileNode? = nodes[id]

    @Synchronized fun list(key: String): List<FileNode>? = lists[key]
    @Synchronized fun putList(key: String, items: List<FileNode>) { lists[key] = items; items.forEach { nodes[it.id] = it } }

    @Synchronized fun clear() { folders.clear(); nodes.clear(); lists.clear() }
}
