package com.ai.android.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * ⭐ 用户批准令牌池。
 *
 * 用途：打通「DangerousCommandGuard 拦截 → AI 调 ask_user → 用户回答"确认" → 应该放行」
 * 这条链路。否则 AI 永远跨不过守卫。
 *
 * 规则：
 *  - Token 授予：ToolRegistry 检测到 ask_user 返回"确认/允许/是/OK" → grantToken(convId)
 *  - Token 消耗：ToolRegistry.execute 在 Guard 链前检查 hasAndConsume(convId)
 *  - TTL 30 秒；每次消耗一次性
 */
object UserApprovalTokens {

    private const val TTL_MS = 30_000L
    private val tokens = ConcurrentHashMap<String, Long>()   // convId -> expiresAt

    fun grant(convId: String) {
        tokens[convId] = System.currentTimeMillis() + TTL_MS
    }

    /** 检查并消耗（一次性） */
    fun hasAndConsume(convId: String): Boolean {
        val exp = tokens.remove(convId) ?: return false
        return exp >= System.currentTimeMillis()
    }

    fun clear(convId: String) { tokens.remove(convId) }
    fun clearAll() = tokens.clear()
}