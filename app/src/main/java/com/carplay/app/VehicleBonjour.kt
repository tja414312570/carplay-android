package com.carplay.app

import android.content.Context
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

/** Publishes the phone and discovers the peer only on the owned vehicle network. */
internal class VehicleBonjour(
    context: Context, private val network: Network, private val local: InetAddress,
    private val port: Int, private val transport: String,
    private val diagnostic: (String) -> Unit,
    private val resolved: (List<InetAddress>,Int,Map<String,ByteArray>) -> Unit,
    private val failed: (Throwable) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean()
    private val lifecycle = Any()
    private val manager = context.getSystemService(NsdManager::class.java)
    private var legacy: JmDNS? = null
    private var registered = false
    private var discovering = false
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executor { it.run() }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            synchronized(lifecycle) {
                if (closed.get()) { releaseRegistration(); return }
                diagnostic("Bonjour 控制服务已发布到车机 Wi-Fi：network=${network.networkHandle}")
            }
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
            synchronized(lifecycle) { registered = false }
            if (!closed.get()) failed(IllegalStateException("车机 Wi-Fi Bonjour 发布失败：$code"))
        }
        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { diagnostic("Bonjour 撤销发布失败：$code") }
    }
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { if (closed.get()) synchronized(lifecycle) { releaseDiscovery() } }
        override fun onDiscoveryStopped(type: String) = Unit
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            synchronized(lifecycle) { discovering = false }
            if (!closed.get()) failed(IllegalStateException("车机 Wi-Fi Bonjour 发现失败：$code"))
        }
        override fun onStopDiscoveryFailed(type: String, code: Int) { diagnostic("Bonjour 停止发现失败：$code") }
        override fun onServiceLost(info: NsdServiceInfo) = Unit
        override fun onServiceFound(info: NsdServiceInfo) {
            if (closed.get() || resolving.size >= 16 || !resolving.add(info.serviceName)) return
            val request = NsdServiceInfo().apply {
                serviceName = info.serviceName; serviceType = info.serviceType
                if (Build.VERSION.SDK_INT >= 33) setNetwork(network)
            }
            @Suppress("DEPRECATION")
            try { manager.resolveService(request,object : NsdManager.ResolveListener {
                override fun onResolveFailed(service: NsdServiceInfo, code: Int) { resolving.remove(info.serviceName) }
                override fun onServiceResolved(service: NsdServiceInfo) {
                    resolving.remove(info.serviceName)
                    if (closed.get()) return
                    if (Build.VERSION.SDK_INT >= 33 && service.network != network) return
                    @Suppress("DEPRECATION")
                    val addresses = if (Build.VERSION.SDK_INT >= 34) service.hostAddresses else listOfNotNull(service.host)
                    resolved(addresses,service.port,service.attributes)
                }
            }) } catch (error: Exception) { resolving.remove(info.serviceName); if (!closed.get()) failed(error) }
        }
    }

    fun start() {
        synchronized(lifecycle) {
            check(!closed.get())
            if (Build.VERSION.SDK_INT >= 33) {
                val info = NsdServiceInfo().apply {
                    serviceName = "CarPlay Android"; serviceType = "_carplay-ctrl._tcp"
                    setPort(this@VehicleBonjour.port); setNetwork(this@VehicleBonjour.network)
                    setAttribute("id",transport); setAttribute("srcvers","320.17")
                }
                registered = true
                manager.registerService(info,NsdManager.PROTOCOL_DNS_SD,registration)
                discovering = true
                manager.discoverServices("_airplay._tcp",NsdManager.PROTOCOL_DNS_SD,network,executor,discovery)
                diagnostic("Bonjour 发布与发现限定车机 Wi-Fi：network=${network.networkHandle}，port=$port")
            } else {
                val dns = JmDNS.create(local,"CarPlay Android"); legacy = dns
                dns.registerService(ServiceInfo.create("_carplay-ctrl._tcp.local.","CarPlay Android",port,0,0,mapOf("id" to transport,"srcvers" to "320.17")))
                dns.addServiceListener("_airplay._tcp.local.",object : ServiceListener {
                    override fun serviceAdded(event: ServiceEvent) { if (!closed.get()) dns.requestServiceInfo(event.type,event.name,true) }
                    override fun serviceRemoved(event: ServiceEvent) = Unit
                    override fun serviceResolved(event: ServiceEvent) {
                        if (closed.get()) return
                        val info = event.info
                        resolved(info.inetAddresses.toList(),info.port,info.propertyNames.toList().associateWith { info.getPropertyBytes(it) ?: ByteArray(0) })
                    }
                })
                diagnostic("Bonjour 使用实际车机 Wi-Fi 本地地址（Android 10–12）")
            }
        }
    }
    private fun releaseRegistration() {
        if (registered) {
            registered = false
            runCatching { manager.unregisterService(registration) }
                .onFailure { diagnostic("Bonjour 撤销请求失败：${it.javaClass.simpleName}") }
        }
    }
    private fun releaseDiscovery() {
        if (discovering) {
            discovering = false
            runCatching { manager.stopServiceDiscovery(discovery) }
                .onFailure { diagnostic("Bonjour 停止请求失败：${it.javaClass.simpleName}") }
        }
    }
    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        synchronized(lifecycle) {
            if (Build.VERSION.SDK_INT >= 33) { releaseDiscovery(); releaseRegistration() }
            runCatching { legacy?.close() }; legacy = null; resolving.clear()
        }
    }
}
