package io.cloudy.app.ui

import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.cloudy.app.data.Live
import io.cloudy.app.data.Office
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.cloudy.app.Services
import io.cloudy.app.work.BackupSettings
import io.cloudy.app.work.Jobs
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class Tab(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MainScreen(sharedUris: MutableState<List<Uri>>) {
    val nav = rememberNavController()
    val rootId = Services.session.user.value?.rootId ?: 0
    val tabs = listOf(
        Tab("files/$rootId", "Files", Icons.Outlined.Folder),
        Tab("calendar", "Calendar", Icons.Outlined.CalendarMonth),
        Tab("contacts", "Contacts", Icons.Outlined.Contacts),
        Tab("more", "More", Icons.Outlined.Menu),
    )
    // Live updates while the app is visible; office file types once per session.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        Office.load(Services.api)
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { Live.run(Services.api) }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val currentFolder = entry?.arguments?.getLong("id")
    val showBar = route == "calendar" || route == "contacts" || route == "more" || (route == "files/{id}" && currentFolder == rootId)

    Scaffold(bottomBar = {
        if (showBar) NavigationBar {
            tabs.forEach { t ->
                val selected = if (t.label == "Files") route == "files/{id}" else route == t.route
                NavigationBarItem(selected = selected, icon = { Icon(t.icon, null) }, label = { Text(t.label) }, onClick = {
                    nav.navigate(t.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        }
    }) { pad ->
        UpdatePrompt()
        NavHost(nav, startDestination = "files/$rootId", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
            composable("files/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                FolderScreen(nav, it.arguments!!.getLong("id"), sharedUris)
            }
            composable("preview/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PreviewScreen(nav, it.arguments!!.getLong("id"))
            }
            composable("office/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                OfficeScreen(nav, it.arguments!!.getLong("id"))
            }
            composable("list/{kind}") { NodeListScreen(nav, it.arguments!!.getString("kind")!!) }
            composable("search") { SearchScreen(nav) }
            composable("trash") { TrashScreen(nav) }
            composable("calendar") { CalendarScreen() }
            composable("contacts") { ContactsScreen() }
            composable("more") { MoreScreen(nav) }
            composable("backup") { BackupScreen(nav) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(nav: NavController) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val user by remember { mutableStateOf(Services.session.user.value) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { Services.api.me() }.onSuccess { Services.session.updateUser(it.copy(rootId = it.rootId.takeIf { r -> r > 0 } ?: user?.rootId ?: 0)) } }
    val u = Services.session.user.value

    Scaffold(topBar = { TopAppBar(title = { Text("More") }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(u?.displayName ?: "", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Column {
                        Text("${u?.id} · ${Services.session.server}")
                        val used = u?.used ?: 0
                        val quota = u?.quotaBytes
                        Text(if (quota != null) "${formatSize(used)} of ${formatSize(quota)} used" else "${formatSize(used)} used")
                        if (quota != null && quota > 0) LinearProgressIndicator(progress = { (used.toFloat() / quota).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                },
            )
            HorizontalDivider()
            @Composable
            fun Entry(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, route: String) =
                ListItem(modifier = Modifier.clickable { nav.navigate(route) }, leadingContent = { Icon(icon, null) }, headlineContent = { Text(label) })
            Entry("Recent", Icons.Outlined.AccessTime, "list/recent")
            Entry("Favorites", Icons.Outlined.Star, "list/favorites")
            Entry("Shared with you", Icons.Outlined.People, "list/shared-in")
            Entry("Shared by you", Icons.Outlined.Share, "list/shared-out")
            Entry("Available offline", Icons.Outlined.CloudDone, "list/offline")
            Entry("Deleted files", Icons.Outlined.Delete, "trash")
            HorizontalDivider()
            Entry("Photo backup", Icons.Outlined.PhotoCamera, "backup")
            ListItem(
                modifier = Modifier.clickable { confirmLogout = true },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text("Log out", color = MaterialTheme.colorScheme.error) },
            )
            ListItem(
                modifier = Modifier.clickable { scope.launch { UpdateState.check(context, manual = true) } },
                leadingContent = { Icon(Icons.Outlined.SystemUpdate, null) },
                headlineContent = { Text("Check for updates") },
                supportingContent = { Text("Version ${io.cloudy.app.data.AppUpdater.currentVersionName} (${io.cloudy.app.data.AppUpdater.currentVersionCode})") },
            )
            Text("Offline storage: ${formatSize(Services.offline.usedBytes())}", Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (confirmLogout) ConfirmDialog("Log out", "This device's token will be revoked.", "Log out", { confirmLogout = false }) {
        confirmLogout = false
        scope.launch { Services.api.logout(); Services.session.signOut() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(nav: NavController) {
    val context = LocalContext.current
    val settings = remember { BackupSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var videos by remember { mutableStateOf(settings.includeVideos) }
    var folder by remember { mutableStateOf(settings.folder) }
    var askExisting by remember { mutableStateOf(false) }

    fun apply() {
        settings.enabled = enabled; settings.wifiOnly = wifiOnly; settings.includeVideos = videos
        settings.folder = folder.trim().ifEmpty { "Camera Uploads" }
        Jobs.schedule(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.all { it }) askExisting = true
        else { enabled = false; toast(context, "Photo access is needed for backup") }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Photo backup") }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 8.dp)) {
            ListItem(headlineContent = { Text("Back up photos automatically") }, supportingContent = { Text("New photos are uploaded in the background") },
                trailingContent = {
                    Switch(enabled, { on ->
                        if (on) {
                            enabled = true
                            if (Jobs.hasMediaPermission(context)) askExisting = true else permission.launch(Jobs.mediaPermissions())
                        } else { enabled = false; apply() }
                    })
                })
            ListItem(headlineContent = { Text("Include videos") }, trailingContent = { Switch(videos, { videos = it; apply() }) })
            ListItem(headlineContent = { Text("Only on Wi-Fi") }, trailingContent = { Switch(wifiOnly, { wifiOnly = it; apply() }) })
            OutlinedTextField(folder, { folder = it; apply() }, label = { Text("Upload folder") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Button(enabled = enabled, onClick = { apply(); Jobs.backupNow(context); toast(context, "Backup started") }, modifier = Modifier.padding(16.dp)) { Text("Back up now") }
            if (settings.lastRun > 0) Text("Last run: ${DateFormat.getDateTimeInstance().format(Date(settings.lastRun))} — ${settings.lastResult}",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (askExisting) AlertDialog(
        onDismissRequest = {},
        title = { Text("Existing photos") },
        text = { Text("Also upload the photos and videos already on this device?") },
        confirmButton = { TextButton(onClick = { askExisting = false; settings.lastAdded = 0; apply(); Jobs.backupNow(context) }) { Text("Upload all") } },
        dismissButton = { TextButton(onClick = { askExisting = false; settings.lastAdded = System.currentTimeMillis() / 1000; apply() }) { Text("Only new ones") } },
    )
}
