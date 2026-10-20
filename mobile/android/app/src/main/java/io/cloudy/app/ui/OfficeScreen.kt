package io.cloudy.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavController
import io.cloudy.app.Services
import io.cloudy.app.data.OfficeLaunch

/**
 * Office document in the server's Collabora Online editor. A small host page (with the server's
 * origin, so Collabora's postMessage checks pass) POSTs the access token into an iframe, as the web app does.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OfficeScreen(nav: NavController, id: Long) {
    var launch by remember { mutableStateOf<OfficeLaunch?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try { launch = Services.api.officeLaunch(id) } catch (e: Exception) { error = e.message ?: "Cannot open the editor" }
    }
    BackHandler { nav.popBackStack() } // Collabora saves when the editor disconnects

    val l = launch
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp))
            l == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            else -> AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        addJavascriptInterface(object {
                            @JavascriptInterface fun close() { post { nav.popBackStack() } }
                        }, "CloudyHost")
                        val origin = Services.session.server.trimEnd('/')
                        loadDataWithBaseURL("$origin/", hostPage(origin + l.url, l), "text/html", "utf-8", null)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}

private fun esc(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private fun hostPage(action: String, l: OfficeLaunch) = """
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1">
<style>html,body,iframe{margin:0;padding:0;border:0;width:100%;height:100%;display:block}</style></head><body>
<form id="f" action="${esc(action)}" method="post" target="editor">
<input type="hidden" name="access_token" value="${esc(l.accessToken)}">
<input type="hidden" name="access_token_ttl" value="${l.accessTokenTtl}"></form>
<iframe name="editor" allow="clipboard-read; clipboard-write; fullscreen"></iframe>
<script>
var ed = document.getElementsByName('editor')[0];
window.addEventListener('message', function (e) {
  if (e.source !== ed.contentWindow) return;
  var m; try { m = typeof e.data === 'string' ? JSON.parse(e.data) : e.data; } catch (x) { return; }
  if (m && m.MessageId === 'App_LoadingStatus' && m.Values && m.Values.Status === 'Frame_Ready')
    ed.contentWindow.postMessage(JSON.stringify({ MessageId: 'Host_PostmessageReady', SendTime: Date.now(), Values: {} }), location.origin);
  if (m && m.MessageId === 'UI_Close') CloudyHost.close();
});
document.getElementById('f').submit();
</script></body></html>
""".trimIndent()
