package com.ai.android.agent

import com.ai.android.model.ToolCall
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 执行结果缓存。
 * - 只缓存 ToolCatalog.metaOf(name).cacheableMs > 0 的工具
 * - key = toolName + SHA-1(归一化参数)
 * - 用户可在设置页全局关闭
 */
object ResultCache {

    private data class Entry(val value: String, val expiresAt: Long)

    private val map = ConcurrentHashMap<String, Entry>()

    /** 全局开关（由 SettingsRepository 同步） */
    @Volatile var enabled: Boolean = true

    fun get(call: ToolCall): String? {
        if (!enabled) return null
        val ttl = ToolCatalog.metaOf(call.name).cacheableMs
        if (ttl <= 0) return null
        val key = makeKey(call)
        val e = map[key] ?: return null
        if (e.expiresAt < System.currentTimeMillis()) {
            map.remove(key)
            return null
        }
        return e.value
    }

    fun put(call: ToolCall, value: String) {
        if (!enabled) return
        val ttl = ToolCatalog.metaOf(call.name).cacheableMs
        if (ttl <= 0) return
        val key = makeKey(call)
        map[key] = Entry(value, System.currentTimeMillis() + ttl)
    }

    /** 写类工具成功后让所有读类缓存失效（文件变了） */
    fun invalidateAfterWrite() {
        map.clear()
    }

    fun clear() = map.clear()

    private fun makeKey(call: ToolCall): String {
        val args = normalizeArgs(call.arguments)
        val sha = MessageDigest.getInstance("SHA-1")
            .digest("${call.name}|$args".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "${call.name}:$sha"
    }

    /** 参数归一化：去空白、按 key 排序 */
    private fun normalizeArgs(raw: String): String {
        if (raw.isBlank()) return "{}"
        return runCatching {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(raw)
                as? kotlinx.serialization.json.JsonObject ?: return raw
            obj.entries.sortedBy { it.key }
                .joinToString("&") { "${it.key}=${it.value}" }
        }.getOrDefault(raw)
    }
}