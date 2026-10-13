package com.ai.android.agent.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ai.android.service.AgentAccessibilityService
import com.ai.android.service.FloatingService
import com.ai.android.service.MediaProjectionBridge
import com.ai.android.service.MirrorService
import com.ai.android.storage.SettingsRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject

/**
 * AI 操控手机前置守卫：
 * - 检查 aiControlEnabled / confirmBeforeControl
 * - 当前前台 App 不符时先 ask_user（若 confirmBeforeControl 开）
 * - 启动 / 停止屏幕镜像（MirrorService + 悬浮窗镜像面板）
 *
 * ⭐ 完全访问档（fullyOpen=true）：跳过 confirmBeforeControl 的用户确认，但保留 aiControlEnabled 总开关
 *   （总开关是"功能权限"，与"审批弹窗"不同）。
 *
 * ⚠️ 本类规避 Kotlin 编译器后端 bug：
 *   1. 不用 Mutex.withLock（inline），改用 try/finally + lock/unlock（非 inline）
 *   2. 锁内复杂逻辑抽成独立函数
 *   3. 不用 runCatching（inline），改用普通 try/catch
 */
class AiControlGuard(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    @Volatile private var mirrorActive = false
    @Volatile private var sessionConfirmed = false

    private val confirmMutex = Mutex()
    private val mirrorMutex = Mutex()

    private val tag = "AiControlGuard"

    /**
     * accessibility_control 工具执行前调用。
     * @param fullyOpen 完全访问档（跳过 confirmBeforeControl 的用户确认）
     * @return null = 继续执行；非 null = 中止原因（作为 ToolResult.output 返回）
     */
    suspend fun preCheck(
        args: JsonObject,
        fullyOpen: Boolean = false,
        askUser: suspend (String, String) -> String,
    ): String? {
        // 1. 总开关
        if (!settings.aiControlEnabled()) return "AI 操控功能已在设置中关闭"

        // 2. 确认操控（需要时；完全访问档跳过）
        if (settings.confirmBeforeControl() && !fullyOpen) {
            val blocked = confirmIfNeeded(args, askUser)
            if (blocked != null) return blocked
        }

        // 3. 启动镜像
        startMirrorSafe()
        return null
    }

    private suspend fun confirmIfNeeded(
        args: JsonObject,
        askUser: suspend (String, String) -> String,
    ): String? {
        confirmMutex.lock()
        try {
            if (sessionConfirmed) return null

            val currentPkg = safeCurrentPackage()
            val action = args.str("action")
            val targetPkg = if (action == "launch_app") args.str("package") else ""
            val question = buildConfirmQuestion(targetPkg, currentPkg)
            val answer = askUser(question, "确认本会话内允许,取消")
            val trimmed = answer.trim()
            if (trimmed.contains("取消") || trimmed == "不") {
                return "用户取消了 AI 操控"
            }
            sessionConfirmed = true
            return null
        } finally {
            confirmMutex.unlock()
        }
    }

    private fun safeCurrentPackage(): String {
        return try {
            AgentAccessibilityService.instance?.currentPackage().orEmpty()
        } catch (e: Exception) {
            Log.w(tag, "safeCurrentPackage failed", e)
            ""
        }
    }

    private fun buildConfirmQuestion(targetPkg: String, currentPkg: String): String {
        val cur = if (currentPkg.isBlank()) "未知" else currentPkg
        return if (targetPkg.isNotBlank()) {
            "AI 即将切换到「$targetPkg」进行操控（本次会话内后续操作不再询问）。当前：$cur"
        } else {
            "AI 即将操控手机（本次会话内后续操作不再询问）。当前前台：$cur"
        }
    }

    fun beginSession() {
        sessionConfirmed = false
    }

    private suspend fun startMirrorSafe() {
        if (mirrorActive) return
        if (!settings.mirrorEnabled() || !settings.floatingEnabled()) return

        mirrorMutex.lock()
        try {
            if (mirrorActive) return
            val ok = startMirrorInternal()
            if (ok) mirrorActive = true
        } finally {
            mirrorMutex.unlock()
        }
    }

    private fun startMirrorInternal(): Boolean {
        return try {
            val ctx = context.applicationContext
            if (!MirrorService.hasProjection()) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    try {
                        val i = Intent(ctx, MediaProjectionBridge::class.java)
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    } catch (e: Exception) {
                        Log.e(tag, "startProjection activity failed", e)
                    }
                }
            }
            FloatingService.setMirrorActive(ctx, true)
            true
        } catch (e: Exception) {
            Log.e(tag, "startMirrorInternal failed", e)
            false
        }
    }

    fun stopMirror() {
        mirrorActive = false
        sessionConfirmed = false
        try {
            FloatingService.setMirrorActive(context.applicationContext, false)
        } catch (e: Exception) {
            Log.w(tag, "stopMirror failed", e)
        }
    }

    fun stopAll() {
        stopMirror()
    }
}