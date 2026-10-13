package com.ai.android.agent.tools

import com.ai.android.agent.AgentCore
import com.ai.android.agent.SessionLog
import com.ai.android.agent.SubAgentTaskStore
import com.ai.android.agent.ToolRegistry
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ⭐ 子代理工具。
 *
 * 参数：
 *  - task：必填，任务描述
 *  - background：可选，true=后台执行（立即返回 taskId，不阻塞主 Agent）
 *  - exclude_tools：可选，逗号分隔的工具名黑名单
 *
 * 强制黑名单：subagent / ask_user（防递归 + UI 冲突）
 */
class SubAgentTool(
    private val registry: ToolRegistry,
    private val settings: SettingsRepository,
    private val convIdProvider: () -> String,
    private val coreProvider: () -> AgentCore?,
    /** ⭐ 后台任务执行作用域（由 ToolRegistry 注入） */
    private val scopeProvider: () -> CoroutineScope? = { null },
    /** ⭐ token 上报回调 */
    private val onTokenUsage: ((prompt: Int, completion: Int, cache: Int) -> Unit)? = null,
    /** ⭐ 后台任务完成回调（用于向对话推送通知） */
    private val onTaskCompleted: ((SubAgentTaskStore.Task) -> Unit)? = null,
) : ToolExecutor {

    override val name = "subagent"
    override val parallelizable = true

    private val forcedBlacklist = setOf("subagent", "ask_user")

    override suspend fun execute(args: JsonObject): String {
        if (!settings.subAgentEnabled()) {
            return "⚠️ 子代理功能已在设置中关闭。若需使用，请到 设置 → Harness / 安全 → 子代理 打开开关。"
        }
        val core = coreProvider()
            ?: return "❌ 子代理核心未就绪（可能还在初始化）"

        val task = args.requireStr("task", "要委派给子代理的任务描述（写得越具体越好）")
        val background = args.bool("background", false)
        val excludeRaw = args.str("exclude_tools", "")
        val userExclude = excludeRaw
            .split(",", "，", " ", "\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        val blacklist = userExclude + forcedBlacklist

        val availableTools = registry.names().filterNot { it in blacklist }
        if (availableTools.isEmpty()) {
            return "❌ 子代理没有任何可用工具（全部被排除）。请检查 exclude_tools 参数。"
        }

        return if (background) runBackground(core, task, blacklist)
        else runForeground(core, task, blacklist)
    }

    // ==================== 前台同步（原行为）====================

    private suspend fun runForeground(
        core: AgentCore,
        task: String,
        blacklist: Set<String>,
    ): String {
        val convId = convIdProvider()
        val beginCall = ToolCall(
            id = "sub-${System.nanoTime()}",
            name = "subagent/begin",
            arguments = buildJsonObject { put("task", task.take(100)) }.toString(),
        )
        SessionLog.recordCall(convId, beginCall)

        val result = try {
            core.runSubAgent(task = task, blacklist = blacklist, maxRounds = 5)
        } catch (e: Exception) {
            SessionLog.recordError(convId, "subagent", e.message ?: "unknown")
            return "❌ 子代理执行失败：${e.message}"
        }

        if (settings.subAgentTokenCountingEnabled()) {
            runCatching {
                onTokenUsage?.invoke(result.promptTokens, result.completionTokens, result.cacheHitTokens)
            }
        }

        SessionLog.recordResult(
            convId,
            ToolResult(
                toolCallId = beginCall.id,
                name = "subagent/end",
                output = "rounds=${result.rounds} prompt=${result.promptTokens} " +
                    "completion=${result.completionTokens} cache=${result.cacheHitTokens}\n" +
                    result.finalText.take(2000),
                success = result.success,
                durationMs = 0,
            ),
        )

        return buildString {
            if (!result.success) {
                appendLine("❌ 子代理未能完成任务（或产出为空）")
            } else {
                appendLine("✅ 子代理已完成任务")
            }
            appendLine()
            appendLine("【子代理总结】")
            appendLine(result.finalText.ifBlank { "（无输出）" })
            appendLine()
            appendLine("---")
            append(
                "子代理统计：${result.rounds}轮 · " +
                    "↑${result.promptTokens} ↓${result.completionTokens} · " +
                    "缓存命中 ${result.cacheHitTokens}"
            )
        }
    }

    // ==================== 后台异步（新增）====================

    private fun runBackground(
        core: AgentCore,
        task: String,
        blacklist: Set<String>,
    ): String {
        val scope = scopeProvider()
            ?: return "❌ 后台模式不可用（作用域未注入）。请改用 background=false 同步执行。"

        val convId = convIdProvider()
        val taskId = SubAgentTaskStore.start(convId, task)

        scope.launch(Dispatchers.Default) {
            val result = try {
                core.runSubAgent(task = task, blacklist = blacklist, maxRounds = 5)
            } catch (e: Exception) {
                SubAgentTaskStore.fail(taskId, e.message ?: "unknown")
                notifyCompleted(taskId)
                return@launch
            }
            SubAgentTaskStore.finish(taskId, result)
            if (settings.subAgentTokenCountingEnabled()) {
                runCatching {
                    onTokenUsage?.invoke(result.promptTokens, result.completionTokens, result.cacheHitTokens)
                }
            }
            notifyCompleted(taskId)
        }

        return buildString {
            append("✅ 子代理已在后台启动\n")
            append("taskId: ").append(taskId).append('\n')
            append("任务: ").append(task.take(120)).append('\n')
            append('\n')
            append("你（AI）可以继续处理其他事情，稍后：\n")
            append("  - subagent_status taskId=$taskId  查看状态\n")
            append("  - subagent_result taskId=$taskId  拿最终总结\n")
            append("任务完成时系统会向对话推送一条通知。")
        }
    }

    private fun notifyCompleted(taskId: String) {
        val t = SubAgentTaskStore.get(taskId) ?: return
        if (t.notified) return
        t.notified = true
        runCatching { onTaskCompleted?.invoke(t) }
    }
}

// ==================== ⭐ 三个子代理任务查询工具 ====================

/** 查询后台子代理任务状态 */
class SubAgentStatusTool : ToolExecutor {
    override val name = "subagent_status"
    override suspend fun execute(args: JsonObject): String {
        val taskId = args.requireStr("taskId", "后台子代理任务 id（subagent background=true 返回的）")
        val t = SubAgentTaskStore.get(taskId) ?: return "❌ 未找到任务 taskId=$taskId"
        return SubAgentTaskStore.describe(t)
    }
}

/** 取后台子代理最终结果 */
class SubAgentResultTool : ToolExecutor {
    override val name = "subagent_result"
    override suspend fun execute(args: JsonObject): String {
        val taskId = args.requireStr("taskId", "后台子代理任务 id")
        val t = SubAgentTaskStore.get(taskId) ?: return "❌ 未找到任务 taskId=$taskId"
        return when (t.status) {
            SubAgentTaskStore.Status.RUNNING ->
                "⏳ 任务仍在运行中（已耗时 ${
                    (System.currentTimeMillis() - t.startedAt) / 1000
                }s）。稍后再试。"
            SubAgentTaskStore.Status.ERROR -> {
                val detail = t.error.ifBlank { t.finalText.take(500) }
                "❌ 任务失败：$detail"
            }
            SubAgentTaskStore.Status.DONE -> buildString {
                appendLine("✅ 任务完成（${t.rounds}轮 · ↑${t.promptTokens} ↓${t.completionTokens} · 缓存 ${t.cacheHitTokens}）")
                appendLine()
                appendLine("【子代理总结】")
                appendLine(t.finalText.ifBlank { "（无输出）" })
            }
        }
    }
}

/** 列出当前对话的后台子代理任务 */
class SubAgentListTool(private val convIdProvider: () -> String) : ToolExecutor {
    override val name = "subagent_list"
    override suspend fun execute(args: JsonObject): String {
        val convId = convIdProvider()
        val list = SubAgentTaskStore.list(convId)
        if (list.isEmpty()) return "（当前对话没有后台子代理任务）"
        return list.joinToString("\n\n") { SubAgentTaskStore.describe(it) }
    }
}