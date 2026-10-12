package com.carplay.adapter.generic

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reusable In-Car Dashboard View component.
 * Can be displayed directly in an Android Floating Overlay Window (WindowManager),
 * or inside a Presentation on a secondary VirtualDisplay.
 */
class CarLauncherDashboardView(context: Context) : FrameLayout(context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeText: TextView? = null
    private var currentTab = 0
    private var isPlaying = false

    private lateinit var contentContainer: FrameLayout
    private lateinit var homeLayout: LinearLayout
    private lateinit var mapLayout: LinearLayout
    private lateinit var musicLayout: LinearLayout
    private lateinit var appsLayout: LinearLayout

    private val timeTicker = object : Runnable {
        override fun run() {
            timeText?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            mainHandler.postDelayed(this, 10000)
        }
    }

    init {
        setBackgroundColor(Color.rgb(13, 17, 23)) // #0D1117 deep dark slate
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        // 1. Left Dock Bar
        val dockBar = createDockBar()
        root.addView(dockBar)

        // 2. Content Container
        contentContainer = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            }
        }

        homeLayout = createHomeDashboard()
        mapLayout = createMapDashboard()
        musicLayout = createMusicDashboard()
        appsLayout = createAppsDashboard()

        contentContainer.addView(homeLayout)
        contentContainer.addView(mapLayout)
        contentContainer.addView(musicLayout)
        contentContainer.addView(appsLayout)

        switchTab(0)
        root.addView(contentContainer)
        addView(root)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        mainHandler.post(timeTicker)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mainHandler.removeCallbacks(timeTicker)
    }

    private fun createDockBar(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(72), LayoutParams.MATCH_PARENT)
            background = GradientDrawable().apply {
                setColor(Color.rgb(22, 27, 34)) // #161B22
            }
            setPadding(0, dp(10), 0, dp(10))

            // Clock
            timeText = TextView(context).apply {
                textSize = 14f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            }
            addView(timeText)

            // CarPlay Badge
            val badge = TextView(context).apply {
                textSize = 8.5f
                setTextColor(Color.rgb(52, 199, 89)) // Apple Green
                typeface = Typeface.DEFAULT_BOLD
                text = "CarPlay"
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(1), dp(4), dp(1))
                background = GradientDrawable().apply {
                    setColor(Color.rgb(30, 58, 47))
                    cornerRadius = dp(4).toFloat()
                }
            }
            addView(badge, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, dp(2), 0, dp(10))
            })

            // Navigation Tab Buttons
            val tabButtons = listOf(
                "🏠\n主页" to 0,
                "🧭\n导航" to 1,
                "🎵\n音乐" to 2,
                "📱\n应用" to 3
            )

            for ((label, index) in tabButtons) {
                val btn = TextView(context).apply {
                    text = label
                    textSize = 12f
                    setTextColor(Color.rgb(180, 195, 210))
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    background = GradientDrawable().apply {
                        setColor(if (currentTab == index) Color.rgb(44, 54, 71) else Color.TRANSPARENT)
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { switchTab(index) }
                }
                val params = LinearLayout.LayoutParams(dp(58), dp(44)).apply {
                    setMargins(0, dp(3), 0, dp(3))
                }
                addView(btn, params)
            }

            // Bottom space
            val spacer = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
            }
            addView(spacer)

            // Status bottom indicator
            val status = TextView(context).apply {
                text = "● 车载就绪"
                textSize = 9.5f
                setTextColor(Color.rgb(100, 116, 139))
                gravity = Gravity.CENTER
            }
            addView(status)
        }
    }

    private fun switchTab(tab: Int) {
        currentTab = tab
        homeLayout.visibility = if (tab == 0) View.VISIBLE else View.GONE
        mapLayout.visibility = if (tab == 1) View.VISIBLE else View.GONE
        musicLayout.visibility = if (tab == 2) View.VISIBLE else View.GONE
        appsLayout.visibility = if (tab == 3) View.VISIBLE else View.GONE
    }

    private fun createHomeDashboard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

            // Left: Large Navigation Card
            val navCard = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground()
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.25f).apply {
                    setMargins(0, 0, dp(6), 0)
                }

                val title = TextView(context).apply {
                    text = "🧭 路线巡航"
                    textSize = 13f
                    setTextColor(Color.rgb(148, 163, 184))
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(title)

                val guidance = TextView(context).apply {
                    text = "沿当前路线巡航中"
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(2))
                }
                addView(guidance)

                val road = TextView(context).apply {
                    text = "道路畅通 · 限速 80 km/h"
                    textSize = 12f
                    setTextColor(Color.rgb(203, 213, 225))
                }
                addView(road)

                val spacer = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(1, 0, 1f)
                }
                addView(spacer)

                val launchBtn = Button(context).apply {
                    text = "🧭 查看完整车载地图"
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(37, 99, 235)) // Blue
                        cornerRadius = dp(8).toFloat()
                    }
                    setOnClickListener { switchTab(1) }
                }
                addView(launchBtn, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(38)))
            }
            addView(navCard)

            // Right Column: Media Card + Status Card
            val rightCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1.0f).apply {
                    setMargins(dp(2), 0, 0, 0)
                }

                // Media Card (Top)
                val mediaCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBackground()
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1.15f).apply {
                        setMargins(0, 0, 0, dp(4))
                    }

                    val mediaTitle = TextView(context).apply {
                        text = "🎵 车载音乐播放"
                        textSize = 12f
                        setTextColor(Color.rgb(148, 163, 184))
                        typeface = Typeface.DEFAULT_BOLD
                    }
                    addView(mediaTitle)

                    val trackName = TextView(context).apply {
                        text = "车载媒体控制器"
                        textSize = 14f
                        setTextColor(Color.WHITE)
                        typeface = Typeface.DEFAULT_BOLD
                        setPadding(0, dp(2), 0, dp(1))
                    }
                    addView(trackName)

                    val artist = TextView(context).apply {
                        text = "支持后台音乐播放与触控控制"
                        textSize = 11f
                        setTextColor(Color.rgb(148, 163, 184))
                    }
                    addView(artist)

                    val controls = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(0, dp(6), 0, 0)

                        val prevBtn = createControlButton("⏮ 上一首") { sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                        val playBtn = createControlButton("▶ 播放/暂停") {
                            isPlaying = !isPlaying
                            sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                        }
                        val nextBtn = createControlButton("⏭ 下一首") { sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT) }

                        addView(prevBtn, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(0, 0, dp(3), 0) })
                        addView(playBtn, LinearLayout.LayoutParams(0, dp(32), 1.2f).apply { setMargins(dp(3), 0, dp(3), 0) })
                        addView(nextBtn, LinearLayout.LayoutParams(0, dp(32), 1f).apply { setMargins(dp(3), 0, 0, 0) })
                    }
                    addView(controls, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                }
                addView(mediaCard)

                // Quick Tools Card (Bottom)
                val toolsCard = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBackground()
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 0.85f).apply {
                        setMargins(0, dp(4), 0, 0)
                    }

                    val info = TextView(context).apply {
                        text = "🌤 24°C 晴 · 车联状态良好"
                        textSize = 11f
                        setTextColor(Color.rgb(148, 163, 184))
                    }
                    addView(info)

                    val toolsRow = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(4), 0, 0)

                        addView(createShortcutBtn("📞 电话") { openDialer() }, LinearLayout.LayoutParams(0, dp(28), 1f))
                        addView(createShortcutBtn("📻 电台") { switchTab(2) }, LinearLayout.LayoutParams(0, dp(28), 1f))
                        addView(createShortcutBtn("📱 应用") { switchTab(3) }, LinearLayout.LayoutParams(0, dp(28), 1f))
                    }
                    addView(toolsRow)
                }
                addView(toolsCard)
            }
            addView(rightCol)
        }
    }

    private lateinit var inCarMapView: InCarMapView

    private fun createMapDashboard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

            // Top Header: Switch Map Sources (高德车机版 / 百度地图 / 腾讯地图)
            val header = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(Color.rgb(22, 27, 34))

                val title = TextView(context).apply {
                    text = "🧭 车载实时地图导航"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(title, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

                val amapBtn = Button(context).apply {
                    text = "高德地图"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(2, 132, 199)); cornerRadius = dp(6).toFloat() }
                    setOnClickListener {
                        inCarMapView.currentMapSource = "高德地图车机版"
                        Toast.makeText(context, "已切换为高德车载导航渲染", Toast.LENGTH_SHORT).show()
                    }
                }
                val baiduBtn = Button(context).apply {
                    text = "百度地图"
                    textSize = 11f
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(225, 29, 72)); cornerRadius = dp(6).toFloat() }
                    setOnClickListener {
                        inCarMapView.currentMapSource = "百度地图车机版"
                        Toast.makeText(context, "已切换为百度车载导航渲染", Toast.LENGTH_SHORT).show()
                    }
                }
                addView(amapBtn, LinearLayout.LayoutParams(dp(76), dp(32)).apply { setMargins(0, 0, dp(6), 0) })
                addView(baiduBtn, LinearLayout.LayoutParams(dp(76), dp(32)))
            }
            addView(header)

            // Map Body: Full Vector Map View
            inCarMapView = InCarMapView(context).apply {
                layoutParams = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            }
            addView(inCarMapView)
        }
    }

    private fun createMusicDashboard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

            val title = TextView(context).apply {
                text = "🎵 音乐媒体控制中心"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(title)

            val desc = TextView(context).apply {
                text = "控制手机后台音频播放或启动主流音乐软件："
                textSize = 12f
                setTextColor(Color.rgb(148, 163, 184))
                setPadding(0, dp(6), 0, dp(12))
            }
            addView(desc)

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL

                val qqMusic = Button(context).apply {
                    text = "QQ音乐"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(16, 185, 129)); cornerRadius = dp(8).toFloat() }
                    setOnClickListener { launchPackage("com.tencent.qqmusic") }
                }
                val netease = Button(context).apply {
                    text = "网易云"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(220, 38, 38)); cornerRadius = dp(8).toFloat() }
                    setOnClickListener { launchPackage("com.netease.cloudmusic") }
                }
                val qishui = Button(context).apply {
                    text = "汽水音乐"
                    setTextColor(Color.WHITE)
                    background = GradientDrawable().apply { setColor(Color.rgb(234, 88, 12)); cornerRadius = dp(8).toFloat() }
                    setOnClickListener { launchPackage("com.luna.music") }
                }

                addView(qqMusic, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(0, 0, dp(4), 0) })
                addView(netease, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                addView(qishui, LinearLayout.LayoutParams(0, dp(42), 1f).apply { setMargins(dp(4), 0, 0, 0) })
            }
            addView(row)
        }
    }

    private fun createAppsDashboard(): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground()
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

            val title = TextView(context).apply {
                text = "📱 车载常用应用"
                textSize = 16f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
            }
            addView(title)

            val row1 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, dp(6))

                addView(createAppIcon("高德地图") { launchNavigation() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(4), 0) })
                addView(createAppIcon("QQ音乐") { launchPackage("com.tencent.qqmusic") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                addView(createAppIcon("拨号通话") { openDialer() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, 0, 0) })
            }
            addView(row1)

            val row2 = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)

                addView(createAppIcon("网易云") { launchPackage("com.netease.cloudmusic") }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(0, 0, dp(4), 0) })
                addView(createAppIcon("返回主页") { switchTab(0) }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                addView(createAppIcon("系统设置") { launchSettings() }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { setMargins(dp(4), 0, 0, 0) })
            }
            addView(row2)
        }
    }

    private fun createAppIcon(title: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = title
            textSize = 12f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(8).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createControlButton(title: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = title
            textSize = 10f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun createShortcutBtn(title: String, onClick: () -> Unit): Button {
        return Button(context).apply {
            text = title
            textSize = 10.5f
            setTextColor(Color.rgb(203, 213, 225))
            background = GradientDrawable().apply {
                setColor(Color.rgb(30, 41, 59))
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun cardBackground(): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.rgb(22, 27, 34)) // #161B22
            cornerRadius = dp(10).toFloat()
            setStroke(1, Color.rgb(48, 54, 61))
        }
    }

    private fun sendMediaKey(keyCode: Int) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
        val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
        audioManager?.dispatchMediaKeyEvent(down)
        audioManager?.dispatchMediaKeyEvent(up)
    }

    private fun launchNavigation() {
        launchPackage("com.autonavi.amapauto", "com.autonavi.minimap", "com.baidu.BaiduMap")
    }

    private fun openDialer() {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun launchSettings() {
        try {
            val intent = Intent(Settings.ACTION_SETTINGS).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    private fun launchPackage(vararg packages: String) {
        val pm = context.packageManager
        for (pkg in packages) {
            try {
                val intent = pm.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) {}
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
