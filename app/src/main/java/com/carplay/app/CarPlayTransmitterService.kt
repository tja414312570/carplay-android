package com.carplay.app

import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.carplay.adapter.PhoneProjectionAdapter
import com.carplay.iap2.catalog.Iap2Endpoints
import com.carplay.iap2.message.Iap2CarPlayMessages
import com.carplay.iap2.message.Iap2Messages
import com.carplay.iap2.session.Iap2Session
import com.carplay.iap2.transport.BluetoothRfcommDuplexStream
import kotlinx.coroutines.*
import java.util.UUID

/**
 * 手机端 CarPlay 无线连接后台核心服务。
 * 流程：
 * 1. 发现/连接车机蓝牙 RFCOMM (标准 UUID 00000000-deca-fade-deca-deafdecacafe 或车机特定通道)。
 * 2. 建立 iAP2 会话，发送官方标准 0x4300 CarPlayAvailability。
 * 3. 接收车机回复的 0x4301 CarPlayStartSession (解析 SSID, Password, AirPlay 端口)。
 * 4. 自动连接车机 Wi-Fi AP。
 * 5. 启动 AirPlay RTSP 投屏握手与视频流推送。
 */
class CarPlayTransmitterService : Service() {
    companion object {
        private const val TAG = "CarPlayTransmitter"
        val IAP2_RFCOMM_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacafe")
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var adapter: PhoneProjectionAdapter

    override fun onCreate() {
        super.onCreate()
        adapter = ProjectionAdapterRegistry.getBestAdapter(this)
        Log.i(TAG, "CarPlayTransmitterService initialized with adapter: ${adapter.name}")
    }

    fun startCarPlayNegotiation(device: BluetoothDevice, bluetoothTransportComponentId: Int = 2) {
        scope.launch {
            try {
                Log.i(TAG, "Connecting to car head unit via Bluetooth RFCOMM: ${device.address}")
                val socket = device.createRfcommSocketToServiceRecord(IAP2_RFCOMM_UUID)
                socket.connect()

                val duplexStream = BluetoothRfcommDuplexStream(socket) { diag ->
                    Log.d(TAG, "RFCOMM: $diag")
                }

                val session = Iap2Session.client(duplexStream, "CarPlayClient") { trace ->
                    Log.d(TAG, "iAP2: $trace")
                }

                if (!session.awaitReady(5000)) {
                    Log.e(TAG, "iAP2 session handshake timeout")
                    return@launch
                }

                // 1. 发送修正后的标准 0x4300 CarPlayAvailability
                val availabilityFrame = Iap2CarPlayMessages.buildAvailability(
                    wirelessSupported = true,
                    bluetoothTransportComponentId = bluetoothTransportComponentId,
                    wiredSupported = false
                )
                session.send(availabilityFrame)
                Log.i(TAG, "Sent official 0x4300 CarPlayAvailability frame to car")

                // 2. 接收车机发回的 0x4301 CarPlayStartSession
                while (isActive) {
                    val incoming = session.recv(10000) ?: continue
                    if (incoming.messageId == Iap2Endpoints.CARPLAY_START_SESSION.id) {
                        Log.i(TAG, "Received 0x4301 CarPlayStartSession from car!")
                        // 解析车机 Wi-Fi 参数并触发连接
                        onCarPlayStartSessionReceived(incoming.payload)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "CarPlay negotiation failed", e)
            }
        }
    }

    private fun onCarPlayStartSessionReceived(payload: ByteArray) {
        Log.i(TAG, "Parsing 0x4301 payload, connecting to car Wi-Fi and launching AirPlay client...")
        // 接下来触发 Wi-Fi NetworkRequest 以及 AirPlay 连接
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
