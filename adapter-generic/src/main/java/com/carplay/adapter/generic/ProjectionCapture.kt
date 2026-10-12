package com.carplay.adapter.generic

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import com.carplay.adapter.*
import com.carplay.sender.VideoPackets
import com.carplay.sender.CaptureGeometry
import java.util.concurrent.atomic.AtomicBoolean

object ProjectionInput {
    var touch: ((Int, Float, Float, Int) -> Boolean)? = null
    var key: ((Int, Boolean) -> Boolean)? = null
}

/** Authorized screen capture. Every instance owns one user consent token and one codec lifecycle. */
open class ProjectionCapture(final override val name: String) : PhoneProjectionAdapter {
    override val capabilities get() = ProjectionCapabilities(true, surfaceSource != null || Build.VERSION.SDK_INT >= 29,
        surfaceSource?.inputAvailable ?: (ProjectionInput.touch != null), surfaceSource?.microphoneAvailable ?: false,
        if (surfaceSource != null) "原生画面由 vivo 宿主 Surface 提供，输入与声音以实际回调为准" else "屏幕需系统授权；音频取决于来源应用允许捕获；触控需无障碍授权")
    private var observer: ProjectionObserver? = null
    private var grant: ProjectionGrant? = null
    private var projection: MediaProjection? = null
    private var codec: MediaCodec? = null
    private var display: VirtualDisplay? = null
    private var inputSurface: android.view.Surface? = null
    private var callbackThread: HandlerThread? = null
    private var audio: AudioRecord? = null
    private var audioCodec: MediaCodec? = null
    private var audioThread: Thread? = null
    private val stopped = AtomicBoolean(true)
    private var context: Context? = null
    private var width = 0; private var height = 0; private var fps = 0
    private var consumed = false
    private val pendingKeyFrame = AtomicBoolean()
    @Volatile private var geometry: CaptureGeometry? = null
    var captureAudio = false
    var surfaceSource: ProjectionSurfaceSource? = null
    private val nativePcm = java.util.concurrent.ArrayBlockingQueue<ByteArray>(24)

