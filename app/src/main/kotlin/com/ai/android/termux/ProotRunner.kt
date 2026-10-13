package com.ai.android.termux

import android.content.Context
import java.io.File

/**
 * 构建 proot 命令。
 *
 * ⭐ 变更（v3）：`-w` 从 `/data/data/com.termux/files` 改为 `.../files/home`，
 * 让 PTY 会话一进来就在 HOME 目录（映射到 host filesDir/termux/home）。
 */
class ProotRunner(private val context: Context) {

    private val root: File = BootstrapInstaller.installRoot(context)

    fun isReady(): Boolean {
        val proot = BootstrapInstaller.getProotPath(context) ?: return false
        return File(proot).exists()
            && File(root, "usr/bin").exists()
            && File(root, ".installed").exists()
    }

    fun buildInteractiveCommand(): List<String> =
        buildCommand(userCommand = "bash", interactive = true)

    /**
     * PS1 初始化命令串（forkpty 后写入 pty 一次）。
     *  - 若 HOME/.bashrc 存在 → source 它
     *  - 否则设一个 Termux 风格彩色提示符
     *
     * 注意：Kotlin 原始字符串里字面美元符写 ${'$'}。
     * ⚠️ 本文件注释里禁止再出现任何「反斜杠」开头的序列（防 KAPT unicode-escape 报错）。
     */
    fun ps1InitCommand(): String = """
cd "${'$'}HOME" 2>/dev/null || true
if [ -f "${'$'}HOME/.bashrc" ]; then
    . "${'$'}HOME/.bashrc" 2>/dev/null
else
    PS1='\[\e[1;32m\]\w\[\e[0m\] ${'$'} '
    export PS1
fi
echo "AI Android PTY ready"
""".trimIndent()

    fun buildCommand(userCommand: String, interactive: Boolean = true): List<String> {
        val prootPath = BootstrapInstaller.getProotPath(context)
            ?: throw IllegalStateException("proot 未安装，请先到设置 → Termux 环境安装")

        val hostRoot = root.absolutePath
        val termuxPrefix = "/data/data/com.termux/files"

        return buildList {
            add(prootPath)
            add("--link2symlink")

            // rootfs = host 的 / + usr + home
            add("-b"); add("$hostRoot:$termuxPrefix")
            add("-b"); add("$hostRoot/usr:/usr")
            // ⭐ 显式挂 home（其实已由上面整目录 bind 覆盖，这里冗余但更清晰）
            add("-b"); add("$hostRoot/home:$termuxPrefix/home")

            // Android 系统目录
            add("-b"); add("/system:/system")
            add("-b"); add("/apex:/apex")
            add("-b"); add("/dev:/dev")
            add("-b"); add("/proc:/proc")
            add("-b"); add("/sys:/sys")
            add("-b"); add("/sdcard:/sdcard")
            add("-b"); add("/vendor:/vendor")
            add("-b"); add("/product:/product")

            // ⭐ cwd 改为 guest 的 home
            add("-w"); add("$termuxPrefix/home")

            if (interactive) {
                add("$termuxPrefix/usr/bin/bash")
                add("--norc")
                add("-i")
            } else {
                add("$termuxPrefix/usr/bin/bash")
                add("--noprofile")
                add("--norc")
                add("-c"); add(userCommand)
            }
        }
    }
}