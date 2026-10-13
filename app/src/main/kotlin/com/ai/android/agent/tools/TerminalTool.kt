package com.ai.android.agent.tools

import android.content.Context
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 终端工具集（只走 PTY，多会话）。
 *
 * ⭐ 所有工具都支持可选参数 session_id：
 *  - 不传：使用当前对话的默认会话（"共享(AI)"）
 *  - 传：使用指定会话（AI 可先 terminal_list_sessions 查）
 */

class TerminalExecTool(
    private val context: Context,
    private val settings: SettingsRepository?,
    private val convIdProvider: () -> String,
) : ToolExecutor {
    override val name = "terminal_exec"
    override val dangerous = true
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String {
        val cmd = args.requireStrAny("command", "cmd", hint = "例如 pkg install python -y")
        checkDangerousShell(cmd)
        val convId = convIdProvider()
        val timeout = args.int("timeout_sec", 60).coerceIn(1, 120)
        val background = args.bool("background", false)

        val sessionId = args.str("session_id", "")
        val sess = if (sessionId.isNotBlank()) {
            PtySessionManager.get(context, sessionId)
                ?: return "❌ 找不到会话 session_id=$sessionId（可先 terminal_list_sessions 查询）"
        } else {
            PtySessionManager.getOrCreateDefault(context, convId)
        }
        PtySessionManager.touch(context, sess.sessionId)

        return if (background) execBackground(sess, cmd)
        else sess.exec(cmd, timeout)
    }

    /** 后台执行（suspend：writeLine / delay / read 都是 suspend） */
    private suspend fun execBackground(sess: PtyTerminalSession, command: String): String {
        val logDir = File("/sdcard/.ai_term_bg").apply { mkdirs() }
        val taskId = System.nanoTime().toString().takeLast(12)
        val logFile = File(logDir, "$taskId.log")
        val escCmd = command.replace("'", "'\\''")
        val wrapped = "nohup sh -c '$escCmd' > '${logFile.absolutePath}' 2>&1 & echo __BG_PID__$!"

        return try {
            sess.writeLine(wrapped)
            delay(400)
            val tail = sess.read(20, false)
            val pidStr = tail.lineSequence().firstOrNull { it.contains("__BG_PID__") }
                ?.substringAfter("__BG_PID__")?.trim() ?: ""
            if (pidStr.isBlank() || pidStr.toLongOrNull() == null) {
                "⚠️ 后台启动失败（未拿到 shell pid）。原始结果：\n$tail"
            } else {
                "✅ 后台任务 task=$taskId\nshell_pid=$pidStr\n日志: ${logFile.absolutePath}\n" +
                    "用 terminal_read_bg task=$taskId 读取输出，或 terminal_wait task=$taskId 等待结束"
            }
        } catch (e: Exception) {
            "⚠️ 后台命令写入失败：${e.message}"
        }
    }
}

/** 读 PTY 会话输出 */
class TerminalReadTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_read"
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String {
        val ctx = context ?: return "❌ context 不可用"
        val convId = convIdProvider()
        val lines = args.int("lines", 40)
        val sinceLast = args.bool("since_last", false)
        val sessionId = args.str("session_id", "")
        val sess = if (sessionId.isNotBlank()) {
            PtySessionManager.get(ctx, sessionId)
                ?: return "❌ 找不到会话 session_id=$sessionId"
        } else {
            PtySessionManager.getOrCreateDefault(ctx, convId)
        }
        return sess.read(lines, sinceLast)
    }
}

