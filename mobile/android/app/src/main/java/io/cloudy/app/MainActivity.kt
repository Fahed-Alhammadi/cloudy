package io.cloudy.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.cloudy.app.data.AppLock
import io.cloudy.app.data.Lang
import io.cloudy.app.ui.CloudyTheme
import io.cloudy.app.ui.LockScreen
import io.cloudy.app.ui.LoginScreen
import io.cloudy.app.ui.MainScreen

// FragmentActivity (a ComponentActivity) because the app lock's BiometricPrompt needs one.
class MainActivity : FragmentActivity() {
    /** Files handed to us via "Share to Cloudy"; the Files screen offers to upload them. */
    val sharedUris = mutableStateOf<List<Uri>>(emptyList())

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Services.api.onUnauthorized = { runOnUiThread { Services.session.signOut() } }
        handleShare(intent)
        setContent {
            CloudyTheme {
                val user by Services.session.user.collectAsStateWithLifecycle()
                val locked by AppLock.locked.collectAsStateWithLifecycle()
                when {
                    user == null || Services.session.token == null -> LoginScreen()
                    locked -> LockScreen()
                    else -> MainScreen(sharedUris)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (Services.session.isLoggedIn) AppLock.onStart(this)
    }

    override fun onStop() {
        super.onStop()
        AppLock.onStop()
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
