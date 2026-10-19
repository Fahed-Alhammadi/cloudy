package io.cloudy.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import io.cloudy.app.BuildConfig
import io.cloudy.app.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Over-the-air updates: the server publishes the newest APK (Administration → Android app).
 * The app downloads it, verifies its SHA-256 and hands it to the system installer, which also
 * checks that it is signed with the same key as the installed app.
 */
object AppUpdater {
    val currentVersionCode: Int get() = BuildConfig.VERSION_CODE
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** The published release if it is newer than this build. */
    suspend fun check(): AppRelease? =
        Services.api.latestRelease()?.takeIf { it.versionCode > currentVersionCode }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun download(context: Context, release: AppRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { it.name != "Cloudy-${release.versionCode}.apk" }?.forEach { it.delete() }
        val dest = File(dir, "Cloudy-${release.versionCode}.apk")
        if (dest.exists() && sha256(dest) == release.sha256) return@withContext dest
        Services.api.downloadUrlTo(Services.api.url(release.url), dest) { got ->
            if (release.size > 0) onProgress((got.toFloat() / release.size).coerceIn(0f, 1f))
        }
        if (sha256(dest) != release.sha256) {
            dest.delete()
            throw IllegalStateException("Downloaded update is corrupt (checksum mismatch)")
        }
        dest
    }

    /** True if the app may launch the installer; otherwise the user must allow it first. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
