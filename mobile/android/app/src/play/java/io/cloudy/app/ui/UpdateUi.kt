package io.cloudy.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.cloudy.app.BuildConfig

// Google Play delivers updates, so this build has no updater (the github flavor has the real one).

@Composable
fun UpdatePrompt() {}

@Composable
fun UpdateEntry() {
    ListItem(
        leadingContent = { Icon(Icons.Outlined.Info, null) },
        headlineContent = { Text("Version ${BuildConfig.VERSION_NAME}") },
    )
}
