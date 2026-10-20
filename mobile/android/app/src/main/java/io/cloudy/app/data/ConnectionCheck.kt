package io.cloudy.app.data

import android.content.Context
import android.net.ConnectivityManager
import io.cloudy.app.Services
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** `GET /api/diagnostics`: how the server sees this request through the reverse proxy. */
@Serializable
data class Diagnostics(
    val version: String = "",
    val user: String = "",
    val secure: Boolean = false,
    val origin: String = "",
    val clientIp: String = "",
    val trustProxy: Boolean = false,
    val publicUrl: Boolean = false,
    val chunkSize: Long = 8L * 1024 * 1024,
    val davFiles: String = "",
    val davRoot: String = "",
)

enum class CheckState { RUNNING, OK, WARN, FAIL, SKIPPED }

data class CheckItem(val title: String, val state: CheckState = CheckState.RUNNING, val detail: String = "", val fix: String = "")

/**
 * Connection check: verifies, from the phone, that the server works through its reverse proxy — the JSON API,
 * WebDAV / CalDAV / CardDAV (special HTTP methods and Basic auth), auto-discovery, large uploads and live
 * updates — and that the proxy forwards the headers Cloudy needs. Changes nothing on the server.
 */
class ConnectionCheck(private val context: Context) {
    private val api = Services.api
    private val base = Services.session.server.trimEnd('/')
    private val quick: OkHttpClient = api.http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
    private val noRedirect: OkHttpClient = quick.newBuilder().followRedirects(false).followSslRedirects(false).build()
    var diag: Diagnostics? = null
        private set

    private fun req(path: String) = Request.Builder().url(if (path.startsWith("http")) path else base + path)

    /** WebDAV and DAV clients sign in with username + app password; the device token is one. */
    private fun basic(): String = Credentials.basic(Services.session.user.value?.id ?: "", Services.session.token ?: "", Charsets.UTF_8)

    private val propfindBody = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
        .toRequestBody("application/xml; charset=utf-8".toMediaType())

