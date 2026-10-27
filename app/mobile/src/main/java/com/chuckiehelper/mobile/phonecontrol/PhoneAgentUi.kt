package com.chuckiehelper.mobile.phonecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chuckiehelper.mobile.background.BackgroundNotification
import com.chuckiehelper.mobile.background.LiveUpdateNotification
import kotlinx.coroutines.*
import org.json.JSONObject

/** One small top-of-screen surface plus notifications, shared by both APKs and all transports. */
class PhoneAgentUi(private val context: Context) {
    private val ledger = AgentActivityLedger(SystemClock::elapsedRealtime)
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var overlay: LinearLayout? = null
    private var widthAnimation: ValueAnimator? = null
    private var pendingWidth: Runnable? = null
    private var targetWidth = 0
    private val windowPrefs = context.getSharedPreferences("phone-agent-window", Context.MODE_PRIVATE)
    private var minimized = windowPrefs.getBoolean("minimized", false)
    private var horizontalPosition = windowPrefs.getFloat("horizontal", .5f)
    private var verticalPosition = windowPrefs.getFloat("vertical", 0f)
    private var contentScroll: ScrollView? = null
    private var contentOffset = 0
    private var scrollDirection = 1
    private var scrollPauseUntil = 0L
    private val scrollTick = object : Runnable {
        override fun run() {
            val view = contentScroll ?: return
            if (!running || minimized || overlay == null) return
            val maximum = ((view.getChildAt(0)?.height ?: 0) - view.height).coerceAtLeast(0)
            if (maximum > 0 && SystemClock.elapsedRealtime() >= scrollPauseUntil) {
                contentOffset = (contentOffset + dp(1) * scrollDirection).coerceIn(0, maximum)
                view.scrollTo(0, contentOffset)
                if (contentOffset == 0 || contentOffset == maximum) {
                    scrollDirection = -scrollDirection; scrollPauseUntil = SystemClock.elapsedRealtime() + 2000
                }
            }
            handler.postDelayed(this, if (maximum > 0) 80 else 1000)
        }
    }
    private var rendered: AgentDisplayState? = null
    private var renderedNotice: String? = null
    private var scope: CoroutineScope? = null
    @Volatile private var running = false
    @Volatile private var island = HyperIslandStatus()
    private val settings = PhoneSettings(context)
    private val tick = object : Runnable { override fun run() { if (running) { render(); handler.postDelayed(this, 1000) } } }

    fun start() {
        running = true
        handler.removeCallbacks(tick); handler.post(tick)
        scope?.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { worker ->
            worker.launch { while (isActive) {
                val next = HyperIslandSupport.query(context)
                val changed = next != island; island = next
                refresh(changed); delay(30_000)
            } }
        }
    }
    fun close() {
        running = false; scope?.cancel(); scope = null; ledger.clear()
        handler.post { handler.removeCallbacks(tick); removeOverlay(); notifications.cancel(42); rendered = null; renderedNotice = null }
    }
    fun refresh(force: Boolean = false) { handler.post { if (force) { rendered = null; renderedNotice = null }; render() } }
    fun seen(id: String, name: String = "Agent") { ledger.seen(id, name); refresh() }
    fun disconnected(id: String) { ledger.disconnect(id); refresh() }
    fun webDisconnected() { ledger.disconnectWeb(); refresh() }
    fun beginUi(id: String, message: String, seconds: Int) { ledger.beginUi(id, message, seconds); refresh() }
    fun endUi(id: String) { ledger.endUi(id); refresh() }
    fun requireUi(id: String) { ledger.requireUi(id); refresh() }
    fun touchConnection(id: String) { ledger.touchConnection(id) }
    internal fun connectionStates() = ledger.snapshot().connections
    fun toolStarted(id: String, tool: String, explanation: String = ""): Boolean {
        val label = label(tool)
        if (label == null && explanation.isBlank()) return false
        ledger.toolStarted(id, label.orEmpty(), explanation); refresh()
        return true
    }
    fun toolFinished(id: String) { ledger.toolFinished(id); refresh() }
    fun notify(id: String, message: String, help: Boolean, seconds: Int): JSONObject {
        val row = ledger.notify(id, message, help, seconds); refresh()
        return noticeJson(row).put("overlay_allowed", Settings.canDrawOverlays(context))
            .put("notifications_allowed", NotificationManagerCompat.from(context).areNotificationsEnabled())
    }
    fun result(owner: String, id: String): JSONObject = ledger.result(owner, id)?.let(::noticeJson)
        ?: JSONObject().put("notice_id", id).put("status", "not_found")
    fun reply(id: String, answer: String) { ledger.reply(id, answer); refresh() }
    fun capabilities(): JSONObject = JSONObject().put("overlay_allowed", Settings.canDrawOverlays(context))
        .put("overlay_enabled", settings.agentOverlay).put("hyperos", island.json())
        .put("live_updates_supported", LiveUpdateNotification.supported).put("live_updates_allowed", LiveUpdateNotification.canPost(context))
    private fun noticeJson(row: AgentUserNotice) = JSONObject().put("notice_id", row.id).put("status", row.status)
        .put("kind", if (row.help) "help" else "info").put("message", row.message).put("agent", row.agent)

