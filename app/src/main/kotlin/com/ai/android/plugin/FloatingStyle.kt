package com.ai.android.plugin

/**
 * 悬浮窗外观样式（可被 THEME 类插件覆盖）。
 * 默认值遵循内置的三套主题风格。
 */
data class FloatingStyle(
    val panelBg: Int = 0xFFFFFFFF.toInt(),
    val panelText: Int = 0xFF1F2937.toInt(),
    val panelTextSecondary: Int = 0xFF6B7280.toInt(),
    val panelActionText: Int = 0xFF9CA3AF.toInt(),
    val accent: Int = 0xFF2563EB.toInt(),
    val divider: Int = 0xFFE5E7EB.toInt(),
    val realtimeText: Int = 0xFF374151.toInt(),
    val ballBg: Int = 0xFF2563EB.toInt(),
    val ballText: Int = 0xFFFFFFFF.toInt(),
    val cornerRadius: Int = 18,
    val panelTitle: String = "AI 助手",
    val ballLabel: String = "AI",

    val ballShape: String = "circle",
    val ballSize: Int = 46,
    val ballIcon: String = "",
    val panelLayout: String = "default",

    /** ⭐ 面板边框宽度 dp（0=无边框）。brutal 风格用 2 */
    val panelStrokeWidth: Int = 0,
    /** ⭐ 面板边框颜色（与 panelBg 区分） */
    val panelStrokeColor: Int = 0xFFE5E7EB.toInt(),

    val chatBubble: ChatBubbleStyle = ChatBubbleStyle(),
    val features: List<String> = emptyList(),
) {
    companion object {
        /** 默认（无主题时用） */
        val DEFAULT = FloatingStyle()

        /** ⭐ 现代极简（Codex 风）：纯白面板 + 近黑球 + 20dp 圆角 + 无描边 */
        val MINIMAL = FloatingStyle(
            panelBg = 0xFFFFFFFF.toInt(),
            panelText = 0xFF1A1A1A.toInt(),
            panelTextSecondary = 0xFF6B6B6B.toInt(),
            panelActionText = 0xFFB0B0B0.toInt(),
            accent = 0xFF1A1A1A.toInt(),
            divider = 0xFFE8E8E8.toInt(),
            realtimeText = 0xFF4A4A4A.toInt(),
            ballBg = 0xFF1A1A1A.toInt(),
            ballText = 0xFFFFFFFF.toInt(),
            cornerRadius = 20,
            panelTitle = "AI 助手",
            ballLabel = "AI",
            ballShape = "circle",
            ballSize = 46,
            panelStrokeWidth = 1,
            panelStrokeColor = 0xFFE8E8E8.toInt(),
        )

        /** ⭐ 原生 Material 3：蓝紫球 + 圆润面板 + 无描边 */
        val NATIVE = FloatingStyle(
            panelBg = 0xFFFBFAFE.toInt(),
            panelText = 0xFF1A1B22.toInt(),
            panelTextSecondary = 0xFF45464F.toInt(),
            panelActionText = 0xFF767680.toInt(),
            accent = 0xFF3B5EDB.toInt(),
            divider = 0xFFC6C5D0.toInt(),
            realtimeText = 0xFF45464F.toInt(),
            ballBg = 0xFF3B5EDB.toInt(),
            ballText = 0xFFFFFFFF.toInt(),
            cornerRadius = 20,
            panelTitle = "AI 助手",
            ballLabel = "AI",
            ballShape = "circle",
            ballSize = 46,
            panelStrokeWidth = 0,
            panelStrokeColor = 0xFFC6C5D0.toInt(),
        )

        /** ⭐ 新粗野主义：橙红球 + 硬边 + 2dp 黑描边 + 小圆角 */
        val BRUTAL = FloatingStyle(
            panelBg = 0xFFFFFFFF.toInt(),
            panelText = 0xFF1F1A17.toInt(),
            panelTextSecondary = 0xFF4A3B32.toInt(),
            panelActionText = 0xFF8E7A6E.toInt(),
            accent = 0xFFFF5722.toInt(),
            divider = 0xFF1F1A17.toInt(),          // 黑描边
            realtimeText = 0xFF2A1200.toInt(),
            ballBg = 0xFFFF5722.toInt(),
            ballText = 0xFFFFFFFF.toInt(),
            cornerRadius = 6,                       // 小圆角
            panelTitle = "AI 助手",
            ballLabel = "AI",
            ballShape = "rounded",                  // 圆角矩形球
            ballSize = 50,
            panelStrokeWidth = 2,                   // 硬黑边
            panelStrokeColor = 0xFF1F1A17.toInt(),
        )

        /** 深色（保留兼容） */
        val DARK = FloatingStyle(
            panelBg = 0xF01F1F1F.toInt(),
            panelText = 0xFFFFFFFF.toInt(),
            panelTextSecondary = 0xFFB0B0B0.toInt(),
            panelActionText = 0xFFAEC6FF.toInt(),
            accent = 0xFF7FD4A8.toInt(),
            divider = 0x33FFFFFF,
            realtimeText = 0xE6E0E0E0.toInt(),
            ballBg = 0xE61F1F1F.toInt(),
            ballText = 0xFFFFFFFF.toInt(),
        )
    }

    fun isFeatureEnabled(feature: String): Boolean =
        features.isEmpty() || feature in features
}

data class ChatBubbleStyle(
    val cornerRadius: Int = 16,
    val userBg: Int? = null,
    val assistantBg: Int? = null,
    val textColor: Int? = null,
    val fontSize: Int = 0,
    val horizontalPadding: Int = 0,
    val verticalPadding: Int = 0,
) {
    companion object {
        val DEFAULT = ChatBubbleStyle()
    }
}

interface ThemeStyleProvider {
    val manifest: PluginManifest
    fun style(): FloatingStyle
}

class StaticThemeStyleProvider(
    override val manifest: PluginManifest,
    private val style: FloatingStyle,
) : ThemeStyleProvider {
    override fun style(): FloatingStyle = style
}