package com.ai.android.provider

import kotlinx.serialization.json.*

/**
 * 管理多个 Provider 实例，并构建全局工具定义。
 */
class ProviderManager {

    private val map = LinkedHashMap<String, AIProvider>()

    var activeId: String = ""
        private set

    val active: AIProvider?
        get() = map[activeId] ?: map.values.firstOrNull()

    val multimodal = MultimodalManager()

    init {
        upsert(ProviderConfig(
            id = "openai", label = "OpenAI",
            baseUrl = "https://api.openai.com/v1", model = "gpt-5.6-sol",
            capabilities = listOf("CHAT", "VISION", "IMAGE", "TTS", "ASR", "REALTIME"),
        ))
        upsert(ProviderConfig(
            id = "anthropic", label = "Anthropic",
            protocol = AIProvider.Protocol.ANTHROPIC,
            baseUrl = "https://api.anthropic.com", model = "claude-opus-5",
            capabilities = listOf("CHAT", "VISION"),
        ))
        upsert(ProviderConfig(
            id = "deepseek", label = "DeepSeek",
            baseUrl = "https://api.deepseek.com/v1", model = "deepseek-v4-pro",
            capabilities = listOf("CHAT", "VISION"),
        ))
    }

    fun upsert(config: ProviderConfig): AIProvider {
        val p = create(config)
        map[config.id] = p
        if (activeId.isEmpty()) activeId = config.id
        return p
    }

    fun activate(id: String): Boolean {
        if (!map.containsKey(id)) return false
        activeId = id
        return true
    }

    fun remove(id: String) {
        map.remove(id)
        if (activeId == id) activeId = map.keys.firstOrNull().orEmpty()
    }

    fun all(): List<AIProvider> = map.values.toList()

    fun create(config: ProviderConfig): AIProvider = when (config.protocol) {
        AIProvider.Protocol.ANTHROPIC -> AnthropicProvider(
            name = config.label,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            extraBodyJson = config.extraBodyJson,
            extraHeaders = config.headerMap(),
        )
        else -> OpenAIProvider(
            name = config.label,
            baseUrl = config.baseUrl,
            apiKey = config.apiKey,
            model = config.model,
            extraBodyJson = config.extraBodyJson,
            extraHeaders = config.headerMap(),
        )
    }

    fun buildTools(): List<JsonObject> = TOOLS

    companion object {

        private val TOOLS: List<JsonObject> by lazy { buildToolList() }

        private fun fn(
            name: String,
            desc: String,
            example: String = "",
            required: List<String> = emptyList(),
            props: JsonObjectBuilder.() -> Unit = {},
        ): JsonObject = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", name)
                val fullDesc = buildString {
                    append(desc.trim())
                    if (example.isNotBlank()) {
                        append("\n参数示例：")
                        append(example)
                    }
                }
                put("description", fullDesc)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject(props))
                    put("required", buildJsonArray { required.forEach { add(it) } })
                })
            })
        }

        private fun JsonObjectBuilder.s(n: String, d: String, required: Boolean = false) =
            put(n, buildJsonObject {
                put("type", "string")
                put("description", (if (required) "[必填] " else "[可选] ") + d)
            })

        private fun JsonObjectBuilder.i(n: String, d: String, required: Boolean = false) =
            put(n, buildJsonObject {
                put("type", "integer")
                put("description", (if (required) "[必填] " else "[可选] ") + d)
            })

        private fun JsonObjectBuilder.b(n: String, d: String) =
            put(n, buildJsonObject {
                put("type", "boolean")
                put("description", "[可选] " + d)
            })

        private fun JsonObjectBuilder.enumOf(n: String, d: String, values: List<String>) =
            put(n, buildJsonObject {
                put("type", "string")
                put("description", "[必填] " + d)
                put("enum", buildJsonArray { values.forEach { add(it) } })
            })

        /** 通用：给任何"调 API"的工具挂上 extra_body / extra_headers */
        private fun JsonObjectBuilder.apiFreedomParams() {
            s(
                "extra_body",
                "可选。JSON 对象字符串，会深合并进本次请求体（同名字段 extra 覆盖代码默认值）。" +
                    "例：{\"audio\":{\"voice\":\"茉莉\"},\"temperature\":0.9}",
            )
            s(
                "extra_headers",
                "可选。额外请求头，每行 \"Key: Value\"。",
            )
        }

