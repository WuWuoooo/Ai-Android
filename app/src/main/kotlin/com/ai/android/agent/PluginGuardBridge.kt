package com.ai.android.agent

import android.util.Log
import com.ai.android.model.ToolCall
import com.ai.android.plugin.PluginGuardDecl
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject

/**
 * ⭐ 插件 Guard 桥接。
 *
 * 两类 Guard：
 *  1. 声明式（PluginGuardDecl）：从 manifest.json 读取，纯正则匹配，无代码
 *  2. 代码式（JS）：插件运行时调 window.host.registerGuard(name, filter, callback)
 *     宿主每次工具调用前 postMessage 给 WebView，等回调（有超时）
 *
 * 优先级：代码式 > 声明式（同插件）
 * 超时兜底：默认 500ms，超时 = 拒绝（安全优先）
 */
object PluginGuardBridge {

    private const val TAG = "PluginGuardBridge"
    private const val JS_GUARD_TIMEOUT_MS = 500L

    /** 代码式 Guard 的 JS 回调桥：插件 id -> (guardName -> jsCallbackId) */
    private val jsGuards = LinkedHashMap<String, MutableList<JsGuardEntry>>()

    private class JsGuardEntry(
        val guardName: String,
        val toolFilter: String,
        val callbackId: String,
        val pluginId: String,
    )

    /** 声明式 Guard：直接来自 manifest */
    private val declGuards = LinkedHashMap<String, MutableList<DeclGuardEntry>>()

    private class DeclGuardEntry(
        val pluginId: String,
        val decl: PluginGuardDecl,
        val toolRegex: Regex,
        val argRegex: Regex?,
    )

    /** 插件 id → 是否已注册（用于卸载时清理） */
    private val registeredPlugins = mutableSetOf<String>()

    /** 桥：宿主调 JS 的回调，由 JsPluginRuntime 注入 */
    @Volatile var jsAskCallback: (suspend (pluginId: String, guardName: String, call: ToolCall) -> Boolean)? = null

    // ==================== 注册 / 卸载 ====================

    fun registerDeclarative(pluginId: String, decls: List<PluginGuardDecl>) {
        if (decls.isEmpty()) return
        synchronized(this) {
            val list = declGuards.getOrPut(pluginId) { mutableListOf() }
            decls.forEach { decl ->
                val toolRegex = try {
                    if (decl.toolFilter == "*" || decl.toolFilter.isBlank()) Regex("^.*$")
                    else {
                        val parts = decl.toolFilter.split(",", "|", " ").map { it.trim() }.filter { it.isNotEmpty() }
                        if (parts.isEmpty()) Regex("^.*$")
                        else Regex("^(${parts.joinToString("|")})$")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "invalid toolFilter: ${decl.toolFilter}", e)
                    Regex("^.*$")
                }
                val argRegex = decl.argPattern.takeIf { it.isNotBlank() }?.let {
                    runCatching { Regex(it) }.getOrNull()
                }
                list.add(DeclGuardEntry(pluginId, decl, toolRegex, argRegex))
            }
            registeredPlugins.add(pluginId)
        }
        Log.d(TAG, "registerDeclarative: plugin=$pluginId count=${decls.size}")
    }

    fun registerJsGuard(pluginId: String, guardName: String, toolFilter: String, callbackId: String) {
        synchronized(this) {
            val list = jsGuards.getOrPut(pluginId) { mutableListOf() }
            list.removeAll { it.guardName == guardName }   // 同名替换
            list.add(JsGuardEntry(guardName, toolFilter, callbackId, pluginId))
            registeredPlugins.add(pluginId)
        }
        Log.d(TAG, "registerJsGuard: plugin=$pluginId guard=$guardName")
    }

    fun unregisterPlugin(pluginId: String) {
        synchronized(this) {
            jsGuards.remove(pluginId)
            declGuards.remove(pluginId)
            registeredPlugins.remove(pluginId)
        }
        Log.d(TAG, "unregisterPlugin: $pluginId")
    }

    fun unregisterAll() {
        synchronized(this) {
            jsGuards.clear()
            declGuards.clear()
            registeredPlugins.clear()
        }
    }

    /** 已注册的插件 Guard 数量（用于 UI 展示） */
    fun guardCountOf(pluginId: String): Int = synchronized(this) {
        (jsGuards[pluginId]?.size ?: 0) + (declGuards[pluginId]?.size ?: 0)
    }

    // ==================== 检查（供 ToolRegistry 调用）====================

    /**
     * 检查所有插件 Guard。
     * 顺序：
     *  1. 代码式 JS Guard（插件主动注册的，优先级高）
     *  2. 声明式 Guard（manifest 兜底）
     *
     * @return null = 全部通过；非 null = 拒绝原因
     */
    suspend fun check(call: ToolCall, args: JsonObject, argsRaw: String): String? {
        // ---- 1. 代码式 ----
        val jsSnapshot = synchronized(this) {
            jsGuards.values.flatten().toList()
        }
        for (entry in jsSnapshot) {
            if (!matchTool(entry.toolFilter, call.name)) continue
            val approved = askJsGuard(entry, call) ?: continue
            if (!approved) {
                return "插件「${entry.pluginId}/${entry.guardName}」拒绝：${call.name}"
            }
        }

        // ---- 2. 声明式 ----
        val declSnapshot = synchronized(this) {
            declGuards.values.flatten().toList()
        }
        for (entry in declSnapshot) {
            if (!entry.toolRegex.containsMatchIn(call.name)) continue
            val argOk = entry.argRegex?.let { regex ->
                regex.containsMatchIn(argsRaw)
            } ?: true
            if (argOk) {
                return "插件「${entry.pluginId}/${entry.decl.name}」拦截：${entry.decl.reason}"
            }
        }

        return null
    }

    private fun matchTool(filter: String, toolName: String): Boolean {
        if (filter == "*" || filter.isBlank()) return true
        val parts = filter.split(",", "|", " ").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.any { it == toolName }
    }

    /**
     * 调 JS Guard 回调（超时 500ms）。
     * @return true=通过；false=拒绝；null=JS 桥未就绪（跳过此 guard）
     */
    private suspend fun askJsGuard(entry: JsGuardEntry, call: ToolCall): Boolean? {
        val cb = jsAskCallback ?: return null
        return try {
            withTimeout(JS_GUARD_TIMEOUT_MS) {
                cb(entry.pluginId, entry.guardName, call)
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "JS guard timeout (${entry.pluginId}/${entry.guardName}) → deny")
            false   // 超时 = 拒绝（安全优先）
        } catch (e: Exception) {
            Log.w(TAG, "JS guard error: ${e.message}", e)
            null   // 异常 = 跳过（不误伤）
        }
    }
}