package io.cloudy.app.work

import androidx.work.WorkManager

// Google Play delivers updates; also drop a check left behind by an earlier self-updating install.
fun scheduleUpdateCheck(wm: WorkManager) {
    wm.cancelUniqueWork("app-update")
}