    private fun label(tool: String) = when (tool) {
        "phone_observe", "phone_find_nodes" -> "正在读取界面"
        "phone_screenshot" -> "正在截取屏幕"
        "phone_tap" -> "正在点击屏幕"
        "phone_swipe", "phone_gesture", "phone_ui_action" -> "正在操作界面"
        "phone_input_text" -> "正在输入文字"
        "phone_key" -> "正在执行按键"
        "phone_launch_app" -> "正在打开应用"
        "phone_intent" -> "正在调用应用组件"
        "phone_shell" -> "正在执行手机命令"
        "phone_manage_app" -> "正在管理应用"
        "phone_system_setting" -> "正在读写系统设置"
        "phone_wait" -> "正在等待界面变化"
        "phone_execute" -> "正在执行流程"
        "phone_get_operation" -> "正在查询回执"
        else -> null
    }

    private fun render() {
        if (!running) return
        val state = ledger.snapshot()
        val show = settings.agentOverlay && Settings.canDrawOverlays(context) && (state.connected || state.notice != null)
        if (state == rendered && (overlay != null) == show) return
        rendered = state
        val title = PhoneAgentStatus.title(state)
        val detail = PhoneAgentStatus.detail(state)
        // Supply the richer Xiaomi layout. LiveUpdateNotification selects this renderer
        // exclusively; generic promotion is used only without an active Xiaomi island.
        val focus = if (settings.agentIsland && state.connected && state.notice == null)
            HyperIslandSupport.extras(context, island, title, detail, false,
                summaryName = state.agent, summaryAction = PhoneAgentStatus.shortText(state)) else null
        BackgroundNotification.update(context, "phone-tools", if (state.connected) "$title · $detail" else "手机操控", focus,
            liveUpdate = settings.agentIsland && state.connected && state.operating,
            liveStatus = PhoneAgentStatus.shortText(state))
        renderNotice(state.notice)
        if (!show) { removeOverlay(); return }
        val maximumContentHeight = minOf(dp(280), (context.resources.displayMetrics.heightPixels * .4f).toInt())
        val groups = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            state.connections.forEachIndexed { index, client ->
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, if (index == 0) 0 else dp(7), 0, dp(3))
                    addView(text("${client.name} · ${if (client.ownsUi) "占用界面" else "已连接"}", 11f,
                        if (client.ownsUi) Color.rgb(136, 234, 206) else Color.WHITE, true))
                    fun information(value: String) {
                        if (value.isNotBlank()) addView(text(value, 11f, Color.rgb(223, 232, 229)).apply { setPadding(dp(10), dp(2), 0, dp(2)) })
                    }
                    listOf(client.goal, client.operation, client.explanation).filter { it.isNotBlank() }.distinct().forEach(::information)
                    client.notices.forEach { notice ->
                        information("${if (notice.help) "请求协助" else "消息"}：${notice.message}")
                        addView(LinearLayout(context).apply {
                            gravity = Gravity.END
                            if (notice.help) addView(button("已完成") { reply(notice.id, "done") })
                            addView(button(if (notice.help) "暂不处理" else "知道了") { reply(notice.id, "dismissed") })
                        })
                    }
                }, LinearLayout.LayoutParams(-1, -2))
                if (index < state.connections.lastIndex) addView(android.view.View(context).apply {
                    setBackgroundColor(Color.argb(35, 255, 255, 255))
                }, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(3) })
            }
        }
        val panel = object : LinearLayout(context) {
            var dragTouch: android.view.View.OnTouchListener? = null
            private var downX = 0f; private var downY = 0f
            override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        dragTouch?.onTouch(this, event)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val moved = kotlin.math.abs(event.rawX - downX) > ViewConfiguration.get(context).scaledTouchSlop ||
                            kotlin.math.abs(event.rawY - downY) > ViewConfiguration.get(context).scaledTouchSlop
                        if (moved) return true
                    }
                }
                return super.onInterceptTouchEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { setColor(Color.argb(208, 25, 40, 38)); cornerRadius = dp(14).toFloat() }
            elevation = dp(5).toFloat()
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(30)
                contentDescription = "按住拖动悬浮窗，点击${if (minimized) "展开" else "最小化"}"
                addView(text("● ${if (minimized && !state.operating) if (state.notice?.help == true) "待协助" else "Agent" else title} · ${state.connections.size}", 11f, Color.rgb(136, 234, 206), true), LinearLayout.LayoutParams(0, -2, 1f))
                addView(button(if (minimized) "展开" else "收起") { toggleMinimized() })
                enableDragging(this)
            }, LinearLayout.LayoutParams(-1, -2))
            if (!minimized) addView(object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    super.onMeasure(widthMeasureSpec, android.view.View.MeasureSpec.makeMeasureSpec(maximumContentHeight, android.view.View.MeasureSpec.AT_MOST))
                }
            }.apply {
                addView(groups); contentScroll = this
                post { contentOffset = contentOffset.coerceAtMost(((groups.height - height).coerceAtLeast(0))); scrollTo(0, contentOffset) }
            }, LinearLayout.LayoutParams(-1, -2))
        }
        panel.dragTouch = enableDragging(panel)
        val layout = WindowManager.LayoutParams(
            (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(160)),
            -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (state.operating) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
            }
        val maximum = (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(1)
        val paint = android.text.TextPaint().apply { textSize = 11f * context.resources.displayMetrics.scaledDensity }
        val strings = listOf("● $title  ·  ${state.connections.size} 个连接") + state.connections.flatMap { client ->
            listOf("${client.name} · ${if (client.ownsUi) "占用界面" else "已连接"}", client.goal, client.operation, client.explanation) +
                client.notices.map { "${if (it.help) "请求协助" else "消息"}：${it.message}" }
        }
        val desired = if (minimized) minOf(dp(175), maximum) else ((strings.flatMap { it.lines() }.maxOfOrNull { paint.measureText(it) } ?: 0f).toInt() + dp(80))
            .coerceIn(minOf(dp(240), maximum), maximum)
        panel.measure(android.view.View.MeasureSpec.makeMeasureSpec(desired, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
        val existing = overlay
        if (existing == null) {
            val host = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(panel, LinearLayout.LayoutParams(-1, -2)) }
            layout.width = desired; targetWidth = desired
            placeWindow(layout, panel.measuredHeight)
            runCatching { windows.addView(host, layout); overlay = host }
        } else {
            existing.removeAllViews(); existing.addView(panel, LinearLayout.LayoutParams(-1, -2))
            val current = existing.layoutParams as WindowManager.LayoutParams
            current.flags = layout.flags
            placeWindow(current, panel.measuredHeight)
            runCatching { windows.updateViewLayout(existing, current) }
            animateWidth(existing, desired)
        }
        handler.removeCallbacks(scrollTick)
        if (!minimized) handler.postDelayed(scrollTick, 1000)
    }

    private fun animateWidth(host: LinearLayout, desired: Int) {
        if (targetWidth == desired) return
        targetWidth = desired
        pendingWidth?.let(handler::removeCallbacks)
        widthAnimation?.cancel()
        val current = host.layoutParams as WindowManager.LayoutParams
        val change = Runnable {
            if (overlay !== host) return@Runnable
            val start = (host.layoutParams as WindowManager.LayoutParams).width
            widthAnimation = ValueAnimator.ofInt(start, desired).apply {
                duration = if (minimized) 180 else 400
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { value ->
                    if (overlay === host) {
                        val params = host.layoutParams as WindowManager.LayoutParams
                        params.width = value.animatedValue as Int
                        placeWindow(params, host.height)
                        runCatching { windows.updateViewLayout(host, params) }
                    }
                }
                start()
            }
        }
        pendingWidth = change
        handler.postDelayed(change, if (minimized) 0 else if (desired < current.width) 1200 else 150)
    }

    private fun toggleMinimized() {
        minimized = !minimized
        windowPrefs.edit().putBoolean("minimized", minimized).apply()
        rendered = null; render()
    }
    private fun placeWindow(params: WindowManager.LayoutParams, height: Int) {
        val metrics = context.resources.displayMetrics
        params.x = ((metrics.widthPixels - params.width).coerceAtLeast(0) * horizontalPosition).toInt()
        val availableHeight = (metrics.heightPixels - height - dp(48)).coerceAtLeast(0)
        params.y = maxOf(dp(4), (availableHeight * verticalPosition).toInt()).coerceAtMost(availableHeight)
    }
    private fun enableDragging(header: LinearLayout): android.view.View.OnTouchListener {
        var downX = 0f; var downY = 0f; var initialX = 0; var initialY = 0; var dragged = false
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        val listener = android.view.View.OnTouchListener touch@{ _, event ->
            val host = overlay ?: return@touch false
            val params = host.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    widthAnimation?.cancel(); pendingWidth?.let(handler::removeCallbacks)
                    scrollPauseUntil = SystemClock.elapsedRealtime() + 1000
                    downX = event.rawX; downY = event.rawY; initialX = params.x; initialY = params.y; dragged = false; true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop) dragged = true
                    if (dragged) {
                        val width = (context.resources.displayMetrics.widthPixels - host.width).coerceAtLeast(0)
                        val height = (context.resources.displayMetrics.heightPixels - host.height - dp(48)).coerceAtLeast(0)
                        params.x = (initialX + dx.toInt()).coerceIn(0, width)
                        params.y = (initialY + dy.toInt()).coerceIn(0, height)
                        horizontalPosition = if (width == 0) .5f else params.x.toFloat() / width
                        verticalPosition = if (height == 0) 0f else params.y.toFloat() / height
                        runCatching { windows.updateViewLayout(host, params) }
                    }; true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragged) windowPrefs.edit().putFloat("horizontal", horizontalPosition).putFloat("vertical", verticalPosition).apply()
                    else if (event.actionMasked == MotionEvent.ACTION_UP) toggleMinimized()
                    true
                }
                else -> false
            }
        }
        header.setOnTouchListener(listener)
        return listener
    }

    private fun renderNotice(notice: AgentUserNotice?) {
        if (notice == null) { notifications.cancel(42); renderedNotice = null; return }
        if (renderedNotice == notice.id) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        notifications.createNotificationChannel(NotificationChannel("phone-agent-messages", "Agent 消息", NotificationManager.IMPORTANCE_DEFAULT))
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        val open = PendingIntent.getActivity(context, 442, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, "phone-agent-messages").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(if (notice.help) "${notice.agent} 请求协助" else notice.agent).setContentText(notice.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.message)).setContentIntent(open)
            .setTimeoutAfter((notice.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(1000L))
            .setOnlyAlertOnce(true).setAutoCancel(false).setOngoing(notice.help)
        val actions = mutableListOf<Pair<String, Notification.Action>>()
        fun action(label: String, response: String) {
            val intent = Intent(context, PhoneAgentReplyReceiver::class.java).setAction("agent.reply")
                .setData(Uri.parse("phone-agent://notice/${notice.id}/$response"))
                .putExtra("notice_id", notice.id).putExtra("reply", response).addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            val pending = PendingIntent.getBroadcast(context, 443, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(0, label, pending)
            actions += "miui.focus.action_$response" to Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_dialog_info), label, pending).build()
        }
        if (notice.help) action("已完成", "done")
        action(if (notice.help) "暂不处理" else "知道了", "dismissed")
        if (settings.agentIsland) HyperIslandSupport.extras(context, island,
            if (notice.help) "等待协助" else "Agent 消息", notice.message, notice.help, actions,
            summaryName = notice.agent, summaryAction = if (notice.help) "等待你的协助" else "有新的消息")?.let(builder::addExtras)
        runCatching { notifications.notify(42, builder.build()); renderedNotice = notice.id }
    }
    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(2), 0, dp(2))
    }
    private fun button(value: String, action: () -> Unit) = text(value, 12f, Color.rgb(136, 234, 206), true).apply {
        setPadding(dp(12), dp(6), dp(4), dp(4)); setOnClickListener { action() }
    }
    private fun dp(value: Int) = (context.resources.displayMetrics.density * value).toInt()
    private fun removeOverlay() {
        handler.removeCallbacks(scrollTick); contentScroll = null
        pendingWidth?.let(handler::removeCallbacks); pendingWidth = null; widthAnimation?.cancel(); widthAnimation = null; targetWidth = 0
        overlay?.let { runCatching { windows.removeViewImmediate(it) } }; overlay = null
    }
}
