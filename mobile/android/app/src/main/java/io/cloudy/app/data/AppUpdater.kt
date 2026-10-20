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
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Over-the-air updates from GitHub: the newest release of [BuildConfig.UPDATE_REPO] carries the signed
 * APK. The app compares the release tag (v1.6.0) with its own version name, downloads the APK without
 * Cloudy credentials, verifies GitHub's SHA-256 when given and hands it to the system installer, which
 * also checks that it is signed with the same key as the installed app.
 */
object AppUpdater {
    val currentVersionName: String get() = BuildConfig.VERSION_NAME

    /** The latest GitHub release if it is newer than this build. */
    suspend fun check(): AppRelease? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val gh = Services.api.plainHttp.newCall(req).execute().use { res ->
            if (res.code == 404) return@withContext null // no release yet
            if (!res.isSuccessful) throw IllegalStateException("GitHub answered HTTP ${res.code}")
            Services.api.json.decodeFromString<GithubRelease>(res.body!!.string())
        }
        val version = gh.tag_name.removePrefix("v")
        if (!isNewer(version, currentVersionName)) return@withContext null
        val apk = gh.assets.firstOrNull { it.name == "Cloudy-$version.apk" } ?: gh.assets.firstOrNull { it.name.endsWith(".apk") }
            ?: return@withContext null
        AppRelease(
            versionName = version,
            notes = gh.body.orEmpty().take(1500),
            size = apk.size,
            sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            url = apk.browser_download_url,
        )
    }

    /** True if version name [a] (e.g. "1.10.0") is newer than [b]. */
    fun isNewer(a: String, b: String): Boolean {
        val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

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
        dir.listFiles()?.filter { it.name != "Cloudy-${release.versionName}.apk" }?.forEach { it.delete() }
        val dest = File(dir, "Cloudy-${release.versionName}.apk")
        if (dest.exists()) {
            if (release.sha256 != null && sha256(dest) == release.sha256) return@withContext dest
            dest.delete() // unverifiable or stale copy: download again
        }
        Services.api.downloadUrlTo(release.url, dest, client = Services.api.plainHttp) { got ->
            if (release.size > 0) onProgress((got.toFloat() / release.size).coerceIn(0f, 1f))
        }
        if (release.sha256 != null && sha256(dest) != release.sha256) {
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
