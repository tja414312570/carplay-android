package com.carplay.app

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.projection.MediaProjectionManager
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import com.carplay.app.diagnostics.*
import com.carplay.iap2.transport.PhoneBluetoothProfile
import com.carplay.iap2.transport.RfcommConnectionDirection
import com.carplay.iap2.controller.PhoneBootstrapProfile
import kotlinx.coroutines.*
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var settings: SettingsStore
    private lateinit var devices: Spinner
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var counters: TextView
    private lateinit var timeline: TextView
    private lateinit var logs: TextView
    private lateinit var uploadStatus: TextView
    private lateinit var chart: MetricsChart
    private lateinit var progress: ProgressBar
    private val addresses = mutableListOf<String>()
    private var startingPeer: String? = null
    private var pendingSession: String? = null
    private val accent = Color.rgb(41,212,181)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this); Diagnostics.initialize(this)
        acceptComponentIntent(intent)
        window.statusBarColor = Color.rgb(13,24,41); window.navigationBarColor = Color.rgb(13,24,41)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22),dp(18),dp(22),dp(24)); setBackgroundColor(Color.rgb(13,24,41)) }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(layout) })
        fun text(value: String, size: Float = 15f): TextView = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(225,233,243)); setPadding(0,dp(8),0,dp(8)); layout.addView(this) }
        fun button(value: String, action: () -> Unit) = Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() }; layout.addView(this) }
        text(if (BuildConfig.IS_CARLIFE_COMPONENT) "JOVI · CARLIFE 组件替换" else "CARPLAY · 无线发送端",12f).setTextColor(accent)
        text(if (BuildConfig.IS_CARLIFE_COMPONENT) "无线 CarPlay 组件" else "手机连接车机",28f)
        status = text("未连接",22f); status.setTextColor(accent)
        detail = text("选择已配对车机，点击开始无线连接，由手机发起")
        progress = ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max = 8; layout.addView(this) }
        devices = Spinner(this).apply { setBackgroundColor(Color.rgb(232,239,248)); layout.addView(this) }
        button("刷新已配对车机") { permissions() }
        button("开始无线连接") { start() }
        button("停止连接") { startService(Intent(this,CarPlayTransmitterService::class.java).setAction(CarPlayTransmitterService.ACTION_STOP)) }
        val audio = CheckBox(this).apply { text = if (BuildConfig.IS_CARLIFE_COMPONENT) "发送 vivo 宿主提供的媒体音频" else "发送允许捕获的媒体音频"; setTextColor(Color.WHITE); buttonTintList = checkTint(); isChecked = settings.audio; setOnCheckedChangeListener { _, checked -> settings.audio = checked }; layout.addView(this) }
        text(if (BuildConfig.IS_CARLIFE_COMPONENT) "手机可主动发起蓝牙连接。投屏画面由 Jovi InCar 宿主提供，连接与首帧状态在此显示。" else "系统录屏路径支持 Android 10+。授权录屏后，由手机主动连接所选车机。",13f)
        counters = text("尚无发送数据")
        chart = MetricsChart(this).apply { layout.addView(this,LinearLayout.LayoutParams(-1,dp(170))) }
        text("连接阶段",18f); timeline = text("等待开始")
        text("诊断与上传",18f)
        button("无线连接方式") { selectConnectionDirection() }
        button("无线请求方案") { selectBootstrapProfile() }
        button("蓝牙握手方案") { selectBluetoothProfile() }
        val phoneAddress = EditText(this).apply {
            setText(settings.phoneBluetoothAddress.orEmpty()); hint = "手机自身的蓝牙地址"
            setTextColor(Color.WHITE); setHintTextColor(Color.rgb(180,200,216)); setSingleLine()
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            layout.addView(this)
        }
        button("保存手机蓝牙地址") { attempt { settings.phoneBluetoothAddress = phoneAddress.text.toString(); toast("已保存，下次连接生效") } }
        text("优先使用系统提供的真实蓝牙地址。系统未开放读取时，可从手机系统设置查看并填写；留空会清除手动配置。",13f)
        val endpoint = EditText(this).apply { setText(settings.server); setTextColor(Color.WHITE); textSize = 14f; setSingleLine(); layout.addView(this) }
        button("保存后管地址") { attempt { settings.server = endpoint.text.toString(); toast("已保存") } }
        CheckBox(this).apply { text = "连接结束后自动上传脱敏日志"; setTextColor(Color.WHITE); buttonTintList = checkTint(); isChecked = settings.autoUpload; setOnCheckedChangeListener { _,checked -> settings.autoUpload = checked }; layout.addView(this) }
        uploadStatus = text("尚未上传")
        if (BuildConfig.IS_CARLIFE_COMPONENT) button("检查组件与宿主接口") { attempt {
            check(Diagnostics.status.value.stage.step == 0) { "请先停止正在进行的连接" }
            ComponentInterfaceCheck.run(applicationContext)
        } }
        button("上传本次日志") { attempt { DiagnosticUploader.enqueue(applicationContext) } }
        button("重试待上传日志") { DiagnosticUploader.retry(applicationContext) }
        button("取消上传") { DiagnosticUploader.cancel() }
        button("导出本次日志") { attempt { Diagnostics.export(); startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"carplay-diagnostics.zip"),EXPORT) } }
        button("导入车机认证根证书") { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),TRUST) }
        CheckBox(this).apply { text = "使用根证书验证车机"; setTextColor(Color.WHITE); buttonTintList = checkTint(); isChecked = settings.requireRoot; setOnCheckedChangeListener { _,checked -> settings.requireRoot = checked }; layout.addView(this) }
        text("未启用根证书验证时，仅通过所选已配对车机的安全蓝牙连接绑定公钥，仍验证签名；公钥变化会停止连接。",13f)
        button("启用触控回传") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        if (BuildConfig.IS_CARLIFE_COMPONENT) text("组件包：${BuildConfig.APPLICATION_ID}\n宿主入口：com.vivo.car.CAR_SERVICE\n等待 Jovi InCar 启动组件后接收原生画面。",13f)
        text("会话日志",18f); logs = text("暂无日志",12f).apply { setTextIsSelectable(true); typeface = Typeface.MONOSPACE }
        scope.launch { Diagnostics.status.collect { state ->
            status.text = state.stage.title
            detail.text = if (state.id.isBlank()) "选择已配对车机，点击开始无线连接，由手机发起" else state.detail
            progress.progress = state.stage.step
            counters.text = "已发送 ${state.frames} 帧 · ${"%.2f".format(Locale.US,state.bytes / 1048576.0)} MB\n丢帧 ${state.dropped} · 音频包 ${state.audioPackets} · 输入 ${state.inputEvents}"
            chart.samples = state.samples; chart.invalidate()
            timeline.text = Stage.entries.filter { it.step > 0 }.joinToString("\n") { stage ->
                val event = state.events.firstOrNull { it.stage == stage.name }
                val symbol = if (state.stage == stage) "●" else if (event != null) "✓" else "○"
                "$symbol ${stage.title}${event?.let { "  ${it.elapsedMillis / 1000.0}s" }.orEmpty()}"
            }
            uploadStatus.text = state.upload + (state.uploadProgress?.let { " · $it%" } ?: "")
            logs.text = state.events.takeLast(80).joinToString("\n") { "${it.elapsedMillis}ms [${it.level}] ${it.message}" }
        } }
        permissions(); DiagnosticUploader.retry(applicationContext)
    }
    private fun permissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) wanted += listOf(Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_ADVERTISE)
        if (Build.VERSION.SDK_INT >= 33) wanted += listOf(Manifest.permission.NEARBY_WIFI_DEVICES,Manifest.permission.POST_NOTIFICATIONS)
        if (settings.audio) wanted += Manifest.permission.RECORD_AUDIO
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(),PERMISSIONS) else refresh()
    }
    private fun refresh() = attempt {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            toast("请授权蓝牙连接后刷新车机"); return@attempt
        }
        val peers = getSystemService(BluetoothManager::class.java).adapter?.bondedDevices.orEmpty().sortedBy { it.name ?: "" }
        addresses.clear(); addresses += peers.map { it.address }
        devices.adapter = ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,peers.map { it.name ?: "已配对车机" }.ifEmpty { listOf("请先在系统蓝牙设置配对车机") })
        settings.peer?.let { address -> addresses.indexOf(address).takeIf { it >= 0 }?.let(devices::setSelection) }
        if (BuildConfig.IS_CARLIFE_COMPONENT) devices.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { addresses.getOrNull(position)?.let { settings.peer = it } }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }
    private fun start() = attempt {
        check(Diagnostics.status.value.stage.step == 0) { "连接已在进行，请先停止" }
        val id = Diagnostics.begin(); pendingSession = id
        try {
            Diagnostics.event("INFO","手机 ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}，应用 ${BuildConfig.VERSION_NAME}",id)
            check(!settings.requireRoot || settings.hasTrust()) { "请先导入车机认证根证书" }
            if (settings.audio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissions(); return@attempt }
            startingPeer = addresses.getOrNull(devices.selectedItemPosition) ?: error("请先选择已配对车机")
            settings.peer = startingPeer
            if (BuildConfig.IS_CARLIFE_COMPONENT) {
                val request = NativeComponentState.request
                Diagnostics.event("INFO",if (request == null) "蓝牙识别先行启动，原生画面入口等待 vivo 宿主调用" else "原生画面宿主入口已收到，启动无线握手",id)
                startForegroundService(Intent(this,CarPlayTransmitterService::class.java).setAction(CarPlayTransmitterService.ACTION_START)
                    .putExtra("peer",startingPeer).putExtra("sessionId",id).putExtra("nativeHost",request?.toIntent()))
                return@attempt
            }
            startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(),CAPTURE)
        } catch (error: Exception) {
            Diagnostics.stage(id,Stage.FAILED,error.message ?: "启动检查失败")
            if (settings.autoUpload) DiagnosticUploader.enqueue(applicationContext,id)
            throw error
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if (requestCode == PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) refresh()
    }
    @Deprecated("Uses the compatible system consent result contract")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (resultCode != Activity.RESULT_OK || data == null) {
            if (requestCode == CAPTURE) pendingSession?.let { Diagnostics.stage(it,Stage.STOPPED,"用户取消屏幕投射授权"); if (settings.autoUpload) DiagnosticUploader.enqueue(applicationContext,it) }
            return
        }
        attempt {
            when (requestCode) {
                CAPTURE -> startForegroundService(Intent(this,CarPlayTransmitterService::class.java).setAction(CarPlayTransmitterService.ACTION_START)
                    .putExtra("peer",startingPeer).putExtra("capture",data).putExtra("captureResult",resultCode).putExtra("sessionId",pendingSession))
                TRUST -> data.data?.let { uri -> contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= 1_000_000) { "证书文件过大" }; output.write(buffer,0,count) }
                    toast("已导入 ${settings.importTrust(output.toByteArray())} 个根证书")
                } }
                EXPORT -> data.data?.let { uri -> contentResolver.openOutputStream(uri)?.use { output -> Diagnostics.export().inputStream().use { it.copyTo(output) } }; toast("日志已导出") }
            }
        }
    }
    private fun attempt(action: () -> Unit) { try { action() } catch (error: Exception) { toast(error.message ?: "操作失败") } }
    private fun selectConnectionDirection() {
        val directions = listOf(RfcommConnectionDirection.PHONE_INITIATED,RfcommConnectionDirection.VEHICLE_INITIATED)
        AlertDialog.Builder(this).setTitle("无线连接方式 · 下次连接生效")
            .setSingleChoiceItems(arrayOf("手机主动连接（推荐）","等待车机发起连接（对照测试）"),
                directions.indexOf(settings.connectionDirection)) { dialog,index ->
                settings.connectionDirection = directions[index]; dialog.dismiss(); toast("已保存；停止后重新连接生效")
            }.setNegativeButton("取消",null).show()
    }
    private fun selectBootstrapProfile() {
        val profiles = listOf(PhoneBootstrapProfile.MODERN_WIRELESS,PhoneBootstrapProfile.VIVO_WIRELESS_READY,PhoneBootstrapProfile.VIVO_WIRELESS_MINIMAL,
            PhoneBootstrapProfile.VIVO_FLAT_WIFI_REQUEST)
        AlertDialog.Builder(this).setTitle("无线请求方案 · 下次连接生效")
            .setSingleChoiceItems(arrayOf("完整无线声明（推荐）","0.12 参考声明（对照测试）","最小无线声明（对照测试）","0.14 根参数方案（对照测试）"),
                profiles.indexOf(settings.bootstrapProfile)) { dialog,index ->
                settings.bootstrapProfile = profiles[index]; dialog.dismiss(); toast("已保存；停止后重新连接生效")
            }.setNegativeButton("取消",null).show()
    }
    private fun selectBluetoothProfile() {
        val profiles = listOf(PhoneBluetoothProfile.VIVO_ACCESSORY_FIRST, PhoneBluetoothProfile.ACCESSORY_FIRST,
            PhoneBluetoothProfile.CAPTURE_IMMEDIATE, PhoneBluetoothProfile.LEGACY_CONTROLLER)
        val labels = arrayOf("vivo v11：车机发起同步", "小米 v11：车机发起同步", "参考方案：手机主动同步", "原有控制器方案")
        AlertDialog.Builder(this).setTitle("蓝牙握手方案 · 下次连接生效")
            .setSingleChoiceItems(labels, profiles.indexOf(settings.bluetoothProfile)) { dialog, index ->
                settings.bluetoothProfile = profiles[index]
                dialog.dismiss()
                toast("已保存；停止后重新连接生效")
            }.setNegativeButton("取消", null).show()
    }
    private fun toast(text: String) { Toast.makeText(this,text,Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun checkTint() = android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(accent,Color.rgb(180,200,216)))
    private fun acceptComponentIntent(intent: Intent?) {
        if (BuildConfig.IS_CARLIFE_COMPONENT && intent?.hasExtra("usb_type") == true) {
            try { NativeComponentState.accept(this,intent) }
            catch (error: Exception) { Diagnostics.event("ERROR","组件入口参数无效：${error.message}") }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); acceptComponentIntent(intent) }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.post { window.decorView.requestLayout(); window.decorView.invalidate() }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private const val PERMISSIONS = 1; private const val CAPTURE = 2; private const val TRUST = 3; private const val EXPORT = 4 }
}

class MetricsChart(context: android.content.Context) : View(context) {
    var samples: List<MetricSample> = emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = Color.rgb(24,39,58); canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),18f,18f,paint)
        paint.textSize = 13 * resources.displayMetrics.scaledDensity; paint.color = Color.rgb(180,200,216)
        val latest = samples.lastOrNull()
        canvas.drawText(latest?.let { "%.1f fps    %.0f kbps".format(Locale.US,it.fps,it.kbps) } ?: "实际发送帧率 · 等待数据",18f,35f,paint)
        if (samples.size < 2) return
        val maximum = samples.maxOf { it.fps }.coerceAtLeast(1.0)
        val path = Path()
        samples.forEachIndexed { index,sample ->
            val x = 18f + index * (width - 36f) / (samples.size - 1)
            val y = height - 18f - (sample.fps / maximum * (height - 75)).toFloat()
            if (index == 0) path.moveTo(x,y) else path.lineTo(x,y)
        }
        paint.color = Color.rgb(41,212,181); paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; canvas.drawPath(path,paint); paint.style = Paint.Style.FILL
    }
}
