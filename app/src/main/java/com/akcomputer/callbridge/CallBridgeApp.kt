package com.akcomputer.callbridge

import android.app.Application
import com.akcomputer.callbridge.core.LocalHistory
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.service.Notifications

class CallBridgeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        LocalHistory.init(this)
        Notifications.createChannels(this)
    }
}
