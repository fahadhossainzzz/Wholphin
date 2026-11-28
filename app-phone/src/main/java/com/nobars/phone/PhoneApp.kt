package com.nobars.phone

import android.app.Application
import com.github.damontecres.wholphin.nobars.playback.NoBarsLog
import com.github.damontecres.wholphin.nobars.playback.StallGuard
import com.nobars.jellyfin.NbJellyfin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

/** NoBars phone: app start. Keeps a log file, records why the last run ended, and restores the remembered login. */
class PhoneApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Timber.plant(NoBarsLog.tree(this))
        StallGuard.logPreviousExits(this, BuildConfig.VERSION_NAME)
        NbJellyfin.get(this, CLIENT_NAME, BuildConfig.VERSION_NAME).restore()
    }

    companion object {
        const val CLIENT_NAME = "Wholphin NoBars Phone"

        /** Work that must finish even after a screen closes (reports at the end of a film). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
