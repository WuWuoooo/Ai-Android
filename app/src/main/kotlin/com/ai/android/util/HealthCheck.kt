package com.ai.android.util

import android.content.Context
import com.ai.android.MainApp
import com.ai.android.agent.PluginGuardBridge
import com.ai.android.plugin.PluginManager
import com.ai.android.plugin.SkillManager
import com.ai.android.service.ShizukuManager
import com.ai.android.service.terminal.TerminalForegroundService
import com.ai.android.termux.BootstrapInstaller
import com.ai.android.termux.ProotRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class HealthLevel { OK, WARN, ERROR }

data class HealthItem(
    val label: String,
    val value: String,
    val level: HealthLevel = HealthLevel.OK,
)

data class HealthSection(
    val title: String,
    val level: HealthLevel,
    val summary: String,
    val items: List<HealthItem> = emptyList(),
)

/**
 * 环境自检。
 *
 * ⭐ v3 关键改动：
 *  - runStreaming 加 safeMode
 *  - checkTerminal 不再触碰 PtySessionManager / PtyTerminalSession（避免 native 层问题）
 */
object HealthCheck {

    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
    private fun ts(ms: Long): String = if (ms <= 0) "—" else fmt.format(Date(ms))

    private fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1024 * 1024 -> "${"%.1f".format(b / 1024.0)} KB"
        b < 1024L * 1024 * 1024 -> "${"%.1f".format(b / 1024.0 / 1024.0)} MB"
        else -> "${"%.1f".format(b / 1024.0 / 1024.0 / 1024.0)} GB"
    }

    /**
     * ⭐ 流式执行：每项检查独立 catch + 独立回调。
     */
    suspend fun runStreaming(
        context: Context,
        convId: String,
        safeMode: Boolean = false,
        onItem: suspend (HealthSection) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val checks: List<Pair<String, suspend () -> HealthSection>> = buildList {
            add("数据库" to { checkDatabase(context) })
            add("模型 Provider" to { checkProvider(context) })
            if (!safeMode) add("终端" to { checkTerminal(context, convId) })
            add("Skill" to { checkSkill(context) })
            add("插件" to { checkPlugin(context) })
            add("会话日志" to { checkSessionLog(context, convId) })
            add("系统权限" to { checkPermissions(context) })
            if (!safeMode) add("Shizuku" to { checkShizuku(context) })
        }
        for ((title, block) in checks) {
            val section = try {
                block()
            } catch (e: Throwable) {
                HealthSection(
                    title, HealthLevel.ERROR,
                    "检查失败：${e.javaClass.simpleName}: ${e.message}"
                )
            }
            onItem(section)
            delay(80)   // 让 UI 喘口气
        }
    }

    // ==================== 数据库 ====================

    private suspend fun checkDatabase(context: Context): HealthSection {
        val app = MainApp.instance
        val db = app.database
        val dbFile = context.getDatabasePath("ai_android.db")
        val size = if (dbFile.exists()) dbFile.length() else 0L

        val convCount = runCatching { db.conversationDao().allMeta().size }.getOrDefault(0)
        val kvCount = runCatching { db.kvDao().all().size }.getOrDefault(0)

        val backupDir = File("/sdcard/AiAndroid/backup")
        val backups = runCatching {
            backupDir.listFiles { f -> f.name.endsWith(".db") }?.size ?: 0
        }.getOrDefault(0)

        val items = buildList {
            add(HealthItem(
                "文件",
                if (dbFile.exists()) "${dbFile.absolutePath}（${fmtSize(size)}）" else "不存在",
                if (dbFile.exists()) HealthLevel.OK else HealthLevel.ERROR,
            ))
            add(HealthItem("对话数", "$convCount"))
            add(HealthItem("KV 记录数", "$kvCount"))
            add(HealthItem("数据库备份数", "$backups（/sdcard/AiAndroid/backup/）"))
        }

        val level = when {
            !dbFile.exists() -> HealthLevel.ERROR
            convCount == 0 -> HealthLevel.WARN
            else -> HealthLevel.OK
        }
        val summary = when {
            !dbFile.exists() -> "数据库文件缺失"
            convCount == 0 -> "0 个对话（新装或数据异常）"
            else -> "$convCount 个对话 · ${fmtSize(size)}"
        }
        return HealthSection("数据库", level, summary, items)
    }

    // ==================== Provider ====================

    private fun checkProvider(context: Context): HealthSection {
        val pm = MainApp.instance.providerManager
        val configs = MainApp.instance.settings.loadConfigs()
        val activeCfg = configs.firstOrNull { it.id == pm.activeId }
        val active = pm.active
        val total = configs.size
        val withKey = configs.count { it.apiKey.isNotBlank() }

        if (active == null || activeCfg == null) {
            return HealthSection(
                "模型 Provider", HealthLevel.ERROR,
                "未配置任何 Provider",
                listOf(
                    HealthItem("配置数", "$total"),
                    HealthItem("有 Key 的", "$withKey"),
                ),
            )
        }

        val hasKey = activeCfg.apiKey.isNotBlank()
        val items = listOf(
            HealthItem("当前 Provider", "${active.name}"),
            HealthItem("模型", active.model),
            HealthItem("Base URL", active.baseUrl),
            HealthItem(
                "API Key",
                if (hasKey) "已配置（${activeCfg.apiKey.take(6)}...）" else "未配置",
                if (hasKey) HealthLevel.OK else HealthLevel.ERROR,
            ),
            HealthItem("配置总数", "$total（有 Key 的 $withKey）"),
        )
        val level = if (hasKey) HealthLevel.OK else HealthLevel.ERROR
        val summary = if (hasKey) "${active.name} · ${active.model}" else "当前 Provider 缺 API Key"
        return HealthSection("模型 Provider", level, summary, items)
    }

    // ==================== 终端（简化版：不触碰 PtySessionManager）====================

    private fun checkTerminal(context: Context, convId: String): HealthSection {
        val installed = try { BootstrapInstaller.isInstalled(context) } catch (e: Throwable) { false }
        val ready = try { ProotRunner(context).isReady() } catch (e: Throwable) { false }
        val serviceRunning = TerminalForegroundService.isRunning

        val homeDir = File(context.filesDir, "termux/home")
        val homeExists = homeDir.exists()

        val items = listOf(
            HealthItem(
                "proot 已安装",
                if (installed) "是" else "否",
                if (installed) HealthLevel.OK else HealthLevel.ERROR,
            ),
            HealthItem(
                "proot 就绪",
                if (ready) "是" else "否",
                if (ready) HealthLevel.OK else HealthLevel.WARN,
            ),
            HealthItem(
                "PTY 前台服务",
                if (serviceRunning) "运行中" else "未启动",
                if (serviceRunning) HealthLevel.OK else HealthLevel.WARN,
            ),
            HealthItem("HOME 目录", homeDir.absolutePath + if (homeExists) "（存在）" else "（未创建）",
                if (homeExists) HealthLevel.OK else HealthLevel.WARN),
            HealthItem("HOME 权限可写", if (homeDir.canWrite() || !homeExists) "是" else "否"),
        )

        val level = when {
            !installed -> HealthLevel.ERROR
            !ready -> HealthLevel.WARN
            !serviceRunning -> HealthLevel.WARN
            else -> HealthLevel.OK
        }
        val summary = when {
            !installed -> "Termux 环境未安装（设置 → 终端 → 安装）"
            !ready -> "proot 未就绪"
            !serviceRunning -> "PTY 前台服务未启动"
            else -> "就绪"
        }
        return HealthSection("终端", level, summary, items)
    }

    // ==================== Skill ====================

    private fun checkSkill(context: Context): HealthSection {
        val list = try { SkillManager.list(context) } catch (e: Throwable) { emptyList() }
        val items = buildList {
            add(HealthItem("已导入 Skill 数", "${list.size}"))
            list.forEach { s ->
                val dir = File(SkillManager.skillsDir(context), s.id)
                val ok = s.content.isNotBlank()
                add(HealthItem(
                    "· ${s.id}",
                    "${s.name} v${s.version} · ${dir.absolutePath}",
                    if (ok) HealthLevel.OK else HealthLevel.WARN,
                ))
            }
        }
        val level = if (list.isEmpty()) HealthLevel.WARN else HealthLevel.OK
        val summary = if (list.isEmpty()) "未导入 Skill" else "${list.size} 个"
        return HealthSection("Skill", level, summary, items)
    }

    // ==================== 插件 ====================

    private fun checkPlugin(context: Context): HealthSection {
        val list = try { PluginManager.list(context) } catch (e: Throwable) { emptyList() }
        val enabled = list.count { PluginManager.isEnabled(context, it.id) }
        val items = buildList {
            add(HealthItem("已导入插件数", "${list.size}"))
            add(HealthItem("已启用", "$enabled"))
            list.forEach { p ->
                val guardN = try { PluginGuardBridge.guardCountOf(p.id) } catch (e: Throwable) { 0 }
                add(HealthItem("· ${p.id}", "${p.name} v${p.version} · Guard×$guardN"))
            }
        }
        val summary = "${list.size} 个导入 / $enabled 启用"
        return HealthSection("插件", HealthLevel.OK, summary, items)
    }

    // ==================== 会话日志 ====================

    private fun checkSessionLog(context: Context, convId: String): HealthSection {
        val dir = File(context.filesDir, "logs")
        if (!dir.exists()) {
            return HealthSection("会话日志", HealthLevel.WARN, "日志目录不存在（未开启或无日志）")
        }
        val files = try {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: emptyArray()
        } catch (e: Throwable) { emptyArray() }
        val totalSize = files.sumOf { it.length() }

        val items = buildList {
            add(HealthItem("目录", dir.absolutePath))
            add(HealthItem("文件数", "${files.size}"))
            add(HealthItem("总大小", fmtSize(totalSize)))
            files.maxByOrNull { it.lastModified() }?.let { latest ->
                add(HealthItem("最新文件", "${latest.name} · ${ts(latest.lastModified())}"))
            }
            if (convId.isNotBlank()) {
                val current = File(dir, "$convId.jsonl")
                add(HealthItem(
                    "当前对话日志",
                    if (current.exists()) "${fmtSize(current.length())} · ${ts(current.lastModified())}"
                    else "（无）",
                ))
            }
        }
        val level = if (files.isEmpty()) HealthLevel.WARN else HealthLevel.OK
        val summary = "${files.size} 个文件 · ${fmtSize(totalSize)}"
        return HealthSection("会话日志", level, summary, items)
    }

    // ==================== 系统权限 ====================

    private fun checkPermissions(context: Context): HealthSection {
        val st = try { PermissionHelper.status(context) }
        catch (e: Throwable) { PermissionHelper.Status(false, false, false, false) }
        val items = listOf(
            HealthItem("存储", if (st.storage) "已授权" else "未授权",
                if (st.storage) HealthLevel.OK else HealthLevel.WARN),
            HealthItem("通知", if (st.notification) "已授权" else "未授权",
                if (st.notification) HealthLevel.OK else HealthLevel.WARN),
            HealthItem("悬浮窗", if (st.overlay) "已授权" else "未授权",
                if (st.overlay) HealthLevel.OK else HealthLevel.WARN),
            HealthItem("无障碍", if (st.accessibility) "已连接" else "未开启",
                if (st.accessibility) HealthLevel.OK else HealthLevel.WARN),
        )
        val missing = listOf(st.storage, st.notification, st.overlay, st.accessibility).count { !it }
        val level = if (missing >= 3) HealthLevel.WARN else HealthLevel.OK
        val summary = if (missing == 0) "全部已授权" else "$missing 项未授权"
        return HealthSection("系统权限", level, summary, items)
    }

    // ==================== Shizuku ====================

    private fun checkShizuku(context: Context): HealthSection {
        val installed = try { ShizukuManager.isInstalled(context) } catch (e: Throwable) { false }
        val available = try { ShizukuManager.isAvailable(context) } catch (e: Throwable) { false }
        val items = listOf(
            HealthItem("已安装", if (installed) "是" else "否"),
            HealthItem("已授权", if (available) "是" else "否",
                if (available) HealthLevel.OK else HealthLevel.WARN),
        )
        val level = when {
            !installed -> HealthLevel.WARN
            !available -> HealthLevel.WARN
            else -> HealthLevel.OK
        }
        val summary = when {
            !installed -> "未安装（自动回退 app 沙箱）"
            !available -> "已装未授权"
            else -> "已授权（shell 提权可用）"
        }
        return HealthSection("Shizuku", level, summary, items)
    }
}