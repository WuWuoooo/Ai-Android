# PrismAI

运行在 Android 手机 / 平板上的智能助手 Agent（Kotlin + Compose），支持大模型对话、手机操控、终端执行、文件管理、定时任务、多模态、插件扩展等能力。

界面采用 **Material Design 3**，并针对**平板做自适应布局**（左侧导航常驻 + 双栏 / 右侧会话面板），整体信息架构模仿桌面端 DeepSeek Harness，但品牌标识为 PrismAI 自己的棱镜标。

## 功能特性

- 🤖 **Agent 对话**：多模型 Provider（OpenAI / Anthropic / DeepSeek / Kimi / 通义 / 智谱 / 自定义），流式输出、工具调用、子代理、会话日志
- 📱 **手机操控**：无障碍服务点按 / 滑动 / 输入 / 启动应用；可选实时镜像
- 💻 **内置终端**：proot + Termux 生态真 PTY；多会话 Tab；与 AI 共享；HOME 私有目录
- 🎨 **Skill（技能）**：导入 `.zip` 技能包，AI 通过 `load_skill` 读取说明并用终端执行其脚本
- 🖼️ **多模态**：文生图 / 文生视频 / 识图 / 识音频 / 识视频 / TTS / ASR / 联网搜索 / 音视频通话
- 🔊 **语音与媒体气泡**：AI 生成的语音、图片、视频以气泡形式嵌入对话
- 🗂️ **文件工具**：读写、搜索、移动、目录树；写路径白名单
- ⏰ **定时任务**：ONCE / INTERVAL / DAILY / WEEKLY
- 🧠 **长期记忆 + 项目记忆**：跨会话、按项目作用域
- 🔐 **权限分级**：读写 / 设备 / 系统 / 完全访问；完全访问跳过全部审批
- 🛡️ **Harness 化**：工具元信息、Guard 链、Shell 频率限制、结果缓存、审批缓存、SessionLog、插件 Guard
- 🔌 **插件系统**：静态挂件 + JS 动态挂件 + 主题 + Guard
- 🌐 **中英切换**：界面 + 提示词双套
- 🩺 **诊断页**：8 项环境自检 + 崩溃日志查看
- 📊 **悬浮窗**：可拖拽状态球 + 实时面板；支持内嵌回复

## 平板自适应

断点参照 Material 3 window size class，实现位于 `ui/adaptive/`：

| 窗口宽度 | 尺寸类别 | 布局 |
|---|---|---|
| < 600dp | COMPACT（手机） | 单栏 + 抽屉式侧栏（与原手机版一致） |
| 600–839dp | MEDIUM（折叠屏/小平板） | 侧栏常驻，默认收起为图标轨 |
| 840–1199dp | EXPANDED（平板横屏） | 侧栏常驻可折叠 + 消息区居中收窄 |
| ≥ 1200dp | LARGE | 再加右侧「会话面板」（工具时间线 / 统计 / 上下文） |

要点：

- **内容区宽度 ≠ 窗口宽度**：`LocalContentWideEnoughForTwoPane` 由 `PrismScaffold`
  在减去侧栏后计算，双栏（设置页"分类 | 内容"）只在真正够宽时才并排，
  避免窄屏上既挤侧栏又挤双栏。
- 侧栏宽度：收起 76dp / 展开 300dp；平板上触控目标不小于 48dp。
- 设置页在平板上由"分类 → 内容"两层导航改为左右并排，省掉来回跳转。
- 聊天页在 ≥1200dp 时默认展开右侧会话面板（可手动收起）。
- 品牌标为 PrismAI 棱镜标识，**未使用** DeepSeek Harness 的图标。

## 主题（Material Design 3）

`ui/theme/Color.kt` 提供 5 套预设，`ThemePreset.all()` 驱动设置页选择器：

| key | 配色 | 结构风格 |
|---|---|---|
| `minimal`（默认） | PrismAI 靛蓝（`#3448CE`） | DSH 极简：AI 消息裸显示 |
| `md3` | Material 3 baseline 紫（`#6750A4`） | M3 标准：靠 surface 层级区分 |
| `native` | Material 3 baseline 紫 | M3 标准 |
| `md3-mint` | Material 3 青绿 | M3 标准 |
| `brutal` | 新粗野主义 | 硬阴影 + 描边 |

每套色板均补齐了 M3 完整的 surface 容器层级
（`surfaceContainerLowest/Low/Container/High/Highest`、`surfaceBright/Dim`、`surfaceTint`…），
`MaterialTheme.shapes` 也切换为 M3 基线圆角阶调（4/8/12/16/28dp）。

## 技术栈

| 组件 | 版本 |
|---|---|
| AGP | 8.1.4 |
| Kotlin | 1.9.22 |
| Compose | 1.7.0 / Material3 1.3.0 |
| Room | 2.6.1 |
| OkHttp (SSE) | 4.12.0 |
| kotlinx.serialization | 1.6.3 |

`minSdk 24` / `targetSdk 34` / Java 17 / **ABI: arm64-v8a**

## 构建

```sh
./gradlew assembleDebug      # Debug
./gradlew assembleRelease    # Release（读根目录 prismai-release.jks，缺失则不打签名）
```

### 签名

Release 签名读取根目录 `prismai-release.jks`（不存在时自动跳过签名，
产出未签名 APK）。**不要**把 `*.jks` / `*.keystore` 提交到公开仓库。

### 原生库

`libproot / libproot_loader / libtalloc / libandroid-shmem / libtermux`
均为 **arm64-v8a 预编译库**，放在 `app/src/main/jniLibs/arm64-v8a/`，
因此 `abiFilters` 收窄为单一 ABI。

`libtermux.so` 的原生源码仍保留在 `terminal-emulator/src/main/jni/`
（`Android.mk` + `termux.c`），需要重建时执行：

```sh
$ANDROID_SDK/ndk/<ver>/ndk-build -C terminal-emulator/src/main/jni APP_ABI=arm64-v8a
```

## 项目结构

```
app/src/main/kotlin/com/prism/ai/
├── agent/           # AgentCore / ToolRegistry / ToolCatalog / ToolGuard
│   ├── tools/       # 各工具（终端 PTY、文件、Shell、多模态、子代理…）
│   └── AgentPrompts.kt
├── service/         # 无障碍 / 悬浮窗 / 镜像 / 调度 / 终端前台 Service / Shizuku
├── ui/
│   ├── adaptive/    # ⭐ 平板自适应：WindowSize / PrismScaffold / PrismSidebar
│   ├── chat/        # DshChatScreen（含右侧会话面板）
│   ├── settings/ terminal/ theme/ call/ components/
├── plugin/          # 插件抽象 + SkillManager
├── provider/        # AIProvider / Multimodal / Search
├── model/ storage/ util/ i18n/ ...
├── assets/          # Termux bootstrap + proot
└── jniLibs/         # arm64 原生库
```

## 版本

当前版本：`2.0.0`（versionCode 11）
- 2.0.0：改名 PrismAI（`applicationId: com.prism.ai`）、Material 3 色板与形状、
  平板自适应布局、新增 PrismAI 棱镜启动图标、外置存储路径迁移到 `/sdcard/PrismAI/`

## License

MIT License，见根目录 [`LICENSE.md`](./LICENSE.md)。

Copyright (c) 2026 PrismAI Contributors
