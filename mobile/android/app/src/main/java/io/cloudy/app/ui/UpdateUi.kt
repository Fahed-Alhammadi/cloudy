package io.cloudy.app.ui

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.cloudy.app.data.AppRelease
import io.cloudy.app.data.AppUpdater
import kotlinx.coroutines.launch

/** Shared update state so the More screen can trigger the same dialog. */
object UpdateState {
    var release by mutableStateOf<AppRelease?>(null)
    var visible by mutableStateOf(false)

    suspend fun check(context: Context, manual: Boolean) {
        val r = runCatching { AppUpdater.check() }.getOrElse {
            if (manual) toast(context, "Update check failed: ${it.message}")
            return
        }
        release = r
        val skipped = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getInt("skipped", 0)
        when {
            r == null -> if (manual) toast(context, "Cloudy is up to date (${AppUpdater.currentVersionName})")
            manual || r.versionCode > skipped -> visible = true
        }
    }
}

/** Checks for a newer APK on launch and offers to install it. */
@Composable
fun UpdatePrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var needsPermission by remember { mutableStateOf(false) }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("askedNotify", false)) {
                prefs.edit().putBoolean("askedNotify", true).apply()
                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        UpdateState.check(context, manual = false)
    }

    val release = UpdateState.release
    if (!UpdateState.visible || release == null) return

    fun start() {
        if (!AppUpdater.canInstall(context)) {
            needsPermission = true
            AppUpdater.openInstallPermissionSettings(context)
            return
        }
        needsPermission = false
        progress = 0f
        scope.launch {
            try {
                val apk = AppUpdater.download(context, release) { progress = it }
                AppUpdater.install(context, apk)
                UpdateState.visible = false
            } catch (e: Exception) {
                toast(context, e.message ?: "Update failed")
            } finally {
                progress = null
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (progress == null) UpdateState.visible = false },
        title = { Text("Update available") },
        text = {
            Column {
                Text("Cloudy ${release.versionName} is available (you have ${AppUpdater.currentVersionName}).")
                if (release.notes.isNotBlank()) Text(release.notes, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
                if (release.size > 0) Text(formatSize(release.size), Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outline,
                    style = MaterialTheme.typography.bodySmall)
                if (needsPermission) Text("Allow Cloudy to install apps in the screen that opened, then tap Update again.",
                    Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) }
            }
        },
        confirmButton = { TextButton(enabled = progress == null, onClick = ::start) { Text("Update") } },
        dismissButton = {
            TextButton(enabled = progress == null, onClick = {
                context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit().putInt("skipped", release.versionCode).apply()
                UpdateState.visible = false
            }) { Text("Later") }
        },
    )
}
