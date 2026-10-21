package io.cloudy.app.ui

import androidx.activity.compose.BackHandler
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import io.cloudy.app.data.Office
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.outlined.Save
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import coil.compose.AsyncImage
import io.cloudy.app.Services
import io.cloudy.app.data.FileNode
import io.cloudy.app.data.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun kindOf(n: FileNode): String = when {
    Office.canPreview(n) -> "office"
    Regex("^image/(png|jpeg|gif|webp|bmp|heic|avif)$").matches(n.mime) -> "image"
    n.mime.startsWith("video/") || n.mime.startsWith("audio/") -> "media"
    n.mime == "application/pdf" -> "pdf"
    (n.mime.startsWith("text/") || n.mime.contains("json") || n.mime.contains("xml")) && n.size <= 1_000_000 -> "text"
    else -> "other"
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(nav: NavController, id: Long) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var node by remember { mutableStateOf<FileNode?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var canEdit by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) } // the text editor has unsaved changes
    var confirmLeave by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty) { confirmLeave = true }
    LaunchedEffect(id) {
        runCatching { Services.api.node(id) }
            .onSuccess { node = it.node; canEdit = it.perm != "read" }
            .onFailure { e ->
                // No connection: open the downloaded copy of an "available offline" file, read-only.
                val saved = Services.offline.savedNode(id)
                if (saved == null) error = e.message else { node = saved; toast(context, "Offline: showing the saved copy") }
            }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(node?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { if (dirty) confirmLeave = true else nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                node?.let { IconButton(onClick = { sheet = true }) { Icon(Icons.Outlined.MoreVert, "Actions") } }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize()) {
            val n = node
            when {
                error != null -> EmptyState(Icons.Outlined.CloudOff, error!!)
                n == null -> Loading()
                else -> when (kindOf(n)) {
                    "image" -> ZoomableImage(n)
                    "media" -> MediaPlayer(n)
                    "pdf" -> PdfView(n)
                    "office" -> PdfView(n, preview = true)
                    "text" -> TextView(n, canEdit, onDirty = { dirty = it }) { saved -> node = saved }
                    else -> Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        FileIcon(n, Modifier.padding(16.dp))
                        Text(n.name, style = MaterialTheme.typography.titleMedium)
                        Text(formatSize(n.size), color = MaterialTheme.colorScheme.outline)
                        Button(onClick = { scope.launch { runCatching { openExternally(context, n) }.onFailure { toast(context, it.message ?: "Failed") } } },
                            modifier = Modifier.padding(top = 16.dp)) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, null); Text("  Open with…")
                        }
                    }
                }
            }
        }
    }
    if (confirmLeave) ConfirmDialog("Discard changes?", "Your edits to this file haven't been saved.", "Discard", { confirmLeave = false }) {
        confirmLeave = false
        dirty = false
        nav.popBackStack()
    }
    if (sheet) node?.let { n ->
        NodeActions(n, nav, canModify = n.owner == Services.session.user.value?.id, reload = {}, scope = scope) { sheet = false }
    }
}

@Composable
private fun ZoomableImage(n: FileNode) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan
    }
    val model: Any = Services.offline.localFile(n) ?: Services.api.downloadUrl(n.id, inline = true)
    Box(Modifier.fillMaxSize().background(Color.Black).transformable(state)) {
        AsyncImage(model = model, contentDescription = n.name, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y))
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun MediaPlayer(n: FileNode) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Services.api.http)))
            .build().apply {
                val local = Services.offline.localFile(n)
                setMediaItem(MediaItem.fromUri(local?.toURI()?.toString() ?: Services.api.downloadUrl(n.id, inline = true)))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    AndroidView({ PlayerView(it).apply { this.player = player } }, Modifier.fillMaxSize().background(Color.Black))
}

/** A PDF, or with [preview] an office document converted to PDF by the server. Cached per file version. */
@Composable
private fun PdfView(n: FileNode, preview: Boolean = false) {
    val context = LocalContext.current
    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(n.id, n.etag) {
        try {
            val file = withContext(Dispatchers.IO) {
                (if (preview) null else Services.offline.localFile(n)) ?: File(context.cacheDir, "pdf/${n.id}-${n.etag}.pdf").also {
                    if (!it.exists()) it.parentFile?.listFiles()?.filter { f -> f.name.startsWith("${n.id}-") }?.forEach { f -> f.delete() }
                }
            }
            if (!file.exists()) {
                if (preview) Services.api.downloadUrlTo(Services.api.previewUrl(n.id), file) else Services.api.download(n.id, file)
            }
            doc = withContext(Dispatchers.IO) { PdfDoc(file) }
        } catch (e: Exception) { error = if (preview) "Preview not available: ${e.message}" else e.message }
    }
    DisposableEffect(doc) { val d = doc; onDispose { d?.close() } }
    val d = doc
    when {
        error != null -> EmptyState(Icons.Outlined.CloudOff, error!!)
        d == null -> Loading()
        else -> BoxWithConstraints(Modifier.fillMaxSize()) {
            val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }.coerceIn(480, 1600)
            // Pages render on demand as they scroll into view, one at a time.
            LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(d.pageCount) { i ->
                    var bmp by remember(i) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(i, widthPx) { bmp = d.render(i, widthPx) }
                    val b = bmp
                    if (b != null) Image(b.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                    else Box(Modifier.fillMaxWidth().aspectRatio(d.aspect(i)).background(Color.White))
                }
            }
        }
    }
}

