package io.cloudy.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.cloudy.app.data.Api
import io.cloudy.app.data.Offline
import io.cloudy.app.data.Session
import io.cloudy.app.work.Jobs

/** Process-wide singletons. */
object Services {
    lateinit var session: Session
    lateinit var api: Api
    lateinit var offline: Offline
}

class CloudyApp : Application(), ImageLoaderFactory {
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
