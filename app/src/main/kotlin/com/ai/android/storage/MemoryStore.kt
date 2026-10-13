package com.ai.android.storage

import android.content.Context
import android.content.SharedPreferences

/**
 * 长期记忆存储（SharedPreferences 键值，跨会话保留）。
 *
 * key 前缀：
 *  - 全局：m:<key>
 *  - 项目：m:proj:<projectId>:<key>
 */
class MemoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("ai_memory", Context.MODE_PRIVATE)

    private val lock = Any()

    fun save(key: String, content: String) {
        synchronized(lock) {
            prefs.edit().putString(PREFIX + key.trim(), content).apply()
        }
    }

    fun read(key: String): String? = synchronized(lock) {
        prefs.getString(PREFIX + key.trim(), null)
    }

    fun list(): Map<String, String> = synchronized(lock) {
        prefs.all.entries
            .filter { it.key.startsWith(PREFIX) && it.value is String }
            // 排除项目记忆条目（去 m: 前缀后以 "proj:" 开头）
            .filter { !it.key.removePrefix(PREFIX).startsWith("proj:") }
            .associate { it.key.removePrefix(PREFIX) to (it.value as String) }
    }

    fun delete(key: String): Boolean {
        val k = PREFIX + key.trim()
        return synchronized(lock) {
            if (!prefs.contains(k)) return false
            prefs.edit().remove(k).apply()
            true
        }
    }

    fun clear() {
        synchronized(lock) {
            val edit = prefs.edit()
            prefs.all.keys.filter { it.startsWith(PREFIX) }.forEach { edit.remove(it) }
            edit.apply()
        }
    }

    /** 生成给系统提示词用的记忆摘要（过长时截断） */
    fun summaryBlock(): String {
        val all = list()
        if (all.isEmpty()) return ""
        val sb = StringBuilder("【长期记忆】\n")
        all.forEach { (k, v) ->
            val text = if (v.length > 300) v.take(300) + "..." else v
            sb.append("- [$k] ").append(text).append('\n')
        }
        return sb.toString()
    }

    // ==================== 项目作用域记忆 ====================

    /** 项目记忆前缀：proj:<id>:（不含全局 PREFIX） */
    fun projectMemoryPrefix(projectId: String) = "proj:$projectId:"

    /**
     * 项目内记忆列表（key 不含前缀，value 完整）。
     *
     * ⭐ 修复：prefs 里实际存的 key 是 "m:proj:<id>:<key>"，
     *    之前漏了 PREFIX 拼接，导致 startsWith("proj:<id>:") 永远匹配失败 → 列表恒为空。
     */
    fun projectMemoryList(projectId: String): Map<String, String> = synchronized(lock) {
        val fullPrefix = PREFIX + projectMemoryPrefix(projectId)   // "m:proj:<id>:"
        prefs.all.entries
            .filter { it.key.startsWith(fullPrefix) && it.value is String }
            .associate { it.key.removePrefix(fullPrefix) to (it.value as String) }
    }

    fun saveProjectMemory(projectId: String, key: String, content: String) {
        synchronized(lock) {
            prefs.edit().putString(
                PREFIX + projectMemoryPrefix(projectId) + key.trim(),
                content,
            ).apply()
        }
    }

    fun readProjectMemory(projectId: String, key: String): String? = synchronized(lock) {
        prefs.getString(PREFIX + projectMemoryPrefix(projectId) + key.trim(), null)
    }

    fun deleteProjectMemory(projectId: String, key: String): Boolean = synchronized(lock) {
        val k = PREFIX + projectMemoryPrefix(projectId) + key.trim()
        if (!prefs.contains(k)) {
            false
        } else {
            prefs.edit().remove(k).apply()
            true
        }
    }

    /** 项目记忆摘要块（注入 AgentCore 系统提示词用，按 projectId 取） */
    fun projectSummaryBlock(projectId: String): String {
        val all = projectMemoryList(projectId)
        if (all.isEmpty()) return ""
        val sb = StringBuilder("【项目记忆（项目ID $projectId）】\n")
        all.forEach { (k, v) ->
            val text = if (v.length > 300) v.take(300) + "..." else v
            sb.append("- [$k] ").append(text).append('\n')
        }
        return sb.toString()
    }

    companion object {
        private const val PREFIX = "m:"
    }
}