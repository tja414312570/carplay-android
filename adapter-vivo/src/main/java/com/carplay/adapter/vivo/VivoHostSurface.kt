package com.carplay.adapter.vivo

import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import com.carplay.adapter.generic.ProjectionSurfaceSource
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

/** Native CarLife 8.8.4 ABI. Host permissions, signing checks and callback UID checks stay enforced. */
class VivoHostSurface(private val request: VivoHostRequest) : ProjectionSurfaceSource {
    private lateinit var context: Context
    private var diagnostic: (String) -> Unit = {}
    private var failed: (Throwable) -> Unit = {}
    private val manager = CompletableFuture<IBinder>()
    private val display = CompletableFuture<Int>()
    private val closed = AtomicBoolean()
    private val closing = AtomicBoolean()
    private val lifecycleLock = Any()
    override val inputAvailable get() = display.isDone && !display.isCompletedExceptionally && (touch != null || key != null)
    override val microphoneAvailable get() = microphone != null && !closed.get()
    private var hostUid = -1
    private var bound = false
    private var prepared = false
    private var connectedStatus = false
    private var width = 0; private var height = 0
    @Volatile private var touch: IBinder? = null
    @Volatile private var key: IBinder? = null
    @Volatile private var microphone: IBinder? = null
    private var pcm: (ByteArray,Int,Int,Int) -> Unit = { _,_,_,_ -> }
    private var music: List<Any?>? = null
    private var musicProgress = 0L
    private val contacts = linkedMapOf<Int,Pair<Float,Float>>()
    private var downTime = 0L
    private val keyTimes = mutableMapOf<Int,Long>()
    private val death = IBinder.DeathRecipient { fail(IllegalStateException("vivo 宿主 Binder 已终止")) }
    private var managerDescriptor = MANAGER
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            try {
                synchronized(lifecycleLock) {
                    check(!closing.get() && !closed.get())
                    val desc = runCatching { binder.interfaceDescriptor }.getOrNull()
                    diagnostic("vivo 宿主投屏服务已连接：${name.flattenToShortString()}，描述符=${desc ?: "无"}")
                    if (!desc.isNullOrBlank()) {
                        managerDescriptor = desc
                    } else {
                        diagnostic("vivo 宿主 Binder 描述符为空，保持默认描述符：$managerDescriptor")
                    }
                    binder.linkToDeath(death,0); manager.complete(binder)
                }
                diagnostic("vivo 原生 CarLife 投屏服务已绑定，等待实际显示回调")
            } catch (e: Exception) { manager.completeExceptionally(e); fail(e) }
        }
        override fun onServiceDisconnected(name: ComponentName) = fail(IllegalStateException("vivo 宿主连接断开"))
        override fun onBindingDied(name: ComponentName) = fail(IllegalStateException("vivo 宿主绑定失效"))
        override fun onNullBinding(name: ComponentName) = fail(IllegalStateException("vivo 宿主返回空接口"))
    }
    private fun fail(error: Throwable) { if (!closed.get()) { manager.completeExceptionally(error); display.completeExceptionally(error); failed(error) } }
    override fun attach(context: Context, diagnostic: (String) -> Unit, failed: (Throwable) -> Unit) {
        synchronized(lifecycleLock) {
            check(!closing.get() && !closed.get()) { "宿主会话已关闭" }
            this.context = context; this.diagnostic = diagnostic; this.failed = failed
            val pm = context.packageManager
            val info = pm.getServiceInfo(request.component,0)
            check(info.enabled && info.exported) { "vivo 宿主投屏服务没有开放" }
            check(pm.checkSignatures(request.packageName,"com.vivo.car.networking") == PackageManager.SIGNATURE_MATCH) { "投屏宿主签名不匹配" }
            info.permission?.let { check(context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) { "缺少宿主要求的权限：$it" } }
            hostUid = info.applicationInfo.uid
            bound = context.bindService(Intent().setComponent(request.component),connection,Context.BIND_AUTO_CREATE)
            check(bound) { "vivo 宿主拒绝投屏绑定" }
        }
        // Binding callbacks need the same lock; never wait for them while holding it.
        manager.get(10,TimeUnit.SECONDS)
    }
    private fun endpoint(descriptor: String, handle: (Int,Parcel,Parcel?) -> Boolean) = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) { reply?.writeString(descriptor); return true }
            check(Binder.getCallingUid() == hostUid) { "拒绝非宿主的投屏回调" }
            runCatching { data.enforceInterface(descriptor) }
            return try { handle(code,data,reply) } catch (e: Exception) { fail(e); if (reply != null) { reply.writeException(e); true } else false }
        }
    }
    private val castCallback = endpoint(CALLBACK) { code,data,reply ->
        when (code) {
            1 -> diagnostic("vivo 宿主投屏回调已就绪")
            2 -> { val id = data.readInt(); require(id >= 0); display.complete(id); diagnostic("vivo 实际投屏显示已准备，显示编号 $id") }
            3 -> touch = data.readStrongBinder()
            4 -> { val binder = data.readStrongBinder(); if (touch == binder) touch = null }
            5 -> key = data.readStrongBinder()
            6 -> { val binder = data.readStrongBinder(); if (key == binder) key = null }
            7 -> { music = if (data.readInt() == 0) null else listOf(data.readString(),data.readString(),data.readString(),data.readLong(),data.readInt(),data.readInt()); diagnostic("宿主音乐信息已更新") }
            8 -> { musicProgress = data.readLong(); diagnostic("宿主音乐进度已更新：$musicProgress") }
            9 -> {
                val intent = if (data.readInt() != 0) Intent.CREATOR.createFromParcel(data) else null
                diagnostic("宿主调用：${intent?.action}")
                reply?.writeNoException(); reply?.writeInt(0)
                return@endpoint true
            }
            10 -> fail(IllegalStateException("vivo 宿主停止投屏服务"))
            11 -> diagnostic("宿主音频状态：${data.readInt()}")
            12 -> microphone = data.readStrongBinder()
            13 -> { val binder = data.readStrongBinder(); if (microphone == binder) microphone = null }
            else -> return@endpoint false
        }
        reply?.writeNoException(); true
    }
    private val audioCallback = endpoint(AUDIO) { code,data,reply ->
        when (code) {
            1 -> {
                val bytes = checkNotNull(data.createByteArray()); val category = data.readInt()
                val rate = data.readInt(); val channels = data.readInt(); val encoding = data.readInt()
                require(bytes.size in 1..16384 && channels in 1..2 && bytes.size % (channels*2) == 0)
                if (category in 1..3) pcm(bytes,rate,channels,encoding) else diagnostic("不支持的宿主音频类别：$category")
            }
            2 -> diagnostic("宿主音频类别 ${data.readInt()}，状态 ${data.readInt()}")
            else -> return@endpoint false
        }
        reply?.writeNoException(); true
    }
    private fun <T> call(remote: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T {
        check(!closed.get()) { "宿主会话已关闭" }
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            if (descriptor.isNotBlank()) data.writeInterfaceToken(descriptor)
            write(data)
            check(remote.transact(code,data,reply,0)) { "宿主不支持事务 $code" }
            reply.readException()
            return read(reply)
        }
        finally { data.recycle(); reply.recycle() }
    }
    private fun invoke(intent: Intent): Bundle? = call(manager.get(10,TimeUnit.SECONDS),managerDescriptor,5,
        { it.writeInt(1); intent.writeToParcel(it,0) }, { if (it.readInt() == 0) null else Bundle.CREATOR.createFromParcel(it) })
    override fun start(surface: Surface, width: Int, height: Int, fps: Int, pcm: (ByteArray,Int,Int,Int) -> Unit) {
        this.width = width; this.height = height; this.pcm = pcm
        val remote = manager.get(10,TimeUnit.SECONDS)
        runCatching {
            call(remote,managerDescriptor,4,{ it.writeStrongBinder(castCallback) },{})
            diagnostic("已注册宿主投屏回调(code 4)")
        }.onFailure { diagnostic("注册宿主投屏回调(code 4)失败或未实现: ${it.message}") }
        runCatching {
            call(remote,managerDescriptor,3,{ it.writeStrongBinder(audioCallback) },{})
            diagnostic("已注册宿主音频回调(code 3)")
        }.onFailure { diagnostic("注册宿主音频回调(code 3)未实现或跳过: ${it.message}") }
        val params = JSONObject().put("width",width).put("height",height).put("real_width",width).put("real_height",height).put("default_width",width).put("default_height",height)
        runCatching {
            invoke(Intent("carlife_encode_format").putExtra("params",params.toString()))
            diagnostic("已发送 carlife_encode_format 通知")
        }.onFailure { diagnostic("通知 carlife_encode_format 未实现或跳过: ${it.message}") }
        runCatching {
            invoke(Intent("carlife_other_notify_connect_status").putExtra("connect_status",true))
            connectedStatus = true
            diagnostic("已通知宿主连接状态 connected=true")
        }.onFailure { diagnostic("通知连接状态未实现或跳过: ${it.message}") }
        try {
            call(remote,managerDescriptor,1,{
                it.writeInt(1); it.writeInt(width); it.writeInt(height); it.writeInt(context.resources.displayMetrics.densityDpi); it.writeParcelable(surface,0)
            },{})
            prepared = true
            diagnostic("宿主 prepareCast(code 1) 成功，等待实际显示回调")
        } catch (e: Throwable) {
            val isNotImplemented = e.message?.contains("Not yet implemented") == true
            if (isNotImplemented && managerDescriptor.isNotEmpty()) {
                diagnostic("prepareCast 带描述符未实现，尝试无描述符重试...")
                runCatching {
                    call(remote,"",1,{
                        it.writeInt(1); it.writeInt(width); it.writeInt(height); it.writeInt(context.resources.displayMetrics.densityDpi); it.writeParcelable(surface,0)
                    },{})
                    prepared = true
                    diagnostic("无描述符 prepareCast(code 1) 成功，等待实际显示回调")
                }.onFailure {
                    diagnostic("宿主 prepareCast(code 1) 最终失败: ${it.message}")
                    throw it
                }
            } else if (isNotImplemented && managerDescriptor.isEmpty()) {
                diagnostic("prepareCast 无描述符未实现，尝试标准描述符重试...")
                runCatching {
                    call(remote,MANAGER,1,{
                        it.writeInt(1); it.writeInt(width); it.writeInt(height); it.writeInt(context.resources.displayMetrics.densityDpi); it.writeParcelable(surface,0)
                    },{})
                    prepared = true
                    diagnostic("标准描述符 prepareCast(code 1) 成功，等待实际显示回调")
                }.onFailure {
                    diagnostic("宿主 prepareCast(code 1) 最终失败: ${it.message}")
                    throw it
                }
            } else {
                diagnostic("宿主 prepareCast(code 1) 失败: ${e.message}")
                throw e
            }
        }
        display.get(10,TimeUnit.SECONDS)
    }
    @Synchronized override fun touch(action: Int, x: Float, y: Float, pointer: Int): Boolean {
        val listener = touch ?: return false
        if (!display.isDone || x !in 0f..width.toFloat() || y !in 0f..height.toFloat()) return false
        val now = SystemClock.uptimeMillis()
        if (action == 0) { if (contacts.isEmpty()) downTime = now; contacts[pointer] = x to y }
        else if (pointer !in contacts) return action == 1 else contacts[pointer] = x to y
        val ids = contacts.keys.toList(); val index = ids.indexOf(pointer)
        val encodedAction = when (action) { 0 -> if (ids.size == 1) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_POINTER_DOWN or (index shl 8); 1 -> if (ids.size == 1) MotionEvent.ACTION_UP else MotionEvent.ACTION_POINTER_UP or (index shl 8); else -> MotionEvent.ACTION_MOVE }
        val properties = ids.map { id -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val positions = ids.map { id -> MotionEvent.PointerCoords().apply { this.x = contacts.getValue(id).first; this.y = contacts.getValue(id).second; pressure = if (action == 1 && id == pointer) 0f else 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downTime,now,encodedAction,ids.size,properties,positions,0,0,1f,1f,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0)
        try { return call(listener,PREFIX+"IRemoteTouchListener",1,{ it.writeInt(1); event.writeToParcel(it,0) }, { it.readInt() != 0 }) }
        finally { event.recycle(); if (action == 1) contacts.remove(pointer) }
    }
    @Synchronized override fun key(code: Int, down: Boolean): Boolean {
        val listener = key ?: return false
        val mapped = when (code) { KeyEvent.KEYCODE_MEDIA_PREVIOUS -> 8101; KeyEvent.KEYCODE_MEDIA_NEXT -> 8102; KeyEvent.KEYCODE_MEDIA_PLAY -> 8109; KeyEvent.KEYCODE_MEDIA_PAUSE -> 8110; KeyEvent.KEYCODE_VOICE_ASSIST -> if (down) 8107 else 8108; else -> code }
        val now = SystemClock.uptimeMillis(); val started = if (down) keyTimes.getOrPut(code) { now } else keyTimes.remove(code) ?: now
        val event = KeyEvent(started,now,if (down || code == KeyEvent.KEYCODE_VOICE_ASSIST) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP,mapped,0)
        return call(listener,PREFIX+"IRemoteKeyListener",1,{ it.writeInt(1); event.writeToParcel(it,0) },{ it.readInt() != 0 })
    }
    override fun microphone(pcm: ByteArray, sampleRate: Int, channels: Int): Boolean {
        val listener = microphone ?: return false
        require(sampleRate == 16000 && channels == 1 && pcm.size in 2..8192 && pcm.size % 2 == 0)
        call(listener,PREFIX+"IRemoteAudioListener",1,{ it.writeByteArray(pcm); it.writeInt(sampleRate); it.writeInt(channels); it.writeInt(2) },{})
        return true
    }
    override fun close() {
        if (!closing.compareAndSet(false,true)) return
        synchronized(lifecycleLock) {
            try {
                if (manager.isDone && !manager.isCompletedExceptionally) {
                    if (connectedStatus) runCatching { invoke(Intent("carlife_other_notify_connect_status").putExtra("connect_status",false)) }
                    if (prepared) runCatching { call(manager.get(),managerDescriptor,2,{}, {}) }
                    runCatching { manager.get().unlinkToDeath(death,0) }
                }
            } finally {
                closed.set(true); manager.cancel(true); display.cancel(true)
                if (bound) { bound = false; runCatching { context.unbindService(connection) } }
                touch = null; key = null; microphone = null; contacts.clear(); keyTimes.clear()
            }
        }
    }
    companion object {
        private const val PREFIX = "com.baidu.carlife.mixing.aidl."
        private const val MANAGER = PREFIX+"ICarLifeCastManager"
        private const val CALLBACK = PREFIX+"ICarLifeCastCallback"
        private const val AUDIO = PREFIX+"ISystemAudioCallback"
    }
}
