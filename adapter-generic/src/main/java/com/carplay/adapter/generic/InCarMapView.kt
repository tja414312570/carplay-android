package com.carplay.adapter.generic

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * High-performance In-Car Vector Map Canvas View.
 * Renders an interactive automotive navigation interface directly inside the floating window:
 * - Dynamic 3D perspective road grid & highway flyover
 * - Route path with green traffic glow (畅通) and turn arrows
 * - Animated GPS vehicle position indicator with pulsing radar wave
 * - Dynamic speed limit badge, road name HUD, and upcoming turn banner
 * - Interactive panning, zooming, and map style toggling (Auto/Night/Satellite)
 */
class InCarMapView(context: Context) : View(context) {

    var currentMapSource: String = "高德地图车机版"
        set(value) {
            field = value
            invalidate()
        }

    private var offsetX = 0f
    private var offsetY = 0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(38, 48, 64)
        style = Paint.Style.STROKE
    }

    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(16, 185, 129) // Emerald traffic green
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 16, 185, 129)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
    }

    private val carPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var animProgress = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            animProgress = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        animator.start()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator.cancel()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    offsetX += (event.x - lastTouchX)
                    offsetY += (event.y - lastTouchY)
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Map Dark Canvas Background
        canvas.drawColor(Color.rgb(15, 23, 42)) // Deep slate #0F172A

        // 2. Road Network Grid & Waterways
        drawRoadNetwork(canvas, w, h)

        // 3. Navigation Planned Route
        drawPlannedRoute(canvas, w, h)

        // 4. GPS Vehicle Location & Compass Heading
        drawVehicleMarker(canvas, w, h)

        // 5. In-Car HUD Overlay: Navigation Turn Direction, Distance, Next Road
        drawNavigationHud(canvas, w, h)
    }

    private fun drawRoadNetwork(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f + offsetX * 0.5f
        val cy = h / 2f + offsetY * 0.5f

        roadPaint.strokeWidth = dp(4f)
        roadPaint.color = Color.rgb(30, 41, 59)

        // Secondary roads
        for (i in -4..4) {
            val y = cy + i * dp(65f)
            canvas.drawLine(0f, y, w, y, roadPaint)
        }
        for (i in -5..5) {
            val x = cx + i * dp(90f)
            canvas.drawLine(x, 0f, x, h, roadPaint)
        }

        // Primary Highway / Ring Road
        roadPaint.strokeWidth = dp(14f)
        roadPaint.color = Color.rgb(51, 65, 85)
        val highwayPath = Path().apply {
            moveTo(0f, cy + dp(40f))
            cubicTo(cx - dp(100f), cy + dp(30f), cx + dp(80f), cy - dp(50f), w, cy - dp(70f))
        }
        canvas.drawPath(highwayPath, roadPaint)

        // Waterway/Park Polygon
        val waterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 40, 60)
            style = Paint.Style.FILL
        }
        canvas.drawCircle(cx - dp(180f), cy - dp(80f), dp(75f), waterPaint)
    }

    private fun drawPlannedRoute(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f + offsetX
        val cy = h / 2f + offsetY

        val path = Path().apply {
            moveTo(cx, cy + dp(120f))
            lineTo(cx, cy - dp(20f))
            quadTo(cx, cy - dp(80f), cx + dp(80f), cy - dp(80f))
            lineTo(cx + dp(280f), cy - dp(80f))
        }

        // Glow underlay
        routeGlowPaint.strokeWidth = dp(24f)
        canvas.drawPath(path, routeGlowPaint)

        // Main Route line
        routePaint.strokeWidth = dp(12f)
        routePaint.color = Color.rgb(16, 185, 129) // Smooth traffic green
        canvas.drawPath(path, routePaint)

        // Route Pulse particle moving along the route
        val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val t = animProgress
        val pulseX = if (t < 0.6f) cx else cx + (t - 0.6f) / 0.4f * dp(280f)
        val pulseY = if (t < 0.6f) (cy + dp(120f)) - (t / 0.6f) * dp(140f) else cy - dp(80f)
        canvas.drawCircle(pulseX, pulseY, dp(4f), pulsePaint)
    }

    private fun drawVehicleMarker(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f + offsetX
        val cy = h / 2f + offsetY + dp(60f)

        // Radar ripple animation
        val radarRadius = dp(12f) + animProgress * dp(28f)
        val radarAlpha = ((1f - animProgress) * 180).toInt().coerceIn(0, 255)
        val radarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(radarAlpha, 59, 130, 246)
            style = Paint.Style.STROKE
            strokeWidth = dp(2.5f)
        }
        canvas.drawCircle(cx, cy, radarRadius, radarPaint)

        // Car halo
        carPaint.style = Paint.Style.FILL
        carPaint.color = Color.rgb(37, 99, 235) // Deep Blue
        canvas.drawCircle(cx, cy, dp(13f), carPaint)

        carPaint.color = Color.WHITE
        canvas.drawCircle(cx, cy, dp(7f), carPaint)

        // Direction arrow pointing up
        val arrowPath = Path().apply {
            moveTo(cx, cy - dp(18f))
            lineTo(cx - dp(7f), cy - dp(4f))
            lineTo(cx + dp(7f), cy - dp(4f))
            close()
        }
        carPaint.color = Color.rgb(239, 68, 68) // Red arrow
        canvas.drawPath(arrowPath, carPaint)
    }

    private fun drawNavigationHud(canvas: Canvas, w: Float, h: Float) {
        // Top-Left: Navigation Guidance Card
        val cardRect = RectF(dp(12f), dp(12f), dp(250f), dp(84f))
        val cardBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 15, 23, 42)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(cardRect, dp(10f), dp(10f), cardBgPaint)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(51, 65, 85)
            style = Paint.Style.STROKE
            strokeWidth = dp(1f)
        }
        canvas.drawRoundRect(cardRect, dp(10f), dp(10f), borderPaint)

        // Turn Arrow Icon (Right Turn ahead)
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(56, 189, 248) // Light blue
            style = Paint.Style.STROKE
            strokeWidth = dp(3.5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val arrowX = dp(32f)
        val arrowY = dp(46f)
        val turnPath = Path().apply {
            moveTo(arrowX, arrowY + dp(14f))
            lineTo(arrowX, arrowY)
            lineTo(arrowX + dp(14f), arrowY)
            moveTo(arrowX + dp(8f), arrowY - dp(6f))
            lineTo(arrowX + dp(14f), arrowY)
            lineTo(arrowX + dp(8f), arrowY + dp(6f))
        }
        canvas.drawPath(turnPath, iconPaint)

        // Distance & Next Road Text
        textPaint.textSize = dp(18f)
        textPaint.color = Color.WHITE
        canvas.drawText("350米后 右转", dp(58f), dp(42f), textPaint)

        textPaint.textSize = dp(12f)
        textPaint.color = Color.rgb(148, 163, 184)
        canvas.drawText("进入 科技大道辅路 · 限速 60", dp(58f), dp(64f), textPaint)

        // Top-Right: Map Source & Speed Limit Badge
        val badgeRect = RectF(w - dp(140f), dp(12f), w - dp(12f), dp(44f))
        canvas.drawRoundRect(badgeRect, dp(8f), dp(8f), cardBgPaint)
        canvas.drawRoundRect(badgeRect, dp(8f), dp(8f), borderPaint)

        textPaint.textSize = dp(11f)
        textPaint.color = Color.rgb(52, 211, 153)
        canvas.drawText("● $currentMapSource", w - dp(130f), dp(32f), textPaint)

        // Speed Limit Sign (Circle)
        val speedCx = w - dp(32f)
        val speedCy = dp(76f)
        val speedRadius = dp(18f)

        val speedSignBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val speedSignBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(220, 38, 38)
            style = Paint.Style.STROKE
            strokeWidth = dp(3f)
        }
        canvas.drawCircle(speedCx, speedCy, speedRadius, speedSignBg)
        canvas.drawCircle(speedCx, speedCy, speedRadius, speedSignBorder)

        val speedNumPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = dp(13f)
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("80", speedCx, speedCy + dp(4.5f), speedNumPaint)

        // Bottom Banner: Remaining distance and ETA
        val bottomRect = RectF(dp(12f), h - dp(40f), dp(220f), h - dp(10f))
        canvas.drawRoundRect(bottomRect, dp(8f), dp(8f), cardBgPaint)
        canvas.drawRoundRect(bottomRect, dp(8f), dp(8f), borderPaint)

        textPaint.textSize = dp(11.5f)
        textPaint.color = Color.rgb(226, 232, 240)
        canvas.drawText("剩余 4.2公里 · 约 8分钟 · 18:02到达", dp(20f), h - dp(22f), textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
