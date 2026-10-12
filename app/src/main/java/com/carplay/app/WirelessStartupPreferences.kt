package com.carplay.app

import android.content.SharedPreferences
import com.carplay.iap2.controller.PhoneBootstrapProfile
import com.carplay.iap2.transport.RfcommConnectionDirection

/** Apply the source-verified full wireless startup once, preserving identity and trust settings. */
internal object WirelessStartupPreferences {
    private const val REVISION_KEY = "wirelessStartupBaselineRevision"
    private const val REVISION = 19

    @Synchronized fun upgrade(prefs: SharedPreferences, component: Boolean) {
        if (!component || prefs.getInt(REVISION_KEY, 0) >= REVISION) return
        check(prefs.edit()
            .putString("connectionDirection", RfcommConnectionDirection.PHONE_INITIATED.name)
            .putString("bootstrapProfile", PhoneBootstrapProfile.MODERN_WIRELESS.name)
            .putInt(REVISION_KEY, REVISION)
            .commit()) { "保存手机主动连接设置失败，请重试" }
    }
}
