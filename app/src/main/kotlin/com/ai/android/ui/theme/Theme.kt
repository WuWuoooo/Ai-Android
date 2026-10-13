package com.ai.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 可配置的软件主题风格（插件 THEME 可覆盖三色）。
 */
data class AppThemeStyle(
    val mode: String = "follow",
    val primary: Int? = null,
    val background: Int? = null,
    val surface: Int? = null,
) {
    companion object {
        val DEFAULT = AppThemeStyle()
    }
}

/**
 * 三套内置风格预设：
 * - "minimal"  现代极简（参考 Codex / ChatGPT 风格）
 * - "native"   原生 Material 3
 * - "brutal"   新粗野主义（Neubrutalism）
 */
data class ThemePreset(
    val key: String,
    val label: String,
    val light: ColorScheme,
    val dark: ColorScheme,
) {
    companion object {

        // ============================================================
        // 1. MINIMAL —— 现代极简（参考 Codex）
        // ============================================================
        val MINIMAL = ThemePreset(
            key = "minimal",
            label = "现代极简",
light = lightColorScheme(
    primary = Color(0xFF1A1A1A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF0F0F0),
    onPrimaryContainer = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFFE8E8E8),

    secondary = Color(0xFF5C5C5C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF5F5F5),
    onSecondaryContainer = Color(0xFF2B2B2B),

    tertiary = Color(0xFF7A7A78),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5F5F5),
    onTertiaryContainer = Color(0xFF3A3A3A),

    // ⭐ 背景纯白（用户要求）
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),

    // ⭐ Surface 也纯白
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),

    // ⭐ 工具卡/代码块底：稍灰（在白底上能区分）
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF6B6B6B),

    outline = Color(0xFFC8C8C5),
    outlineVariant = Color(0xFFE8E8E8),

    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDE7E5),
    onErrorContainer = Color(0xFF5F1410),

    // ⭐ 用户气泡深色底
    inverseSurface = Color(0xFF2A2A2A),
    inverseOnSurface = Color(0xFFF5F5F3),
    scrim = Color(0xFF000000),

    // ⭐ Surface 系列：纯白 → 浅灰递进
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEDEDED),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFA),
    surfaceContainer = Color(0xFFF5F5F5),
    surfaceContainerHigh = Color(0xFFEFEFEF),
    surfaceContainerHighest = Color(0xFFE8E8E8),
    surfaceTint = Color(0xFF1A1A1A),
),
            dark = darkColorScheme(
                primary = Color(0xFFE8E8E6),
                onPrimary = Color(0xFF1A1A1A),
                primaryContainer = Color(0xFF2E2E2E),
                onPrimaryContainer = Color(0xFFE8E8E6),
                inversePrimary = Color(0xFF1A1A1A),

                secondary = Color(0xFFB0B0AE),
                onSecondary = Color(0xFF1A1A1A),
                secondaryContainer = Color(0xFF262626),
                onSecondaryContainer = Color(0xFFD6D6D4),

                tertiary = Color(0xFF8E8E8C),
                onTertiary = Color(0xFF1A1A1A),
                tertiaryContainer = Color(0xFF222222),
                onTertiaryContainer = Color(0xFFC8C8C6),

                background = Color(0xFF0F0F0F),
                onBackground = Color(0xFFECECEA),

                surface = Color(0xFF171717),
                onSurface = Color(0xFFECECEA),

                surfaceVariant = Color(0xFF262626),
                onSurfaceVariant = Color(0xFF9A9A98),

                outline = Color(0xFF3A3A3A),
                outlineVariant = Color(0xFF2A2A2A),

                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),

                inverseSurface = Color(0xFFECECEA),
                inverseOnSurface = Color(0xFF1A1A1A),
                scrim = Color(0xFF000000),

                surfaceBright = Color(0xFF2A2A2A),
                surfaceDim = Color(0xFF0F0F0F),
                surfaceContainerLowest = Color(0xFF0A0A0A),
                surfaceContainerLow = Color(0xFF151515),
                surfaceContainer = Color(0xFF1A1A1A),
                surfaceContainerHigh = Color(0xFF232323),
                surfaceContainerHighest = Color(0xFF2A2A2A),
                surfaceTint = Color(0xFFE8E8E6),
            ),
        )

        // ============================================================
        // 2. NATIVE —— 现代 Material 3
        // ============================================================
        val NATIVE = ThemePreset(
            key = "native",
            label = "原生",
            light = lightColorScheme(
                primary = Color(0xFF3B5EDB),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFDCE3FF),
                onPrimaryContainer = Color(0xFF00154D),
                inversePrimary = Color(0xFFB7C4FF),

                secondary = Color(0xFF5C5F71),
                onSecondary = Color(0xFFFFFFFF),
                secondaryContainer = Color(0xFFE1E2F1),
                onSecondaryContainer = Color(0xFF191B2C),

                tertiary = Color(0xFF76546E),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFFFD7F3),
                onTertiaryContainer = Color(0xFF2C122A),

                background = Color(0xFFFBFAFE),
                onBackground = Color(0xFF1A1B22),

                surface = Color(0xFFFBFAFE),
                onSurface = Color(0xFF1A1B22),

                surfaceVariant = Color(0xFFE2E1EC),
                onSurfaceVariant = Color(0xFF45464F),

                outline = Color(0xFF767680),
                outlineVariant = Color(0xFFC6C5D0),

                error = Color(0xFFB3261E),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFF9DEDC),
                onErrorContainer = Color(0xFF410E0B),

                inverseSurface = Color(0xFF2F3038),
                inverseOnSurface = Color(0xFFF2F0F7),
                scrim = Color(0xFF000000),

                surfaceBright = Color(0xFFFFFFFF),
                surfaceDim = Color(0xFFDBDAE0),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFF5F3FA),
                surfaceContainer = Color(0xFFF0EEF4),
                surfaceContainerHigh = Color(0xFFEAE8EF),
                surfaceContainerHighest = Color(0xFFE4E2E9),
                surfaceTint = Color(0xFF3B5EDB),
            ),
            dark = darkColorScheme(
                primary = Color(0xFFB7C4FF),
                onPrimary = Color(0xFF002584),
                primaryContainer = Color(0xFF1A3EB8),
                onPrimaryContainer = Color(0xFFDCE3FF),
                inversePrimary = Color(0xFF3B5EDB),

                secondary = Color(0xFFC5C6D7),
                onSecondary = Color(0xFF2E3041),
                secondaryContainer = Color(0xFF444658),
                onSecondaryContainer = Color(0xFFE1E2F1),

                tertiary = Color(0xFFE5BBD9),
                onTertiary = Color(0xFF442741),
                tertiaryContainer = Color(0xFF5D3D57),
                onTertiaryContainer = Color(0xFFFFD7F3),

                background = Color(0xFF121318),
                onBackground = Color(0xFFE4E1E9),

                surface = Color(0xFF121318),
                onSurface = Color(0xFFE4E1E9),

                surfaceVariant = Color(0xFF45464F),
                onSurfaceVariant = Color(0xFFC6C5D0),

                outline = Color(0xFF90909A),
                outlineVariant = Color(0xFF45464F),

                error = Color(0xFFF2B8B5),
                onError = Color(0xFF601410),
                errorContainer = Color(0xFF8C1D18),
                onErrorContainer = Color(0xFFF9DEDC),

                inverseSurface = Color(0xFFE4E1E9),
                inverseOnSurface = Color(0xFF2F3038),
                scrim = Color(0xFF000000),

                surfaceBright = Color(0xFF383840),
                surfaceDim = Color(0xFF121318),
                surfaceContainerLowest = Color(0xFF0D0E13),
                surfaceContainerLow = Color(0xFF1A1B21),
                surfaceContainer = Color(0xFF1E1F25),
                surfaceContainerHigh = Color(0xFF292A30),
                surfaceContainerHighest = Color(0xFF34353B),
                surfaceTint = Color(0xFFB7C4FF),
            ),
        )

        // ============================================================
        // 3. BRUTAL —— 新粗野主义（弱化刺眼感）
        // ============================================================
        val BRUTAL = ThemePreset(
            key = "brutal",
            label = "新粗野主义",
            light = lightColorScheme(
                primary = Color(0xFFFF5722),
                onPrimary = Color(0xFFFFFFFF),
                primaryContainer = Color(0xFFFFDCCF),
                onPrimaryContainer = Color(0xFF3A0C00),
                inversePrimary = Color(0xFFFFB59B),

                secondary = Color(0xFF1F1F1F),
                onSecondary = Color(0xFFFFEB3B),
                secondaryContainer = Color(0xFFFFF3A8),
                onSecondaryContainer = Color(0xFF1F1F1F),

                tertiary = Color(0xFF00A6A6),
                onTertiary = Color(0xFFFFFFFF),
                tertiaryContainer = Color(0xFFB8F0F0),
                onTertiaryContainer = Color(0xFF003434),

                background = Color(0xFFFFF8F2),
                onBackground = Color(0xFF1F1A17),

                surface = Color(0xFFFFFFFF),
                onSurface = Color(0xFF1F1A17),

                surfaceVariant = Color(0xFFFFE8D9),
                onSurfaceVariant = Color(0xFF4A3B32),

                outline = Color(0xFF8E7A6E),
                outlineVariant = Color(0xFFE8D2C2),

                error = Color(0xFFC62828),
                onError = Color(0xFFFFFFFF),
                errorContainer = Color(0xFFFFDAD6),
                onErrorContainer = Color(0xFF410E0B),

                inverseSurface = Color(0xFF2A2A2A),
                inverseOnSurface = Color(0xFFFFF3A8),
                scrim = Color(0xFF000000),

                surfaceBright = Color(0xFFFFFFFF),
                surfaceDim = Color(0xFFE8D6C7),
                surfaceContainerLowest = Color(0xFFFFFFFF),
                surfaceContainerLow = Color(0xFFFFF5EC),
                surfaceContainer = Color(0xFFFFEFE4),
                surfaceContainerHigh = Color(0xFFFFE8D9),
                surfaceContainerHighest = Color(0xFFF9DDCB),
                surfaceTint = Color(0xFFFF5722),
            ),
            dark = darkColorScheme(
                primary = Color(0xFFFF8A5C),
                onPrimary = Color(0xFF461300),
                primaryContainer = Color(0xFF8A2800),
                onPrimaryContainer = Color(0xFFFFDCCF),
                inversePrimary = Color(0xFFFF5722),

                secondary = Color(0xFFF5E04A),
                onSecondary = Color(0xFF1F1F1F),
                secondaryContainer = Color(0xFF4A4100),
                onSecondaryContainer = Color(0xFFFFF3A8),

                tertiary = Color(0xFF5CE0E0),
                onTertiary = Color(0xFF003434),
                tertiaryContainer = Color(0xFF005353),
                onTertiaryContainer = Color(0xFFB8F0F0),

                background = Color(0xFF1A1008),
                onBackground = Color(0xFFFCE0D0),

                surface = Color(0xFF1F1409),
                onSurface = Color(0xFFFCE0D0),

                surfaceVariant = Color(0xFF4A3222),
                onSurfaceVariant = Color(0xFFFFCCB5),

                outline = Color(0xFFA08B7A),
                outlineVariant = Color(0xFF4A3222),

                error = Color(0xFFFFB4AB),
                onError = Color(0xFF690005),
                errorContainer = Color(0xFF93000A),
                onErrorContainer = Color(0xFFFFDAD6),

                inverseSurface = Color(0xFFFFDCCF),
                inverseOnSurface = Color(0xFF2A1200),
                scrim = Color(0xFF000000),

                surfaceBright = Color(0xFF3A2A1E),
                surfaceDim = Color(0xFF1A1008),
                surfaceContainerLowest = Color(0xFF140C04),
                surfaceContainerLow = Color(0xFF20160B),
                surfaceContainer = Color(0xFF241A0F),
                surfaceContainerHigh = Color(0xFF2F2216),
                surfaceContainerHighest = Color(0xFF3A2A1E),
                surfaceTint = Color(0xFFFF8A5C),
            ),
        )

        fun all(): List<ThemePreset> = listOf(MINIMAL, NATIVE, BRUTAL)
        fun find(key: String): ThemePreset = all().firstOrNull { it.key == key } ?: MINIMAL
    }
}

