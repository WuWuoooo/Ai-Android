package com.ai.android.agent

import android.util.Log
import com.ai.android.model.ToolCall
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 审批管理器。
 *
 * 策略（按 scope 分级）：
 *  - READ_ONLY  → 自动放行
 *  - WRITE      → 首次弹窗，可"本会话始终允许"（缓存）
 *  - DEVICE     → 交给 AiControlGuard（不走本类）
 *  - SYSTEM     → 每次弹窗（不缓存）
 *
 * ⭐ 用 kotlinx.coroutines.sync.Mutex 串行化（不用 ReentrantLock）：
 *    - 允许在临界区里挂起（askUser 是 suspend）
 *    - 避免 Kotlin 编译器的 "suspension point inside critical section" 报错
 */
object ApprovalManager {

    private const val TAG = "ApprovalManager"

    /** 会话内已批准的工具名：convId -> Set<toolName> */
    private val sessionApproved = ConcurrentHashMap<String, MutableSet<String>>()

    private val mutex = Mutex()

    /**
     * 请求审批。
     * @return true=批准；false=拒绝或取消
     */
    suspend fun request(
        convId: String,
        call: ToolCall,
        meta: ToolMeta,
        args: JsonObject,
        askUser: (suspend (String, String) -> String)?,
    ): Boolean {
        // 快路径：缓存命中直接放行（无需加锁）
        if (ToolCatalog.cacheableApproval(meta.scope)) {
            val set = sessionApproved[convId]
            if (set != null && call.name in set) return true
        }

        val handler = askUser ?: run {
            Log.w(TAG, "无 UI 可用，${call.name} 拒绝")
            return false
        }

        // 慢路径：加锁串行化（避免并发弹多个对话框）
        mutex.lock()
        try {
            // double-check
            if (ToolCatalog.cacheableApproval(meta.scope)) {
                val set = sessionApproved[convId]
                if (set != null && call.name in set) return true
            }

            val scopeLabel = when (meta.scope) {
                ToolScope.READ_ONLY -> "只读"
                ToolScope.WRITE -> "写入"
                ToolScope.DEVICE -> "设备"
                ToolScope.SYSTEM -> "系统"
            }

            val argsPreview = call.arguments.take(200).replace("\n", " ")
            val question = "AI 即将执行「${call.name}」（${scopeLabel}）\n\n参数：$argsPreview"

            val options = if (ToolCatalog.cacheableApproval(meta.scope)) {
                "允许本次,本会话始终允许,拒绝"
            } else {
                "允许本次,拒绝"
            }

            // ⭐ suspend 在 Mutex 临界区内是安全的（kotlinx Mutex 支持挂起）
            val answer = runCatching { handler(question, options) }
                .getOrDefault("拒绝")
                .trim()

            val approved = when {
                answer.contains("始终") -> {
                    sessionApproved.getOrPut(convId) { mutableSetOf() }.add(call.name)
                    true
                }
                answer.contains("允许") || answer == "确认" || answer == "是" -> true
                else -> false
            }

            SessionLog.recordApproval(convId, call, approved, meta.scope.name)
            return approved
        } finally {
            mutex.unlock()
        }
    }

    fun clearSession(convId: String) { sessionApproved.remove(convId) }
    fun clearAll() { sessionApproved.clear() }
}