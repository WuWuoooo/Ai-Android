package com.ai.android.plugin

import kotlinx.serialization.Serializable

/**
 * 插件清单：由导入的 .zip 包内 manifest.json 解析而来。
 *
 * capabilities 取值：
 *   "WIDGET" / "THEME" / "TOOL" / "EVENT_HOOK" / "MIRROR" / "FUNCTION" / "JS" / "GUARD"
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "1.0.0",
    val author: String = "",
    val capabilities: List<String> = emptyList(),
    /** beta5 = 静态资源路径；beta6 = JS 模块名 */
    val entryPoint: String = "",
    /** 挂件图标相对路径（相对插件目录） */
    val iconPath: String = "",
    /**
     * ⭐ 声明式 Guard（代码式 Guard 优先，声明式作为兜底/免 JS 场景）
     * manifest.json 里写：
     * "guards": [
     *   {"name":"no_delete_sdcard","toolFilter":"delete_file","argPattern":"/sdcard/重要","reason":"不能删重要文件"}
     * ]
     */
    val guards: List<PluginGuardDecl> = emptyList(),
)

/**
 * ⭐ 声明式 Guard 声明（无需 JS 代码）。
 *
 * 规则：
 *  - toolFilter：工具名精确匹配或 "*" 通配；支持逗号分隔多个
 *  - argPattern：可选，参数 JSON 字符串的正则（命中就拒绝）；空 = 只匹配工具名
 *  - reason：拒绝原因
 */
@Serializable
data class PluginGuardDecl(
    val name: String,
    val toolFilter: String = "*",
    val argPattern: String = "",
    val reason: String = "被插件 ${'$'}{name} 拦截",
)