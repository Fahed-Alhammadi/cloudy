package io.filecloud.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.filecloud.app.data.Api
import io.filecloud.app.data.Offline
import io.filecloud.app.data.Session
import io.filecloud.app.work.Jobs

/** Process-wide singletons. */
object Services {
    lateinit var session: Session
    lateinit var api: Api
    lateinit var offline: Offline
}

class FileCloudApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Services.session = Session(this)
        Services.api = Api(Services.session)
        Services.offline = Offline(this, Services.api)
        if (Services.session.isLoggedIn) Jobs.schedule(this)
    }

    // Coil uses our authenticated HTTP client so image previews work.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).okHttpClient { Services.api.http }.crossfade(true).build()
}
