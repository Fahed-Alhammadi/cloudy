package io.filecloud.app.ui

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
import androidx.compose.foundation.layout.fillMaxSize
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
import io.filecloud.app.Services
import io.filecloud.app.data.FileNode
import io.filecloud.app.data.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun kindOf(n: FileNode): String = when {
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
    LaunchedEffect(id) { runCatching { Services.api.node(id).node }.onSuccess { node = it }.onFailure { error = it.message } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(node?.name ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                node?.let { IconButton(onClick = { sheet = true }) { Icon(Icons.Outlined.MoreVert, "Actions") } }
            },
        )
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val n = node
            when {
                error != null -> EmptyState(Icons.Outlined.CloudOff, error!!)
                n == null -> Loading()
                else -> when (kindOf(n)) {
                    "image" -> ZoomableImage(n)
                    "media" -> MediaPlayer(n)
                    "pdf" -> PdfView(n)
                    "text" -> TextView(n)
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

@Composable
private fun PdfView(n: FileNode) {
    val context = LocalContext.current
    var pages by remember { mutableStateOf<List<Bitmap>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(n.id) {
        try {
            val file = Services.offline.localFile(n) ?: File(context.cacheDir, "pdf/${n.id}.pdf").also { if (!it.exists()) Services.api.download(n.id, it) }
            pages = withContext(Dispatchers.IO) {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                    (0 until minOf(r.pageCount, 200)).map { i ->
                        r.openPage(i).use { p ->
                            val w = 1240
                            val bmp = Bitmap.createBitmap(w, (w.toFloat() * p.height / p.width).toInt(), Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            bmp
                        }
                    }
                }
            }
        } catch (e: Exception) { error = e.message }
    }
    when {
        error != null -> EmptyState(Icons.Outlined.CloudOff, error!!)
        pages == null -> Loading()
        else -> LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(pages!!) { bmp -> Image(bmp.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
        }
    }
}

@Composable
private fun TextView(n: FileNode) {
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(n.id) {
        text = Services.offline.localFile(n)?.readText() ?: runCatching { Services.api.downloadText(n.id) }.getOrElse { "Error: ${it.message}" }
    }
    if (text == null) Loading()
    else Text(text!!, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp))
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
