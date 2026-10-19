package io.filecloud.app.work

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.filecloud.app.MainActivity
import io.filecloud.app.Services
import io.filecloud.app.data.AppUpdater
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.util.concurrent.TimeUnit

/** Photo backup settings, stored in app preferences. */
class BackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()
    var includeVideos: Boolean
        get() = prefs.getBoolean("videos", true)
        set(v) = prefs.edit().putBoolean("videos", v).apply()
    var folder: String
        get() = prefs.getString("folder", "Camera Uploads") ?: "Camera Uploads"
        set(v) = prefs.edit().putString("folder", v).apply()
    /** MediaStore DATE_ADDED (seconds) of the newest item already uploaded. */
    var lastAdded: Long
        get() = prefs.getLong("lastAdded", 0)
        set(v) = prefs.edit().putLong("lastAdded", v).apply()
    var lastRun: Long
        get() = prefs.getLong("lastRun", 0)
        set(v) = prefs.edit().putLong("lastRun", v).apply()
    var lastResult: String
        get() = prefs.getString("lastResult", "") ?: ""
        set(v) = prefs.edit().putString("lastResult", v).apply()
}

object Jobs {
    fun schedule(context: Context) {
        val wm = WorkManager.getInstance(context)
        val settings = BackupSettings(context)
        if (settings.enabled) {
            val c = Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            wm.enqueueUniquePeriodicWork("backup", ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES).setConstraints(c).build())
        } else {
            wm.cancelUniqueWork("backup")
        }
        wm.enqueueUniquePeriodicWork("app-update", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        wm.enqueueUniquePeriodicWork("offline", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OfflineWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
    }

    fun backupNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("backup-now", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BackupWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
    }

    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    fun hasMediaPermission(context: Context) =
        mediaPermissions().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

/** Daily check for a newer app release; posts a notification once per version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Services.session.isLoggedIn) return Result.success()
        val release = runCatching { AppUpdater.check() }.getOrElse { return Result.retry() } ?: return Result.success()
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getInt("notified", 0) >= release.versionCode) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(1001, NotificationCompat.Builder(ctx, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("FileCloud ${release.versionName} is available")
            .setContentText(release.notes.ifBlank { "Tap to update" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        prefs.edit().putInt("notified", release.versionCode).apply()
        return Result.success()
    }
}

class OfflineWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Services.session.isLoggedIn) return Result.success()
        return runCatching { Services.offline.sync() }.fold({ Result.success() }, { Result.retry() })
    }
}

/** Uploads photos/videos added to the device since the last run. */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private data class Item(val uri: Uri, val name: String, val size: Long, val added: Long)

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val settings = BackupSettings(ctx)
        if (!settings.enabled || !Services.session.isLoggedIn || !Jobs.hasMediaPermission(ctx)) return Result.success()
        val api = Services.api
        var uploaded = 0
        try {
            val rootId = Services.session.user.value?.rootId ?: return Result.success()
            val root = api.node(rootId)
            val folder = root.children.orEmpty().firstOrNull { it.isDir && it.name == settings.folder }
                ?: api.mkdir(rootId, settings.folder)
            val existing = api.node(folder.id).children.orEmpty().associate { it.name to it.size }.toMutableMap()

            val items = query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, settings.lastAdded) +
                (if (settings.includeVideos) query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, settings.lastAdded) else emptyList())
            for (item in items.sortedBy { it.added }) {
                if (isStopped) break
                var name = item.name
                val prev = existing[name]
                if (prev == item.size) {
                    settings.lastAdded = item.added
                    continue
                }
                if (prev != null) {
                    val dot = name.lastIndexOf('.')
                    val suffix = "_" + ContentUris.parseId(item.uri)
                    name = if (dot > 0) name.substring(0, dot) + suffix + name.substring(dot) else name + suffix
                }
                api.upload(folder.id, name, item.size, { ctx.contentResolver.openInputStream(item.uri)!! })
                existing[name] = item.size
                settings.lastAdded = item.added
                uploaded++
            }
            settings.lastResult = "Uploaded $uploaded new item(s)"
            return Result.success()
        } catch (e: Exception) {
            settings.lastResult = "Failed: ${e.message}"
            return Result.retry()
        } finally {
            settings.lastRun = System.currentTimeMillis()
        }
    }

    private fun query(collection: Uri, since: Long): List<Item> {
        val out = mutableListOf<Item>()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED)
        applicationContext.contentResolver.query(collection, cols, "${MediaStore.MediaColumns.DATE_ADDED} > ?", arrayOf(since.toString()),
            "${MediaStore.MediaColumns.DATE_ADDED} ASC")?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1) ?: "media_$id"
                val size = c.getLong(2)
                if (size <= 0) continue
                out += Item(ContentUris.withAppendedId(collection, id), name, size, c.getLong(3))
            }
        }
        return out
    }
}
