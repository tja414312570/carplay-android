package com.carplay.app

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.*
import android.widget.*
import com.carplay.adapter.generic.CarLauncherDashboardView
import kotlin.math.min

/** Owns the window; renders the In-Car Launcher directly and supports native TextureView if available. */
internal class ComponentPreviewWindow(
    context: Context,
    private val surfaceReady: (Surface, SurfaceTexture) -> Unit,
    private val frame: (Long) -> Unit,
    private val surfaceLost: () -> Unit,
    private val stop: () -> Unit,
) {
    private val windowContext = if (Build.VERSION.SDK_INT >= 30) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(checkNotNull(display)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else context
    private val manager = windowContext.getSystemService(WindowManager::class.java)
    private val metrics get() = windowContext.resources.displayMetrics
    private fun dp(value: Int) = (value * metrics.density).toInt()
    private var shown = false
    private var expanded = false
    private val root = LinearLayout(windowContext).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.rgb(13, 24, 41))
        setPadding(dp(4), dp(4), dp(4), dp(4))
    }
    private val description = TextView(windowContext).apply {
        setTextColor(Color.WHITE); textSize = 12f
        setPadding(dp(8), dp(4), dp(8), dp(4))
        text = "独立车载桌面渲染中 · 支持触控切歌/导航"
    }
    private val dashboard = CarLauncherDashboardView(windowContext)
    private val texture = TextureView(windowContext).apply {
        isOpaque = true
        contentDescription = "Jovi CarLife 组件原生画面预览"
        visibility = View.GONE
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                value.setDefaultBufferSize(WIDTH, HEIGHT)
                surfaceReady(Surface(value), value)
            }
            override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                if (visibility != View.VISIBLE) {
                    visibility = View.VISIBLE
                    dashboard.visibility = View.GONE
                }
                frame(value.timestamp)
            }
            override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                surfaceLost()
                return false
            }
        }
    }
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(8); y = dp(56); title = "车载桌面悬浮窗" }

    init {
        val header = LinearLayout(windowContext)
        val title = TextView(windowContext).apply {
            text = "车载桌面 · 拖动"; textSize = 13f; setTextColor(Color.rgb(41, 212, 181))
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), 0, 0, 0)
        }
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0
        title.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (initialX + event.rawX - downX).toInt().coerceIn(0, (metrics.widthPixels - root.width).coerceAtLeast(0))
                    params.y = (initialY + event.rawY - downY).toInt().coerceIn(0, (metrics.heightPixels - root.height).coerceAtLeast(0))
                    if (shown) manager.updateViewLayout(root, params)
                }
                MotionEvent.ACTION_UP -> view.performClick()
            }
            true
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(Button(windowContext).apply {
            text = "放大"; textSize = 12f; isAllCaps = false
            setOnClickListener { expanded = !expanded; text = if (expanded) "缩小" else "放大"; resize() }
        }, LinearLayout.LayoutParams(dp(64), dp(44)))
        header.addView(Button(windowContext).apply {
            text = "关闭"; textSize = 12f; isAllCaps = false; setOnClickListener { stop() }
        }, LinearLayout.LayoutParams(dp(64), dp(44)))
        root.addView(header)
        root.addView(dashboard)
        root.addView(texture)
        root.addView(description, LinearLayout.LayoutParams(-1, dp(48)))
        resize()
    }

    fun resize() {
        val availableWidth = (metrics.widthPixels - dp(16)).coerceAtLeast(1)
        val byHeight = ((metrics.heightPixels - dp(140)).coerceAtLeast(1) * WIDTH / HEIGHT)
        val contentWidth = min((availableWidth * if (expanded) 0.98 else 0.88).toInt(), byHeight).coerceAtLeast(1)
        val viewHeight = contentWidth * HEIGHT / WIDTH
        dashboard.layoutParams = LinearLayout.LayoutParams(contentWidth, viewHeight)
        texture.layoutParams = LinearLayout.LayoutParams(contentWidth, viewHeight)
        params.width = contentWidth + dp(8)
        params.x = params.x.coerceIn(0, (metrics.widthPixels - params.width).coerceAtLeast(0))
        val windowHeight = viewHeight + dp(104)
        params.y = params.y.coerceIn(0, (metrics.heightPixels - windowHeight).coerceAtLeast(0))
        if (shown) manager.updateViewLayout(root, params)
    }
    fun show() { manager.addView(root, params); shown = true }
    fun status(message: String) { description.text = message }
    fun dismiss() {
        if (!shown) return
        shown = false
        manager.removeViewImmediate(root)
    }
    companion object {
        const val WIDTH = 1280
        const val HEIGHT = 720
    }
}
