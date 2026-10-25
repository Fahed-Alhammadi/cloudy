package io.cloudy.app.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.cloudy.app.R
import io.cloudy.app.data.FileNode

// Multi-select, the same on Android, iOS and the web: long-press a file → "Select", tap more files, then act on
// all of them from the bar at the bottom.

/** The bar shown while files are selected. [onMove]/[onDelete] are null when none of them may be changed. */
@Composable
fun SelectionBar(
    count: Int,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onSend: () -> Unit,
    onFavorite: () -> Unit,
    onMove: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp).sideInsets(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.cancel)) }
            Text(stringResource(R.string.n_selected, count), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.send_to)) }
            onMove?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.move_or_copy)) } }
            onDelete?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error) } }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.more)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(stringResource(R.string.select_all)) }, leadingIcon = { Icon(Icons.Outlined.SelectAll, null) },
                        onClick = { menu = false; onSelectAll() })
                    DropdownMenuItem({ Text(stringResource(R.string.add_favorite)) }, leadingIcon = { Icon(Icons.Outlined.Star, null) },
                        onClick = { menu = false; onFavorite() })
                }
            }
        }
    }
}

/** Sends several files (not links) to another app in one go; one file uses the normal single send. */
suspend fun sendManyToApp(context: Context, nodes: List<FileNode>) {
    if (nodes.size == 1) return sendToApp(context, nodes[0])
    val uris = ArrayList<Uri>(nodes.map { localCopy(context, it) })
    // "image/*" when all are images, and so on; anything mixed is "*/*".
    val type = nodes.map { it.mime.substringBefore('/').ifEmpty { "*" } }.distinct().singleOrNull()?.let { "$it/*" } ?: "*/*"
    val clip = ClipData.newRawUri(nodes[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType(type)
        .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .apply { clipData = clip } // grants read access to every file
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.send_n, nodes.size)))
}
