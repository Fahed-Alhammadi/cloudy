package io.filecloud.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.IntentCompat
import io.filecloud.app.ui.FileCloudTheme
import io.filecloud.app.ui.LoginScreen
import io.filecloud.app.ui.MainScreen

class MainActivity : ComponentActivity() {
    /** Files handed to us via "Share to FileCloud"; the Files screen offers to upload them. */
    val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Services.api.onUnauthorized = { runOnUiThread { Services.session.signOut() } }
        handleShare(intent)
        setContent {
            FileCloudTheme {
                val user by Services.session.user.collectAsStateWithLifecycle()
                if (user == null || Services.session.token == null) LoginScreen()
                else MainScreen(sharedUris)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    private fun handleShare(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { sharedUris.value = listOf(it) }
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let { sharedUris.value = it }
        }
    }
}