    /** Runs every check in order, reporting each result as soon as it is known. */
    suspend fun run(report: (List<CheckItem>) -> Unit) = withContext(Dispatchers.IO) {
        val items = mutableListOf<CheckItem>()
        suspend fun step(title: String, block: suspend () -> CheckItem) {
            items += CheckItem(title)
            report(items.toList())
            items[items.lastIndex] = try { block().copy(title = title) } catch (e: Exception) {
                CheckItem(title, CheckState.FAIL, e.message ?: e.javaClass.simpleName)
            }
            report(items.toList())
        }

        var reachable = false
        step("Server reachable") {
            quick.newCall(req("/status").build()).execute().use { r ->
                if (!r.isSuccessful) return@step CheckItem("", CheckState.FAIL, "HTTP ${r.code} from $base/status",
                    "The reverse proxy answers but doesn't reach Cloudy. Check that it forwards to the Cloudy port (default 8080).")
                val v = runCatching { api.json.parseToJsonElement(r.body!!.string()).jsonObject["version"]?.jsonPrimitive?.content }.getOrNull()
                reachable = true
                CheckItem("", CheckState.OK, "Cloudy ${v ?: "?"} at $base")
            }
        }
        if (!reachable) return@withContext

        step("Encrypted connection (HTTPS)") {
            val host = URI(base).host ?: ""
            when {
                base.startsWith("https://") -> CheckItem("", CheckState.OK, "Connected over HTTPS")
                isPrivateHost(host) -> CheckItem("", CheckState.WARN, "Plain HTTP on a local network ($host)",
                    "Fine at home, but use an https:// address from the internet.")
                else -> CheckItem("", CheckState.FAIL, "Plain HTTP to $host — passwords and files travel unencrypted",
                    "Put Cloudy behind a reverse proxy with a TLS certificate and sign in with https://")
            }
        }

        step("Signed-in API") {
            quick.newCall(req("/api/diagnostics").build()).execute().use { r ->
                when {
                    r.code == 401 -> CheckItem("", CheckState.FAIL, "The server rejected this device's token", "Sign out and sign in again.")
                    r.code == 404 -> CheckItem("", CheckState.WARN, "Server too old for the full check", "Update the server (docker compose pull && docker compose up -d).")
                    !r.isSuccessful -> CheckItem("", CheckState.FAIL, "HTTP ${r.code}")
                    else -> {
                        diag = api.json.decodeFromString<Diagnostics>(r.body!!.string())
                        CheckItem("", CheckState.OK, "Signed in as ${diag!!.user}")
                    }
                }
            }
        }
        val d = diag

        step("Proxy forwards the right headers") {
            if (d == null) return@step CheckItem("", CheckState.SKIPPED, "Needs the signed-in API")
            val problems = mutableListOf<Pair<String, String>>()
            val https = base.startsWith("https://")
            if (https && !d.secure) problems += "The server thinks this connection is plain HTTP" to
                "Set TRUST_PROXY=true in .env and have the proxy send X-Forwarded-Proto (nginx: proxy_set_header X-Forwarded-Proto \$scheme)."
            val appHost = URI(base).let { "${it.host}${if (it.port > 0) ":${it.port}" else ""}" }.lowercase()
            val seenHost = runCatching { URI(d.origin).let { "${it.host}${if (it.port > 0) ":${it.port}" else ""}" } }.getOrDefault("").lowercase()
            if (seenHost.isNotEmpty() && seenHost != appHost) problems += "The server sees its address as ${d.origin}, the app uses $base" to
                "Have the proxy keep the Host header (nginx: proxy_set_header Host \$host) or set PUBLIC_URL=$base in .env. Email links and passkeys use this address."
            if (!isPrivateHost(URI(base).host ?: "") && isPrivateIp(d.clientIp)) problems += "Every visitor looks like ${d.clientIp} to the server" to
                "Set TRUST_PROXY=true and have the proxy send X-Forwarded-For (nginx: proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for). Otherwise one person's failed logins lock out everyone."
            if (problems.isEmpty()) CheckItem("", CheckState.OK, "HTTPS, address ${d.origin} and your IP ${d.clientIp} arrive correctly")
            else CheckItem("", CheckState.WARN, problems.joinToString("\n") { it.first }, problems.joinToString("\n\n") { it.second })
        }

        val user = d?.user ?: Services.session.user.value?.id ?: ""
        val davFiles = d?.davFiles ?: "$base/dav/files/${java.net.URLEncoder.encode(user, "UTF-8").replace("+", "%20")}/"
        val davRoot = d?.davRoot ?: "$base/dav/"
        step("Files over WebDAV") { propfind(davFiles) }
        step("Calendars & contacts (CalDAV / CardDAV)") { propfind(davRoot) }

        step("Calendar & contacts auto-discovery") {
            val bad = listOf("caldav", "carddav").mapNotNull { kind ->
                noRedirect.newCall(req("/.well-known/$kind").build()).execute().use { r ->
                    val loc = r.header("Location") ?: ""
                    if (r.code in 300..399 && loc.trimEnd('/').endsWith("/dav")) null else "/.well-known/$kind → HTTP ${r.code}"
                }
            }
            if (bad.isEmpty()) CheckItem("", CheckState.OK, "/.well-known/caldav and /carddav point to /dav/")
            else CheckItem("", CheckState.WARN, bad.joinToString("\n"),
                "Forward /.well-known/ to Cloudy too, so iPhone, macOS and DAVx⁵ find calendars and contacts from just the server name.")
        }

        step("Large uploads") { uploadCheck(d) }
        step("Live updates") { liveCheck() }
    }

    private fun propfind(url: String): CheckItem {
        val r = quick.newCall(req(url).method("PROPFIND", propfindBody).header("Depth", "0").header("Authorization", basic()).build()).execute()
        return r.use {
            when (it.code) {
                207 -> CheckItem("", CheckState.OK, url)
                401 -> CheckItem("", CheckState.FAIL, "$url → 401 Unauthorized",
                    "The proxy may drop the Authorization header, or this token was revoked. DAV apps sign in with your username and an app password.")
                403, 405, 501 -> CheckItem("", CheckState.FAIL, "$url → HTTP ${it.code}: the proxy blocks WebDAV methods",
                    "Allow all HTTP methods (PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK, REPORT) on the proxy.")
                404 -> CheckItem("", CheckState.FAIL, "$url → 404", "Forward /dav/ to Cloudy (the whole site, not only /api).")
                else -> CheckItem("", CheckState.FAIL, "$url → HTTP ${it.code}")
            }
        }
    }