/** 列出当前对话的所有会话 */
class TerminalListSessionsTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
) : ToolExecutor {
    override val name = "terminal_list_sessions"
    override val parallelizable = true

    override suspend fun execute(args: JsonObject): String {
        val ctx = context ?: return "❌ context 不可用"
        val convId = convIdProvider()
        val list = PtySessionManager.list(ctx, convId)
        if (list.isEmpty()) return "（当前对话还没有终端会话）"

        // ⭐ 先 map（inline，允许 suspend）拿状态，再 joinToString（非 inline，不可 suspend）
        val lines = list.map { m ->
            val s = PtySessionManager.get(ctx, m.id)
            val state = when (s?.stateFlow?.value) {
                PtyTerminalSession.State.RUNNING -> "运行中"
                PtyTerminalSession.State.EXITED -> "已退出"
                PtyTerminalSession.State.ERROR -> "错误"
                else -> "空闲"
            }
            "id=${m.id} 「${m.title}」 状态=$state 最后活动=${fmtTime(m.lastActiveAt)}"
        }
        return lines.joinToString("\n")
    }

    private fun fmtTime(ms: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.CHINA).format(java.util.Date(ms))
}

/** 关闭指定会话 */
class TerminalCloseSessionTool(
    private val context: Context?,
) : ToolExecutor {
    override val name = "terminal_close_session"
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String {
        val ctx = context ?: return "❌ context 不可用"
        val sessionId = args.requireStr("session_id", "要关闭的会话 id")
        PtySessionManager.close(ctx, sessionId)
        return "✅ 已关闭会话 $sessionId"
    }
}

/** 读取后台任务日志 */
class TerminalReadBgTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_read_bg"
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val taskId = args.requireStr("task", "后台任务 id")
        val lines = args.int("lines", 100)
        val logFile = File("/sdcard/.ai_term_bg/$taskId.log")
        if (!logFile.exists()) return@withContext "❌ 找不到 task=$taskId 的后台日志"
        val tail = logFile.readLines().takeLast(lines.coerceIn(1, 500))
        "=== 后台任务 task=$taskId ===\n" + (if (tail.isEmpty()) "（暂无输出）" else tail.joinToString("\n"))
    }
}

/** 等待后台任务结束或日志匹配 */
class TerminalWaitTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_wait"
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val taskId = args.requireStr("task", "后台任务 id")
        val pattern = args.str("pattern", "")
        val timeout = args.int("timeout_sec", 300).coerceIn(1, 600)
        val logFile = File("/sdcard/.ai_term_bg/$taskId.log")
        if (!logFile.exists()) return@withContext "❌ 找不到 task=$taskId 的日志"

        val deadline = System.currentTimeMillis() + timeout * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (pattern.isNotBlank() && logFile.readText().contains(pattern)) {
                return@withContext "✅ 匹配到 \"$pattern\"\n=== 输出 ===\n" +
                    logFile.readLines().takeLast(200).joinToString("\n")
            }
            val lastModified = logFile.lastModified()
            if (System.currentTimeMillis() - lastModified > 3000) {
                return@withContext "✅ 后台任务 task=$taskId 已结束（日志 3s 未更新）\n=== 输出 ===\n" +
                    logFile.readLines().takeLast(200).joinToString("\n")
            }
            delay(1000)
        }
        "⏱ 等待 task=$taskId 超时（${timeout}s）\n=== 当前输出 ===\n" +
            logFile.readLines().takeLast(200).joinToString("\n")
    }
}

/** 列出后台任务 */
class TerminalListBgTool(
    private val convIdProvider: () -> String,
    private val context: Context?,
    private val settings: SettingsRepository?,
) : ToolExecutor {
    override val name = "terminal_list_bg"
    override val parallelizable = true

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val dir = File("/sdcard/.ai_term_bg")
        if (!dir.exists()) return@withContext "（当前没有后台任务）"
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return@withContext "（无）"
        if (files.isEmpty()) return@withContext "（当前没有后台任务）"
        files.sortedByDescending { it.lastModified() }.joinToString("\n") { f ->
            val stale = System.currentTimeMillis() - f.lastModified() > 3000
            "task=${f.nameWithoutExtension} ${if (stale) "已结束" else "运行中"} 大小=${f.length()}B"
        }
    }
}