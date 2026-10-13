package com.ai.android.agent

import android.content.Context
import android.util.Log
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话日志（append-only JSONL）。
 *
 * 目录：filesDir/logs/<convId>.jsonl
 * 导出：/sdcard/AiAndroid/logs/<convId>-<时间戳>.jsonl
 *
 * 记录内容：
 *  - tool/call    工具名 + 参数（敏感字段脱敏）
 *  - tool/result  成功/失败 + 输出（截断到 4KB）
 *  - guard/deny   守卫拦截
 *  - approval     用户批准/拒绝
 *  - error        异常
 */
object SessionLog {

    private const val TAG = "SessionLog"
    private const val MAX_OUTPUT_IN_LOG = 4_000
    private const val MAX_TOTAL_SIZE = 50L * 1024 * 1024   // 单对话最多 50MB

    /** 脱敏关键字（参数 key 名包含以下任意一个 → 值替换为 ***） */
    private val SENSITIVE_KEYS = setOf(
        "api_key", "apikey", "token", "password", "secret",
        "authorization", "auth", "bearer",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.CHINA)

    private var appCtx: Context? = null

    fun init(context: Context) {
        appCtx = context.applicationContext
        // 启动时清理一次（幂等）
        runCatching { cleanupIfNeeded() }
    }

    private fun logsDir(): File =
        File(appCtx!!.filesDir, "logs").apply { mkdirs() }

    private fun logFile(convId: String): File {
        val safe = convId.ifBlank { "default" }.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(logsDir(), "$safe.jsonl")
    }

    // ==================== 写日志 ====================

    @Synchronized
    private fun append(convId: String, type: String, data: Map<String, String>) {
        val ctx = appCtx ?: return
        runCatching {
            val f = logFile(convId)
            // 单文件超限：直接轮转（重命名为 .1）
            if (f.exists() && f.length() > MAX_TOTAL_SIZE) {
                val rotated = File(f.parentFile, f.name + ".1")
                runCatching { rotated.delete() }
                f.renameTo(rotated)
            }
            val line = buildJsonObject {
                put("ts", System.currentTimeMillis())
                put("time", df.format(Date()))
                put("type", type)
                data.forEach { (k, v) -> put(k, v) }
            }.toString()
            f.appendText(line + "\n")
        }.onFailure { Log.w(TAG, "append failed", it) }
    }

    fun recordCall(convId: String, call: ToolCall) {
        append(convId, "tool/call", mapOf(
            "id" to call.id,
            "name" to call.name,
            "args" to sanitizeArgs(call.arguments),
        ))
    }

    fun recordResult(convId: String, result: ToolResult) {
        append(convId, "tool/result", mapOf(
            "id" to result.toolCallId,
            "name" to result.name,
            "success" to result.success.toString(),
            "durationMs" to result.durationMs.toString(),
            "output" to result.output.take(MAX_OUTPUT_IN_LOG),
        ))
    }

    fun recordGuardDeny(convId: String, call: ToolCall, guard: String, reason: String) {
        append(convId, "guard/deny", mapOf(
            "name" to call.name,
            "guard" to guard,
            "reason" to reason,
        ))
    }

    fun recordApproval(convId: String, call: ToolCall, approved: Boolean, scope: String) {
        append(convId, "approval", mapOf(
            "name" to call.name,
            "approved" to approved.toString(),
            "scope" to scope,
        ))
    }

    fun recordError(convId: String, name: String, msg: String) {
        append(convId, "error", mapOf("name" to name, "msg" to msg.take(1000)))
    }

    // ==================== 参数脱敏 ====================

    private fun sanitizeArgs(raw: String): String {
        if (raw.isBlank()) return "{}"
        return runCatching {
            val obj = json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonObject ?: return raw
            val sanitized = buildJsonObject {
                obj.forEach { (k, v) ->
                    if (SENSITIVE_KEYS.any { s -> k.lowercase().contains(s) }) {
                        put(k, "***")
                    } else {
                        put(k, v.toString().take(1000))
                    }
                }
            }
            sanitized.toString()
        }.getOrDefault(raw.take(1000))
    }

    // ==================== 读 / 导出 ====================

    fun readRecent(convId: String, maxLines: Int = 200): String {
        val f = logFile(convId)
        if (!f.exists()) return "（暂无日志）"
        return runCatching {
            f.readLines().takeLast(maxLines.coerceIn(1, 2000)).joinToString("\n")
        }.getOrDefault("（读取失败）")
    }

    /** 导出到 /sdcard/AiAndroid/logs/，返回导出路径 */
    fun exportToSdcard(convId: String): String? {
        val src = logFile(convId)
        if (!src.exists()) return null
        return runCatching {
            val dstDir = File("/sdcard/AiAndroid/logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            val dst = File(dstDir, "${src.nameWithoutExtension}-$stamp.jsonl")
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        }.getOrNull()
    }

    fun exportAll(): Int {
        val dir = logsDir()
        if (!dir.exists()) return 0
        var n = 0
        dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.forEach {
            val r = runCatching {
                val dstDir = File("/sdcard/AiAndroid/logs").apply { mkdirs() }
                it.copyTo(File(dstDir, it.name), overwrite = true)
                true
            }.getOrDefault(false)
            if (r) n++
        }
        return n
    }

    fun listConversationLogs(): List<Pair<String, Long>> {
        val dir = logsDir()
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.map { it.nameWithoutExtension to it.length() }
            ?.sortedByDescending { it.second }
            ?: emptyList()
    }

    // ==================== 清理 ====================

    private fun cleanupIfNeeded() {
        val dir = logsDir()
        val files = dir.listFiles { f -> f.isFile } ?: return
        val totalSize = files.sumOf { it.length() }
        if (totalSize <= MAX_TOTAL_SIZE * 2) return

        // 超过 100MB → 删掉最旧一半
        val sorted = files.sortedBy { it.lastModified() }
        val toDelete = sorted.take(sorted.size / 2)
        toDelete.forEach { runCatching { it.delete() } }
        Log.i(TAG, "cleanup: 删除 ${toDelete.size} 个旧日志")
    }

    fun clearAll() {
        logsDir().listFiles()?.forEach { runCatching { it.delete() } }
    }
}