    /** Sends one full-size chunk of a throwaway upload and cancels it; no file is created. */
    private fun uploadCheck(d: Diagnostics?): CheckItem {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm?.isActiveNetworkMetered == true) return CheckItem("", CheckState.SKIPPED,
            "Skipped on mobile data (it sends ${(d?.chunkSize ?: 8L * 1024 * 1024) / 1024 / 1024} MB). Run it again on Wi-Fi.")
        val root = Services.session.user.value?.rootId ?: return CheckItem("", CheckState.SKIPPED, "Not signed in")
        val chunk = d?.chunkSize ?: 8L * 1024 * 1024
        val start = buildJsonObject { put("parentId", root); put("name", ".cloudy-connection-test-${UUID.randomUUID()}"); put("size", chunk + 1) }
        val id = quick.newCall(req("/api/uploads").post(start.toString().toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) return CheckItem("", CheckState.FAIL, "Could not start a test upload (HTTP ${r.code})")
            api.json.parseToJsonElement(r.body!!.string()).jsonObject["id"]!!.jsonPrimitive.content
        }
        try {
            val t0 = System.nanoTime()
            val client = quick.newBuilder().writeTimeout(2, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()
            client.newCall(req("/api/uploads/$id").put(ByteArray(chunk.toInt()).toRequestBody("application/octet-stream".toMediaType()))
                .header("Upload-Offset", "0").build()).execute().use { r ->
                val secs = (System.nanoTime() - t0) / 1e9
                return when {
                    r.isSuccessful -> {
                        val got = runCatching { api.json.parseToJsonElement(r.body!!.string()).jsonObject["received"]!!.jsonPrimitive.long }.getOrDefault(chunk)
                        if (got == chunk) CheckItem("", CheckState.OK, "${chunk / 1024 / 1024} MB chunk accepted (${"%.1f".format(chunk / 1024.0 / 1024 / secs)} MB/s)")
                        else CheckItem("", CheckState.FAIL, "Only $got of $chunk bytes arrived", "The proxy cuts request bodies short; allow large uploads.")
                    }
                    r.code == 413 -> CheckItem("", CheckState.FAIL, "The proxy refuses ${chunk / 1024 / 1024} MB requests (413)",
                        "Allow large request bodies (nginx: client_max_body_size 0; and proxy_request_buffering off).")
                    else -> CheckItem("", CheckState.FAIL, "Upload chunk → HTTP ${r.code}")
                }
            }
        } finally {
            runCatching { quick.newCall(req("/api/uploads/$id").delete().build()).execute().close() }
        }
    }

    /** The server sends "retry:" at once; a buffering proxy holds it back, so live updates never arrive. */
    private fun liveCheck(): CheckItem {
        val client = quick.newBuilder().readTimeout(8, TimeUnit.SECONDS).build()
        val call = client.newCall(req("/api/events").header("Accept", "text/event-stream").build())
        return try {
            call.execute().use { r ->
                if (!r.isSuccessful) return CheckItem("", CheckState.FAIL, "HTTP ${r.code}")
                val first = r.body!!.source().readUtf8Line()
                if (first != null) CheckItem("", CheckState.OK, "Changes from other devices appear right away")
                else CheckItem("", CheckState.WARN, "The stream closed immediately", PROXY_BUFFERING)
            }
        } catch (e: java.net.SocketTimeoutException) {
            CheckItem("", CheckState.WARN, "Nothing arrived within 8 seconds", PROXY_BUFFERING)
        } finally {
            call.cancel()
        }
    }

    companion object {
        private const val PROXY_BUFFERING = "The proxy buffers responses, so lists only refresh when you pull down. Turn buffering off for Cloudy (nginx: proxy_buffering off) and allow long-lived connections (proxy_read_timeout 3600s)."

        fun isPrivateIp(ip: String): Boolean {
            val v4 = ip.removePrefix("::ffff:")
            return Regex("""^(10\.|127\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)""").containsMatchIn(v4) ||
                ip == "::1" || ip.startsWith("fc", true) || ip.startsWith("fd", true) || ip.startsWith("fe80", true)
        }

        /** Hostname that is clearly local: an address in a private range, localhost, or a .local / .lan name. */
        fun isPrivateHost(host: String): Boolean {
            val h = host.lowercase().trim('[', ']')
            if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
            return Regex("""^[\d.]+$|:""").containsMatchIn(h) && isPrivateIp(h)
        }
    }
}