/** An open PDF; renders single pages on a background thread (PdfRenderer allows one open page at a time). */
private class PdfDoc(file: File) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private val aspects = HashMap<Int, Float>()
    private var closed = false
    val pageCount = renderer.pageCount

    /** Width / height of page [i] (A4 portrait until the page has been opened once). */
    fun aspect(i: Int): Float = aspects[i] ?: (1f / 1.414f)

    suspend fun render(i: Int, width: Int): Bitmap? = lock.withLock {
        if (closed) return@withLock null
        withContext(Dispatchers.IO) {
            renderer.openPage(i).use { p ->
                aspects[i] = p.width.toFloat() / p.height
                val bmp = Bitmap.createBitmap(width, (width.toFloat() * p.height / p.width).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

    fun close() {
        // In the background, after a render in progress has finished (never block the UI thread).
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch { lock.withLock { closed = true; renderer.close(); fd.close() } }
    }
}

private val draftSaver = Saver<String?, String>(save = { it?.takeIf { s -> s.length <= 100_000 } }, restore = { it })

/** Text file; editable (with Save) when the user may change it. Saving keeps the old content as a version. */
@Composable
private fun TextView(n: FileNode, canEdit: Boolean, onDirty: (Boolean) -> Unit, onSaved: (FileNode) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<String?>(null) }
    // The draft survives rotation (up to a size the saved-state bundle can hold); null until the file is loaded.
    var draft by rememberSaveable(n.id, stateSaver = draftSaver) { mutableStateOf<String?>(null) }
    val text = draft ?: ""
    var failed by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    LaunchedEffect(n.id) {
        runCatching { Services.offline.localFile(n)?.readText() ?: Services.api.downloadText(n.id) }
            .onSuccess { loaded = it; if (draft == null) draft = it }
            .onFailure { failed = it.message }
    }
    val original = loaded
    val changed = canEdit && original != null && text != original
    LaunchedEffect(changed) { onDirty(changed) }
    when {
        failed != null -> EmptyState(Icons.Outlined.CloudOff, failed!!)
        original == null -> Loading()
        !canEdit -> Text(original, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp))
        // imePadding: edge-to-edge, so the editor must shrink above the keyboard itself (adjustResize no longer does it).
        else -> Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (text != original) "Unsaved changes" else "Saved", Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelMedium)
                Button(enabled = text != original && !saving, onClick = {
                    saving = true
                    scope.launch {
                        runCatching { Services.api.saveText(n.id, text) }
                            .onSuccess { loaded = text; onSaved(it); toast(context, "Saved") }
                            .onFailure { toast(context, it.message ?: "Save failed") }
                        saving = false
                    }
                }) { Icon(Icons.Outlined.Save, null); Text("  Save") }
            }
            OutlinedTextField(
                value = text, onValueChange = { draft = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp).padding(bottom = 8.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- trash

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(nav: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<TrashItem>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    suspend fun load() { items = runCatching { Services.api.trash() }.getOrElse { toast(context, it.message ?: "Failed"); emptyList() } }
    LaunchedEffect(Unit) { load() }
    fun act(block: suspend () -> Unit) = scope.launch { runCatching { block() }.onFailure { toast(context, it.message ?: "Failed") }; load() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Deleted files") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (!items.isNullOrEmpty()) IconButton(onClick = { confirmEmpty = true }) { Icon(Icons.Outlined.DeleteSweep, "Empty trash") } },
        )
    }) { pad ->
        Box(Modifier.padding(pad)) {
            val list = items
            when {
                list == null -> Loading()
                list.isEmpty() -> EmptyState(Icons.Outlined.DeleteSweep, "Trash is empty")
                else -> LazyColumn {
                    items(list, key = { it.id }) { t ->
                        ListItem(
                            leadingContent = { FileIcon(FileNode(t.id, t.name, t.isDir, mime = t.mime)) },
                            headlineContent = { Text(t.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${t.originalPath ?: ""} · ${formatDate(t.deletedAt)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            trailingContent = {
                                androidx.compose.foundation.layout.Row {
                                    IconButton(onClick = { act { Services.api.restore(t.id) } }) { Icon(Icons.Outlined.Restore, "Restore") }
                                    IconButton(onClick = { act { Services.api.purge(t.id) } }) { Icon(Icons.Outlined.DeleteForever, "Delete permanently") }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (confirmEmpty) ConfirmDialog("Empty trash", "All deleted files will be removed permanently.", "Empty", { confirmEmpty = false }) {
        confirmEmpty = false
        act { Services.api.emptyTrash() }
    }
}
