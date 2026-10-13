# Ai Android

运行在 Android 手机上的智能助手 Agent（Kotlin + Compose），支持大模型对话、手机操控、终端执行、文件管理、定时任务、多模态、插件扩展等能力。

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

## 技术栈

| 组件 | 版本 |
|---|---|
| AGP | 8.1.4 |
| Kotlin | 1.9.22 |
| Compose | 1.7.0 / Material3 1.3.0 |
| Room | 2.6.1 |
| OkHttp (SSE) | 4.12.0 |
| kotlinx.serialization | 1.6.3 |

`minSdk 24` / `targetSdk 34` / Java 17

## 构建

```sh
./gradlew assembleDebug      # Debug
./gradlew assembleRelease    # Release（需签名配置）
```

Release 签名：自行创建 keystore，**不要**把 `*.jks` / `*.keystore` 提交仓库。

## 项目结构

```
app/src/main/kotlin/com/ai/android/
├── agent/           # AgentCore / ToolRegistry / ToolCatalog / ToolGuard
│   ├── tools/       # 各工具（终端 PTY、文件、Shell、多模态、子代理…）
│   └── AgentPrompts.kt
├── service/         # 无障碍 / 悬浮窗 / 镜像 / 调度 / 终端前台 Service / Shizuku
├── ui/              # chat / terminal / settings / theme / call
├── plugin/          # 插件抽象 + SkillManager
├── provider/        # AIProvider / Multimodal / Search
├── model/ storage/ util/ i18n/ ...
├── assets/          # Termux bootstrap + proot
└── jniLibs/         # arm64 原生库
```

## 版本

当前版本：`1.3.0`（versionCode 10）

## License

MIT License，见根目录 [`LICENSE.md`](./LICENSE.md)。

Copyright (c) 2026 Ai Android Contributors