/** 把十进制 ARGB Int 转成 Compose Color */
private fun toColor(v: Int): Color = Color(v.toLong() and 0xFFFFFFFFL)

/**
 * 三套风格对应的 Typography 覆写。
 */
private fun typographyFor(
    presetKey: String,
    base: androidx.compose.material3.Typography,
): androidx.compose.material3.Typography = when (presetKey) {

    "minimal" -> base.copy(
        displayLarge = TextStyle(fontWeight = FontWeight.Light, fontSize = 40.sp, letterSpacing = 0.2.sp),
        displayMedium = TextStyle(fontWeight = FontWeight.Light, fontSize = 34.sp, letterSpacing = 0.2.sp),
        displaySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 28.sp, letterSpacing = 0.2.sp),

        headlineLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 26.sp, letterSpacing = 0.1.sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp, letterSpacing = 0.1.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 19.sp),

        titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp),

        bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, letterSpacing = 0.1.sp, lineHeight = 22.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, letterSpacing = 0.1.sp, lineHeight = 20.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, letterSpacing = 0.1.sp, lineHeight = 16.sp),

        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.5.sp, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.5.sp, letterSpacing = 0.3.sp),
    )

    "brutal" -> base.copy(
        displayLarge = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 48.sp),
        displayMedium = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 40.sp),
        displaySmall = TextStyle(fontWeight = FontWeight.ExtraBold, fontSize = 32.sp),

        headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp),
        headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
        headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),

        titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),

        bodyLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp),

        labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 13.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 11.sp),
    )

    else -> base
}

