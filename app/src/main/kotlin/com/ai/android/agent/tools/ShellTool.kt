package com.ai.android.agent.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 危险 shell 模式（命中即拦截，要求模型先 ask_user）。
 *
 * 注意：
 * - 全部使用普通字符串 + 双反斜杠转义，不用 raw string，避免 KAPT 在生成 Java stub
 *   时对三引号内的特殊字符（dollar / 反斜杠）做二次处理导致编译崩溃。
 * - 正则里需要匹配命令行的字面美元符时，Kotlin 字符串里写双反斜杠加 dollar。
 */
private val DANGEROUS_SHELL = listOf(
    // 任何带 -r / -R 的 rm
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*-[rR]"),
    // rm ... 根目录（斜杠后跟空白 / 行尾 / 分号 / & / |）
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/(\\s|\\$|;|&|\\|)"),
    // rm ... /sdcard
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/sdcard(\\s|\\$|/|\\*|;|&|\\|)"),
    // rm ... /storage
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*/storage(\\s|\\$|/|\\*|;|&|\\|)"),
    // rm ... HOME 环境变量
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*\\\$HOME"),
    // rm ... ~
    Regex("\\brm\\s+(-[a-zA-Z]+\\s+)*~"),

    // 格式化 / 覆写系统块设备
    Regex("\\bmkfs(\\.[a-z0-9]+)?\\b"),
    Regex("\\bdd\\s+if="),
    Regex(">\\s*/dev/block"),
    Regex("\\bmke2fs\\b"),

    // 提权（su / sudo）
    Regex("(^|\\s|;|&|\\|)(su|sudo)(\\s|\\$)"),

    // 卸载 / 清数据
    Regex("\\bpm\\s+uninstall\\b"),
    Regex("\\bpm\\s+clear\\b"),

    // 关机 / 重启
    Regex("\\b(shutdown|reboot|halt|poweroff)\\b"),

    // fork bomb
    Regex("\\(\\)\\s*\\{.*\\}"),
)

internal fun checkDangerousShell(cmd: String) {
    DANGEROUS_SHELL.firstOrNull { it.containsMatchIn(cmd) }?.let {
        throw IllegalStateException(
            "检测到潜在危险命令（匹配：${it.pattern}）。请先用 ask_user 向用户确认，得到明确同意后再执行。"
        )
    }
}

/** 一次性 shell 命令 */
class ShellTool : ToolExecutor {
    override val name = "run_shell_command"
    override val dangerous = true

    // 命令有先后依赖，必须串行
    override val parallelizable = false

    override suspend fun execute(args: JsonObject): String = withContext(Dispatchers.IO) {
        val command = args.requireStrAny("command", "cmd", hint = "例如 ls /sdcard/Download")
        checkDangerousShell(command)
        val timeout = args.int("timeout_sec", 15).coerceIn(1, 60)

        val out = runCatching {
            com.ai.android.service.ShizukuManager.executeShell(
                com.ai.android.MainApp.instance, command, timeout,
            )
        }.getOrElse {
            runLocalShell(command, timeout)
        }
        out
    }

    private fun runLocalShell(command: String, timeout: Int): String {
        val proc = try {
            ProcessBuilder("sh", "-c", command)
                .directory(File("/sdcard"))
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException("启动命令失败: ${e.message}")
        }

        val output = StringBuilder()
        val readerThread = Thread {
            runCatching {
                proc.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        synchronized(output) {
                            if (output.length < 20_000) output.append(line).append('\n')
                        }
                    }
                }
            }
        }
        readerThread.start()

        val finished = proc.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            readerThread.join(2000)
            val partial = synchronized(output) { output.toString() }
            return "⏱ 命令超时（${timeout}s）已终止\n--- 部分输出 ---\n${partial.take(8000)}"
        }
        readerThread.join(2000)
        val exit = proc.exitValue()
        val text = synchronized(output) { output.toString() }.take(10_000)
        val head = if (exit == 0) "✅ 退出码: 0" else "⚠️ 退出码: $exit"
        return if (text.isBlank()) "$head\n（无输出）" else "$head\n$text"
    }
}

/** 获取当前日期时间 */
class GetCurrentTimeTool : ToolExecutor {
    override val name = "get_current_time"

    override suspend fun execute(args: JsonObject): String {
        val now = Date()
        val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(now)
        val week = SimpleDateFormat("EEEE", Locale.CHINA).format(now)
        return "当前时间: $full（$week）\n时间戳: ${System.currentTimeMillis()}"
    }
}