package com.carplay.iap2.controller

/** One peer-requested reinitialization for an observed firmware, within the original budget. */
class PhoneBluetoothRecovery(private val deadlineNanos: Long, private val nanoTime: () -> Long = System::nanoTime) {
    private var used = false
    var lastRejection: String? = null
        private set

    fun claim(version: AccessoryVersionInfo?, status: BootstrapStatus, profile: PhoneBootstrapProfile,
        cancelled: Boolean): Boolean {
        lastRejection = when {
            cancelled -> "连接已停止"
            used -> "本次连接已完成一次重新协商，不循环重试"
            remainingMillis() == 0L -> "原控制预算已耗尽"
            profile !in setOf(PhoneBootstrapProfile.VIVO_WIRELESS_READY, PhoneBootstrapProfile.MODERN_WIRELESS) -> "当前请求方案未启用该兼容处理"
            !isObservedFirmware(version) -> "当前固件不匹配已观察的 GM (A2LL/E2LB) 版本"
            !status.identified || !status.authenticated -> "识别和签名验证尚未完成"
            !status.modernWirelessSupported || !status.networkRequestQueued -> "尚未完成现代无线请求发布"
            status.endpointReceived -> "网络配置已收到，不在引导阶段重新开始"
            else -> null
        }
        if (lastRejection != null) return false
        used = true
        return true
    }

    fun remainingMillis(): Long = ((deadlineNanos - nanoTime()) / 1_000_000).coerceAtLeast(0)

    companion object {
        val OBSERVED_FIRMWARE_A2LL = AccessoryVersionInfo(
            "A2LL",
            "General Motors",
            "aegean_orange_n-user 12 SQBP5-39 67 release-keys",
            "SA8155-1.0"
        )
        val OBSERVED_FIRMWARE_E2LB = AccessoryVersionInfo(
            "E2LB",
            "Saic General Motors",
            "aegean_n-user 12 SQBP5-39 83 release-keys",
            "SA8155-1.0"
        )
        val OBSERVED_FIRMWARES = setOf(OBSERVED_FIRMWARE_A2LL, OBSERVED_FIRMWARE_E2LB)

        @Deprecated("Use OBSERVED_FIRMWARE_A2LL or OBSERVED_FIRMWARES", ReplaceWith("OBSERVED_FIRMWARE_A2LL"))
        val OBSERVED_FIRMWARE = OBSERVED_FIRMWARE_A2LL

        fun isObservedFirmware(version: AccessoryVersionInfo?): Boolean = version in OBSERVED_FIRMWARES
    }
}