@Composable
fun AiAndroidTheme(
    appTheme: AppThemeStyle = AppThemeStyle.DEFAULT,
    stylePreset: String = "minimal",
    revisionKey: Int = 0,
    content: @Composable () -> Unit,
) {
    val isDark = when (appTheme.mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }

    val preset = ThemePreset.find(stylePreset)
    val baseScheme = if (isDark) preset.dark else preset.light

    val colorScheme = if (appTheme != AppThemeStyle.DEFAULT) {
        baseScheme.copy(
            primary = if (appTheme.primary != null) toColor(appTheme.primary) else baseScheme.primary,
            background = if (appTheme.background != null) toColor(appTheme.background) else baseScheme.background,
            surface = if (appTheme.surface != null) toColor(appTheme.surface) else baseScheme.surface,
        )
    } else baseScheme

    val typography = typographyFor(preset.key, MaterialTheme.typography)
    val styleConfig = ThemeStyleConfigs.of(preset.key)

    // 系统状态栏 / 底部导航栏配色跟主题 background 同色
    val ctx = LocalContext.current
    SideEffect {
        val bg = colorScheme.background
        val statusBar = if (isDark) Color.Black else bg
        var unwrapped: android.content.Context = ctx
        while (unwrapped is android.content.ContextWrapper && unwrapped.baseContext != null) {
            unwrapped = unwrapped.baseContext
        }
        (unwrapped as? android.app.Activity)?.window?.let { w ->
            runCatching {
                w.statusBarColor = statusBar.toArgb()
                w.navigationBarColor = bg.toArgb()
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    w.isStatusBarContrastEnforced = false
                    w.isNavigationBarContrastEnforced = false
                    val flags = w.decorView.systemUiVisibility
                    w.decorView.systemUiVisibility = if (isDark)
                        flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                    else
                        flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
            }
        }
    }

    CompositionLocalProvider(LocalThemeStyleConfig provides styleConfig) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}