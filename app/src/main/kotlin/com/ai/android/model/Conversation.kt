package com.ai.android.model

import kotlinx.serialization.Serializable

@Serializable
data class Conversation(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = System.currentTimeMillis(),
    val providerName: String = "",
    val model: String = "",
    /** 置顶 */
    var pinned: Boolean = false,
    /** 所属项目文件夹 ID（空 = 未归入项目） */
    var projectId: String = "",
    /** ⭐ 最近一次 Agent 任务结束时的会话统计（按对话隔离 + 持久化） */
    var lastSessionStats: SessionStats = SessionStats(),
    /**
     * ⭐ 本对话的权限等级（Harness）：
     *  - 0 = 读写（只读 + 文件/记忆读写 + 多模态生成 + subagent）
     *  - 1 = 设备（读写 + accessibility_control）
     *  - 2 = 系统（设备 + shell/js/terminal + 文件删除移动等）【默认】
     *  - 3 = 完全访问（系统 + 跳过所有审批与护栏）
     */
    var permissionLevel: Int = 2,
) {
    val lastMessageTime: Long
        get() = messages.lastOrNull()?.timestamp ?: createdAt

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        updatedAt = msg.timestamp
    }

    /** 取所有非 null 的 TokenStats 求和（assistant 消息携带） */
    fun totalTokens(): TokenTotals {
        var p = 0; var c = 0; var cache = 0
        for (m in messages) {
            val s = m.tokenStats ?: continue
            p += s.promptTokens; c += s.completionTokens; cache += s.cacheHitTokens
        }
        return TokenTotals(promptTokens = p, completionTokens = c, cacheHitTokens = cache)
    }

    /** 所有携带 tokenStats 的消息（用于导出 / 展示） */
    fun messagesWithStats(): List<ChatMessage> = messages.filter { it.tokenStats != null }
}

@Serializable
data class AgentState(
    val status: Status = Status.IDLE,
    val currentTool: String = "",
    val progressText: String = "",
    val startedAt: Long = 0,
) {
    @Serializable
    enum class Status { IDLE, THINKING, TOOL_CALLING, WAITING_USER, COMPLETED, ERROR }

    val isActive: Boolean
        get() = status != Status.IDLE && status != Status.COMPLETED && status != Status.ERROR
}

@Serializable
data class ScheduledTask(
    val id: String,
    val name: String,
    val prompt: String,
    val type: TaskType,
    val intervalMinutes: Int = 0,
    val timeOfDay: String = "",
    val daysOfWeek: String = "",
    val conversationId: String = "",
    val enabled: Boolean = true,
    val lastRunAt: Long = 0,
    val nextRunAt: Long = 0,
) {
    @Serializable
    enum class TaskType { ONCE, INTERVAL, DAILY, WEEKLY }
}