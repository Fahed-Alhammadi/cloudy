package io.cloudy.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadForOffline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.navigation.NavController
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.cloudy.app.Services
import io.cloudy.app.data.ApiException
import io.cloudy.app.data.FileNode
import io.cloudy.app.data.NodeListing
import io.cloudy.app.data.Live
import io.cloudy.app.data.ListCache
import io.cloudy.app.data.Share
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/** Upload progress shown above the file list. */
class UploadState {
    var name by mutableStateOf<String?>(null)
    var progress by mutableStateOf(0f)
    var remaining by mutableIntStateOf(0)
}

fun queryName(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment ?: "file"
    var size = -1L
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    return name to size
}

suspend fun uploadUris(context: Context, parentId: Long, uris: List<Uri>, state: UploadState): Int {
    var ok = 0
    state.remaining = uris.size
    for (uri in uris) {
        val (name, knownSize) = queryName(context, uri)
        state.name = name
        state.progress = 0f
        try {
            // Unknown size: copy to cache first so the server gets an exact length.
            var source: () -> java.io.InputStream = { context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot read $name") }
            var size = knownSize
            var temp: File? = null
            if (size < 0) {
                temp = File(context.cacheDir, "up-${System.nanoTime()}")
                source().use { i -> temp.outputStream().use { i.copyTo(it) } }
                size = temp.length()
                source = { temp.inputStream() }
            }
            Services.api.upload(parentId, name, size, source) { sent -> state.progress = if (size > 0) sent.toFloat() / size else 1f }
            temp?.delete()
            ok++
        } catch (e: Exception) {
            toast(context, "$name: ${e.message}")
        }
        state.remaining--
    }
    state.name = null
    return ok
}

/** The file on the device for handing to another app: the offline copy, or a download into the cache. */
private suspend fun localCopy(context: Context, node: FileNode): Uri {
    val file = Services.offline.localFile(node) ?: File(context.cacheDir, "open/${node.id}-${node.etag}/${node.name}").also {
        if (!it.exists()) {
            if (node.size > 5_000_000) toast(context, "Downloading ${node.name}…")
            it.parentFile?.parentFile?.listFiles()?.filter { d -> d.name.startsWith("${node.id}-") }?.forEach { d -> d.deleteRecursively() }
            Services.api.download(node.id, it)
        }
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
}

/** Sends the file itself (not a link) to another app: WhatsApp, Telegram, email, Bluetooth… */
suspend fun sendToApp(context: Context, node: FileNode) {
    val uri = localCopy(context, node)
    val intent = Intent(Intent.ACTION_SEND).setType(node.mime.ifEmpty { "application/octet-stream" })
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = ClipData.newRawUri(node.name, uri) } // grants read access to the chosen app
    context.startActivity(Intent.createChooser(intent, "Send ${node.name}"))
}

/** Downloads (or reuses the offline copy) and opens a file in another app. */
suspend fun openExternally(context: Context, node: FileNode) {
    val uri = localCopy(context, node)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, node.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (_: ActivityNotFoundException) {
        toast(context, "No app can open this file")
    }
}

// ---------------------------------------------------------------- folder screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderScreen(nav: NavController, folderId: Long, sharedUris: MutableState<List<Uri>>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Last known contents show at once; load() refreshes them.
    var listing by remember { mutableStateOf(ListCache.folder(folderId)) }
    var offlineMode by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var newFolder by remember { mutableStateOf(false) }
    var newTextFile by remember { mutableStateOf(false) }
    val uploads = remember { UploadState() }
    val snackbar = remember { SnackbarHostState() }

    suspend fun load() {
        try {
            val l = Services.api.node(folderId)
            listing = l
            ListCache.putFolder(l)
            offlineMode = false
            error = null
            withContext(Dispatchers.IO) { if (Services.offline.hasCachedListing(folderId)) Services.offline.cacheListing(l) }
        } catch (e: ApiException) {
            error = e.message
        } catch (e: IOException) {
            val cached = withContext(Dispatchers.IO) { Services.offline.cachedListing(folderId) }
            if (cached != null) { listing = cached; offlineMode = true } else error = "Offline: ${e.message}"
        }
    }
    LaunchedEffect(folderId) { load() }
    // Live updates: reload when this folder changes on any device (short delay batches bursts like uploads).
    LaunchedEffect(folderId) { Live.events.filter { folderId in it.folders }.collectLatest { delay(400); load() } }
    val reload: () -> Unit = { scope.launch { load() } }
    val canEdit = listing?.perm != "read" && !offlineMode

    fun startUpload(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val n = uploadUris(context, folderId, uris, uploads)
            snackbar.showSnackbar("Uploaded $n of ${uris.size} file(s)")
            load()
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { startUpload(it) }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { startUpload(it) }

    val known = listing?.node ?: ListCache.node(folderId)
    val title = known?.let { n -> if (n.parentId == null) "Files" else n.name } ?: ""
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (known?.parentId != null) IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                actions = { IconButton(onClick = { nav.navigate("search") }) { Icon(Icons.Outlined.Search, "Search") } },
            )
        },
        floatingActionButton = {
            if (canEdit) Box {
                FloatingActionButton(onClick = { menuOpen = true }, modifier = Modifier.sideInsets()) { Icon(Icons.Filled.Add, "Add") }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem({ Text("Upload photos & videos") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = {
                        menuOpen = false; pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                    })
                    DropdownMenuItem({ Text("Upload files") }, leadingIcon = { Icon(Icons.Outlined.UploadFile, null) }, onClick = {
                        menuOpen = false; pickFiles.launch(arrayOf("*/*"))
                    })
                    DropdownMenuItem({ Text("New folder") }, leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) }, onClick = {
                        menuOpen = false; newFolder = true
                    })
                    DropdownMenuItem({ Text("New text file") }, leadingIcon = { Icon(Icons.Outlined.NoteAdd, null) }, onClick = {
                        menuOpen = false; newTextFile = true
                    })
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (offlineMode) Text("Offline — showing saved copy", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium)
            if (listing?.perm == "read") Text("Read-only shared folder", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium)
            uploads.name?.let { name ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Uploading $name (${uploads.remaining} left)", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { uploads.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            if (sharedUris.value.isNotEmpty() && canEdit) {
                ListItem(
                    headlineContent = { Text("Upload ${sharedUris.value.size} shared item(s) here") },
                    leadingContent = { Icon(Icons.Outlined.UploadFile, null) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { sharedUris.value = emptyList() }) { Text("Dismiss") }
                            TextButton(onClick = { val u = sharedUris.value; sharedUris.value = emptyList(); startUpload(u) }) { Text("Upload") }
                        }
                    },
                )
            }
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { scope.launch { refreshing = true; load(); refreshing = false } }) {
                val l = listing
                when {
                    error != null && l == null -> EmptyState(Icons.Outlined.CloudOff, error!!)
                    l == null -> Loading()
                    l.children.isNullOrEmpty() -> EmptyState(Icons.Outlined.FolderOpen, if (canEdit) "Empty folder — tap + to upload" else "Empty folder")
                    else -> NodeList(l.children!!, nav, canModify = { canEdit }, reload = reload, scope = scope)
                }
            }
        }
    }
    if (newFolder) TextPrompt("New folder", "New folder", confirm = "Create", onDismiss = { newFolder = false }) { name ->
        newFolder = false
        scope.launch { runCatching { Services.api.mkdir(folderId, name) }.onFailure { toast(context, it.message ?: "Failed") }; load() }
    }
    if (newTextFile) TextPrompt("New text file", "Notes.txt", confirm = "Create", onDismiss = { newTextFile = false }) { name ->
        newTextFile = false
        val fileName = if ('.' in name) name else "$name.txt"
        scope.launch {
            runCatching { Services.api.createTextFile(folderId, fileName) }
                .onSuccess { load(); nav.navigate("preview/${it.id}") }
                .onFailure { toast(context, it.message ?: "Failed") }
        }
    }
}