private fun buildToolList(): List<JsonObject> = listOf(

    // ---------- 文件 ----------
    fn("read_file",
        "读取文件全文（大文件截断到 100KB）。改文件前务必先用本工具确认内容。" +
            "若文件是二进制（.dex/.so/图片/AXML 等），会返回 hex 预览并建议用 terminal_exec 跑 xxd/strings；" +
            "传 binary=true 可让工具直接返回前 4KB 的 hex dump。",
        """{"path":"/sdcard/Download/note.txt","binary":false}""",
        listOf("path"),
    ) {
        s("path", "文件的绝对路径，例：/sdcard/Download/note.txt", required = true)
        b("binary", "true=对二进制文件返回 hex dump（前4KB），默认 false")
    },
    fn("write_file",
        "创建新文件或整文件覆写。只想改一段内容请用 edit_file。",
        """{"path":"/sdcard/test.txt","content":"hello world"}""",
        listOf("path", "content"),
    ) {
        s("path", "文件绝对路径", required = true)
        s("content", "完整文件内容", required = true)
    },
    fn("edit_file",
        "精确替换文件中一段内容。old_string 必须与文件逐字符一致。",
        """{"path":"/sdcard/a.txt","old_string":"foo","new_string":"bar"}""",
        listOf("path", "old_string", "new_string"),
    ) {
        s("path", "文件路径", required = true)
        s("old_string", "要被替换的原文，逐字符一致", required = true)
        s("new_string", "替换后的内容", required = true)
        b("replace_all", "是否替换所有匹配，默认 false")
    },
    fn("list_files", "列出目录下一层内容。", """{"path":"/sdcard/Download"}""", listOf("path")) {
        s("path", "目录路径", required = true)
    },
    fn("list_dir_tree",
        "把目录列成缩进树形结构，适合快速概览。",
        """{"path":"/sdcard/AiAndroid","max_depth":3}""",
        listOf("path"),
    ) {
        s("path", "根目录路径", required = true)
        i("max_depth", "最大深度，默认 3，范围 1-8")
        i("max_entries", "最多条目数，默认 300")
    },
    fn("search_files",
        "按文件名或内容搜索文件。",
        """{"path":"/sdcard/Download","keyword":"invoice","in_content":true}""",
        listOf("path", "keyword"),
    ) {
        s("path", "搜索起始目录", required = true)
        s("keyword", "关键词，不区分大小写", required = true)
        b("in_content", "true=同时搜内容，false=只匹配文件名")
    },
    fn("create_dir", "创建目录（含中间目录）。", """{"path":"/sdcard/Download/Pictures"}""", listOf("path")) {
        s("path", "目录路径", required = true)
    },
    fn("delete_file",
        "删除文件或目录。删除目录必须 recursive=true。⚠️ 危险操作，先 ask_user。",
        """{"path":"/sdcard/tmp.txt"}""",
        listOf("path"),
    ) {
        s("path", "路径", required = true)
        b("recursive", "删目录时是否连带内容一起删，默认 false")
    },
    fn("move_file", "移动文件或目录。", """{"src":"/sdcard/a.txt","dst":"/sdcard/Download/a.txt"}""", listOf("src", "dst")) {
        s("src", "源路径", required = true)
        s("dst", "目标路径", required = true)
        b("overwrite", "目标存在时是否覆盖，默认 false")
    },
    fn("copy_file", "复制文件或目录。", """{"src":"/sdcard/a.txt","dst":"/sdcard/b.txt"}""", listOf("src", "dst")) {
        s("src", "源路径", required = true)
        s("dst", "目标路径", required = true)
        b("overwrite", "目标存在时是否覆盖，默认 false")
    },
    fn("rename_file", "重命名（只改名字，不移动位置）。", """{"path":"/sdcard/a.txt","new_name":"b.txt"}""", listOf("path", "new_name")) {
        s("path", "原完整路径", required = true)
        s("new_name", "新文件名（不含路径分隔符）", required = true)
    },

    // ---------- 系统 ----------
    fn("run_shell_command",
        "执行一次性 shell 命令并等待结果（超时最长 60s）。适合快速、短小、无副作用的命令。" +
            "长任务/装包(pip/apt/pkg)/跑 Python 脚本 超过 60s 请用 terminal_exec。",
        """{"command":"ls /sdcard/Download","timeout_sec":15}""",
        listOf("command"),
    ) {
        s("command", "sh 语法命令", required = true)
        i("timeout_sec", "超时秒数，默认 15，最大 60")
    },
    fn("run_js",
        "在 WebView V8 引擎执行 JavaScript。用 __send(结果) 把结果传回；不调用则超时。",
        """{"code":"__send(1+2)"}""",
        listOf("code"),
    ) {
        s("code", "JS 代码，用 __send(结果) 返回", required = true)
        i("timeout_sec", "超时秒数，默认 15，最大 60")
    },

    // ---------- 终端（PTY + 多会话）----------
    fn("terminal_exec",
        "在 PTY 终端执行命令（跨调用保持 cwd/env）。适合：装包(pkg/apt)、跑 Python、长时间任务。\n" +
            "支持多会话：不传 session_id 用当前对话默认会话；传则用指定会话（先 terminal_list_sessions 查询）。\n" +
            "如果超时，命令仍在后台跑，用 terminal_read 读后续输出。\n" +
            "传 background=true 则命令立即后台化（nohup + 重定向日志），返回 task id。",
        """{"command":"pkg install python -y","timeout_sec":60,"session_id":""}""",
        listOf("command"),
    ) {
        s("command", "sh 语法命令", required = true)
        i("timeout_sec", "等待超时秒数，默认 60，最大 120")
        b("background", "true=立即后台化并返回 task id；默认 false 阻塞等待")
        s("session_id", "可选：指定会话 id；不填则用当前对话的默认会话")
    },
    fn("terminal_read",
        "读取 PTY 终端最近 N 行输出。",
        """{"lines":50,"since_last":true,"session_id":""}""",
    ) {
        i("lines", "读取行数，默认 40，最大 500")
        b("since_last", "true=只返回上次读取后新增的输出")
        s("session_id", "可选：指定会话 id；不填则用当前对话默认会话")
    },
    fn("terminal_list_sessions",
        "列出当前对话的所有 PTY 会话及状态。",
        "{}",
    ),
    fn("terminal_close_session",
        "关闭指定 PTY 会话。",
        """{"session_id":"pty-xxxxxxxx"}""",
        listOf("session_id"),
    ) {
        s("session_id", "要关闭的会话 id（terminal_list_sessions 查询）", required = true)
    },
    fn("terminal_read_bg",
        "按 task id 读取 terminal_exec background=true 启动的后台命令日志（不阻塞）。",
        """{"task":"abc123","lines":100}""",
        listOf("task"),
    ) {
        s("task", "后台任务 id", required = true)
        i("lines", "读取日志最近行数，默认 100，最大 500")
    },
    fn("terminal_wait",
        "非阻塞等待后台任务结束或日志匹配到指定模式。",
        """{"task":"abc123","pattern":"done","timeout_sec":300}""",
        listOf("task"),
    ) {
        s("task", "后台任务 id", required = true)
        s("pattern", "可选：日志里出现此字符串时立即返回")
        i("timeout_sec", "最长等待秒数，默认 300，最大 600")
    },
    fn("terminal_list_bg", "列出当前所有后台任务。", "{}"),

    // ---------- ⭐ Skill ----------
    fn("list_skills",
        "列出所有已导入的 Skill（技能）。返回 id / 名称 / 简介 / 标签。",
        "{}",
    ),
    fn("load_skill",
        "加载指定 Skill 的完整说明（SKILL.md 全文 + Skill 根目录路径）。\n" +
            "使用流程：先 list_skills 看有哪些 → load_skill 拿全文 → 按说明用 terminal_exec " +
            "cd 到 Skill 根目录并运行 scripts/ 里的脚本。",
        """{"skill_id":"geo-sleuth"}""",
        listOf("skill_id"),
    ) {
        s("skill_id", "Skill id（用 list_skills 查询）", required = true)
    },

    // ---------- 网络 ----------
    fn("get_current_time", "获取当前日期时间（含星期）。无需参数。", "{}"),
    fn("web_search", "联网搜索，返回标题/链接/摘要。", """{"query":"今天上海天气"}""", listOf("query")) {
        s("query", "搜索关键词", required = true)
    },
    fn("fetch_url", "抓取网页转纯文本。", """{"url":"https://example.com/article"}""", listOf("url")) {
        s("url", "完整网址，必须 http:// 或 https:// 开头", required = true)
    },

    // ---------- 设备 ----------
    fn("accessibility_control",
        "通过无障碍服务操控手机界面。先 screen 看控件再点。敏感操作先 ask_user。",
        """{"action":"tap","x":540,"y":1200}""",
        listOf("action"),
    ) {
        enumOf("action", "操作类型",
            listOf("screen", "screenshot", "tap", "long_press", "swipe", "click_text", "input",
                "back", "home", "scroll", "launch_app", "sleep", "recents", "notifications", "stop_projection"))
        i("x", "tap/long_press 的横坐标")
        i("y", "tap/long_press 的纵坐标")
        i("x1", "swipe 起点横坐标")
        i("y1", "swipe 起点纵坐标")
        i("x2", "swipe 终点横坐标")
        i("y2", "swipe 终点纵坐标")
        i("duration_ms", "swipe 时长 ms，默认 400")
        s("text", "click_text=控件文字 / input=输入文本")
        enumOf("direction", "scroll 方向", listOf("up", "down"))
        s("package", "launch_app 的包名，如 com.tencent.mm")
        i("ms", "sleep 等待毫秒数，最长 5000")
    },

    // ---------- 定时任务 ----------
    fn("create_scheduled_task",
        "创建定时任务，到点自动用 Agent 执行 prompt。",
        """{"name":"早安提醒","prompt":"告诉我今天天气","type":"DAILY","time_of_day":"08:00"}""",
        listOf("name", "prompt", "type"),
    ) {
        s("name", "任务名", required = true)
        s("prompt", "要执行的独立指令", required = true)
        enumOf("type", "任务类型", listOf("ONCE", "INTERVAL", "DAILY", "WEEKLY"))
        i("interval_minutes", "INTERVAL 类型：间隔分钟数")
        s("time_of_day", "DAILY/WEEKLY：HH:mm 格式，例 08:00")
        s("days_of_week", "WEEKLY：1=周一…7=周日，逗号分隔")
    },
    fn("list_scheduled_tasks", "列出全部定时任务。", "{}"),
    fn("cancel_scheduled_task", "取消定时任务。", """{"id":"a1b2c3d4"}""", listOf("id")) {
        s("id", "任务 ID（来自 list_scheduled_tasks）", required = true)
    },

    // ---------- 记忆 ----------
    fn("save_memory", "写入跨会话长期记忆。", """{"key":"user_name","content":"小明"}""", listOf("key", "content")) {
        s("key", "记忆标识（英文短词）", required = true)
        s("content", "要记住的内容", required = true)
    },
    fn("read_memory", "读取指定长期记忆。", """{"key":"user_name"}""", listOf("key")) {
        s("key", "记忆标识", required = true)
    },
    fn("list_memory", "列出所有长期记忆。", "{}"),
    fn("delete_memory",
        "删除指定 key 的长期记忆。⚠️ 不可恢复。",
        """{"key":"user_name"}""",
        listOf("key"),
    ) {
        s("key", "要删除的记忆标识", required = true)
    },

    // ---------- 交互 ----------
    fn("ask_user",
        "向用户提问并等待回答。危险操作前必须先调用本工具确认。",
        """{"question":"确认删除？","options":"确认,取消"}""",
        listOf("question"),
    ) {
        s("question", "要问的问题", required = true)
        s("options", "可选：逗号分隔的选项")
    },

    // ---------- 多模态：生成 ----------
    fn("generate_image",
        "用文生图 Provider 生成图片（需已配置 IMAGE 能力）。\n" +
            "⭐ 支持 extra_body / extra_headers 覆盖代码默认值。",
        """{"prompt":"一只在草地上奔跑的橘猫","size":"1024x1024"}""",
        listOf("prompt"),
    ) {
        s("prompt", "图片描述", required = true)
        s("size", "尺寸，默认 1024x1024")
        apiFreedomParams()
    },
    fn("generate_video",
        "用视频生成 Provider 生成视频（需已配置 VIDEO 能力）。\n" +
            "⭐ 支持 extra_body / extra_headers。\n" +
            "⭐ 支持首帧控制（first_frame）实现图生视频。",
        """{"prompt":"雨后的未来城市街道","duration_sec":5,"size":"720P","aspect_ratio":"16:9"}""",
        listOf("prompt"),
    ) {
        s("prompt", "视频内容描述", required = true)
        i("duration_sec", "时长秒数，默认 5，范围 4-12")
        s("size", "分辨率，默认 720P")
        s("aspect_ratio", "画幅比例，默认 16:9")
        s("first_frame", "首帧图片 URL（图生视频，可选）")
        apiFreedomParams()
    },
    fn("generate_speech",
        "用 TTS Provider 将文字合成为语音。成功后以「语音气泡」形式出现在对话里。\n" +
            "⭐ 若绑定的是音色设计模型（voicedesign），用 style_prompt 描述音色。\n" +
            "⭐ 支持 extra_body / extra_headers。",
        """{"text":"你好，我是 AI 助手","voice":"冰糖","style_prompt":"慵懒温柔的女声"}""",
        listOf("text"),
    ) {
        s("text", "要朗读的正文（最多 4096 字符）", required = true)
        s("voice", "音色，默认 冰糖；可选 茉莉/苏打/白桦/Mia/Chloe/Milo/Dean 等")
        s("style_prompt", "音色设计描述（仅 voicedesign 模型生效）")
        apiFreedomParams()
    },

    // ---------- 多模态：理解 ----------
    fn("analyze_image",
        "用识图（VISION）Provider 分析图片。\n" +
            "⭐ 支持 extra_body / extra_headers。",
        """{"image_path":"/sdcard/Pictures/cat.jpg","question":"这是什么猫？"}""",
        emptyList(),
    ) {
        s("image_path", "图片文件绝对路径（与 image_base64 / image_url 三选一）")
        s("image_base64", "图片 base64 字符串")
        s("image_url", "图片 URL")
        s("question", "针对图片的问题，默认：请描述这张图片的内容")
        apiFreedomParams()
    },
    fn("analyze_audio",
        "用多模态 Provider 理解音频内容。\n" +
            "⭐ 支持 extra_body / extra_headers。",
        """{"audio_path":"/sdcard/AiAndroidAudio/tts_xxx.wav","question":"这段音频说了什么？"}""",
        emptyList(),
    ) {
        s("audio_path", "音频文件绝对路径（与 audio_base64 / audio_url 三选一）")
        s("audio_base64", "音频 base64 字符串")
        s("audio_url", "音频 URL")
        s("question", "针对音频的问题，默认：请描述这段音频的内容")
        apiFreedomParams()
    },
    fn("analyze_video",
        "用多模态 Provider 理解视频内容。\n" +
            "⭐ 支持 extra_body / extra_headers。\n" +
            "⭐ fps 控制抽帧密度，media_resolution 控制分辨率档次。",
        """{"video_url":"https://example.com/clip.mp4","question":"视频里发生了什么？","fps":2}""",
        emptyList(),
    ) {
        s("video_path", "视频文件绝对路径（与 video_base64 / video_url 三选一）")
        s("video_base64", "视频 base64 字符串")
        s("video_url", "视频 URL")
        s("question", "针对视频的问题，默认：请描述这段视频的内容")
        i("fps", "每秒抽帧数，默认 2，范围 0.1-10")
        s("media_resolution", "帧分辨率档次，默认 default；可选 max")
        apiFreedomParams()
    },

    // ---------- 项目记忆 ----------
    fn("project_memory",
        "管理当前对话所属项目的独立项目记忆。action=list / save / read / delete。",
        """{"action":"save","key":"goal","content":"本项目目标是…"}""",
        listOf("action"),
    ) {
        enumOf("action", "动作", listOf("list", "save", "read", "delete"))
        s("key", "记忆标识（save/read/delete 必填）")
        s("content", "要保存的内容（save 必填）")
    },

    // ---------- 子代理 ----------
    fn("subagent",
        "把一段独立任务委派给子代理执行。子代理有独立的上下文和工具循环，只把最终总结返回。\n\n" +
            "【前台模式 background=false（默认）】主 Agent 阻塞等子代理跑完。\n" +
            "【后台模式 background=true】立即返回 taskId，子代理在后台异步执行。\n\n" +
            "限制：子代理最多 5 轮，不能再调 subagent，不能调 ask_user。",
        """{"task":"在 /sdcard/Download 里找出所有 .pdf 文件并列出","background":false}""",
        listOf("task"),
    ) {
        s("task", "要委派给子代理的任务描述。越具体越好。", required = true)
        b("background", "true=后台异步执行（立即返回 taskId）；false=前台阻塞（默认）")
        s("exclude_tools", "可选：逗号分隔的工具名黑名单")
    },
    fn("subagent_status",
        "查询后台子代理任务的状态（运行中 / 已完成 / 失败）及耗时。",
        """{"taskId":"sub-1234567890"}""",
        listOf("taskId"),
    ) {
        s("taskId", "后台子代理任务 id（subagent background=true 返回的）", required = true)
    },
    fn("subagent_result",
        "取后台子代理任务的最终总结。",
        """{"taskId":"sub-1234567890"}""",
        listOf("taskId"),
    ) {
        s("taskId", "后台子代理任务 id", required = true)
    },
    fn("subagent_list",
        "列出当前对话的所有后台子代理任务及其状态。",
        "{}",
    ),
)
    }
}