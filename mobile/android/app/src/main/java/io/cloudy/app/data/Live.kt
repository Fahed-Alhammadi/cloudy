package io.cloudy.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive

/**
 * Live updates: one Server-Sent Events stream while the app is visible. Screens collect [events]
 * and reload when their folder is listed, so changes from other devices show up without refreshing.
 */
object Live {
    private val _events = MutableSharedFlow<ChangeEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<ChangeEvent> = _events

    /** Runs until cancelled (call while the UI is started); reconnects after network errors. */
    suspend fun run(api: Api) {
        while (currentCoroutineContext().isActive) {
            try {
                api.streamEvents { _events.tryEmit(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.status == 401 || e.status == 404) return // logged out, or a server without live updates
            } catch (_: Exception) {
                // network error: retry below
            }
            delay(5_000)
        }
    }
}

/** File types the server's office editor opens (loaded after login; empty when it is off). */
object Office {
    @Volatile var extensions: Map<String, String> = emptyMap()

    fun canOpen(node: FileNode): Boolean =
        !node.isDir && node.name.substringAfterLast('.', "").lowercase() in extensions

    suspend fun load(api: Api) {
        extensions = runCatching { api.office().extensions }.getOrDefault(extensions)
    }
}
