package io.cloudy.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.cloudy.app.MainActivity
import io.cloudy.app.Services
import io.cloudy.app.data.AppUpdater
import java.util.concurrent.TimeUnit

fun scheduleUpdateCheck(wm: WorkManager) {
    wm.enqueueUniquePeriodicWork("app-update", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
}

/** Daily check for a newer app release; posts a notification once per version. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!Services.session.isLoggedIn) return Result.success()
        val release = runCatching { AppUpdater.check() }.getOrElse { return Result.retry() } ?: return Result.success()
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("updates", Context.MODE_PRIVATE)
        if (prefs.getString("notifiedVersion", null) == release.versionName) return Result.success()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(1001, NotificationCompat.Builder(ctx, "updates")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Cloudy ${release.versionName} is available")
            .setContentText(release.notes.ifBlank { "Tap to update" })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
        prefs.edit().putString("notifiedVersion", release.versionName).apply()
        return Result.success()
    }
}
