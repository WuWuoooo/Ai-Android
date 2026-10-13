package com.ai.android.agent.tools

import android.content.Context
import android.util.Log
import com.ai.android.termux.ProotRunner
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 单个 PTY 会话（com.termux.terminal.TerminalSession）。
 *
 * ⭐ 变更（v3）：
 *  - HOME 从 `/sdcard` 改为 `/data/data/com.termux/files/home`
 *    → 映射到 host 的 `filesDir/termux/home`，永远有写权限
 *  - forkpty 前自动 mkdirs（home / tmp）
 *
 * ⚠️ com.termux 的 TerminalSession 内部用主线程 Handler，所有操作必须在主线程。
 */
class PtyTerminalSession(
    val sessionId: String,
    val convId: String,
    @Volatile var title: String,
    val createdAt: Long,
    private val context: Context,
) {

    companion object {
        private const val INIT_COLS = 80
        private const val INIT_ROWS = 24
        private const val CELL_W = 8
        private const val CELL_H = 16
        private const val OUTPUT_TAIL_LIMIT = 50_000

        /** guest 侧 HOME（映射到 host filesDir/termux/home） */
        const val GUEST_HOME = "/data/data/com.termux/files/home"
        /** guest 侧 tmp（映射到 host filesDir/termux/tmp） */
        const val GUEST_TMP = "/data/data/com.termux/files/tmp"
    }

    enum class State { IDLE, RUNNING, EXITED, ERROR }

    private val client = object : TerminalSessionClient {
        override fun onTextChanged(s: TerminalSession) {
            refreshOutput()
            onScreenUpdate?.invoke()
        }
        override fun onTitleChanged(s: TerminalSession) {}
        override fun onSessionFinished(s: TerminalSession) {
            _stateFlow.value = State.EXITED
            refreshOutput()
        }
        override fun onCopyTextToClipboard(s: TerminalSession, t: String) {}
        override fun onPasteTextFromClipboard(s: TerminalSession?) {}
        override fun onBell(s: TerminalSession) {}
        override fun onColorsChanged(s: TerminalSession) {}
        override fun onTerminalCursorStateChange(b: Boolean) {}
        override fun setTerminalShellPid(s: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(a: String, b: String) {}
        override fun logWarn(a: String, b: String) {}
        override fun logInfo(a: String, b: String) {}
        override fun logDebug(a: String, b: String) {}
        override fun logVerbose(a: String, b: String) {}
        override fun logStackTraceWithMessage(a: String, b: String, e: Exception) {}
        override fun logStackTrace(a: String, e: Exception) {}
    }

    @Volatile private var session: TerminalSession? = null
    @Volatile private var lastReadPos = 0L

    @Volatile var onScreenUpdate: (() -> Unit)? = null

    private val _outputFlow = MutableStateFlow("")
    val outputFlow: StateFlow<String> = _outputFlow.asStateFlow()

    private val _stateFlow = MutableStateFlow(State.IDLE)
    val stateFlow: StateFlow<State> = _stateFlow.asStateFlow()

    private val _terminalSessionFlow = MutableStateFlow<TerminalSession?>(null)
    val terminalSessionFlow: StateFlow<TerminalSession?> = _terminalSessionFlow.asStateFlow()

    @Volatile var lastActiveAt: Long = createdAt
        private set

    private fun started(): Boolean = session?.getEmulator() != null
    private fun isDead(): Boolean = started() && session?.isRunning() == false

    fun uiSession(): TerminalSession? = session

    private fun refreshOutput() {
        val text = session?.getEmulator()?.getScreen()?.getTranscriptText().orEmpty()
        _outputFlow.value = if (text.length > OUTPUT_TAIL_LIMIT)
            text.takeLast(OUTPUT_TAIL_LIMIT) else text
    }

    suspend fun ensureHealthy() = withContext(Dispatchers.Main) {
        if (isDead()) restart()
        ensureStarted()
    }

    suspend fun ensureStarted() = withContext(Dispatchers.Main) {
        lastActiveAt = System.currentTimeMillis()
        val cur = session
        if (started()) {
            if (_stateFlow.value == State.EXITED) {
                _stateFlow.value = if (cur?.isRunning() == true) State.RUNNING else State.EXITED
            }
            return@withContext
        }

        // ⭐ 确保 guest 的 HOME / tmp 目录在 host 侧存在（proot 映射前必须建好）
        runCatching {
            File(context.filesDir, "termux/home").mkdirs()
            File(context.filesDir, "termux/tmp").mkdirs()
        }

        runCatching { cur?.finishIfRunning() }
        val s = runCatching {
            val cmdList = ProotRunner(context).buildInteractiveCommand()
            val shell = cmdList.first()
            val args = cmdList.toTypedArray()
            val t = TerminalSession(shell, "/", args, buildEnv(), null, client)
            runCatching { t.initializeEmulator(INIT_COLS, INIT_ROWS, CELL_W, CELL_H) }
            t
        }.onFailure {
            Log.w("PtyTerminalSession", "PTY 启动失败（proot 未安装/未就绪？）", it)
            _stateFlow.value = State.ERROR
        }.getOrNull() ?: return@withContext
        session = s
        _stateFlow.value = if (s.isRunning()) State.RUNNING else State.EXITED
        _terminalSessionFlow.value = s
        lastReadPos = 0L

        runCatching {
            val ps1cmd = ProotRunner(context).ps1InitCommand()
            s.write(ps1cmd.toByteArray(), 0, ps1cmd.toByteArray().size)
        }
        Unit
    }

    suspend fun restart() = withContext(Dispatchers.Main) {
        runCatching { session?.finishIfRunning() }
        session = null
        _stateFlow.value = State.IDLE
        _terminalSessionFlow.value = null
        _outputFlow.value = ""
        Unit
    }

    suspend fun sendCtrl(byteValue: Int) = withContext(Dispatchers.Main) {
        ensureHealthy()
        runCatching { session?.write(byteArrayOf(byteValue.toByte()), 0, 1) }
        Unit
    }

    suspend fun writeLine(line: String) = withContext(Dispatchers.Main) {
        ensureHealthy()
        val bytes = (line + "\n").toByteArray()
        runCatching { session?.write(bytes, 0, bytes.size) }
        Unit
    }

    suspend fun writeRaw(text: String) = withContext(Dispatchers.Main) {
        ensureHealthy()
        val bytes = text.toByteArray()
        runCatching { session?.write(bytes, 0, bytes.size) }
        Unit
    }

    suspend fun sendKeySeq(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean) =
        withContext(Dispatchers.Main) {
            val seq = buildKeySeq(name, ctrl, alt, shift)
            if (seq.isNotEmpty()) writeRaw(seq)
            Unit
        }

    fun buildKeySeq(name: String, ctrl: Boolean, alt: Boolean, shift: Boolean): String {
        var key = name
        var c = ctrl; var a = alt
        if (key.startsWith("ctrl-")) { key = key.removePrefix("ctrl-"); c = true }
        if (key.startsWith("alt-")) { key = key.removePrefix("alt-"); a = true }
        fun altPrefix(s: String) = if (a) "\u001b" + s else s
        val base = when (key) {
            "up" -> "\u001b[A"; "down" -> "\u001b[B"
            "left" -> "\u001b[D"; "right" -> "\u001b[C"
            "pgup" -> "\u001b[5~"; "pgdn" -> "\u001b[6~"
            "home" -> "\u001b[H"; "end" -> "\u001b[F"
            "esc" -> "\u001b"
            "tab" -> if (c) "\u0001" else "\u0009"
            "enter" -> "\r"
            else -> {
                val ch = key.firstOrNull()?.toString() ?: return ""
                val up = if (shift) ch.uppercase() else ch
                if (c && up.isNotEmpty()) {
                    val n = up.lowercase()[0].code - 'a'.code
                    if (n in 0..25) (n + 1).toChar().toString() else ""
                } else up
            }
        }
        return altPrefix(base)
    }

    suspend fun exec(command: String, timeoutSec: Int): String = withContext(Dispatchers.Main) {
        ensureHealthy()
        lastActiveAt = System.currentTimeMillis()
        val s = session ?: return@withContext "❌ PTY 会话不可用（proot 未就绪？请到 设置 → Termux 环境 安装）"
        val mark = "__AI_DONE_PTY__${System.nanoTime()}__"
        val before = (s.getEmulator()?.getScreen()?.getTranscriptText()?.length ?: 0).toLong()
        val cmdBytes = (command + "\n").toByteArray()
        val markBytes = "echo $mark\$?\n".toByteArray()
        runCatching {
            s.write(cmdBytes, 0, cmdBytes.size)
            s.write(markBytes, 0, markBytes.size)
        }.onFailure {
            return@withContext "❌ 写入 PTY 失败：${it.message}"
        }

        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            val t = s.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
            val idx = t.lastIndexOf(mark)
            if (idx >= 0) {
                val from = before.toInt().coerceIn(0, idx)
                val body = t.substring(from, idx).trim()
                val exit = t.substring(idx + mark.length).lineSequence().firstOrNull().orEmpty().trim().ifEmpty { "?" }
                return@withContext if (body.isBlank()) "✅ 退出码: $exit\n（无输出）"
                else "✅ 退出码: $exit\n$body"
            }
            if (!s.isRunning()) break
            delay(80)
        }
        val full = s.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
        val partial = full.substring(before.toInt().coerceAtLeast(0)).take(8000)
        if (s.isRunning()) {
            "⏱ 命令超时（${timeoutSec}s）但仍在后台运行，可用 terminal_read since_last=true 查看后续输出\n--- 当前输出 ---\n$partial"
        } else "❌ PTY 会话在执行期间退出"
    }

    suspend fun read(lines: Int, sinceLast: Boolean): String = withContext(Dispatchers.Main) {
        val full = session?.getEmulator()?.getScreen()?.getTranscriptText() ?: ""
        if (full.isBlank()) return@withContext "（终端暂无输出）"
        if (sinceLast) {
            val from = lastReadPos.toInt().coerceIn(0, full.length)
            val tail = full.substring(from)
            lastReadPos = full.length.toLong()
            if (tail.isBlank()) "（自上次读取后没有新输出）"
            else tail.split('\n').takeLast(lines.coerceIn(1, 500)).joinToString("\n")
        } else {
            lastReadPos = full.length.toLong()
            full.split('\n').takeLast(lines.coerceIn(1, 500)).joinToString("\n")
        }
    }

    fun dispose() {
        runCatching { session?.finishIfRunning() }
        session = null
        _terminalSessionFlow.value = null
        _stateFlow.value = State.IDLE
        _outputFlow.value = ""
    }

    private fun buildEnv(): Array<String> {
        val usrPath = "/data/data/com.termux/files/usr"
        val nativeLib = context.applicationInfo.nativeLibraryDir
        val loader = File(nativeLib, "libproot_loader.so")
        val tmp = File(context.cacheDir, "proot_tmp").apply { mkdirs() }
        return buildList {
            if (loader.exists()) add("PROOT_LOADER=" + loader.absolutePath)
            add("LD_LIBRARY_PATH=$usrPath/lib:$nativeLib")
            add("PROOT_TMP_DIR=${tmp.absolutePath}")
            // ⭐ HOME 从 /sdcard 改为私有目录（永远可写）
            add("HOME=$GUEST_HOME")
            add("PREFIX=$usrPath")
            add("PATH=$usrPath/bin:/system/bin:$usrPath/bin/applets")
            add("TERM=xterm-256color")
            add("LANG=zh_CN.UTF-8")
            add("SHELL=$usrPath/bin/bash")
            add("TMPDIR=$usrPath/tmp")
            add("ANDROID_ROOT=/system")
            add("ANDROID_DATA=/data")
            add("EXTERNAL_STORAGE=/sdcard")
        }.toTypedArray()
    }
}