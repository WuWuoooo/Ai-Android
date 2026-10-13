package com.ai.android.agent.tools

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.ai.android.service.terminal.TerminalForegroundService
import com.ai.android.storage.SessionMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 终端会话门面：绑定 TerminalForegroundService 并转发调用。
 *
 * - 首次调用时 bindService，永久保持绑定（不 unbind，避免反复重连）
 * - App 进程被杀 → Service 也一起死（in-process），下一轮启动重新绑定
 */
object PtySessionManager {

    @Volatile private var service: TerminalForegroundService? = null
    private val bindMutex = Mutex()
    private var pending: CompletableDeferred<TerminalForegroundService>? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val b = binder as? TerminalForegroundService.LocalBinder
            if (b != null) {
                service = b.service
                pending?.complete(b.service)
                pending = null
            } else {
                pending?.completeExceptionally(IllegalStateException("binder cast failed"))
                pending = null
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    private suspend fun ensureBound(context: Context): TerminalForegroundService {
        service?.let { return it }
        bindMutex.withLock {
            service?.let { return it }
            val app = context.applicationContext
            // 先启动 Service（幂等），再 bind
            TerminalForegroundService.start(app)
            val d = CompletableDeferred<TerminalForegroundService>()
            pending = d
            val ok = withContext(Dispatchers.Main) {
                app.bindService(
                    Intent(app, TerminalForegroundService::class.java),
                    conn, Context.BIND_AUTO_CREATE,
                )
            }
            if (!ok) {
                pending = null
                throw IllegalStateException("bindService 失败")
            }
            return d.await()
        }
    }

    // ==================== 对外 API（suspend）====================

    suspend fun create(context: Context, convId: String, title: String? = null): String {
        val s = ensureBound(context)
        return s.createSession(convId, title)
    }

    suspend fun close(context: Context, sessionId: String) {
        ensureBound(context).closeSession(sessionId)
    }

    suspend fun list(context: Context, convId: String): List<SessionMeta> {
        return ensureBound(context).listSessionsSync(convId)
    }

    suspend fun get(context: Context, sessionId: String): PtyTerminalSession? {
        return ensureBound(context).getSession(sessionId)
    }

    /** 该对话的默认会话（不存在则创建 "共享(AI)"） */
    suspend fun getOrCreateDefault(context: Context, convId: String): PtyTerminalSession {
        return ensureBound(context).getOrCreateDefault(convId)
    }

    suspend fun rename(context: Context, sessionId: String, title: String) {
        ensureBound(context).renameSession(sessionId, title)
    }

    suspend fun setPinned(context: Context, sessionId: String, pinned: Boolean) {
        ensureBound(context).setPinned(sessionId, pinned)
    }

    suspend fun touch(context: Context, sessionId: String) {
        ensureBound(context).touch(sessionId)
    }
}