    override fun setObserver(observer: ProjectionObserver) { this.observer = observer }
    override fun setGrant(grant: ProjectionGrant) { check(projection == null); this.grant = grant; consumed = false }
    override fun isSupported(context: Context) = context.getSystemService(MediaProjectionManager::class.java) != null
    override suspend fun prepare(context: Context, width: Int, height: Int, fps: Int): Boolean {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0 && fps in 1..60)
        check(surfaceSource != null || grant != null && !consumed) { "请重新授权屏幕投射" }
        this.context = context.applicationContext; this.width = width; this.height = height; this.fps = fps
        surfaceSource?.attach(context.applicationContext, { observer?.diagnostic(it) }, { observer?.failed(it) })
        observer?.diagnostic("$name 准备编码 ${width}×${height} / $fps fps")
        return true
    }

    override suspend fun startProjection(onFrameEncoded: (ByteArray, Long, Boolean) -> Unit) {
        check(stopped.compareAndSet(true, false)) { "屏幕捕获已运行" }
        try {
            val app = checkNotNull(context)
            val worker = HandlerThread("carplay-capture").apply { start() }; callbackThread = worker
            val handler = Handler(worker.looper)
            val media = if (surfaceSource == null) {
                val consent = checkNotNull(grant); check(!consumed); consumed = true; grant = null
                checkNotNull(app.getSystemService(MediaProjectionManager::class.java).getMediaProjection(consent.resultCode, consent.data)) { "屏幕投射授权被拒绝" }
            } else null
            projection = media
            media?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { release(); observer?.failed(IllegalStateException("系统已停止屏幕投射，请重新授权")) }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    if (width > 0 && height > 0) geometry = CaptureGeometry(width,height,this@ProjectionCapture.width,this@ProjectionCapture.height)
                }
            }, handler)
            val bounds = app.resources.displayMetrics
            geometry = if (surfaceSource == null) CaptureGeometry(bounds.widthPixels,bounds.heightPixels,width,height) else CaptureGeometry(width,height,width,height)
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC); codec = encoder
            encoder.setCallback(object : MediaCodec.Callback() {
                override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
                override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                    try {
                        if (!stopped.get() && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = checkNotNull(codec.getOutputBuffer(index)).duplicate()
                            data.position(info.offset); data.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size); data.get(bytes)
                            onFrameEncoded(VideoPackets.lengthPrefixed(bytes), info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                        }
                    } catch (error: Exception) { observer?.failed(error) }
                    finally { runCatching { codec.releaseOutputBuffer(index, false) } }
                }
                override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) { observer?.failed(error) }
                override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                    try {
                        fun bytes(name: String) = checkNotNull(format.getByteBuffer(name)).duplicate().let { buffer -> ByteArray(buffer.remaining()).also(buffer::get) }
                        observer?.videoConfig(VideoPackets.avcC(bytes("csd-0"), bytes("csd-1")))
                    } catch (error: Exception) { observer?.failed(error) }
                }
            }, handler)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps / 8).coerceIn(1_000_000, 20_000_000).toInt())
                setInteger(MediaFormat.KEY_FRAME_RATE, fps); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface(); inputSurface = surface; encoder.start()
            if (pendingKeyFrame.getAndSet(false)) requestKeyFrame()
            display = media?.createVirtualDisplay("CarPlay Sender", width, height, app.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler)
            surfaceSource?.let { source ->
                if (captureAudio) startNativeAudio()
                source.start(surface,width,height,fps) { pcm,rate,channels,encoding ->
                    if (captureAudio && !stopped.get()) {
                        if (rate != 48000 || channels != 2 || encoding != AudioFormat.ENCODING_PCM_16BIT || pcm.size % 4 != 0) {
                            observer?.failed(IllegalArgumentException("宿主 PCM 格式不匹配：$rate Hz / $channels 声道 / $encoding"))
                        } else if (!nativePcm.offer(pcm.copyOf())) observer?.diagnostic("宿主音频接收队列满，已记录丢包")
                    }
                }
            }
            if (captureAudio && media != null) {
                check(Build.VERSION.SDK_INT >= 29) { "播放音频捕获需要 Android 10 或更高版本" }
                startAudio(media)
            }
        } catch (error: Exception) { release(); throw error }
    }

    private fun startNativeAudio() {
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); audioCodec = encoder
        encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,48000,2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE,128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,262144)
        },null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        audioThread = Thread({
            try {
                var samples = 0L; val info = MediaCodec.BufferInfo()
                fun drain() {
                    var index = encoder.dequeueOutputBuffer(info,0)
                    while (index >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val output = checkNotNull(encoder.getOutputBuffer(index)).duplicate().apply { position(info.offset); limit(info.offset+info.size) }
                            val data = ByteArray(info.size); output.get(data); observer?.audioFrame(data,info.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(index,false); index = encoder.dequeueOutputBuffer(info,0)
                    }
                }
                while (!stopped.get()) {
                    val bytes = nativePcm.poll(100,java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (bytes != null) {
                        var offset = 0
                        while (offset < bytes.size && !stopped.get()) {
                            val index = encoder.dequeueInputBuffer(10000)
                            if (index < 0) { drain(); continue }
                            val input = checkNotNull(encoder.getInputBuffer(index)).apply { clear() }
                            val count = minOf(input.remaining(),bytes.size-offset) / 4 * 4
                            check(count > 0); input.put(bytes,offset,count)
                            encoder.queueInputBuffer(index,0,count,samples * 1_000_000 / 48000,0)
                            samples += count / 4; offset += count
                            drain()
                        }
                    }
                    drain()
                }
            } catch (error: Exception) { if (!stopped.get()) observer?.failed(error) }
        },"carplay-host-audio").apply { start() }
    }

    @android.annotation.TargetApi(29)
    private fun startAudio(media: MediaProjection) {
        val app = checkNotNull(context)
        if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("请授权录音权限后发送媒体音频")
        }
        val buffer = maxOf(8192, AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT))
        val capture = AudioPlaybackCaptureConfiguration.Builder(media).addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).build()
        val record = AudioRecord.Builder().setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(buffer).setAudioPlaybackCaptureConfig(capture).build()
        audio = record; check(record.state == AudioRecord.STATE_INITIALIZED) { "音频捕获初始化失败" }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC); audioCodec = encoder
        encoder.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, 48000, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 128000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, buffer)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start(); record.startRecording()
        audioThread = Thread({
            try {
                var samples = 0L; val info = MediaCodec.BufferInfo()
                while (!stopped.get()) {
                    val index = encoder.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val input = checkNotNull(encoder.getInputBuffer(index)); input.clear()
                        val count = record.read(input, minOf(input.capacity(), buffer), AudioRecord.READ_BLOCKING)
                        check(count > 0) { "音频读取失败：$count" }
                        encoder.queueInputBuffer(index, 0, count, samples * 1_000_000 / 48000, 0); samples += count / 4
                    }
                    var out = encoder.dequeueOutputBuffer(info, 0)
                    while (out >= 0) {
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val bytes = ByteArray(info.size); checkNotNull(encoder.getOutputBuffer(out)).apply { position(info.offset); get(bytes) }
                            observer?.audioFrame(bytes, info.presentationTimeUs)
                        }
                        encoder.releaseOutputBuffer(out, false); out = encoder.dequeueOutputBuffer(info, 0)
                    }
                }
            } catch (error: Exception) { if (!stopped.get()) observer?.failed(error) }
        }, "carplay-audio-capture").apply { start() }
    }

    override fun requestKeyFrame() {
        val encoder = codec
        if (encoder == null) { pendingKeyFrame.set(true); return }
        encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
    }
    override fun onTouchEvent(action: Int, x: Float, y: Float, pointerId: Int) {
        surfaceSource?.let { check(it.touch(action,x,y,pointerId)) { "宿主触控回调未接受操作" }; return }
        val point = checkNotNull(geometry) { "捕获画面尺寸尚未就绪" }.point(x,y) ?: return
        check(ProjectionInput.touch?.invoke(action, point.first, point.second, pointerId) == true) { "触控未执行：请启用无障碍输入服务" }
    }
    override fun onKeyEvent(keyCode: Int, isDown: Boolean) {
        surfaceSource?.let { check(it.key(keyCode,isDown)) { "宿主按键回调未接受操作" }; return }
        check(ProjectionInput.key?.invoke(keyCode, isDown) == true) { "当前输入服务不支持此按键" }
    }
    override fun deliverMicrophone(pcm: ByteArray, sampleRate: Int, channels: Int): Boolean {
        surfaceSource?.let { return it.microphone(pcm,sampleRate,channels) }
        observer?.diagnostic("通用捕获无法将车机麦克风注入其他应用；未声明麦克风能力")
        return false
    }
    override suspend fun stopProjection() { release() }
    private fun release() {
        if (!stopped.compareAndSet(false, true)) { runCatching { surfaceSource?.close() }; return }
        runCatching { surfaceSource?.close() }; nativePcm.clear()
        runCatching { audio?.stop() }; audioThread?.let { if (it != Thread.currentThread()) it.join(1000) }
        runCatching { audio?.release() }; audio = null
        runCatching { audioCodec?.stop() }; runCatching { audioCodec?.release() }; audioCodec = null
        display?.release(); display = null
        runCatching { codec?.stop() }; runCatching { codec?.release() }; codec = null
        inputSurface?.release(); inputSurface = null
        projection?.stop(); projection = null
        callbackThread?.quitSafely(); callbackThread = null
    }
}
