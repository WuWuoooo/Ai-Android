package com.ai.android.agent

import android.util.Log
import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.TokenStats
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.provider.ProviderManager
import com.ai.android.provider.StreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import java.util.UUID

sealed class AgentEvent {
    data class RoundStart(val round: Int) : AgentEvent()
    data class ReasoningDelta(val text: String) : AgentEvent()
    data class ContentDelta(val text: String) : AgentEvent()
    data class MessageDone(val message: ChatMessage) : AgentEvent()
    data class ToolStart(val call: ToolCall) : AgentEvent()
    data class ToolEnd(val result: ToolResult) : AgentEvent()
    data class ToolMessage(val message: ChatMessage) : AgentEvent()
    data class Stats(val stats: TokenStats) : AgentEvent()
    data class State(val state: AgentState) : AgentEvent()
    data class Error(val msg: String) : AgentEvent()
    data object Done : AgentEvent()
}

data class AgentConfig(
    val maxRounds: Int = 30,
    val temperature: Float = 0.3f,
    val reasoningEffort: String = "",
    val systemPromptExtra: String = "",
    val networkRetries: Int = 2,
    val contextCompressRounds: Int = 0,
)

data class SubAgentResult(
    val success: Boolean,
    val finalText: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val cacheHitTokens: Int,
    val rounds: Int,
)

