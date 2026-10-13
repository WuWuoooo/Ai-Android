package com.ai.android.service.terminal

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.ai.android.MainActivity
import com.ai.android.MainApp
import com.ai.android.agent.tools.PtyTerminalSession
import com.ai.android.storage.SessionEntity
import com.ai.android.storage.SessionMeta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台终端服务：持有所有 PtyTerminalSession，保证 App 后台不被杀。
 *
 * ⭐ 架构：in-process Service + LocalBinder（对齐 Termux 官方做法）。
 *  - UI / AI 拿到同一个 Service 引用，零 IPC 开销
 *  - 前台通知保证存活
 *  - 会话元信息落 Room（sessions 表），重启后能恢复列表
 */
class TerminalForegroundService : Service() {

    inner class LocalBinder : Binder() {
        val service: TerminalForegroundService get() = this@TerminalForegroundService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** sessionId -> PtyTerminalSession */
    private val sessions = ConcurrentHashMap<String, PtyTerminalSession>()

    /** sessionId -> SessionMeta（内存镜像，供 UI 快速读） */
    private val metaMap = ConcurrentHashMap<String, SessionMeta>()

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        startForegroundSafe()
        // 从 Room 恢复元信息（PTY 不重建，等 attach 时 ensureStarted）
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = MainApp.instance.database.sessionDao()
                dao.purgeOrphans()
                // 只加载全部 sessions，不做过滤（会话可能跨对话）
                // 这里遍历需要全量方法，简化：忽略恢复，等用户切换对话时再查
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        isRunning = false
        // 保存元信息
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = MainApp.instance.database.sessionDao()
                metaMap.values.forEach { m ->
                    dao.upsert(SessionEntity(
                        id = m.id, convId = m.convId, title = m.title,
                        pinned = m.pinned, createdAt = m.createdAt, lastActiveAt = m.lastActiveAt,
                    ))
                }
            }
        }
        // 销毁所有 PTY
        sessions.values.forEach { runCatching { it.dispose() } }
        sessions.clear()
        scope.cancel()
        super.onDestroy()
    }

    // ==================== 对外 API ====================

    /** 新建会话（写 Room + 加入内存池），返回 sessionId */
    fun createSession(convId: String, title: String? = null): String {
        val id = "pty-" + UUID.randomUUID().toString().take(8)
        val now = System.currentTimeMillis()
        val realTitle = title ?: "终端 ${sessionsCountForConv(convId) + 1}"
        val sess = PtyTerminalSession(
            sessionId = id, convId = convId, title = realTitle, createdAt = now,
            context = applicationContext,
        )
        sessions[id] = sess
        val meta = SessionMeta(id, convId, realTitle, false, now, now)
        metaMap[id] = meta
        scope.launch(Dispatchers.IO) {
            runCatching {
                MainApp.instance.database.sessionDao().upsert(SessionEntity(
                    id = id, convId = convId, title = realTitle, pinned = false,
                    createdAt = now, lastActiveAt = now,
                ))
            }
        }
        updateNotification()
        return id
    }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)?.let { runCatching { it.dispose() } }
        metaMap.remove(sessionId)
        scope.launch(Dispatchers.IO) {
            runCatching { MainApp.instance.database.sessionDao().delete(sessionId) }
        }
        updateNotification()
    }

    fun getSession(sessionId: String): PtyTerminalSession? = sessions[sessionId]

    fun listSessionsSync(convId: String): List<SessionMeta> =
        metaMap.values
            .filter { it.convId == convId }
            .sortedWith(compareByDescending<SessionMeta> { it.pinned }.thenBy { it.createdAt })

    fun renameSession(sessionId: String, title: String) {
        sessions[sessionId]?.title = title
        metaMap[sessionId]?.let { old ->
            metaMap[sessionId] = old.copy(title = title)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { MainApp.instance.database.sessionDao().rename(sessionId, title) }
        }
        updateNotification()
    }

    fun setPinned(sessionId: String, pinned: Boolean) {
        metaMap[sessionId]?.let { old ->
            metaMap[sessionId] = old.copy(pinned = pinned)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { MainApp.instance.database.sessionDao().setPinned(sessionId, pinned) }
        }
        updateNotification()
    }

    fun touch(sessionId: String) {
        val now = System.currentTimeMillis()
        metaMap[sessionId]?.let { old ->
            metaMap[sessionId] = old.copy(lastActiveAt = now)
        }
        scope.launch(Dispatchers.IO) {
            runCatching { MainApp.instance.database.sessionDao().touch(sessionId, now) }
        }
    }

    /** 该对话的第一个（或默认）会话；不存在则自动创建一个 */
    fun getOrCreateDefault(convId: String): PtyTerminalSession {
        val existing = listSessionsSync(convId)
        if (existing.isNotEmpty()) {
            sessions[existing.first().id]?.let { return it }
        }
        val id = createSession(convId, "共享(AI)")
        return sessions[id]!!
    }

    private fun sessionsCountForConv(convId: String): Int =
        metaMap.values.count { it.convId == convId }

    // ==================== 通知 ====================

    private fun startForegroundSafe() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID, "终端会话", NotificationManager.IMPORTANCE_LOW)
                    )
                }
            }
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        runCatching {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val activeCount = sessions.values.count {
            it.stateFlow.value == PtyTerminalSession.State.RUNNING
        }
        val text = if (sessions.isEmpty()) "无活跃会话"
        else "活跃会话 ${activeCount} / 共 ${sessions.size}"

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TerminalForegroundService::class.java).setAction(ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Ai Android 终端")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_delete, "关闭所有", stop)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            sessions.keys.toList().forEach { closeSession(it) }
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "pty_terminal_service"
        private const val NOTIF_ID = 2024
        const val ACTION_STOP_ALL = "com.ai.android.terminal.STOP_ALL"

        @Volatile var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            val i = Intent(context, TerminalForegroundService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, i)
                } else {
                    context.startService(i)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TerminalForegroundService::class.java)) }
        }
    }
}