package com.amitbharat.phonedialer

import android.app.Application
import com.amitbharat.phonedialer.service.DialerKeepAliveService
import com.amitbharat.phonedialer.telecom.DialerInCallService
import com.amitbharat.phonedialer.utils.PreferencesManager
import com.amitbharat.phonedialer.utils.SmsNotificationHelper
import com.amitbharat.phonedialer.utils.ThemeUtils

class DialerApp : Application() {
    companion object {
        lateinit var instance: DialerApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        ThemeUtils.applyTheme(this)
        DialerInCallService.createNotificationChannels(this)
        SmsNotificationHelper.createNotificationChannel(this)
        DialerKeepAliveService.createNotificationChannel(this)

        if (PreferencesManager.getInstance(this).isKeepAliveEnabled()) {
            DialerKeepAliveService.startService(this)
        }
    }
}