class AgentCore(
    private val providers: ProviderManager,
    private val registry: ToolRegistry,
    private val config: () -> AgentConfig = { AgentConfig() },
    private val memoryPromptProvider: (Conversation) -> String = { "" },
    /** ⭐ 新增：Skill 清单注入（返回空串则不注入） */
    private val skillsPromptProvider: () -> String = { "" },
) {

    private class CompState(var count: Int = 0, var summary: String = "")

    fun run(
        conversation: Conversation,
        toolBlacklist: Set<String> = emptySet(),
        maxRoundsOverride: Int? = null,
    ): Flow<AgentEvent> = channelFlow {
        val cfg = config()
        val effectiveMax = maxRoundsOverride ?: cfg.maxRounds
        var round = 0
        val comp = CompState()
        try {
            while (true) {
                round++
                if (effectiveMax > 0 && round > effectiveMax) {
                    send(AgentEvent.Error("已达到最大循环次数（${effectiveMax}），停止执行"))
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }
                send(AgentEvent.RoundStart(round))
                val provider = providers.active
                    ?: throw IllegalStateException("没有可用的 Provider，请先到「设置」中填入 API Key")

                if (cfg.contextCompressRounds > 0 && round > 1 &&
                    (round - 1) % cfg.contextCompressRounds == 0
                ) {
                    runCatching { compressHistory(provider, conversation, comp) }
                }

                send(AgentEvent.State(AgentState(
                    status = AgentState.Status.THINKING,
                    progressText = "思考中（第 $round 轮）",
                    startedAt = System.currentTimeMillis(),
                )))

                var request = buildRequest(conversation, cfg, comp)
                val content = StringBuilder()
                val reasoning = StringBuilder()
                var toolCalls: List<ToolCall> = emptyList()
                var stats: TokenStats? = null
                var streamError: String? = null
                var finishReason: String = ""
                var autoContinueRounds = 0

                var attempt = 0
                while (true) {
                    content.clear(); reasoning.clear()
                    toolCalls = emptyList(); stats = null; streamError = null; finishReason = ""

                    provider.chatStream(request, providers.buildTools(), cfg.reasoningEffort, cfg.temperature)
                        .collect { ev ->
                            when (ev) {
                                is StreamEvent.Reasoning -> {
                                    reasoning.append(ev.text)
                                    send(AgentEvent.ReasoningDelta(ev.text))
                                }
                                is StreamEvent.Content -> {
                                    content.append(ev.text)
                                    send(AgentEvent.ContentDelta(ev.text))
                                }
                                is StreamEvent.ToolCalls -> { toolCalls = ev.calls }
                                is StreamEvent.Stats -> { stats = ev.stats; send(AgentEvent.Stats(ev.stats)) }
                                is StreamEvent.Error -> streamError = ev.msg
                                is StreamEvent.FinishInfo -> finishReason = ev.reason
                                StreamEvent.Done -> Unit
                            }
                        }

                    val cleanEnd = finishReason in setOf(
                        "stop", "content_filter", "tool_calls", "function_call",
                        "end_turn", "stop_sequence", "tool_use",
                    )
                    val explicitTrunc = finishReason == "length" || finishReason == "max_tokens"
                    val missingSignalButLong = !cleanEnd && streamError == null &&
                        toolCalls.isEmpty() && content.length >= 300
                    val shouldContinue = (explicitTrunc || missingSignalButLong) && autoContinueRounds < 6
                    if (shouldContinue) {
                        autoContinueRounds++
                        val partialMsg = ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = content.toString(),
                            reasoning = reasoning.toString(),
                            toolCalls = toolCalls,
                            tokenStats = stats,
                        )
                        conversation.addMessage(partialMsg)
                        send(AgentEvent.MessageDone(partialMsg))
                        val contMsg = ChatMessage.user("（请继续输出剩余内容，不要重复已输出的部分）")
                        conversation.addMessage(contMsg)
                        send(AgentEvent.ToolMessage(contMsg))
                        request = buildRequest(conversation, cfg, comp)
                        continue
                    }

                    val err = streamError
                    if (err == null) break

                    val isClientError = Regex("""HTTP 4\d\d""").containsMatchIn(err) &&
                        !err.contains("HTTP 408") && !err.contains("HTTP 429")

                    if (isClientError) {
                        if (attempt < 1) {
                            attempt++
                            delay(800L)
                            continue
                        }
                        send(AgentEvent.Error(err))
                        send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                        send(AgentEvent.Done)
                        return@channelFlow
                    }

                    if (attempt >= cfg.networkRetries) {
                        if (content.isNotEmpty() || reasoning.isNotEmpty() || toolCalls.isNotEmpty()) {
                            val partial = ChatMessage(
                                role = ChatMessage.Role.ASSISTANT,
                                content = content.toString(),
                                reasoning = reasoning.toString(),
                                toolCalls = toolCalls,
                                tokenStats = stats,
                            )
                            conversation.addMessage(partial)
                            send(AgentEvent.MessageDone(partial))
                            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                            send(AgentEvent.Error("连接中断（已自动重试 ${attempt} 次），以上为中断前已生成的内容：$err"))
                        } else {
                            send(AgentEvent.Error(err))
                            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
                        }
                        send(AgentEvent.Done)
                        return@channelFlow
                    }
                    attempt++
                    delay(800L * attempt)
                }

                val assistantMsg = ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = content.toString(),
                    reasoning = reasoning.toString(),
                    toolCalls = toolCalls,
                    tokenStats = stats,
                )
                conversation.addMessage(assistantMsg)
                send(AgentEvent.MessageDone(assistantMsg))

                if (toolCalls.isEmpty()) {
                    send(AgentEvent.State(AgentState(status = AgentState.Status.COMPLETED)))
                    break
                }

                toolCalls.forEach { call ->
                    send(AgentEvent.State(AgentState(
                        status = AgentState.Status.TOOL_CALLING,
                        currentTool = call.name,
                        progressText = "执行工具: ${call.name}",
                        startedAt = System.currentTimeMillis(),
                    )))
                    send(AgentEvent.ToolStart(call))
                }

                val blacklisted = toolCalls.filter { it.name in toolBlacklist }
                val allowed = toolCalls.filter { it.name !in toolBlacklist }

                val serial = allowed.filterNot { registry.isParallelizable(it.name) }
                val parallel = allowed.filter { registry.isParallelizable(it.name) }

                val results = HashMap<String, ToolResult>(toolCalls.size)

                for (call in blacklisted) {
                    results[call.id] = ToolResult(
                        call.id, call.name,
                        "❌ 工具「${call.name}」在当前上下文不可用（被过滤）", false, 0
                    )
                }

                for (call in serial) {
                    results[call.id] = registry.execute(call)
                }

                if (parallel.isNotEmpty()) {
                    val jobs = parallel.map { call -> async { call.id to registry.execute(call) } }
                    jobs.awaitAll().forEach { (id, r) -> results[id] = r }
                }

                toolCalls.forEach { call ->
                    val result = results[call.id]
                        ?: ToolResult(call.id, call.name, "⚠️ 工具未执行", false, 0)
                    send(AgentEvent.ToolEnd(result))
                    val toolMsg = ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = result.output,
                        toolResults = listOf(result),
                    )
                    conversation.addMessage(toolMsg)
                    send(AgentEvent.ToolMessage(toolMsg))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(AgentEvent.Error(e.message ?: e.javaClass.simpleName))
            send(AgentEvent.State(AgentState(status = AgentState.Status.ERROR)))
        }
        send(AgentEvent.Done)
    }

    suspend fun runSubAgent(
        task: String,
        blacklist: Set<String>,
        maxRounds: Int = 5,
    ): SubAgentResult {
        val tempConv = Conversation(
            id = "sub-${UUID.randomUUID().toString().take(8)}",
            title = "[子代理]",
        )
        tempConv.addMessage(ChatMessage.user(task))

        var finalText = ""
        var promptT = 0
        var completionT = 0
        var cacheT = 0
        var rounds = 0

        run(tempConv, toolBlacklist = blacklist, maxRoundsOverride = maxRounds)
            .collect { ev ->
                when (ev) {
                    is AgentEvent.Stats -> {
                        promptT += ev.stats.promptTokens
                        completionT += ev.stats.completionTokens
                        cacheT += ev.stats.cacheHitTokens
                    }
                    is AgentEvent.MessageDone -> {
                        if (ev.message.content.isNotBlank()) finalText = ev.message.content
                    }
                    is AgentEvent.RoundStart -> rounds = ev.round
                    is AgentEvent.Error -> {
                        Log.w("SubAgent", "sub agent error: ${ev.msg}")
                    }
                    else -> Unit
                }
            }

        return SubAgentResult(
            success = finalText.isNotBlank(),
            finalText = finalText,
            promptTokens = promptT,
            completionTokens = completionT,
            cacheHitTokens = cacheT,
            rounds = rounds,
        )
    }

    suspend fun summarizeTitle(userText: String, assistantText: String): String {
        val provider = providers.active ?: return userText.take(16)
        val prompt = AgentPrompts.titleSummarizePrompt(userText, assistantText)
        return runCatching {
            provider.chatOnce(listOf(ChatMessage.user(prompt)), temperature = 0.1f).trim()
                .take(20).ifBlank { userText.take(16) }
        }.getOrDefault(userText.take(16))
    }

    suspend fun continueFrom(conversation: Conversation): String {
        val provider = providers.active ?: throw IllegalStateException("没有可用的 Provider")

        val lastAssistantIdx = conversation.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return ""
        val lastContent = conversation.messages[lastAssistantIdx].content

        val history = conversation.messages.take(lastAssistantIdx + 1)
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .filter { !(it.role == ChatMessage.Role.ASSISTANT && it.content.isBlank()
                && it.toolCalls.isEmpty() && it.reasoning.isBlank()) }
            .filter { !(it.role == ChatMessage.Role.TOOL && it.content.isBlank()) }

        val memory = runCatching { memoryPromptProvider(conversation) }.getOrDefault("")
        val skills = runCatching { skillsPromptProvider() }.getOrDefault("")

        val baseSystemPrompt = AgentPrompts.systemPrompt("", "", memory, skills)
        val continueInstruction = AgentPrompts.continueInstruction(
            baseSystemPrompt,
            lastContent.takeLast(80),
        )

        val req = buildList {
            add(ChatMessage.system(continueInstruction))
            addAll(history)
            add(ChatMessage.user(AgentPrompts.continueUserMessage()))
        }
        val out = provider.chatOnce(req, temperature = 0.3f).trim()
        val prefix = lastContent.takeLast(50).trim()
        return if (out.startsWith(prefix)) out.removePrefix(prefix).trimStart() else out
    }

    private fun buildRequest(
        conversation: Conversation,
        cfg: AgentConfig,
        comp: CompState = CompState(),
    ): List<ChatMessage> {
        val history = conversation.messages.toList()
        val sysExtra = history.filter { it.role == ChatMessage.Role.SYSTEM }
            .joinToString("\n") { it.content }
        val memoryPrompt = runCatching { memoryPromptProvider(conversation) }.getOrDefault("")
        val skillsPrompt = runCatching { skillsPromptProvider() }.getOrDefault("")
        val rest = history
            .drop(comp.count.coerceIn(0, history.size))
            .filter { it.role != ChatMessage.Role.SYSTEM }
            .filter { !(it.role == ChatMessage.Role.ASSISTANT && it.content.isBlank() && it.toolCalls.isEmpty() && it.reasoning.isBlank()) }
            .filter { !(it.role == ChatMessage.Role.TOOL && it.content.isBlank()) }
        return buildList {
            add(ChatMessage.system(AgentPrompts.systemPrompt(cfg.systemPromptExtra, sysExtra, memoryPrompt, skillsPrompt)))
            if (comp.summary.isNotBlank()) {
                add(ChatMessage.system("【较早对话的自动摘要】\n" + comp.summary))
            }
            addAll(rest)
        }
    }

    private suspend fun compressHistory(
        provider: com.ai.android.provider.AIProvider,
        conversation: Conversation,
        comp: CompState,
    ) {
        val tailKeep = 6
        val history = conversation.messages
        val from = comp.count.coerceIn(0, history.size)
        val to = (history.size - tailKeep).coerceAtLeast(from)
        if (to - from < 3) return
        val slice = history.subList(from, to).map { m ->
            when {
                m.role == ChatMessage.Role.TOOL -> "工具[${m.toolResults.firstOrNull()?.toolCallId}]: ${m.content.take(400)}"
                m.role == ChatMessage.Role.SYSTEM -> ""
                else -> "${m.role.name}: ${m.content.take(400)}" +
                    if (m.toolCalls.isNotEmpty()) " 〔工具: ${m.toolCalls.joinToString(",") { it.name }}〕" else ""
            }
        }.filter { it.isNotBlank() }
        if (slice.isEmpty()) return
        val prev = comp.summary
        val prompt = AgentPrompts.compressPrompt(prev, slice.joinToString("\n"))
        val out = runCatching { provider.chatOnce(listOf(ChatMessage.user(prompt)), temperature = 0.2f) }
            .getOrNull()?.trim()
        if (!out.isNullOrBlank()) {
            comp.summary = if (prev.isBlank()) out else "$prev\n$out"
            comp.count = to
        }
    }
}