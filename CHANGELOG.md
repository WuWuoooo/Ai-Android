# Changelog

格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [1.3.0] - 2026-10-13
**versionCode 10**

### Added
- **Skill 系统**：导入 `.zip` 技能包（递归查找 `SKILL.md`）；新增 `list_skills` / `load_skill` 工具，AI 可读取技能说明并用终端运行其脚本。
- **诊断页**：8 项环境自检（数据库 / Provider / 终端 / Skill / 插件 / 日志 / 权限 / Shizuku），流式展示，支持简化模式。
- **CrashHandler**：未捕获异常写入 `filesDir/crash_last.txt`，诊断页可查看与复制。
- **终端 Service 化**：前台 Service + `LocalBinder` 持有全部 PTY 会话，App 后台存活；多会话 Tab；会话元信息落 Room（`sessions` 表）。
- **语音 / 图片 / 视频气泡**：`generate_speech` / `generate_image` / `generate_video` 结构化输出后自动插入媒体气泡，语音支持进度条。
- **多模态扩展**：新增 VIDEO / AUDIO / VIDEO_UNDERSTAND 能力；`generate_video` / `analyze_audio` / `analyze_video` 工具；所有 API 调用类工具支持 `extra_body` / `extra_headers` 自定义。
- **本对话权限分级**：读写 / 设备 / 系统 / 完全访问 4 档；完全访问跳过全部 Guard 与审批。
- **子代理后台模式**：`subagent background=true` 立即返回 taskId；新增 `subagent_status` / `subagent_result` / `subagent_list` 工具。
- **中英双套提示词**：`AgentPrompts` 按当前语言输出系统提示词、标题总结、继续生成、上下文压缩。
- 数据库启动自动备份到 `/sdcard/AiAndroid/backup/`（保留最近 3 份）。

### Changed
- 终端 HOME 从 `/sdcard` 迁到 `/data/data/com.ai.android/files/termux/home`（永远可写）。
- 对话历史 / 插件 / 消息菜单统一为 `AnimatedPopup` + `AppMenuPanel`（三主题自适应）。
- 所有可折叠面板箭头统一为 `KeyboardArrowRight` + 旋转动画。
- 文件 / 图片选择器背景改用主题色（不再硬编码白色）。
- `AppDatabase` v3 → v5（新增 `sessions` 表，清理遗留索引）。
- `SettingsScreen` 分类：模型 / Agent / 界面 / 插件 / Harness / 系统 / 终端 / 对话 / 诊断 / 关于。

### Fixed
- **数据库"丢失"**：`MIGRATION_3_4` 多建索引导致 Room schema 校验失败抛异常被吞 → 删索引 + `MIGRATION_4_5` 清理 + 启动自动备份。
- **项目记忆永远为空**：`MemoryStore.projectMemoryList` 漏拼 `PREFIX` 前缀。
- **置顶 / 重命名 / 权限切换不生效**：改用 `.copy()` 生成新对象，`StateFlow` 才会发射。
- **LaTeX 渲染失败**：`\textsf` / `\hspace` / `\(...\)` 命令预处理（长名优先 + 剥离 + 文本兜底）。
- **诊断页闪退**：`LazyColumn` 的 item 内禁止嵌套 `verticalScroll`。
- **诊断页返回键**：加 `BackHandler`，不再直接弹回聊天。
- `ProviderManager` 缺 `subagent` 工具定义（AI 看不到工具）。
- `runCatching { }` 内调用 suspend 函数导致编译失败 → 改 `try/catch`。

## [1.2.0] - 2026-10-05
**versionCode 9**

### Added
- 语音输入、上下文窗口字段、对话历史抽屉、项目分区 + 项目记忆。
- 自绘文件 / 图片选择器；悬浮窗内直接回复；JS 动态悬浮窗。
- 多模态工具：`generate_image` / `generate_speech` / `analyze_image`。
- 自绘 `AppMenuPanel` / `AppDialog`；过渡动画。

### Fixed
- Shizuku 检测与提权（user-service 真提权）。
- 工作流静默停（`FinishInfo` + max_tokens 自动续写）。
- 菜单挤占布局 / 位置错误 → 全部改 material3 `DropdownMenu`。
- 文生图 404、TTS 无声。

### Changed
- `versionCode 8 → 9`，`versionName 1.1.0 → 1.2.0`。

## [1.1.0] - 2026-10-05
**versionCode 8**

### Added
- 请求体新手化（`ParamRow` 表单 / JSON 双向同步）。
- 多语言 + 语言包导入（`I18nManager`）。
- 插件升级（`FloatingStyle` / `ChatBubbleStyle` / `features`）。
- 用户消息编辑 + 重新生成。

### Fixed
- Agent 流式断流自动重试；底部 Ctrl/Alt 无效。

## [1.0.0-Stable] - 2026-10-05
**versionCode 7**

### Added
- 完整 PTY 终端模块（NDK / `libtermux.so`）。
- 终端交互化（Ctrl-C/D/Z、清屏、重启）。
- pkg/apt 优化、AI 二进制识别、终端输出残留修复。

## [1.0.0-beta5] - 2026-10-04
**versionCode 6**

### Added
- AI 操控手机（后台 + 实时镜像）。
- 插件接口抽象层。
- 悬浮球 / 面板重做。
- 终端按对话隔离。

## [1.0.0-beta4] - 2026-10-04
**versionCode 5**

### Added
- 对话导出 / 导入 JSON。
- 音视频通话（GLM-4-Voice / GLM-Realtime）。
- 多模态能力绑定 CALL。

### Fixed
- HTTP 400 根因修复：`sanitizeToolMessages`。

## [1.0.0-beta3] - 2026-10-03
**versionCode 4**

### Added
- 内置终端常驻 shell、定时任务、长期记忆、LaTeX 渲染、Web 搜索。

## [1.0.0-beta2] - 2026-10-03
**versionCode 2**

### Added
- Agent 对话与工具执行核心、手机操控、多模型 Provider、Compose UI。

## [1.0.0] - 2026-10-03
**versionCode 1**

### Added
- 项目初始版本。