// ---------------------------------------------------------------- shared list + actions

fun openNode(nav: NavController, node: FileNode) {
    when {
        node.isDir -> nav.navigate("files/${node.id}")
        else -> nav.navigate("preview/${node.id}")
    }
}

@Composable
fun NodeList(
    items: List<FileNode>,
    nav: NavController,
    canModify: (FileNode) -> Boolean,
    reload: () -> Unit,
    scope: CoroutineScope,
    subtitle: (FileNode) -> String? = { null },
) {
    var sheetFor by remember { mutableStateOf<FileNode?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { node ->
            ListItem(
                modifier = Modifier.clickable { openNode(nav, node) },
                leadingContent = { FileIcon(node) },
                headlineContent = { Text(node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Text(subtitle(node) ?: listOfNotNull(if (node.isDir) null else formatSize(node.size), formatDate(node.mtime).ifEmpty { null }).joinToString(" · "),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (node.favorite) Icon(Icons.Filled.Star, null, tint = androidx.compose.ui.graphics.Color(0xFFE5A50A), modifier = Modifier.size(16.dp))
                        if (Services.offline.isPinned(node.id)) Icon(Icons.Outlined.CloudDone, "Available offline", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        if (node.shared) Icon(Icons.Outlined.Share, "Shared", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                        IconButton(onClick = { sheetFor = node }) { Icon(Icons.Outlined.MoreVert, "Actions for ${node.name}") }
                    }
                },
            )
        }
    }
    sheetFor?.let { node -> NodeActions(node, nav, canModify(node), reload, scope) { sheetFor = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeActions(node: FileNode, nav: NavController, canModify: Boolean, reload: () -> Unit, scope: CoroutineScope, onClose: () -> Unit) {
    val context = LocalContext.current
    val me = Services.session.user.value
    val isOwner = node.owner == me?.id
    var dialog by remember { mutableStateOf<String?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(node.mime.ifEmpty { "application/octet-stream" })) { uri ->
        if (uri != null) scope.launch {
            try {
                val tmp = File(context.cacheDir, "save-${node.id}")
                Services.api.download(node.id, tmp)
                context.contentResolver.openOutputStream(uri)?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
                toast(context, "Saved")
            } catch (e: Exception) { toast(context, e.message ?: "Failed") }
        }
    }

    fun act(block: suspend () -> Unit) {
        onClose()
        scope.launch {
            try { block() } catch (e: Exception) { toast(context, e.message ?: "Failed") }
            reload()
        }
    }

    if (dialog == null) ModalBottomSheet(onDismissRequest = onClose) {
        Text(node.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp), maxLines = 2)
        HorizontalDivider()
        @Composable
        fun Item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, danger: Boolean = false, onClick: () -> Unit) =
            ListItem(
                modifier = Modifier.clickable(onClick = onClick),
                leadingContent = { Icon(icon, null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
                headlineContent = { Text(label, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) },
            )
        if (!node.isDir) Item("Send to…", Icons.AutoMirrored.Outlined.Send) { onClose(); scope.launch { runCatching { sendToApp(context, node) }.onFailure { toast(context, it.message ?: "Failed") } } }
        if (!node.isDir) Item("Open with…", Icons.AutoMirrored.Outlined.OpenInNew) { onClose(); scope.launch { runCatching { openExternally(context, node) }.onFailure { toast(context, it.message ?: "Failed") } } }
        Item(if (node.isDir) "Save as ZIP…" else "Save to device…", Icons.Outlined.Download) { onClose(); saveAs.launch(node.name + if (node.isDir) ".zip" else "") }
        if (Services.offline.isPinned(node.id)) Item("Remove offline copy", Icons.Outlined.CloudOff) { act { Services.offline.unpin(node.id) } }
        else Item("Make available offline", Icons.Outlined.DownloadForOffline) { act { Services.offline.pin(node); toast(context, "Available offline") } }
        if (isOwner) {
            Item("Share link", Icons.Outlined.Link) { dialog = "link" }
            Item("Share with people", Icons.Outlined.PersonAdd) { dialog = "people" }
            if (node.shared) Item("Manage sharing", Icons.Outlined.ManageAccounts) { dialog = "shares" }
        }
        Item(if (node.favorite) "Remove from favorites" else "Add to favorites", if (node.favorite) Icons.Outlined.StarOutline else Icons.Outlined.Star) {
            act { Services.api.setFavorite(node.id, !node.favorite) }
        }
        if (canModify) {
            Item("Rename", Icons.Outlined.Edit) { dialog = "rename" }
            Item("Move or copy", Icons.AutoMirrored.Outlined.DriveFileMove) { dialog = "move" }
            Item("Delete", Icons.Outlined.Delete, danger = true) { dialog = "delete" }
        }
        node.shareId?.takeIf { node.shareType == "user" }?.let { sid ->
            Item("Leave share", Icons.Outlined.Delete, danger = true) { act { Services.api.deleteShare(sid) } }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }

    when (dialog) {
        "rename" -> TextPrompt("Rename", node.name, confirm = "Rename", onDismiss = onClose) { name -> act { Services.api.rename(node.id, name) } }
        "delete" -> ConfirmDialog("Delete", "Move \"${node.name}\" to the trash?", "Delete", onClose) { act { Services.api.delete(node.id) } }
        "move" -> FolderPicker(node, onDismiss = onClose) { dest, copy -> act { if (copy) Services.api.copy(node.id, dest) else Services.api.move(node.id, dest) } }
        "link" -> LinkDialog(node, onClose)
        "people" -> PeopleDialog(node, onClose)
        "shares" -> SharesDialog(node) { act {} }
    }
}

@Composable
fun FolderPicker(node: FileNode, onDismiss: () -> Unit, onPick: (Long, Boolean) -> Unit) {
    val rootId = Services.session.user.value?.rootId ?: return
    var current by remember { mutableStateOf(rootId) }
    var listing by remember { mutableStateOf<NodeListing?>(null) }
    LaunchedEffect(current) { listing = runCatching { Services.api.node(current) }.getOrNull() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Move or copy \"${node.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text(listing?.breadcrumb?.joinToString(" / ") { it.name.ifEmpty { "Files" } } ?: "…", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    listing?.node?.parentId?.let { parent ->
                        item { ListItem(modifier = Modifier.clickable { current = parent }, headlineContent = { Text("..") }, leadingContent = { Icon(Icons.Outlined.Folder, null) }) }
                    }
                    items(listing?.children.orEmpty().filter { it.isDir && it.id != node.id }, key = { it.id }) { d ->
                        ListItem(modifier = Modifier.clickable { current = d.id }, headlineContent = { Text(d.name) }, leadingContent = { FileIcon(d, Modifier.size(24.dp)) })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(current, false) }) { Text("Move here") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onPick(current, true) }) { Text("Copy here") }
            }
        },
    )
}

@Composable
fun LinkDialog(node: FileNode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Anyone with the link can open \"${node.name}\".")
                OutlinedTextField(password, { password = it }, label = { Text("Password (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val s = Services.api.createLink(node.id, password)
                        shareText(context, Services.api.url(s.url ?: ""))
                        onDismiss()
                    } catch (e: Exception) { toast(context, e.message ?: "Failed"); busy = false }
                }
            }) { Text("Create & share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Lists a node's links and user/group shares, so they can be re-sent or stopped. */
@Composable
fun SharesDialog(node: FileNode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var shares by remember { mutableStateOf<List<Share>?>(null) }
    var stopping by remember { mutableStateOf<Share?>(null) }
    suspend fun load() { shares = runCatching { Services.api.shares(node.id) }.getOrElse { toast(context, it.message ?: "Failed"); emptyList() } }
    LaunchedEffect(node.id) { load() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sharing \"${node.name}\"", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            val list = shares
            when {
                list == null -> Loading()
                list.isEmpty() -> Text("Not shared any more.")
                else -> Column(Modifier.verticalScroll(rememberScrollState())) {
                    list.forEach { sh ->
                        val link = sh.type == "link"
                        ListItem(
                            leadingContent = { Icon(if (link) Icons.Outlined.Link else Icons.Outlined.PersonAdd, null) },
                            headlineContent = { Text(if (link) "Public link" else (sh.shareWith ?: ""), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(listOfNotNull(
                                    if (sh.canEdit) "can edit" else if (link) null else "read only",
                                    if (sh.hasPassword) "password" else null,
                                    sh.expiresAt?.let { "expires ${formatDate(it)}" },
                                ).joinToString(" · ").ifEmpty { "anyone with the link" })
                            },
                            trailingContent = {
                                Row {
                                    if (link && sh.url != null) IconButton(onClick = { shareText(context, Services.api.url(sh.url)) }) { Icon(Icons.Outlined.Share, "Send link") }
                                    IconButton(onClick = { stopping = sh }) { Icon(Icons.Outlined.LinkOff, "Stop sharing", tint = MaterialTheme.colorScheme.error) }
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
    stopping?.let { sh ->
        ConfirmDialog("Stop sharing",
            if (sh.type == "link") "The link stops working for everyone who has it." else "${sh.shareWith} loses access to \"${node.name}\".",
            "Stop sharing", { stopping = null }) {
            stopping = null
            scope.launch {
                runCatching { Services.api.deleteShare(sh.id) }.onFailure { toast(context, it.message ?: "Failed") }
                load()
            }
        }
    }
}

@Composable
fun PeopleDialog(node: FileNode, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var target by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(false) }
    var suggestions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(target) {
        if (target.length >= 1) runCatching { Services.api.sharees(target.removePrefix("group:")) }.onSuccess { s ->
            suggestions = s.users.map { it.id to it.displayName } + s.groups.map { "group:${it.id}" to "Group ${it.id}" }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share with people") },
        text = {
            Column {
                OutlinedTextField(target, { target = it }, label = { Text("Username or group:name") }, singleLine = true)
                suggestions.take(5).forEach { (id, label) ->
                    Text("$label ($id)", Modifier.fillMaxWidth().clickable { target = id }.padding(vertical = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(canEdit, { canEdit = it }); Text("Can edit")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = target.isNotBlank(), onClick = {
                scope.launch {
                    try { Services.api.shareWith(node.id, target.trim(), canEdit); toast(context, "Shared"); onDismiss() }
                    catch (e: Exception) { toast(context, e.message ?: "Failed") }
                }
            }) { Text("Share") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- list screens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeListScreen(nav: NavController, kind: String) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf(ListCache.list(kind)) }
    var error by remember { mutableStateOf<String?>(null) }
    val me = Services.session.user.value
    val title = mapOf("recent" to "Recent", "favorites" to "Favorites", "shared-in" to "Shared with you", "shared-out" to "Shared by you", "offline" to "Available offline")[kind] ?: kind
    var outgoing by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }

    suspend fun load() {
        try {
            items = when (kind) {
                "recent" -> Services.api.recent()
                "favorites" -> Services.api.favorites()
                "shared-in" -> Services.api.sharedWithMe()
                "shared-out" -> Services.api.sharedByMe().also { list ->
                    outgoing = list.groupBy { it.nodeId }.mapValues { (_, s) -> s.joinToString { if (it.type == "link") "link" else it.shareWith ?: "" } }
                }.mapNotNull { it.node }.distinctBy { it.id }
                "offline" -> coroutineScope {
                    Services.offline.pinned.map { id ->
                        async { runCatching { Services.api.node(id).node }.getOrNull() ?: Services.offline.savedNode(id) }
                    }.awaitAll().filterNotNull()
                }
                else -> emptyList()
            }.also { ListCache.putList(kind, it) }
            error = null
        } catch (e: Exception) { if (items == null) error = e.message }
    }
    LaunchedEffect(kind) { load() }
    LaunchedEffect(kind) {
        if (kind != "offline") Live.events.filter { it.folders.isNotEmpty() || it.shares }.collectLatest { delay(400); load() }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Box(Modifier.padding(pad)) {
            val list = items
            when {
                error != null && list == null -> EmptyState(Icons.Outlined.CloudOff, error!!)
                list == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.FolderOpen, "Nothing here yet")
                else -> NodeList(list, nav, canModify = { it.owner == me?.id }, reload = { scope.launch { load() } }, scope = scope, subtitle = { n ->
                    when (kind) {
                        "shared-in" -> "From ${n.ownerName ?: n.owner}" + if (n.canEdit == true) " · can edit" else ""
                        "shared-out" -> "With ${outgoing[n.id]}"
                        else -> null
                    }
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    var q by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<FileNode>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() } // open the keyboard right away
    LaunchedEffect(q) {
        kotlinx.coroutines.delay(250)
        results = if (q.isBlank()) emptyList() else runCatching { Services.api.search(q.trim()) }.getOrDefault(emptyList())
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { OutlinedTextField(q, { q = it }, placeholder = { Text("Search files") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(end = 8.dp).focusRequester(focus)) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Box(Modifier.padding(pad)) {
            if (q.isNotBlank() && results.isEmpty()) EmptyState(Icons.Outlined.Search, "No matches")
            else NodeList(results, nav, canModify = { true }, reload = {}, scope = scope, subtitle = { it.path })
        }
    }
}

@Suppress("unused")
private val keepWidth = Modifier.width(0.dp)
