package io.cloudy.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.cloudy.app.data.FileNode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

val Accent = Color(0xFF0B6BCB)

@Composable
fun CloudyTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFF4A9DF0), onPrimary = Color(0xFF0A1220), primaryContainer = Color(0xFF1D3350),
            background = Color(0xFF14181F), surface = Color(0xFF14181F), surfaceContainer = Color(0xFF1B2029))
    else
        lightColorScheme(primary = Accent, primaryContainer = Color(0xFFE1ECFA), onPrimaryContainer = Accent,
            surfaceContainer = Color(0xFFF4F6F9))
    MaterialTheme(colorScheme = colors, content = content)
}

fun formatSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var n = bytes.toDouble()
    var i = 0
    while (n >= 1024 && i < units.size - 1) { n /= 1024; i++ }
    return if (i == 0) "$bytes B" else String.format(if (n < 10) "%.1f %s" else "%.0f %s", n, units[i])
}

private val dateFmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
fun formatDate(iso: String): String =
    runCatching { dateFmt.format(Instant.parse(iso).atZone(ZoneId.systemDefault())) }.getOrDefault("")

fun iconFor(node: FileNode): Pair<ImageVector, Color> {
    val m = node.mime
    return when {
        node.isDir -> Icons.Outlined.Folder to Color(0xFF3D8EF0)
        m.startsWith("image/") -> Icons.Outlined.Image to Color(0xFF2E9E6A)
        m.startsWith("video/") -> Icons.Outlined.VideoFile to Color(0xFFC05621)
        m.startsWith("audio/") -> Icons.Outlined.AudioFile to Color(0xFF8B5CF6)
        m == "application/pdf" -> Icons.Outlined.PictureAsPdf to Color(0xFFD03B3B)
        Regex("zip|tar|gzip|7z|rar").containsMatchIn(m) -> Icons.Outlined.FolderZip to Color(0xFFA07A2C)
        m.startsWith("text/") || m.contains("json") || m.contains("xml") -> Icons.Outlined.Article to Color(0xFF64708A)
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile to Color(0xFF64708A)
    }
}

@Composable
fun FileIcon(node: FileNode, modifier: Modifier = Modifier.size(32.dp)) {
    val (icon, tint) = iconFor(node)
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

fun toast(context: Context, msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    context.startActivity(Intent.createChooser(send, null))
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun EmptyState(icon: ImageVector, text: String) =
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
            Text(text, color = MaterialTheme.colorScheme.outline, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
        }
    }

@Composable
fun TextPrompt(title: String, initial: String = "", label: String = "Name", confirm: String = "OK", onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, singleLine = true) },
        confirmButton = { TextButton(enabled = value.isNotBlank(), onClick = { onConfirm(value.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) =
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm, color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
