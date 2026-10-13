package com.ai.android.agent

import com.ai.android.model.ToolCall
import com.ai.android.storage.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 守卫决策：单调（只能拒绝，不能放行）。
 */
sealed class GuardDecision {
    object Pass : GuardDecision()
    data class Deny(val reason: String) : GuardDecision()
}

interface ToolGuard {
    val name: String
    suspend fun check(call: ToolCall, meta: ToolMeta, args: JsonObject): GuardDecision
}

/** 本地 JsonObject 取字符串（避免依赖 agent.tools 包扩展的 import 顺序） */
private fun JsonObject.getStr(vararg keys: String): String {
    for (k in keys) {
        val v = this[k]?.jsonPrimitive?.contentOrNull
        if (!v.isNullOrBlank()) return v
    }
    return ""
}

// ==================== 1. 危险命令守卫 ====================

class DangerousCommandGuard : ToolGuard {
    override val name = "dangerous_command"

    private val patterns = listOf(
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*-[rR]"),
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/(\\s|\\$|;|&|\\|)"),
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/sdcard(\\s|\\$|/|\\*|;|&|\\|)"),
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/storage(\\s|\\$|/|\\*|;|&|\\|)"),
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*\\\$HOME"),
        Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*~"),
        Regex("\\bmkfs(\\.[a-z0-9]+)?\\b"),
        Regex("\\bdd\\s+if="),
        Regex(">\\s*/dev/block"),
        Regex("\\bmke2fs\\b"),
        Regex("(^|\\s|;|&|\\|)(su|sudo)(\\s|\\$)"),
        Regex("\\bpm\\s+uninstall\\b"),
        Regex("\\bpm\\s+clear\\b"),
        Regex("\\b(shutdown|reboot|halt|poweroff)\\b"),
        Regex("\\(\\)\\s*\\{.*\\}"),
    )

    override suspend fun check(call: ToolCall, meta: ToolMeta, args: JsonObject): GuardDecision {
        if (meta.scope != ToolScope.SYSTEM) return GuardDecision.Pass
        val cmd = args.getStr("command", "cmd", "code")
        if (cmd.isBlank()) return GuardDecision.Pass
        patterns.firstOrNull { it.containsMatchIn(cmd) }?.let {
            return GuardDecision.Deny(
                "命中危险命令模式 `${it.pattern}`。请先用 ask_user 向用户确认，用户回答\"确认\"后即可放行。"
            )
        }
        return GuardDecision.Pass
    }
}

// ==================== 2. 路径白名单守卫 ====================

class PathWhitelistGuard(
    private val settings: SettingsRepository,
    private val allowedRoots: () -> List<String>,
) : ToolGuard {
    override val name = "path_whitelist"

    private val writeTools = setOf(
        "write_file", "edit_file", "create_dir",
        "delete_file", "move_file", "copy_file", "rename_file",
    )

    override suspend fun check(call: ToolCall, meta: ToolMeta, args: JsonObject): GuardDecision {
        if (!settings.pathGuardEnabled()) return GuardDecision.Pass
        if (call.name !in writeTools) return GuardDecision.Pass

        val paths = mutableListOf<String>()
        args.getStr("path").takeIf { it.isNotBlank() }?.let { paths.add(it) }
        args.getStr("src").takeIf { it.isNotBlank() }?.let { paths.add(it) }
        args.getStr("dst").takeIf { it.isNotBlank() }?.let { paths.add(it) }
        if (paths.isEmpty()) return GuardDecision.Pass

        val allowed = allowedRoots()
        for (p in paths) {
            if (allowed.none { p == it || p.startsWith("$it/") }) {
                return GuardDecision.Deny(
                    "路径不在允许范围：`$p`\nAI 只能写入：\n" + allowed.joinToString("\n") { "- $it" }
                )
            }
        }
        return GuardDecision.Pass
    }
}

// ==================== 3. Shell 频率守卫 ====================

class ShellFrequencyGuard(
    private val maxPerMinute: () -> Int,
) : ToolGuard {
    override val name = "shell_frequency"
    private val timestamps = ArrayDeque<Long>()

    override suspend fun check(call: ToolCall, meta: ToolMeta, args: JsonObject): GuardDecision {
        if (meta.scope != ToolScope.SYSTEM) return GuardDecision.Pass
        val limit = maxPerMinute()
        if (limit <= 0) return GuardDecision.Pass

        val now = System.currentTimeMillis()
        synchronized(timestamps) {
            while (timestamps.isNotEmpty() && now - timestamps.first() > 60_000) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= limit) {
                return GuardDecision.Deny("Shell 类命令频率超限（$limit 次/分钟）")
            }
            timestamps.addLast(now)
        }
        return GuardDecision.Pass
    }
}

// 注：UserApprovalTokens 是独立对象，见 agent/UserApprovalTokens.kt
//     不再作为 Guard 接入（避免 suspend 复杂化）