package com.carplay.adapter.vivo

import android.content.Context
import android.view.Surface
import com.carplay.adapter.generic.ProjectionSurfaceSource
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth bootstrap is independent of capture. Only an actual, validated host request supplies video. */
class DeferredVivoHostSurface(private val request: () -> VivoHostRequest?) : ProjectionSurfaceSource {
    private val closed = AtomicBoolean()
    @Volatile private var source: VivoHostSurface? = null
    override val inputAvailable get() = source?.inputAvailable == true
    override val microphoneAvailable get() = source?.microphoneAvailable == true
    override fun attach(context: Context, diagnostic: (String) -> Unit, failed: (Throwable) -> Unit) {
        diagnostic("车机媒体通道已建立；等待 vivo 宿主提供真实投屏入口")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!closed.get() && System.nanoTime() < deadline) {
            request()?.let { launch ->
                val host = VivoHostSurface(launch); source = host
                if (closed.get()) { host.close(); throw CancellationException("投屏已停止") }
                diagnostic("已收到真实宿主入口 ${launch.packageName}/${launch.className}，版本 ${launch.version}，无线类型 ${launch.usbType}")
                host.attach(context,diagnostic,failed)
                return
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt(); throw CancellationException("等待宿主已取消")
            }
        }
        if (closed.get()) throw CancellationException("投屏已停止")
        error("车机已完成媒体握手，但 vivo 宿主未提供原生画面入口；请检查 Jovi 组件调用")
    }
    override fun start(surface: Surface, width: Int, height: Int, fps: Int, pcm: (ByteArray,Int,Int,Int) -> Unit) =
        checkNotNull(source).start(surface,width,height,fps,pcm)
    override fun touch(action: Int, x: Float, y: Float, pointer: Int) = source?.touch(action,x,y,pointer) == true
    override fun key(code: Int, down: Boolean) = source?.key(code,down) == true
    override fun microphone(pcm: ByteArray, sampleRate: Int, channels: Int) = source?.microphone(pcm,sampleRate,channels) == true
    override fun close() { if (closed.compareAndSet(false,true)) source?.close() }
}
