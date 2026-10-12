package com.carplay.app

import android.net.Network
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.system.StructTimeval
import com.carplay.sender.RtspWire
import java.io.Closeable
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** The listener and its accepted descriptors are scoped to the actual car Wi-Fi Network. */
internal class VehicleControlServer(network: Network, local: InetAddress) : Closeable {
    private val closed = AtomicBoolean()
    private val descriptor = Os.socket(if (local is Inet6Address) OsConstants.AF_INET6 else OsConstants.AF_INET,
        OsConstants.SOCK_STREAM,OsConstants.IPPROTO_TCP)
    val localPort: Int

    init {
        try {
            network.bindSocket(descriptor)
            Os.setsockoptInt(descriptor,OsConstants.SOL_SOCKET,OsConstants.SO_REUSEADDR,1)
            Os.bind(descriptor,local,0)
            Os.listen(descriptor,50)
            localPort = (Os.getsockname(descriptor) as InetSocketAddress).port
        } catch (error: Exception) { runCatching { Os.close(descriptor) }; throw IOException("车机 Wi-Fi 控制监听创建失败",error) }
    }

    fun accept(): Connection {
        if (closed.get()) throw IOException("车机控制监听已关闭")
        val poll = StructPollfd().apply { fd = descriptor; events = OsConstants.POLLIN.toShort() }
        if (Os.poll(arrayOf(poll),1000) == 0) throw SocketTimeoutException("等待车机控制邀请")
        if (closed.get()) throw IOException("车机控制监听已关闭")
        val accepted = Os.accept(descriptor,null)
        try { return Connection(accepted) } catch (error: Exception) { runCatching { Os.close(accepted) }; throw error }
    }

    override fun close() {
        if (!closed.compareAndSet(false,true)) return
        runCatching { Os.shutdown(descriptor,OsConstants.SHUT_RDWR) }
        runCatching { Os.close(descriptor) }
    }

    class Connection(private val descriptor: FileDescriptor) : Closeable {
        private val closed = AtomicBoolean()
        val remoteAddress: InetAddress = (Os.getpeername(descriptor) as InetSocketAddress).address
        private val input: FileInputStream
        private val output: FileOutputStream
        init {
            if (Build.VERSION.SDK_INT >= 29) {
                Os.setsockoptTimeval(descriptor,OsConstants.SOL_SOCKET,OsConstants.SO_RCVTIMEO,StructTimeval.fromMillis(5000))
            } else error("车机无线控制监听需要 Android 10 或更高版本")
            input = FileInputStream(Os.dup(descriptor))
            try { output = FileOutputStream(Os.dup(descriptor)) } catch (error: Exception) { input.close(); throw error }
        }
        fun wire() = RtspWire({input},{output},::close)
        override fun close() {
            if (!closed.compareAndSet(false,true)) return
            runCatching { Os.shutdown(descriptor,OsConstants.SHUT_RDWR) }
            runCatching { input.close() }; runCatching { output.close() }
            runCatching { Os.close(descriptor) }
        }
    }
}
