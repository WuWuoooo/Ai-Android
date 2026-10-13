package com.ai.android.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * ⭐ 后台子代理任务状态存储（内存）。
 *
 * 用途：background=true 的 subagent 立即返回 taskId，
 * 真实执行在 app.appScope 里跑；主 Agent / 用户可用 subagent_status / subagent_result 查询。
 */
object SubAgentTaskStore {

    enum class Status { RUNNING, DONE, ERROR }

    class Task(
        val taskId: String,
        val description: String,
        val convId: String,
        val startedAt: Long,
    ) {
        @Volatile var status: Status = Status.RUNNING
        @Volatile var finishedAt: Long = 0L
        @Volatile var finalText: String = ""
        @Volatile var error: String = ""
        @Volatile var rounds: Int = 0
        @Volatile var promptTokens: Int = 0
        @Volatile var completionTokens: Int = 0
        @Volatile var cacheHitTokens: Int = 0
        /** 是否已经向对话推送过完成通知（避免重复） */
        @Volatile var notified: Boolean = false
    }

    private val tasks = ConcurrentHashMap<String, Task>()

    fun start(convId: String, description: String): String {
        val id = "sub-" + System.nanoTime().toString().takeLast(10)
        tasks[id] = Task(
            taskId = id,
            description = description.take(200),
            convId = convId,
            startedAt = System.currentTimeMillis(),
        )
        cleanupOld()
        return id
    }

    fun finish(taskId: String, result: SubAgentResult) {
        val t = tasks[taskId] ?: return
        t.status = if (result.success) Status.DONE else Status.ERROR
        t.finishedAt = System.currentTimeMillis()
        t.finalText = result.finalText
        t.rounds = result.rounds
        t.promptTokens = result.promptTokens
        t.completionTokens = result.completionTokens
        t.cacheHitTokens = result.cacheHitTokens
    }

    fun fail(taskId: String, error: String) {
        val t = tasks[taskId] ?: return
        t.status = Status.ERROR
        t.finishedAt = System.currentTimeMillis()
        t.error = error.take(500)
    }

    fun get(taskId: String): Task? = tasks[taskId]

    fun list(convId: String = ""): List<Task> {
        val all = tasks.values.toList()
        return if (convId.isBlank()) all.sortedByDescending { it.startedAt }
        else all.filter { it.convId == convId }.sortedByDescending { it.startedAt }
    }

    fun remove(taskId: String) { tasks.remove(taskId) }

    fun clearAll() { tasks.clear() }

    private fun cleanupOld() {
        val now = System.currentTimeMillis()
        val toRemove = tasks.values.filter { t ->
            t.status != Status.RUNNING && (now - t.startedAt) > 3_600_000L
        }.map { it.taskId }
        toRemove.forEach { tasks.remove(it) }
    }

    /** 组装给 AI 看的状态文本 */
    fun describe(t: Task): String {
        val elapsed = if (t.status == Status.RUNNING)
            (System.currentTimeMillis() - t.startedAt) / 1000
        else (t.finishedAt - t.startedAt) / 1000
        val statusText = when (t.status) {
            Status.RUNNING -> "🔄 运行中"
            Status.DONE -> "✅ 已完成"
            Status.ERROR -> "❌ 失败"
        }
        return buildString {
            append("taskId: ").append(t.taskId).append('\n')
            append("状态: ").append(statusText).append("（耗时 ").append(elapsed).append("s）\n")
            append("任务: ").append(t.description)
        }
    }
}