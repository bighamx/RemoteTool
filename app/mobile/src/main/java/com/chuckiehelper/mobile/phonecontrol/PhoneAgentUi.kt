package com.chuckiehelper.mobile.phonecontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.chuckiehelper.mobile.background.BackgroundNotification
import kotlinx.coroutines.*
import org.json.JSONObject

/** One small top-of-screen surface plus notifications, shared by both APKs and all transports. */
class PhoneAgentUi(private val context: Context) {
    private val ledger = AgentActivityLedger(SystemClock::elapsedRealtime)
    private val handler = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var overlay: LinearLayout? = null
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
    private fun noticeJson(row: AgentUserNotice) = JSONObject().put("notice_id", row.id).put("status", row.status)
        .put("kind", if (row.help) "help" else "info").put("message", row.message)

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
        else -> null
    }

    private fun render() {
        if (!running) return
        // Keep a visible assistance card stable while unrelated observation/tap
        // tools run, so its buttons and accessibility nodes are not recreated.
        val state = ledger.snapshot().let { value ->
            if (value.notice != null) value.copy(agent = value.notice.agent, operating = false, operation = "", explanation = "") else value
        }
        val show = settings.agentOverlay && Settings.canDrawOverlays(context) && (state.connected || state.notice != null)
        if (state == rendered && (overlay != null) == show) return
        rendered = state
        val title = when { state.notice?.help == true -> "等待协助"; state.operating -> "Agent 操作"; else -> "Agent 连接" }
        val detail = state.notice?.message ?: listOf(state.operation, state.explanation).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "${state.agent} 已连接" }
        val focus = if (settings.agentIsland && state.connected && state.notice == null)
            HyperIslandSupport.extras(context, island, title, detail, false) else null
        BackgroundNotification.update(context, "phone-tools", if (state.connected) "$title · $detail" else "手机操控", focus)
        renderNotice(state.notice)
        removeOverlay()
        if (!show) return
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply { setColor(Color.rgb(25, 40, 38)); cornerRadius = dp(14).toFloat() }
            elevation = dp(5).toFloat()
            addView(text("● $title  ·  ${state.agent}", 11f, Color.rgb(136, 234, 206), true))
            if (state.notice != null) {
                addView(ScrollView(context).apply {
                    addView(text(state.notice.message, 12f, Color.WHITE))
                }, LinearLayout.LayoutParams(-1, -2).apply { height = if (state.notice.message.length > 140) dp(110) else -2 })
                addView(LinearLayout(context).apply {
                    gravity = Gravity.END
                    if (state.notice.help) addView(button("已完成") { reply(state.notice.id, "done") })
                    addView(button(if (state.notice.help) "暂不处理" else "知道了") { reply(state.notice.id, "dismissed") })
                })
            } else {
                if (state.operation.isNotBlank()) addView(text(state.operation, 11f, Color.WHITE))
                if (state.explanation.isNotBlank()) addView(text(state.explanation, 11f, Color.WHITE).apply {
                    maxWidth = minOf(dp(320), (context.resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(120)))
                    maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                })
            }
        }
        val layout = WindowManager.LayoutParams(
            if (state.notice != null) (context.resources.displayMetrics.widthPixels - dp(24)).coerceAtLeast(dp(160)) else -2,
            -2, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                (if (state.notice == null) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0) or
                (if (state.operating) WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON else 0),
            PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(4)
            }
        runCatching { windows.addView(panel, layout); overlay = panel }
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
            if (notice.help) "等待协助" else "Agent 消息", notice.message, notice.help, actions)?.let(builder::addExtras)
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
    private fun removeOverlay() { overlay?.let { runCatching { windows.removeViewImmediate(it) } }; overlay = null }
}
