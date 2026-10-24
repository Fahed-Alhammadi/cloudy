package io.cloudy.app.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.cloudy.app.data.AppLock
import io.cloudy.app.data.Lang
import io.cloudy.app.data.Live
import io.cloudy.app.data.Office
import io.cloudy.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Language
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
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
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
import io.cloudy.app.BuildConfig
import io.cloudy.app.Services
import io.cloudy.app.work.BackupSettings
import io.cloudy.app.work.Jobs
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import java.text.DateFormat
import java.util.Date

private const val NAV_MS = 180

private data class Tab(val route: String, val label: Int, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun MainScreen(sharedUris: MutableState<List<Uri>>) {
    val nav = rememberNavController()
    val rootId = Services.session.user.value?.rootId ?: 0
    val tabs = listOf(
        Tab("files/$rootId", R.string.tab_files, Icons.Outlined.Folder),
        Tab("calendar", R.string.tab_calendar, Icons.Outlined.CalendarMonth),
        Tab("contacts", R.string.tab_contacts, Icons.Outlined.Contacts),
        Tab("more", R.string.more, Icons.Outlined.Menu),
    )
    // Live updates while the app is visible; office file types once per session.
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = LocalContext.current.findActivity()
    LaunchedEffect(lifecycleOwner) {
        // A language chosen for the account (on the web, or before this phone signed in) applies here too.
        runCatching { Services.api.me() }.onSuccess { me -> activity?.let { Lang.followAccount(it, me.id, me.language) } }
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
                val selected = if (t.route.startsWith("files/")) route == "files/{id}" else route == t.route
                NavigationBarItem(selected = selected, icon = { Icon(t.icon, null) }, label = { Text(stringResource(t.label)) }, onClick = {
                    nav.navigate(t.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                })
            }
        }
    }) { pad ->
        // Short transitions: the library default is a 700 ms cross-fade, which makes every tap feel slow.
        NavHost(nav, startDestination = "files/$rootId", modifier = Modifier.padding(bottom = pad.calculateBottomPadding()),
            enterTransition = { fadeIn(tween(NAV_MS)) + slideInHorizontally(tween(NAV_MS)) { it / 12 } },
            exitTransition = { fadeOut(tween(NAV_MS / 2)) },
            popEnterTransition = { fadeIn(tween(NAV_MS)) },
            popExitTransition = { fadeOut(tween(NAV_MS / 2)) + slideOutHorizontally(tween(NAV_MS)) { it / 12 } },
        ) {
            composable("files/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                FolderScreen(nav, it.arguments!!.getLong("id"), sharedUris)
            }
            composable("preview/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                PreviewScreen(nav, it.arguments!!.getLong("id"))
            }
            composable("list/{kind}") { NodeListScreen(nav, it.arguments!!.getString("kind")!!) }
            composable("search") { SearchScreen(nav) }
            composable("trash") { TrashScreen(nav) }
            composable("calendar") { CalendarScreen() }
            composable("contacts") { ContactsScreen() }
            composable("more") { MoreScreen(nav) }
            composable("backup") { BackupScreen(nav) }
            composable("connection") { ConnectionScreen(nav) }
            composable("devices") { DevicesScreen(nav) }
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
    var chooseLanguage by remember { mutableStateOf(false) }
    var lockOn by remember { mutableStateOf(AppLock.isEnabled(context)) }
    LaunchedEffect(Unit) { runCatching { Services.api.me() }.onSuccess { Services.session.updateUser(it.copy(rootId = it.rootId.takeIf { r -> r > 0 } ?: user?.rootId ?: 0)) } }
    val u = Services.session.user.value

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.more)) }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState())) {
            ListItem(
                headlineContent = { Text(u?.displayName ?: "", style = MaterialTheme.typography.titleMedium) },
                supportingContent = {
                    Column {
                        Text("${u?.id} · ${Services.session.server}")
                        val used = u?.used ?: 0
                        val quota = u?.quotaBytes
                        Text(if (quota != null) stringResource(R.string.storage_used_of, formatSize(used), formatSize(quota)) else stringResource(R.string.storage_used, formatSize(used)))
                        if (quota != null && quota > 0) LinearProgressIndicator(progress = { (used.toFloat() / quota).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    }
                },
            )
            HorizontalDivider()
            @Composable
            fun Entry(label: Int, icon: androidx.compose.ui.graphics.vector.ImageVector, route: String) =
                ListItem(modifier = Modifier.clickable { nav.navigate(route) }, leadingContent = { Icon(icon, null) }, headlineContent = { Text(stringResource(label)) })
            Entry(R.string.recent, Icons.Outlined.AccessTime, "list/recent")
            Entry(R.string.favorites, Icons.Outlined.Star, "list/favorites")
            Entry(R.string.shared_with_you, Icons.Outlined.People, "list/shared-in")
            Entry(R.string.shared_by_you, Icons.Outlined.Share, "list/shared-out")
            Entry(R.string.available_offline, Icons.Outlined.CloudDone, "list/offline")
            Entry(R.string.deleted_files, Icons.Outlined.Delete, "trash")
            HorizontalDivider()
            Entry(R.string.photo_backup, Icons.Outlined.PhotoCamera, "backup")
            Entry(R.string.connection_check, Icons.Outlined.NetworkCheck, "connection")
            HorizontalDivider()
            Text(stringResource(R.string.section_security), Modifier.padding(start = 16.dp, top = 12.dp),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ListItem(
                modifier = Modifier.clickable { chooseLanguage = true },
                leadingContent = { Icon(Icons.Outlined.Language, null) },
                headlineContent = { Text(stringResource(R.string.language)) },
                supportingContent = { Text(Lang.current(context)?.let { Lang.NAMES[it] } ?: stringResource(R.string.language_system)) },
            )
            val lockAvailable = remember { AppLock.isAvailable(context) }
            val confirmTitle = stringResource(R.string.app_lock_turn_on)
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Fingerprint, null) },
                headlineContent = { Text(stringResource(R.string.app_lock)) },
                supportingContent = { Text(stringResource(if (lockAvailable || lockOn) R.string.app_lock_summary else R.string.app_lock_unavailable)) },
                trailingContent = {
                    Switch(lockOn, enabled = lockAvailable || lockOn, onCheckedChange = { on ->
                        if (!on) { AppLock.setEnabled(context, false); lockOn = false }
                        // Turning it on asks once, so nobody locks themselves out by accident.
                        else context.findActivity()?.let { AppLock.confirm(it, confirmTitle) { AppLock.setEnabled(context, true); lockOn = true } }
                    })
                },
            )
            Entry(R.string.devices, Icons.Outlined.Devices, "devices")
            HorizontalDivider()
            ListItem(
                modifier = Modifier.clickable { confirmLogout = true },
                leadingContent = { Icon(Icons.AutoMirrored.Outlined.Logout, null, tint = MaterialTheme.colorScheme.error) },
                headlineContent = { Text(stringResource(R.string.log_out), color = MaterialTheme.colorScheme.error) },
            )
            ListItem(
                leadingContent = { Icon(Icons.Outlined.Info, null) },
                headlineContent = { Text(stringResource(R.string.version, BuildConfig.VERSION_NAME)) },
            )
            // Adding up the offline folder reads the disk: done in the background, shown when ready.
            val offlineBytes by produceState<Long?>(null) { value = withContext(Dispatchers.IO) { Services.offline.usedBytes() } }
            offlineBytes?.let { bytes -> Text(stringResource(R.string.offline_storage, formatSize(bytes)), Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
    if (chooseLanguage) LanguageDialog { chooseLanguage = false }
    if (confirmLogout) ConfirmDialog(stringResource(R.string.log_out), stringResource(R.string.log_out_text), stringResource(R.string.log_out), { confirmLogout = false }) {
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
        else { enabled = false; toast(context, context.getString(R.string.backup_permission_needed)) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(R.string.photo_backup)) }, navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } })
    }) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 8.dp)) {
            ListItem(headlineContent = { Text(stringResource(R.string.backup_auto)) }, supportingContent = { Text(stringResource(R.string.backup_auto_summary)) },
                trailingContent = {
                    Switch(enabled, { on ->
                        if (on) {
                            enabled = true
                            if (Jobs.hasMediaPermission(context)) askExisting = true else permission.launch(Jobs.mediaPermissions())
                        } else { enabled = false; apply() }
                    })
                })
            ListItem(headlineContent = { Text(stringResource(R.string.backup_videos)) }, trailingContent = { Switch(videos, { videos = it; apply() }) })
            ListItem(headlineContent = { Text(stringResource(R.string.backup_wifi)) }, trailingContent = { Switch(wifiOnly, { wifiOnly = it; apply() }) })
            OutlinedTextField(folder, { folder = it; apply() }, label = { Text(stringResource(R.string.backup_folder)) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
            Button(enabled = enabled, onClick = { apply(); Jobs.backupNow(context); toast(context, context.getString(R.string.backup_started)) }, modifier = Modifier.padding(16.dp)) { Text(stringResource(R.string.backup_now)) }
            if (settings.lastRun > 0) Text(stringResource(R.string.backup_last_run, DateFormat.getDateTimeInstance().format(Date(settings.lastRun)), settings.lastResult),
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
    }
    if (askExisting) AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_existing_title)) },
        text = { Text(stringResource(R.string.backup_existing_text)) },
        confirmButton = { TextButton(onClick = { askExisting = false; settings.lastAdded = 0; apply(); Jobs.backupNow(context) }) { Text(stringResource(R.string.backup_upload_all)) } },
        dismissButton = { TextButton(onClick = { askExisting = false; settings.lastAdded = System.currentTimeMillis() / 1000; apply() }) { Text(stringResource(R.string.backup_only_new)) } },
    )
}
