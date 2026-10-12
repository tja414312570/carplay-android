package com.carplay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class P2pDirectTestReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "P2pDirectTest"
        const val ACTION_TEST = "com.carplay.app.TEST_P2P"

        private var logFile: java.io.File? = null

        fun initLog(context: Context) {
            try {
                logFile = java.io.File(context.filesDir, "p2p_test.log")
            } catch (_: Exception) {}
        }

        fun log(msg: String) {
            Log.i(TAG, msg)
            try {
                logFile?.appendText("${java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())} $msg\n")
            } catch (_: Exception) {}
            try {
                com.carplay.app.diagnostics.Diagnostics.event("INFO", "[P2P] $msg")
            } catch (_: Exception) {}
        }

        fun buildConfig(ssid: String, passphrase: String, frequency: Int, bssid: String?): WifiP2pConfig? {
            return try {
                val builderClass = Class.forName("android.net.wifi.p2p.WifiP2pConfig\$Builder")
                val builderInstance = builderClass.getDeclaredConstructor().newInstance()

                log("Builder declared fields: " + builderClass.declaredFields.joinToString { "${it.name}:${it.type.simpleName}" })
                
                // Let's check WifiP2pConfig fields too
                val configClass = WifiP2pConfig::class.java
                log("WifiP2pConfig declared fields: " + configClass.declaredFields.joinToString { "${it.name}:${it.type.simpleName}" })

                val mNetworkNameField = builderClass.declaredFields.firstOrNull { it.name.contains("networkName", ignoreCase = true) }?.apply { isAccessible = true }

                if (mNetworkNameField != null) {
                    mNetworkNameField.set(builderInstance, ssid)
                    log("Reflectively set ${mNetworkNameField.name} = $ssid on Builder")
                } else {
                    log("No networkName field in Builder! Trying direct method or direct config modification")
                }

                val setPassphraseMethod = builderClass.declaredMethods.firstOrNull { it.name == "setPassphrase" }
                setPassphraseMethod?.invoke(builderInstance, passphrase)

                if (frequency > 0) {
                    val setFreqMethod = builderClass.declaredMethods.firstOrNull { it.name == "setGroupOperatingFrequency" }
                    setFreqMethod?.invoke(builderInstance, frequency)
                }

                val config: WifiP2pConfig = try {
                    val buildMethod = builderClass.getDeclaredMethod("build")
                    buildMethod.invoke(builderInstance) as WifiP2pConfig
                } catch (e: Exception) {
                    log("Builder.build() failed: ${e.cause ?: e}, falling back to new WifiP2pConfig()")
                    WifiP2pConfig().apply {
                        // Reflect networkName into WifiP2pConfig directly
                        val netField = configClass.declaredFields.firstOrNull { it.name.contains("networkName", ignoreCase = true) }?.apply { isAccessible = true }
                        netField?.set(this, ssid)
                        val passField = configClass.declaredFields.firstOrNull { it.name.contains("passphrase", ignoreCase = true) }?.apply { isAccessible = true }
                        passField?.set(this, passphrase)
                        val freqField = configClass.declaredFields.firstOrNull { it.name.contains("groupOperatingFrequency", ignoreCase = true) || it.name.contains("frequency", ignoreCase = true) }?.apply { isAccessible = true }
                        freqField?.set(this, frequency)
                    }
                }

                if (!bssid.isNullOrEmpty()) {
                    try {
                        config.deviceAddress = bssid
                    } catch (e: Exception) {
                        log("Failed to set deviceAddress: ${e.message}")
                    }
                }
                config
            } catch (e: Exception) {
                log("Failed to build WifiP2pConfig: $e")
                null
            }
        }

        fun runDirectTest(context: Context, ssid: String, password: String, freq: Int, bssid: String, onFeedback: (String) -> Unit) {
            initLog(context)
            log("==================================================")
            log("=== Starting 1002 P2P Direct verification test ===")
            log("Target AP SSID: $ssid, Passphrase: $password, BSSID: $bssid, Freq: $freq")
            log("==================================================")

            val p2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ApGcProbe.testApGcSupport(context)
            if (p2pManager == null) {
                val err = "WifiP2pManager is null!"
                log(err)
                onFeedback(err)
                return
            }

            val channel = p2pManager.initialize(context.applicationContext, Looper.getMainLooper()) {
                log("P2P Channel disconnected")
            }

            val config = buildConfig(ssid, password, freq, bssid)
            if (config == null) {
                val err = "WifiP2pConfig build failed!"
                log(err)
                onFeedback(err)
                return
            }

            log("Successfully constructed WifiP2pConfig: networkName=${config.networkName}, passphrase=${config.passphrase}, deviceAddress=${config.deviceAddress}, wps=${config.wps.setup}")
            onFeedback("已构造P2P配置: SSID=${config.networkName}, 密码=${config.passphrase}，发起connect...")

            p2pManager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("removeGroup succeeded, starting discoverPeers...")
                    startDiscoveryAndConnect(context, p2pManager, channel, config, bssid, onFeedback)
                }

                override fun onFailure(reason: Int) {
                    log("removeGroup returned reason=$reason, starting discoverPeers...")
                    startDiscoveryAndConnect(context, p2pManager, channel, config, bssid, onFeedback)
                }
            })
        }

        private fun startDiscoveryAndConnect(context: Context, manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig, targetBssid: String, onFeedback: (String) -> Unit) {
            log("Calling discoverPeers() to populate mPeers list...")
            manager.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    log("discoverPeers initiated successfully. Polling requestPeers...")
                    pollPeersAndConnect(manager, channel, config, targetBssid, onFeedback, retryCount = 0)
                }

                override fun onFailure(reason: Int) {
                    log("discoverPeers failed with reason=$reason, trying direct connect anyway...")
                    doConnect(manager, channel, config, onFeedback)
                }
            })
        }

        private fun pollPeersAndConnect(manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig, targetBssid: String, onFeedback: (String) -> Unit, retryCount: Int) {
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                manager.requestPeers(channel) { peers ->
                    val list = peers?.deviceList ?: emptyList()
                    log("P2P Discovered peers count: ${list.size}")
                    for (dev in list) {
                        log("Discovered Peer: name=${dev.deviceName}, addr=${dev.deviceAddress}, primaryType=${dev.primaryDeviceType}, status=${dev.status}")
                    }

                    val matched = list.firstOrNull { it.deviceAddress.equals(targetBssid, ignoreCase = true) }
                    if (matched != null) {
                        log(">>> Target peer $targetBssid FOUND in mPeers! Calling connect() now! <<<")
                        doConnect(manager, channel, config, onFeedback)
                    } else if (retryCount < 8) {
                        log("Target peer $targetBssid not in list yet, retrying in 1.5s (attempt ${retryCount + 1}/8)...")
                        pollPeersAndConnect(manager, channel, config, targetBssid, onFeedback, retryCount + 1)
                    } else {
                        log("Target peer $targetBssid not found after timeout. Calling connect() anyway to check system response...")
                        doConnect(manager, channel, config, onFeedback)
                    }
                }
            }, 1500L)
        }

        private fun doConnect(manager: WifiP2pManager, channel: WifiP2pManager.Channel, config: WifiP2pConfig, onFeedback: (String) -> Unit) {
            log("Calling WifiP2pManager.connect() with non-DIRECT SSID config...")
            manager.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    val okMsg = "connect()调用成功！握手已发起"
                    log(">>> SUCCESS: $okMsg with ${config.networkName} <<<")
                    onFeedback(okMsg)
                    
                    val handler = Handler(Looper.getMainLooper())
                    for (delay in listOf(2000L, 5000L, 8000L, 12000L)) {
                        handler.postDelayed({
                            manager.requestConnectionInfo(channel) { info: WifiP2pInfo? ->
                                val infoStr = "ConnectionInfo: groupFormed=${info?.groupFormed}, isGO=${info?.isGroupOwner}, GO_IP=${info?.groupOwnerAddress}"
                                log(infoStr)
                            }
                            manager.requestGroupInfo(channel) { group: WifiP2pGroup? ->
                                val groupStr = "GroupInfo: iface=${group?.`interface`}, networkName=${group?.networkName}, clients=${group?.clientList?.size}"
                                log(groupStr)
                            }
                        }, delay)
                    }
                }

                override fun onFailure(reason: Int) {
                    val reasonStr = when (reason) {
                        WifiP2pManager.ERROR -> "ERROR(0)"
                        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED(1)"
                        WifiP2pManager.BUSY -> "BUSY(2)"
                        else -> "REASON($reason)"
                    }
                    val failMsg = "connect()被拒绝: $reasonStr"
                    log(">>> FAILED: $failMsg <<<")
                    onFeedback(failMsg)
                }
            })
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        log("onReceive intent action = ${intent.action}")
        if (intent.action != ACTION_TEST) return
        val ssid = intent.getStringExtra("ssid") ?: "kjb13-2"
        val password = intent.getStringExtra("password") ?: "kjb123456789"
        val freq = intent.getIntExtra("freq", 5220)
        val bssid = intent.getStringExtra("bssid") ?: "82:87:93:48:40:cd"

        runDirectTest(context, ssid, password, freq, bssid) {}
    }
}
