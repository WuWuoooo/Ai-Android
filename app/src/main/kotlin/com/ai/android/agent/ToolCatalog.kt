package com.ai.android.agent

import kotlinx.serialization.json.JsonObject

enum class ToolScope { READ_ONLY, WRITE, DEVICE, SYSTEM }
enum class Concurrency { PARALLEL, SERIAL }

data class ToolMeta(
    val scope: ToolScope,
    val concurrency: Concurrency = Concurrency.PARALLEL,
    val timeoutMs: Long = 30_000,
    val cacheableMs: Long = 0,
)

object ToolCatalog {

    fun metaOf(name: String): ToolMeta = when (name) {

        "read_file" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 30_000, cacheableMs = 30_000)
        "list_files" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 15_000, cacheableMs = 30_000)
        "list_dir_tree" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 30_000, cacheableMs = 30_000)
        "search_files" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 60_000, cacheableMs = 30_000)

        "web_search" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 45_000, cacheableMs = 5 * 60_000)
        "fetch_url" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 60_000, cacheableMs = 5 * 60_000)

        "get_current_time" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)

        "read_memory" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "list_memory" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "list_scheduled_tasks" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "analyze_image" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 180_000)
        "analyze_audio" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 180_000)
        "analyze_video" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 300_000)

        "terminal_read" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.SERIAL, 15_000)
        "terminal_read_bg" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.SERIAL, 15_000)
        "terminal_list_bg" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.SERIAL, 15_000)

        "subagent_status" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "subagent_result" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "subagent_list" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)

        // ⭐ Skill 相关（只读）
        "list_skills" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "load_skill" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)

        "write_file" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 30_000)
        "edit_file" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 30_000)
        "create_dir" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 15_000)
        "save_memory" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 5_000)
        "delete_memory" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 5_000)
        "project_memory" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 5_000)
        "create_scheduled_task" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 10_000)
        "cancel_scheduled_task" -> ToolMeta(ToolScope.WRITE, Concurrency.SERIAL, 5_000)
        "generate_image" -> ToolMeta(ToolScope.WRITE, Concurrency.PARALLEL, 180_000)
        "generate_video" -> ToolMeta(ToolScope.WRITE, Concurrency.PARALLEL, 300_000)
        "generate_speech" -> ToolMeta(ToolScope.WRITE, Concurrency.PARALLEL, 120_000)

        "accessibility_control" -> ToolMeta(ToolScope.DEVICE, Concurrency.SERIAL, 60_000)

        "run_shell_command" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 90_000)
        "run_js" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 90_000)
        "terminal_exec" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 150_000)
        "terminal_wait" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 660_000)
        "terminal_list_sessions" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 5_000)
        "terminal_close_session" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 5_000)
        "delete_file" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 30_000)
        "move_file" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 60_000)
        "copy_file" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 120_000)
        "rename_file" -> ToolMeta(ToolScope.SYSTEM, Concurrency.SERIAL, 15_000)

        "ask_user" -> ToolMeta(ToolScope.READ_ONLY, Concurrency.SERIAL, 600_000)

        "subagent" -> ToolMeta(ToolScope.WRITE, Concurrency.PARALLEL, 600_000)

        else -> ToolMeta(ToolScope.READ_ONLY, Concurrency.PARALLEL, 30_000)
    }

    fun isParallelizable(name: String): Boolean = metaOf(name).concurrency == Concurrency.PARALLEL

    fun requiresApproval(scope: ToolScope): Boolean = when (scope) {
        ToolScope.READ_ONLY -> false
        ToolScope.WRITE -> true
        ToolScope.DEVICE -> true
        ToolScope.SYSTEM -> true
    }

    fun cacheableApproval(scope: ToolScope): Boolean = when (scope) {
        ToolScope.READ_ONLY -> false
        ToolScope.WRITE -> true
        ToolScope.DEVICE -> false
        ToolScope.SYSTEM -> false
    }

    fun levelName(level: Int): String = when (level) {
        0 -> "读写"
        1 -> "设备"
        2 -> "系统"
        else -> "完全访问"
    }

    fun minLevelOf(name: String): Int = when (name) {
        // 0 读写
        "read_file", "list_files", "list_dir_tree", "search_files",
        "write_file", "edit_file", "create_dir",
        "web_search", "fetch_url", "get_current_time",
        "read_memory", "list_memory", "save_memory", "delete_memory", "project_memory",
        "list_scheduled_tasks",
        "analyze_image", "analyze_audio", "analyze_video",
        "generate_image", "generate_video", "generate_speech",
        "ask_user", "subagent",
        "subagent_status", "subagent_result", "subagent_list",
        "terminal_read", "terminal_read_bg", "terminal_list_bg",
        "terminal_list_sessions",
        "list_skills", "load_skill" -> 0

        // 1 设备
        "accessibility_control" -> 1

        // 2 系统
        "run_shell_command", "run_js",
        "terminal_exec", "terminal_wait",
        "terminal_close_session",
        "delete_file", "move_file", "copy_file", "rename_file",
        "create_scheduled_task", "cancel_scheduled_task" -> 2

        else -> 2
    }
}