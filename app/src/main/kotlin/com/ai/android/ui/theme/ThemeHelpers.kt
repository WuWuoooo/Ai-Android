package com.ai.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** 从 role 字符串取当前主题的颜色 */
@Composable
fun themeColorOf(role: String): Color = when (role) {
    "primary" -> MaterialTheme.colorScheme.primary
    "onPrimary" -> MaterialTheme.colorScheme.onPrimary
    "primaryContainer" -> MaterialTheme.colorScheme.primaryContainer
    "onPrimaryContainer" -> MaterialTheme.colorScheme.onPrimaryContainer
    "secondary" -> MaterialTheme.colorScheme.secondary
    "secondaryContainer" -> MaterialTheme.colorScheme.secondaryContainer
    "tertiary" -> MaterialTheme.colorScheme.tertiary
    "background" -> MaterialTheme.colorScheme.background
    "surface" -> MaterialTheme.colorScheme.surface
    "onSurface" -> MaterialTheme.colorScheme.onSurface
    "surfaceVariant" -> MaterialTheme.colorScheme.surfaceVariant
    "onSurfaceVariant" -> MaterialTheme.colorScheme.onSurfaceVariant
    "surfaceContainerLow" -> MaterialTheme.colorScheme.surfaceContainerLow
    "surfaceContainer" -> MaterialTheme.colorScheme.surfaceContainer
    "surfaceContainerHigh" -> MaterialTheme.colorScheme.surfaceContainerHigh
    "surfaceContainerHighest" -> MaterialTheme.colorScheme.surfaceContainerHighest
    "outline" -> MaterialTheme.colorScheme.outline
    "outlineVariant" -> MaterialTheme.colorScheme.outlineVariant
    "inverseSurface" -> MaterialTheme.colorScheme.inverseSurface
    "inverseOnSurface" -> MaterialTheme.colorScheme.inverseOnSurface
    "error" -> MaterialTheme.colorScheme.error
    "none", "" -> Color.Transparent
    else -> Color.Transparent
}

/**
 * Neubrutalism 硬阴影包裹：
 * 在内容层下方画一个同形状的实色块，向右下偏移 offsetX/Y。
 */
@Composable
fun HardShadowWrapper(
    enabled: Boolean,
    offsetX: Int,
    offsetY: Int,
    shape: Shape,
    color: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (!enabled || (offsetX == 0 && offsetY == 0)) {
        Box(modifier, contentAlignment = Alignment.TopStart) { content() }
        return
    }
    Box(modifier, contentAlignment = Alignment.TopStart) {
        // 阴影层（同尺寸、偏移、填色）
        Box(
            Modifier
                .matchParentSize()
                .offset(x = offsetX.dp, y = offsetY.dp)
                .clip(shape)
                .background(color),
        )
        content()
    }
}

/** 快捷：构造圆形/圆角形状 */
fun radiusShape(radius: Int): Shape = RoundedCornerShape(radius.dp)