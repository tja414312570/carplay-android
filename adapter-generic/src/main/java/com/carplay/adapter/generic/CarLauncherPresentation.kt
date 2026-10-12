package com.carplay.adapter.generic

import android.app.Presentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*

/**
 * Dedicated In-Car Dashboard Presentation rendered onto the VirtualDisplay.
 * Streams CarLauncherDashboardView to the car screen while keeping the phone display independent.
 */
class CarLauncherPresentation(
    private val hostContext: Context,
    display: Display
) : Presentation(hostContext, display) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downTime = 0L
    private lateinit var dashboardView: CarLauncherDashboardView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.let { win ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Settings.canDrawOverlays(hostContext)) {
                win.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            }
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            win.setBackgroundDrawable(null)
        }

        dashboardView = CarLauncherDashboardView(context)
        setContentView(dashboardView)
    }

    /** Dispatches CarPlay touch event directly to the Presentation's decor view */
    fun dispatchTouch(action: Int, x: Float, y: Float, pointerId: Int) {
        val decor = window?.decorView ?: return
        mainHandler.post {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val properties = arrayOf(MotionEvent.PointerProperties().apply {
                id = pointerId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            })
            val coords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            })
            val event = MotionEvent.obtain(
                downTime, now, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0
            )
            try {
                decor.dispatchTouchEvent(event)
            } catch (_: Exception) {
            } finally {
                event.recycle()
            }
        }
    }
}
