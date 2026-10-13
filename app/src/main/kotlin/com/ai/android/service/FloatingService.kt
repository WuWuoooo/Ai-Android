package com.ai.android.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity
import com.ai.android.MainApp
import com.ai.android.model.AgentState
import com.ai.android.plugin.FloatingStyle
import com.ai.android.plugin.WidgetSlot
import kotlin.math.abs

/**
 * 悬浮窗前台服务。
 *
 * ⭐ 主题感知：style() 优先插件 THEME，否则按 settings.themeStyle() 走内置三套
 *   （minimal / native / brutal）。
 */
class FloatingService : Service() {

    private var wm: WindowManager? = null
    private var rootView: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var downRawX = 0f
    @Volatile private var downRawY = 0f
    @Volatile private var startLpX = 0
    @Volatile private var startLpY = 0
    @Volatile private var dragMoved = false
    private val onDrag = View.OnTouchListener { _, ev -> handleDrag(ev) }

    private var lastState: AgentState = AgentState()
    private var lastTokenText: String = ""
    var expanded = false
        private set

    private lateinit var ballRow: LinearLayout
    private lateinit var defaultBall: TextView
    private val pluginBallViews = mutableListOf<View>()

    private lateinit var panelView: LinearLayout
    private lateinit var progressText: TextView
    private lateinit var toolText: TextView
    private lateinit var timeText: TextView
    private lateinit var realTimeScroll: ScrollView
    private lateinit var realTimeText: TextView
    private lateinit var mirrorView: ImageView
    private lateinit var mirrorCaption: TextView
    private lateinit var tokenText: TextView
    private lateinit var pauseBtn: TextView
    private lateinit var closeBtn: TextView

    private lateinit var replyInput: EditText
    private lateinit var replySendBtn: TextView

    private val pluginWidgetViews = mutableListOf<View>()
    private val pluginSlots = mutableListOf<WidgetSlot>()

    private val jsWidgetViews = mutableListOf<View>()

    @Volatile private var reasoningBuffer = ""
    @Volatile private var contentBuffer = ""
    private val MAX_RT = 3_000

