package com.ai.android.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String = "",
    val reasoning: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val images: List<String> = emptyList(),
    /** ⭐ 语音气泡：本地 wav 路径 */
    val audioPath: String = "",
    /** ⭐ 语音气泡：时长（ms） */
    val audioDurationMs: Int = 0,
    /** ⭐ 图片气泡：本地图片路径 */
    val imagePath: String = "",
    /** ⭐ 视频气泡：本地视频路径 */
    val videoPath: String = "",
    /** ⭐ 视频气泡：时长（ms） */
    val videoDurationMs: Int = 0,
    /** 附件文件路径 */
    val files: List<String> = emptyList(),
    val quotedContent: String = "",
    val tokenStats: TokenStats? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val error: String? = null,
    val parentId: String? = null,
) {
    @Serializable
    enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

    companion object {
        fun user(content: String) = ChatMessage(role = Role.USER, content = content)
        fun system(content: String) = ChatMessage(role = Role.SYSTEM, content = content)
        fun assistant(content: String) = ChatMessage(role = Role.ASSISTANT, content = content)
        fun tool(toolCallId: String, content: String) =
            ChatMessage(
                role = Role.TOOL,
                content = content,
                toolResults = listOf(ToolResult(toolCallId = toolCallId, name = "", output = content)),
            )
    }
}

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class ToolResult(
    val toolCallId: String,
    val name: String,
    val output: String,
    val success: Boolean = true,
    val durationMs: Long = 0,
)

@Serializable
data class TokenStats(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
) {
    val cacheRate: String
        get() = if (promptTokens > 0)
            String.format("%.1f%%", cacheHitTokens * 100.0 / promptTokens)
        else "0%"
}

@Serializable
data class TokenTotals(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cacheHitTokens: Int = 0,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

@Serializable
data class TokenExportReport(
    val conversationId: String,
    val title: String,
    val totals: TokenTotals,
    val exportedAt: Long,
    val perMessage: List<TokenEntry> = emptyList(),
)

@Serializable
data class TokenEntry(
    val messageId: String,
    val role: String,
    val stats: TokenStats,
)