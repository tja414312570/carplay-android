package com.carplay.app

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import com.carplay.app.diagnostics.Diagnostics

object ApGcProbe {
    fun testApGcSupport(context: Context) {
        try {
            val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            if (p2pManager == null) {
                P2pDirectTestReceiver.log("ApGcProbe: p2pManager is null")
                return
            }

            val method = try {
                WifiP2pManager::class.java.getMethod("isApGcSupported")
            } catch (e: Exception) {
                P2pDirectTestReceiver.log("ApGcProbe: WifiP2pManager.isApGcSupported() not found: ${e.message}")
                null
            }

            if (method != null) {
                val result = method.invoke(p2pManager)
                P2pDirectTestReceiver.log(">>> WifiP2pManager.isApGcSupported() = $result <<<")
            }

            // Also check IWifiP2pManager AIDL service directly
            try {
                val serviceField = WifiP2pManager::class.java.getDeclaredField("mService")
                serviceField.isAccessible = true
                val mService = serviceField.get(p2pManager)
                P2pDirectTestReceiver.log("ApGcProbe: mService = $mService")
                val iMethod = mService?.javaClass?.getMethod("isApGcSupported")
                val iResult = iMethod?.invoke(mService)
                P2pDirectTestReceiver.log(">>> IWifiP2pManager.isApGcSupported() = $iResult <<<")
            } catch (e: Exception) {
                P2pDirectTestReceiver.log("ApGcProbe: mService reflection error: ${e.message}")
            }
        } catch (t: Throwable) {
            P2pDirectTestReceiver.log("ApGcProbe error: ${t.message}")
        }
    }
}
