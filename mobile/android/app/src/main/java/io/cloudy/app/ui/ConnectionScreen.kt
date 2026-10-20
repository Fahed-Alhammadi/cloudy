package io.cloudy.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Error
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.cloudy.app.Services
import io.cloudy.app.data.CheckItem
import io.cloudy.app.data.CheckState
import io.cloudy.app.data.ConnectionCheck
import io.cloudy.app.data.Diagnostics

/** More → Connection check: is the server reachable, and does the reverse proxy pass everything Cloudy needs? */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionScreen(nav: NavController) {
    val context = LocalContext.current
    var items by remember { mutableStateOf<List<CheckItem>>(emptyList()) }
    var diag by remember { mutableStateOf<Diagnostics?>(null) }
    var running by remember { mutableStateOf(false) }
    var runKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(runKey) {
        running = true
        items = emptyList()
        val check = ConnectionCheck(context)
        check.run { items = it }
        diag = check.diag
        running = false
    }

    val server = Services.session.server.trimEnd('/')
    val user = diag?.user ?: Services.session.user.value?.id ?: ""
    val davFiles = diag?.davFiles ?: "$server/dav/files/$user/"
    val davRoot = diag?.davRoot ?: "$server/dav/"

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Connection check") },
            navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton(enabled = !running, onClick = { shareReport(context, server, items) }) { Icon(Icons.Outlined.Share, "Share report") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("Checks that $server works from this phone through its reverse proxy, including the addresses other apps use. Nothing on the server is changed.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            items.forEach { CheckRow(it) }
            if (!running && items.isNotEmpty()) {
                val fails = items.count { it.state == CheckState.FAIL }
                val warns = items.count { it.state == CheckState.WARN }
                Text(
                    when {
                        fails > 0 -> "$fails problem(s) found. See the fixes above."
                        warns > 0 -> "Works, with $warns warning(s)."
                        else -> "Everything works."
                    },
                    Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall,
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !running, onClick = { runKey++ }) { Text(if (running) "Checking…" else "Run again") }
                OutlinedButton(enabled = !running && items.isNotEmpty(), onClick = { shareReport(context, server, items) }) { Text("Share report") }
            }

            HorizontalDivider(Modifier.padding(top = 16.dp))
            Text("Addresses for other apps", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
            Text("Use these in a WebDAV client (Files apps, rclone…) or a calendar/contacts app (DAVx⁵, iPhone, Thunderbird). Sign in with your username and an app password from Cloudy's web Settings.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            UrlRow(context, "Files (WebDAV)", davFiles)
            UrlRow(context, "Calendars & contacts", davRoot)
            UrlRow(context, "Username", user)
        }
    }
}

@Composable
private fun CheckRow(item: CheckItem) {
    val (icon, tint) = when (item.state) {
        CheckState.OK -> Icons.Outlined.CheckCircle to Color(0xFF2E9E6A)
        CheckState.WARN -> Icons.Outlined.Warning to Color(0xFFD08A00)
        CheckState.FAIL -> Icons.Outlined.Error to MaterialTheme.colorScheme.error
        CheckState.SKIPPED -> Icons.Outlined.RemoveCircleOutline to MaterialTheme.colorScheme.outline
        CheckState.RUNNING -> null to Color.Unspecified
    }
    ListItem(
        leadingContent = {
            if (icon == null) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            else Icon(icon, item.state.name, tint = tint)
        },
        headlineContent = { Text(item.title) },
        supportingContent = {
            Column {
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall)
                if (item.fix.isNotBlank()) Text("How to fix: ${item.fix}", Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

@Composable
private fun UrlRow(context: Context, label: String, value: String) {
    ListItem(
        headlineContent = { Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline) },
        supportingContent = { Text(value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            IconButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, value))
                toast(context, "$label copied")
            }) { Icon(Icons.Outlined.ContentCopy, "Copy $label") }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun shareReport(context: Context, server: String, items: List<CheckItem>) {
    val text = buildString {
        appendLine("Cloudy connection check — $server")
        items.forEach { i ->
            appendLine("[${i.state}] ${i.title}: ${i.detail}")
            if (i.fix.isNotBlank()) appendLine("    fix: ${i.fix}")
        }
    }
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share report"))
}