    @Volatile private var overlayReady = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        isRunning = true
        startForegroundSafe()
        addOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) addOverlay()
        render(lastState)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlaySync()
        super.onDestroy()
    }

    fun close() {
        removeOverlaySync()
        stopSelf()
    }

    private fun removeOverlaySync() {
        isRunning = false
        if (instance === this) instance = null
        runCatching {
            pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
            pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
            if (rootView != null) {
                runCatching { wm?.removeView(rootView!!) }
            }
        }
        pluginWidgetViews.clear()
        pluginBallViews.clear()
        pluginSlots.clear()
        rootView = null
        overlayReady = false
    }

    private fun startForegroundSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "Agent 运行状态", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AI Android")
                .setContentText("Agent 正在后台工作")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /**
     * 构造悬浮球背景。
     */
    private fun ballDrawable(s: FloatingStyle): android.graphics.drawable.Drawable {
        if (s.ballIcon.isNotBlank()) {
            val host = com.ai.android.MainApp.instance.pluginRegistry
            val pluginId = host.activeThemePluginId()
                ?: host.allWidgets().firstOrNull()?.manifest?.id
            if (pluginId != null) {
                val iconFile = java.io.File(
                    com.ai.android.plugin.PluginManager.pluginsDir(this),
                    "$pluginId/${s.ballIcon}",
                )
                val bmp = runCatching {
                    android.graphics.BitmapFactory.decodeFile(iconFile.takeIf { it.exists() }?.absolutePath)
                }.getOrNull()
                if (bmp != null) {
                    return android.graphics.drawable.BitmapDrawable(resources, bmp).apply {
                        setBounds(0, 0, dp(s.ballSize), dp(s.ballSize))
                    }
                }
            }
        }
        val gd = android.graphics.drawable.GradientDrawable()
        gd.setColor(s.ballBg)
        gd.cornerRadius = when (s.ballShape) {
            "capsule" -> dp(s.ballSize).toFloat() / 2f
            "rounded" -> (dp(s.ballSize).toFloat() * 0.28f)
            "square" -> 0f
            else -> dp(s.ballSize).toFloat() / 2f
        }
        // ⭐ brutal 风格给球加黑边
        if (s.panelStrokeWidth > 0 && s.ballShape == "rounded") {
            gd.setStroke(dp(s.panelStrokeWidth), s.panelStrokeColor)
        }
        return gd
    }

    /**
     * ⭐ 主题感知的悬浮窗样式：
     *   1. 插件 THEME 有非默认样式 → 用插件
     *   2. 否则按 settings.themeStyle() 走内置三套（minimal/native/brutal）
     */
    private fun style(): FloatingStyle {
        val plugin = runCatching { MainApp.instance.pluginRegistry.activeFloatingStyle }.getOrNull()
        if (plugin != null && plugin != FloatingStyle.DEFAULT) return plugin
        val preset = runCatching { MainApp.instance.settings.themeStyle() }.getOrDefault("minimal")
        return when (preset) {
            "native" -> FloatingStyle.NATIVE
            "brutal" -> FloatingStyle.BRUTAL
            else -> FloatingStyle.MINIMAL
        }
    }

    private fun addOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        if (rootView != null) {
            runCatching {
                pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
                pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
                rootView?.let { wm?.removeView(it) }
            }
            pluginWidgetViews.clear(); pluginBallViews.clear()
            overlayReady = false
        }

        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = windowManager

        val s = style()
        val ballSize = s.ballSize.coerceIn(24, 96)

        defaultBall = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(s.ballText)
            text = s.ballLabel
            background = ballDrawable(s)
            layoutParams = LinearLayout.LayoutParams(dp(ballSize), dp(ballSize))
            isClickable = true
        }

        ballRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(defaultBall, LinearLayout.LayoutParams(dp(ballSize), dp(ballSize)))
        }
        renderPluginBall()
        renderPluginWidgets()

        // ---- 面板 ----
        panelView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable()
            bg.cornerRadius = dp(s.cornerRadius).toFloat()
            bg.setColor(s.panelBg)
            // ⭐ 描边：brutal 时 2dp 黑边
            if (s.panelStrokeWidth > 0) {
                bg.setStroke(dp(s.panelStrokeWidth), s.panelStrokeColor)
            }
            background = bg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val panelTitleText = TextView(this).apply {
            textSize = 14f; setTextColor(s.panelText); text = s.panelTitle
            typeface = Typeface.DEFAULT_BOLD
        }
        progressText = TextView(this).apply {
            textSize = 13f; setTextColor(s.panelText); text = "就绪"
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val collapseBtn = TextView(this).apply {
            textSize = 15f
            setTextColor(s.panelActionText)
            text = "—"; isClickable = true
            setOnClickListener { toggleExpand(false) }
        }
        titleBar.addView(panelTitleText)
        titleBar.addView(progressText)
        titleBar.addView(collapseBtn)

        realTimeText = TextView(this).apply {
            textSize = 11f
            setTextColor(s.realtimeText)
            setLineSpacing(dp(2).toFloat(), 1f)
            text = ""
            setPadding(0, dp(4), 0, dp(4))
        }
        realTimeScroll = ScrollView(this).apply {
            addView(realTimeText)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
        }
        val rtLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(180))
        realTimeScroll.layoutParams = rtLp

        toolText = TextView(this).apply {
            textSize = 11f; setTextColor(s.panelTextSecondary); text = ""
        }
        timeText = TextView(this).apply {
            textSize = 11f; setTextColor(s.panelTextSecondary); text = ""
        }
        tokenText = TextView(this).apply {
            textSize = 11f; setTextColor(s.accent)
            typeface = Typeface.DEFAULT_BOLD
            text = ""
            visibility = View.GONE
        }

        mirrorView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.GONE
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
            layoutParams = LinearLayout.LayoutParams(dp(200), dp(300)).also { it.bottomMargin = dp(4) }
        }
        mirrorCaption = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xCCFFFFFF.toInt())
            text = "AI 操作中…（点击暂停）"
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(0x66000000.toInt())
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            visibility = View.GONE
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
        }

        pauseBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(s.accent)
            text = "⏸ 暂停 AI"
            isClickable = true
            setOnClickListener { MainApp.instance.agentStopCallback?.invoke() }
        }

        panelView.addView(titleBar)
        panelView.addView(realTimeScroll)
        panelView.addView(toolText)
        panelView.addView(timeText)
        panelView.addView(tokenText)
        panelView.addView(mirrorView)
        panelView.addView(mirrorCaption)
        panelView.addView(pauseBtn)

        val s7 = style()
        replyInput = EditText(this).apply {
            hint = "回复 AI…"
            textSize = 12f
            setTextColor(s7.panelText)
            setHintTextColor(s7.panelTextSecondary)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 2
            minLines = 1
            setPadding(dp(8), dp(5), dp(8), dp(5))
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(s7.panelBg)
                setStroke(dp(1), s7.divider)
            }
            background = bg
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) }
        }

        replyInput.setOnFocusChangeListener { _, hasFocus ->
            val p = params ?: return@setOnFocusChangeListener
            val root = rootView ?: return@setOnFocusChangeListener
            val newFlags = if (hasFocus)
                p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else
                p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (newFlags != p.flags) {
                p.flags = newFlags
                runCatching { wm?.updateViewLayout(root, p) }
            }
        }

        replySendBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(s7.ballText)
            text = "发送"
            isClickable = true
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(s7.ballBg)
            }
            setPadding(dp(14), dp(4), dp(14), dp(4))
            setOnClickListener {
                val txt = replyInput.text.toString().trim()
                if (txt.isEmpty()) return@setOnClickListener
                replyInput.setText("")
                com.ai.android.AgentBridge.postMessage(txt)
            }
        }

        val replyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inputLp = LinearLayout.LayoutParams(0, -2, 1f)
            inputLp.setMargins(0, 0, dp(6), 0)
            addView(replyInput, inputLp)
            addView(replySendBtn)
        }
        panelView.addView(replyRow)

        closeBtn = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFE08080.toInt())
            text = "✕ 关闭悬浮窗"
            isClickable = true
            setOnClickListener { close() }
            setPadding(0, dp(6), 0, 0)
        }
        panelView.addView(closeBtn)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ballRow)
            addView(panelView)
        }

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(12)
            y = dp(300)
        }

        ballRow.setOnTouchListener(onDrag)
        defaultBall.setOnTouchListener(onDrag)
        titleBar.setOnTouchListener(onDrag)

        runCatching { windowManager.addView(root, lp) }
        rootView = root
        params = lp
        overlayReady = true
    }

    private fun renderPluginBall() {
        pluginBallViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
        pluginBallViews.clear()
        if (!::ballRow.isInitialized) return
        val ctx = this
        val slots = runCatching { MainApp.instance.pluginRegistry.allWidgets() }.getOrDefault(emptyList())
        val ballSlot = slots.firstOrNull()
        if (ballSlot != null) {
            val v = runCatching { ballSlot.render(ctx) { toggleExpand(true) } }.getOrNull()
            if (v != null) {
                v.layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                v.setOnTouchListener(onDrag)
                ballRow.addView(v)
                pluginBallViews.add(v)
                defaultBall.visibility = View.INVISIBLE
                runCatching { ballSlot.onBind(Bundle()) }
            }
        }
    }

    private fun renderPluginWidgets() {
        pluginWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
        pluginWidgetViews.clear()
        pluginSlots.clear()
        val slots = runCatching { MainApp.instance.pluginRegistry.allWidgets() }.getOrDefault(emptyList())
        slots.drop(1).forEach { slot ->
            val v = runCatching { slot.render(this) { toggleExpand(true) } }.getOrNull()
            if (v != null) {
                pluginWidgetViews.add(v)
                v.setOnTouchListener(onDrag)
                ballRow.addView(v, LinearLayout.LayoutParams(dp(44), dp(44)).also {
                    val p = dp(6); it.setMargins(0, 0, p, 0)
                })
                pluginSlots.add(slot)
            }
        }
    }

    fun rebuildWidgets() {
        handler.post {
            if (!overlayReady) return@post
            runCatching {
                renderPluginBall()
                renderPluginWidgets()
                applyStyleRecolor()
                render(lastState)
            }
        }
    }

    fun reclamp() {
        handler.post {
            runCatching { clampToScreen() }
        }
    }

    /**
     * ⭐ 样式变更后对已存在的球 / 面板重新配色。
     */
    private fun applyStyleRecolor() {
        if (!::defaultBall.isInitialized) return
        val s = style()
        runCatching {
            defaultBall.setTextColor(s.ballText)
            defaultBall.background = ballDrawable(s)
            (panelView.background as? GradientDrawable)?.let {
                it.setColor(s.panelBg)
                it.cornerRadius = dp(s.cornerRadius).toFloat()
                // ⭐ 同步描边
                if (s.panelStrokeWidth > 0) {
                    it.setStroke(dp(s.panelStrokeWidth), s.panelStrokeColor)
                } else {
                    it.setStroke(0, android.graphics.Color.TRANSPARENT)
                }
            }
            progressText.setTextColor(s.panelText)
            toolText.setTextColor(s.panelTextSecondary)
            timeText.setTextColor(s.panelTextSecondary)
            tokenText.setTextColor(s.accent)
            realTimeText.setTextColor(s.realtimeText)
            pauseBtn.setTextColor(s.accent)
        }
    }

    private fun toggleExpand(expand: Boolean) {
        expanded = expand
        handler.post {
            if (!overlayReady) return@post
            ballRow.visibility = if (expand) View.GONE else View.VISIBLE
            panelView.visibility = if (expand) View.VISIBLE else View.GONE
            rootView?.post { clampToScreen() }
            render(lastState)
        }
    }

    private fun clampToScreen() {
        val root = rootView ?: return
        val p = params ?: return
        val dm = resources.displayMetrics
        val rootW = root.width
        val rootH = root.height
        if (rootW <= 0 || rootH <= 0) return
        val maxX = (dm.widthPixels - rootW).coerceAtLeast(0)
        val maxY = (dm.heightPixels - rootH).coerceAtLeast(0)
        p.x = p.x.coerceIn(0, maxX)
        p.y = p.y.coerceIn(0, maxY)
        runCatching { wm?.updateViewLayout(root, p) }
    }

    private fun render(state: AgentState) {
        lastState = state
        val label = when (state.status) {
            AgentState.Status.IDLE -> "待机"
            AgentState.Status.THINKING -> "思考"
            AgentState.Status.TOOL_CALLING -> "工具"
            AgentState.Status.WAITING_USER -> "等待"
            AgentState.Status.COMPLETED -> "完成"
            AgentState.Status.ERROR -> "错误"
        }
        handler.post {
            if (!overlayReady) return@post
            progressText.text = label + if (state.progressText.isNotBlank()) " · ${state.progressText.take(20)}" else ""
            toolText.text = state.currentTool.takeIf { it.isNotBlank() }?.let { "工具: $it" } ?: ""
            timeText.text = if (state.startedAt > 0) {
                "已运行: ${(System.currentTimeMillis() - state.startedAt) / 1000}s"
            } else ""
            if (lastTokenText.isNotBlank()) {
                tokenText.visibility = View.VISIBLE
                tokenText.text = lastTokenText
            }
            val bundle = Bundle().apply {
                putString("state", state.status.name)
                putString("progress", state.progressText)
                putString("tool", state.currentTool)
                putString("token_text", lastTokenText)
            }
            pluginBallViews.forEach { v ->
                val slot = runCatching { MainApp.instance.pluginRegistry.allWidgets().firstOrNull() }.getOrNull()
                if (slot != null) runCatching { slot.onBind(bundle) }
            }
            pluginSlots.forEach { slot -> runCatching { slot.onBind(bundle) } }
            val hasRt = reasoningBuffer.isNotEmpty() || contentBuffer.isNotEmpty()
            realTimeScroll.visibility = if (hasRt) View.VISIBLE else View.GONE
        }
    }

    private fun setTokenText(text: String) {
        lastTokenText = text
        handler.post {
            if (!overlayReady) return@post
            tokenText.visibility = if (text.isNotBlank()) View.VISIBLE else View.GONE
            tokenText.text = text
        }
    }

    private fun pushStreaming(kind: String, fragment: String) {
        if (fragment.isEmpty()) return
        if (kind == "reasoning") {
            reasoningBuffer = (reasoningBuffer + fragment).takeLast(MAX_RT)
        } else {
            contentBuffer = (contentBuffer + fragment).takeLast(MAX_RT)
        }
        refreshRealTime()
    }

    private fun resetStreaming() {
        reasoningBuffer = ""
        contentBuffer = ""
        handler.post { if (overlayReady) refreshRealTime() }
    }

    private fun refreshRealTime() {
        handler.post {
            if (!overlayReady) return@post
            val sb = StringBuilder()
            if (reasoningBuffer.isNotEmpty()) {
                sb.append("💭 思考：").append('\n').append(reasoningBuffer).append("\n\n")
            }
            if (contentBuffer.isNotEmpty()) {
                sb.append("📝 生成：").append('\n').append(contentBuffer)
            }
            val has = sb.isNotEmpty()
            realTimeText.text = sb.toString()
            realTimeScroll.visibility = if (has) View.VISIBLE else View.GONE
            realTimeScroll.post {
                realTimeScroll.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun handleDrag(ev: MotionEvent): Boolean {
        val w = wm ?: return false
        val root = rootView ?: return false
        val p = params ?: return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = ev.rawX; downRawY = ev.rawY
                startLpX = p.x; startLpY = p.y
                dragMoved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downRawX
                val dy = ev.rawY - downRawY
                if (!dragMoved && (abs(dx) > 10f || abs(dy) > 10f)) dragMoved = true
                if (dragMoved) {
                    val dm = resources.displayMetrics
                    val rootW = root.width
                    val rootH = root.height
                    val maxX = if (rootW > 0) (dm.widthPixels - rootW).coerceAtLeast(0) else 0
                    val maxY = if (rootH > 0) (dm.heightPixels - rootH).coerceAtLeast(0) else 0
                    p.x = (startLpX + dx.toInt()).coerceIn(0, maxX)
                    p.y = (startLpY + dy.toInt()).coerceIn(0, maxY)
                    runCatching { w.updateViewLayout(root, p) }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val wasMoved = dragMoved
                dragMoved = false
                if (!wasMoved) {
                    runCatching { pushBallClickToJs(p.x.toFloat(), p.y.toFloat()) }
                    toggleExpand(!expanded)
                } else {
                    runCatching { pushBallDragEndToJs(p.x.toFloat(), p.y.toFloat()) }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                dragMoved = false
                return true
            }
            else -> return false
        }
    }

    private fun showFrame(bmp: Bitmap) {
        handler.post {
            if (!overlayReady) return@post
            mirrorView.setImageBitmap(bmp)
        }
    }

    fun renderJsWidget(text: String, colorHex: String?) {
        handler.post {
            if (!::ballRow.isInitialized) return@post
            val s = style()
            jsWidgetViews.forEach { v -> if (v.parent != null) (v.parent as? ViewGroup)?.removeView(v) }
            jsWidgetViews.clear()
            val color = colorHex?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() } ?: s.ballBg
            val label2 = text.take(2)
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            val tv = TextView(this).apply {
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(s.ballText)
                this.text = label2
                background = bg
                isClickable = true
                setOnClickListener { toggleExpand(true) }
            }
            tv.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.setMargins(dp(6), 0, 0, 0)
            }
            tv.setOnTouchListener(onDrag)
            ballRow.addView(tv)
            jsWidgetViews.add(tv)
            runCatching { rootView?.post { clampToScreen() } }
        }
    }

    @Volatile private var jsBallPluginId: String? = null

    fun applyJsBall(json: kotlinx.serialization.json.JsonObject, pluginId: String) {
        handler.post {
            if (!::defaultBall.isInitialized) return@post
            jsBallPluginId = pluginId
            val text = json["text"]?.let {
                if (it is kotlinx.serialization.json.JsonPrimitive) it.content else null
            } ?: "AI"
            defaultBall.text = text.take(3)

            json["color"]?.let { colorEl ->
                if (colorEl is kotlinx.serialization.json.JsonPrimitive) {
                    runCatching {
                        defaultBall.background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(android.graphics.Color.parseColor(colorEl.content))
                        }
                    }
                }
            }

            json["size"]?.let { sizeEl ->
                if (sizeEl is kotlinx.serialization.json.JsonPrimitive) {
                    val sz = sizeEl.content.toIntOrNull()?.coerceIn(24, 96) ?: 46
                    val lp = defaultBall.layoutParams as? LinearLayout.LayoutParams
                    if (lp != null) {
                        lp.width = dp(sz); lp.height = dp(sz)
                        defaultBall.layoutParams = lp
                    }
                }
            }

            json["shape"]?.let { shapeEl ->
                if (shapeEl is kotlinx.serialization.json.JsonPrimitive) {
                    val shape = shapeEl.content
                    val size = (defaultBall.layoutParams as? LinearLayout.LayoutParams)?.width?.let { (it / resources.displayMetrics.density).toInt() } ?: 46
                    val gd = GradientDrawable()
                    gd.cornerRadius = when (shape) {
                        "capsule" -> dp(size).toFloat() / 2f
                        "rounded" -> dp(size).toFloat() * 0.28f
                        "square" -> 0f
                        else -> dp(size).toFloat() / 2f
                    }
                    defaultBall.background = gd
                }
            }
            runCatching { rootView?.post { clampToScreen() } }
        }
    }

    fun restoreDefaultBall() {
        handler.post {
            if (!::defaultBall.isInitialized) return@post
            jsBallPluginId = null
            val s = style()
            defaultBall.text = s.ballLabel
            defaultBall.setTextColor(s.ballText)
            defaultBall.background = ballDrawable(s)
            runCatching {
                val sz = s.ballSize.coerceIn(24, 96)
                val lp = defaultBall.layoutParams as? LinearLayout.LayoutParams
                if (lp != null) { lp.width = dp(sz); lp.height = dp(sz) }
                rootView?.post { clampToScreen() }
            }
        }
    }

    fun pushBallClickToJs(x: Float, y: Float) {
        val pid = jsBallPluginId ?: return
        runCatching {
            MainApp.instance.jsPluginRuntime.pushBallEvent(
                pid, "click",
                """{"x":$x,"y":$y}"""
            )
        }
    }

    fun pushBallDragEndToJs(x: Float, y: Float) {
        val pid = jsBallPluginId ?: return
        runCatching {
            MainApp.instance.jsPluginRuntime.pushBallEvent(
                pid, "dragEnd",
                """{"x":$x,"y":$y}"""
            )
        }
    }

    private fun setMirrorPanel(visible: Boolean) {
        handler.post {
            if (!overlayReady) return@post
            mirrorView.visibility = if (visible) View.VISIBLE else View.GONE
            mirrorCaption.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && !expanded) toggleExpand(true)
        }
    }

    companion object {
        private const val CHANNEL_ID = "agent_status"
        private const val NOTIF_ID = 1001

        @Volatile private var instance: FloatingService? = null
        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) { runCatching { context.startService(Intent(context, FloatingService::class.java)) } ; return }
            val i = Intent(context, FloatingService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ContextCompat.startForegroundService(context, i)
                else context.startService(i)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, FloatingService::class.java)) }
        }

        fun update(state: AgentState) { instance?.render(state) }

        fun showWork(context: Context, state: AgentState) {
            if (state.isActive && !isRunning) start(context)
            instance?.resetStreaming()
            update(state)
        }

        fun finishWork(context: Context, state: AgentState) {
            update(state)
        }

        fun setTokenText(context: Context, text: String) { instance?.setTokenText(text) }

        fun pushStreaming(context: Context, kind: String, fragment: String) {
            instance?.pushStreaming(kind, fragment)
        }

        fun setMirrorActive(context: Context, active: Boolean) {
            val ins = instance
            if (active && ins == null) start(context)
            handlerFor(context).post { instance?.setMirrorPanel(active) }
            if (active) MirrorService.start(context) else MirrorService.stop(context)
        }

        fun pushFrame(bmp: Bitmap) { instance?.showFrame(bmp) }

        fun rebuildWidgets(context: Context) {
            Handler(Looper.getMainLooper()).post { instance?.rebuildWidgets() }
        }

        fun tokenTextSnapshot(): String =
            instance?.let { runCatching { it.lastTokenText }.getOrDefault("") } ?: ""

        fun stateSnapshot(): String =
            instance?.let { runCatching { it.lastState.status.name }.getOrDefault("IDLE") } ?: "IDLE"

        var jsRenderWidgetHandler: ((pluginId: String, text: String, colorHex: String?) -> Unit)? = null

        fun pushJsWidget(context: Context, text: String, colorHex: String?) {
            Handler(Looper.getMainLooper()).post { instance?.renderJsWidget(text, colorHex) }
        }

        fun renderJsBall(context: Context, pluginId: String, jsonStr: String) {
            Handler(Looper.getMainLooper()).post {
                val ins = instance ?: return@post
                runCatching {
                    val jsonObj = kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                    if (jsonObj !is kotlinx.serialization.json.JsonObject) return@post
                    if (jsonObj["remove"]?.let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "true" } == true) {
                        ins.restoreDefaultBall()
                        return@post
                    }
                    ins.applyJsBall(jsonObj, pluginId)
                }.onFailure { android.util.Log.w("FloatingService", "renderJsBall parse fail: $jsonStr", it) }
            }
        }

        private fun handlerFor(context: Context) = Handler(Looper.getMainLooper())
    }
}