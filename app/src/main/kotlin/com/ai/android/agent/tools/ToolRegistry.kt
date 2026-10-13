package com.ai.android.agent

import android.content.Context
import android.util.Log
import com.ai.android.agent.tools.*
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.storage.MemoryStore
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolRegistry(
    private val context: Context,
    private val settings: SettingsRepository,
) {

    private val TAG = "ToolRegistry"
    private val executors = LinkedHashMap<String, ToolExecutor>()

    var askUserHandler: (suspend (question: String, options: String) -> String)? = null
    var convIdProvider: (() -> String)? = null
    var projectIdProvider: (() -> String)? = null
    var convPermissionLevelProvider: (() -> Int)? = null

    @Volatile var agentCoreRef: AgentCore? = null
    @Volatile var onSubAgentToken: ((prompt: Int, completion: Int, cache: Int) -> Unit)? = null
    @Volatile var onSubAgentTaskCompleted: ((SubAgentTaskStore.Task) -> Unit)? = null

    @Volatile private var appScopeRef: CoroutineScope? = null
    fun setAppScope(scope: CoroutineScope) { appScopeRef = scope }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    val aiControlGuard = AiControlGuard(context, settings)

    private val guards: List<ToolGuard> = listOf(
        DangerousCommandGuard(),
        PathWhitelistGuard(settings) {
            listOf(
                "/sdcard",
                "/storage/emulated/0",
                context.filesDir.absolutePath,
                context.cacheDir.absolutePath,
                "/data/local/tmp",
            )
        },
        ShellFrequencyGuard { settings.shellFrequencyLimit() },
    )

    fun stopAiMirror() { aiControlGuard.stopAll() }
    fun register(tool: ToolExecutor) { executors[tool.name] = tool }
    fun names(): List<String> = executors.keys.toList()
    fun has(name: String): Boolean = executors.containsKey(name)
    fun isParallelizable(name: String): Boolean = ToolCatalog.isParallelizable(name)

    fun registerDefaultTools(memory: MemoryStore) {
        register(ReadFileTool(settings))
        register(WriteFileTool())
        register(EditFileTool())
        register(ListFilesTool())
        register(ListDirTreeTool())
        register(SearchFilesTool())
        register(CreateDirTool())
        register(DeleteFileTool())
        register(MoveFileTool())
        register(CopyFileTool())
        register(RenameFileTool())
        register(ShellTool())
        register(JsTool(context))
        register(GetCurrentTimeTool())
        register(WebSearchTool())
        register(FetchUrlTool())

        register(TerminalExecTool(context, settings) { convIdProvider?.invoke().orEmpty() })
        register(TerminalReadTool({ convIdProvider?.invoke().orEmpty() }, context, settings))
        register(TerminalListSessionsTool({ convIdProvider?.invoke().orEmpty() }, context))
        register(TerminalCloseSessionTool(context))
        register(TerminalReadBgTool({ convIdProvider?.invoke().orEmpty() }, context, settings))
        register(TerminalWaitTool({ convIdProvider?.invoke().orEmpty() }, context, settings))
        register(TerminalListBgTool({ convIdProvider?.invoke().orEmpty() }, context, settings))

        // ⭐ Skill 工具
        register(ListSkillsTool(context))
        register(LoadSkillTool(context))

        register(AccessibilityTool())
        register(SchedulerTool(context))
        register(ListTasksTool(context))
        register(CancelTaskTool(context))
        register(SaveMemoryTool(memory))
        register(ReadMemoryTool(memory))
        register(ListMemoryTool(memory))
        register(DeleteMemoryTool(memory))
        register(AskUserTool { q, o -> askUserHandler?.invoke(q, o) ?: "（当前没有可用的用户界面，无法提问）" })
        register(GenerateImageTool())
        register(GenerateVideoTool())
        register(GenerateSpeechTool())
        register(AnalyzeImageTool())
        register(AnalyzeAudioTool())
        register(AnalyzeVideoTool())
        register(ProjectMemoryTool(memory) { projectIdProvider?.invoke().orEmpty() })
        register(SubAgentTool(
            registry = this,
            settings = settings,
            convIdProvider = { convIdProvider?.invoke().orEmpty() },
            coreProvider = { agentCoreRef },
            scopeProvider = { appScopeRef },
            onTokenUsage = { p, c, cache -> onSubAgentToken?.invoke(p, c, cache) },
            onTaskCompleted = { task -> onSubAgentTaskCompleted?.invoke(task) },
        ))
        register(SubAgentStatusTool())
        register(SubAgentResultTool())
        register(SubAgentListTool { convIdProvider?.invoke().orEmpty() })
    }

    private suspend fun pluginGuardCheck(call: ToolCall, rawArgs: String): String? {
        return try {
            val argsObj = if (rawArgs.isBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(rawArgs).jsonObject
            PluginGuardBridge.check(call, argsObj, rawArgs)
        } catch (e: Exception) {
            Log.w(TAG, "pluginGuardCheck failed", e)
            null
        }
    }

    suspend fun execute(call: ToolCall): ToolResult {
        val start = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - start
        val convId = convIdProvider?.invoke().orEmpty()

        val executor = executors[call.name]
            ?: return ToolResult(call.id, call.name,
                "❌ 未知工具: ${call.name}（可用: ${names().joinToString()}）", false, elapsed())

        val args: JsonObject = try {
            if (call.arguments.isBlank()) JsonObject(emptyMap())
            else json.parseToJsonElement(call.arguments).jsonObject
        } catch (e: Exception) {
            return ToolResult(call.id, call.name,
                "❌ 参数 JSON 解析失败：${e.message}\n原始参数：${call.arguments.take(500)}", false, elapsed())
        }

        val convLevel = convPermissionLevelProvider?.invoke() ?: 2
        val requiredLevel = ToolCatalog.minLevelOf(call.name)
        if (convLevel < requiredLevel) {
            val reason = "本对话权限为「${ToolCatalog.levelName(convLevel)}」，" +
                "不允许调用「${call.name}」（需「${ToolCatalog.levelName(requiredLevel)}」或更高）。"
            SessionLog.recordGuardDeny(convId, call, "permission_level", reason)
            return ToolResult(call.id, call.name, "🛑 $reason", false, elapsed())
        }

        val fullyOpen = convLevel >= 3
        val meta = ToolCatalog.metaOf(call.name)

        if (settings.resultCacheEnabled()) {
            ResultCache.get(call)?.let { cached ->
                SessionLog.recordCall(convId, call)
                SessionLog.recordResult(convId, ToolResult(call.id, call.name, "[cache] $cached", true, elapsed()))
                return ToolResult(call.id, call.name, cached, true, elapsed())
            }
        }

        val hasUserApproval = UserApprovalTokens.hasAndConsume(convId)

        if (!fullyOpen) {
            for (guard in guards) {
                if (hasUserApproval && guard is DangerousCommandGuard) continue
                val decision = try {
                    guard.check(call, meta, args)
                } catch (e: Exception) {
                    Log.w(TAG, "guard ${guard.name} threw", e)
                    GuardDecision.Pass
                }
                if (decision is GuardDecision.Deny) {
                    SessionLog.recordGuardDeny(convId, call, guard.name, decision.reason)
                    return ToolResult(call.id, call.name, "🛑 [${guard.name}] ${decision.reason}", false, elapsed())
                }
            }
            pluginGuardCheck(call, call.arguments)?.let { reason ->
                SessionLog.recordGuardDeny(convId, call, "plugin_guard", reason)
                return ToolResult(call.id, call.name, "🛑 $reason", false, elapsed())
            }
        }

        if (call.name == "accessibility_control") {
            val block = aiControlGuard.preCheck(args, fullyOpen) { q, o ->
                askUserHandler?.invoke(q, o) ?: "（用户无界面，默认放行）"
            }
            if (block != null) {
                SessionLog.recordGuardDeny(convId, call, "ai_control", block)
                return ToolResult(call.id, call.name, "⚠️ $block", false, elapsed())
            }
        }

        val needsApproval = !fullyOpen &&
            ToolCatalog.requiresApproval(meta.scope) &&
            meta.scope != ToolScope.DEVICE &&
            !hasUserApproval
        if (needsApproval) {
            val approved = ApprovalManager.request(convId, call, meta, args, askUserHandler)
            if (!approved) {
                return ToolResult(call.id, call.name, "用户拒绝了「${call.name}」", false, elapsed())
            }
        }

        SessionLog.recordCall(convId, call)
        val result = try {
            withTimeout(meta.timeoutMs) {
                val out = executor.execute(args)
                ToolResult(call.id, call.name, out.ifBlank { "（执行成功，无输出）" }, true, elapsed())
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult(call.id, call.name, "⏱ 工具执行超时（${meta.timeoutMs}ms）", false, elapsed())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ToolResult(call.id, call.name, "❌ 执行失败: $msg", false, elapsed())
        }

        val finalResult = if (settings.uiOutputClampEnabled()) {
            result.copy(output = postProcessOutput(result.output))
        } else result

        if (finalResult.success && settings.resultCacheEnabled()) {
            ResultCache.put(call, finalResult.output)
        }
        if (finalResult.success && (meta.scope == ToolScope.WRITE || meta.scope == ToolScope.SYSTEM)) {
            ResultCache.invalidateAfterWrite()
        }

        if (call.name == "ask_user" && finalResult.success) {
            if (isUserConfirmAnswer(finalResult.output)) {
                UserApprovalTokens.grant(convId)
            }
        }

        SessionLog.recordResult(convId, finalResult)
        return finalResult
    }

    private fun isUserConfirmAnswer(answer: String): Boolean {
        val a = answer.trim()
        if (a.isEmpty()) return false
        val keywords = listOf("确认", "允许", "同意", "是", "确定", "OK", "ok", "yes", "Yes", "YES", "继续")
        return keywords.any { a.contains(it) } && a.length <= 60
    }

    private fun postProcessOutput(output: String): String {
        var s = output
        s = s.replace(Regex("\u001B\\[[0-9;]*[a-zA-Z]"), "")
        s = s.replace(Regex("\u001B\\][0-9;]*\u0007"), "")
        s = s.replace("\u0000", "")
        return s
    }
}

// ==================== 记忆工具 ====================

class SaveMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "save_memory"
    override val parallelizable = false
    override suspend fun execute(args: JsonObject): String {
        val key = args.requireStr("key", "记忆标识，英文短词，例如 user_name")
        val content = args.str("content")
        store.save(key, content)
        return "✅ 已保存记忆 [$key]（${content.length} 字符）"
    }
}

class ReadMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "read_memory"
    override suspend fun execute(args: JsonObject): String {
        val key = args.requireStr("key", "记忆标识，例如 user_name")
        return store.read(key) ?: "没有找到记忆 [$key]"
    }
}

class ListMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "list_memory"
    override suspend fun execute(args: JsonObject): String {
        val all = store.list()
        if (all.isEmpty()) return "当前没有保存任何长期记忆"
        return all.entries.joinToString("\n\n") { (k, v) ->
            val brief = if (v.length > 200) v.take(200) + "..." else v
            "[$k]\n$brief"
        }
    }
}

class DeleteMemoryTool(private val store: MemoryStore) : ToolExecutor {
    override val name = "delete_memory"
    override val parallelizable = false
    override suspend fun execute(args: JsonObject): String {
        val key = args.requireStr("key", "记忆标识，与 save/read_memory 的 key 一致")
        val existed = store.read(key) != null
        val ok = store.delete(key)
        return if (ok) "✅ 已删除记忆 [$key]"
        else if (existed) "❌ 删除记忆 [$key] 失败"
        else "⚠️ 记忆 [$key] 不存在（无需删除）"
    }
}

class ProjectMemoryTool(
    private val store: MemoryStore,
    private val projectId: () -> String,
) : ToolExecutor {
    override val name = "project_memory"
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String {
        val pid = projectId()
        if (pid.isBlank()) {
            return "⚠️ 当前对话不属于任何项目，无法使用项目记忆。"
        }
        return when (val action = args.strAny("action", "op", def = "list").lowercase()) {
            "list" -> {
                val all = store.projectMemoryList(pid)
                if (all.isEmpty()) "当前项目（$pid）还没有任何项目记忆"
                else all.entries.joinToString("\n\n") { (k, v) ->
                    val brief = if (v.length > 300) v.take(300) + "..." else v
                    "[$k]\n$brief"
                }
            }
            "save", "add", "set", "write" -> {
                val key = args.requireStr("key", "项目记忆标识")
                val content = args.str("content")
                store.saveProjectMemory(pid, key, content)
                "✅ 已写入项目记忆 [$key]（${content.length} 字符）"
            }
            "read", "get" -> {
                val key = args.requireStr("key", "项目记忆标识")
                store.readProjectMemory(pid, key) ?: "项目记忆里没有 [$key]"
            }
            "delete", "remove", "del" -> {
                val key = args.requireStr("key", "项目记忆标识")
                val existed = store.readProjectMemory(pid, key) != null
                val ok = store.deleteProjectMemory(pid, key)
                if (ok) "✅ 已删除项目记忆 [$key]"
                else if (existed) "❌ 删除项目记忆 [$key] 失败"
                else "⚠️ 项目记忆 [$key] 不存在（无需删除）"
            }
            else -> "❌ 未知 action：$action（可选 list / save / read / delete）"
        }
    }
}

class AskUserTool(private val handler: suspend (String, String) -> String) : ToolExecutor {
    override val name = "ask_user"
    override val parallelizable = false
    override suspend fun execute(args: JsonObject): String {
        val question = args.requireStr("question", "要向用户确认的问题")
        return handler(question, args.str("options"))
    }
}