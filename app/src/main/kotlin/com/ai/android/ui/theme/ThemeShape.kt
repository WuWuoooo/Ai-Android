package com.ai.android.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 主题"结构"配置（区别于 ThemePreset 的纯色板）。
 *
 * 让三套风格在结构上区分，而不是只换颜色：
 * - minimal（Codex 风）：AI 消息裸显示、用户消息深色气泡、工具卡浅灰无描边
 * - native（Material 3）：AI/用户都有气泡、标准圆润、无描边
 * - brutal（Neubrutalism）：所有卡片都有 2dp 黑边、小圆角、硬阴影（黑块偏移）
 */
data class ThemeStyleConfig(
    // ---- AI 消息 ----
    /** AI 消息是否画背景气泡。false = 裸文本直接显示在页面背景上（Codex 风） */
    val showAiBubble: Boolean,

    // ---- 用户消息 ----
    /** 用户消息是否画气泡 */
    val showUserBubble: Boolean,
    /** 用户气泡是否用 inverseSurface 深色底 + inverseOnSurface 文字（Codex 风） */
    val useInverseUserBubble: Boolean,

    // ---- 气泡通用 ----
    /** 气泡圆角（dp） */
    val bubbleRadius: Int,
    /** 气泡边框宽度（dp），0=无边框 */
    val bubbleBorderWidth: Int,
    /** 气泡边框颜色从哪个 role 取：outline / outlineVariant / onSurface / none */
    val bubbleBorderRole: String,

    // ---- 工具卡 ----
    /** 工具卡圆角（dp） */
    val toolRadius: Int,
    /** 工具卡背景 role：surfaceContainerLow / surfaceContainer / surfaceVariant / none */
    val toolBgRole: String,
    /** 工具卡边框宽度（dp） */
    val toolBorderWidth: Int,
    /** 工具卡边框颜色 role */
    val toolBorderRole: String,

    // ---- 输入栏 ----
    /** 输入栏胶囊圆角（dp） */
    val inputRadius: Int,
    /** 输入栏边框宽度（dp） */
    val inputBorderWidth: Int,
    /** 输入栏边框颜色 role */
    val inputBorderRole: String,

    // ---- 硬阴影（brutal 专属）----
    /** 是否绘制硬阴影（offsetX/Y 的实色块，模拟 Neubrutalism 偏移阴影） */
    val useHardShadow: Boolean,
    val hardShadowOffsetX: Int,
    val hardShadowOffsetY: Int,
    /** 硬阴影颜色 role：onSurface / primary */
    val hardShadowColorRole: String,
)

object ThemeStyleConfigs {

    /** 现代极简（Codex 风） */
    val MINIMAL = ThemeStyleConfig(
        showAiBubble = false,               // ⭐ AI 裸文本
        showUserBubble = true,
        useInverseUserBubble = false,        // ⭐ 深色气泡白字
        bubbleRadius = 18,
        bubbleBorderWidth = 0,
        bubbleBorderRole = "none",
        toolRadius = 8,
        toolBgRole = "surfaceContainerLow", // 浅灰、无描边
        toolBorderWidth = 0,
        toolBorderRole = "none",
        inputRadius = 22,
        inputBorderWidth = 1,
        inputBorderRole = "outlineVariant",
        useHardShadow = false,
        hardShadowOffsetX = 0,
        hardShadowOffsetY = 0,
        hardShadowColorRole = "onSurface",
    )

    /** 原生 Material 3 */
    val NATIVE = ThemeStyleConfig(
        showAiBubble = true,
        showUserBubble = true,
        useInverseUserBubble = false,
        bubbleRadius = 20,
        bubbleBorderWidth = 1,
        bubbleBorderRole = "outlineVariant",
        toolRadius = 12,
        toolBgRole = "surfaceContainer",
        toolBorderWidth = 0,
        toolBorderRole = "none",
        inputRadius = 28,
        inputBorderWidth = 0,
        inputBorderRole = "none",
        useHardShadow = false,
        hardShadowOffsetX = 0,
        hardShadowOffsetY = 0,
        hardShadowColorRole = "onSurface",
    )

    /** 新粗野主义（Neubrutalism） */
    val BRUTAL = ThemeStyleConfig(
        showAiBubble = true,
        showUserBubble = true,
        useInverseUserBubble = false,
        bubbleRadius = 4,                   // ⭐ 小圆角、硬边感
        bubbleBorderWidth = 2,              // ⭐ 粗黑边
        bubbleBorderRole = "onSurface",
        toolRadius = 4,
        toolBgRole = "surfaceContainer",
        toolBorderWidth = 2,
        toolBorderRole = "onSurface",
        inputRadius = 8,
        inputBorderWidth = 2,
        inputBorderRole = "onSurface",
        useHardShadow = true,               // ⭐ 硬阴影
        hardShadowOffsetX = 4,
        hardShadowOffsetY = 4,
        hardShadowColorRole = "onSurface",
    )

    fun of(presetKey: String): ThemeStyleConfig = when (presetKey) {
        "native" -> NATIVE
        "brutal" -> BRUTAL
        else -> MINIMAL
    }
}

/** 当前生效的主题结构配置；组件通过 LocalThemeStyleConfig.current 读取 */
val LocalThemeStyleConfig = staticCompositionLocalOf { ThemeStyleConfigs.MINIMAL }