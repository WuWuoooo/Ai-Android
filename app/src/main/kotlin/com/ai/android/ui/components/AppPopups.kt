package com.ai.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

// ==================== 菜单项数据 ====================

data class AppMenuItem(
    val label: String,
    val sub: String = "",
    val icon: ImageVector? = null,
    val active: Boolean = false,
    val enabled: Boolean = true,
)

// ==================== 自绘菜单面板 ====================

@Composable
fun AppMenuPanel(
    items: List<AppMenuItem>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    emptyText: String? = null,
    scrollable: Boolean = true,
    maxWidth: Dp = 300.dp,
    chrome: Boolean = true,
    /** ⭐ 紧凑模式：缩小 padding / 字号 / 图标（用于对话历史、用户消息菜单） */
    compact: Boolean = false,
) {
    val hPad = if (compact) 12.dp else 14.dp
    val vPad = if (compact) 6.dp else 10.dp
    val titleHPad = if (compact) 12.dp else 14.dp
    val titleVPad = if (compact) 4.dp else 6.dp
    val iconSize = if (compact) 16.dp else 18.dp
    val iconGap = if (compact) 8.dp else 10.dp
    val labelFontSize = if (compact) 13.sp else 14.sp
    val subFontSize = if (compact) 10.sp else 11.sp
    val minHeight = if (compact) 32.dp else 40.dp
    val scrollMaxHeight = if (compact) 220.dp else 280.dp

    Column(
        modifier = modifier
            .widthIn(max = maxWidth)
            .then(
                if (chrome) Modifier
                    .shadow(8.dp, RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        RoundedCornerShape(14.dp),
                    )
                else Modifier
            )
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (scrollable) Modifier.heightIn(max = scrollMaxHeight).verticalScroll(rememberScrollState())
                else Modifier
            )
            .padding(vertical = 4.dp),
    ) {
        if (title != null) {
            Text(
                title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = titleHPad, vertical = titleVPad),
            )
        }
        if (items.isEmpty()) {
            if (emptyText != null) {
                Text(
                    emptyText,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = titleHPad, vertical = vPad),
                )
            }
        } else {
            items.forEachIndexed { idx, it ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .background(
                            if (it.active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                            else Color.Transparent
                        )
                        .then(if (it.enabled) Modifier.clickable { onPick(idx) } else Modifier)
                        .padding(horizontal = hPad, vertical = vPad),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    it.icon?.let { ic ->
                        Icon(
                            ic, null,
                            Modifier.size(iconSize),
                            tint = if (it.active) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(iconGap))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            it.label,
                            fontSize = labelFontSize,
                            fontWeight = if (it.active) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (it.enabled) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            maxLines = 1,
                        )
                        if (it.sub.isNotEmpty()) {
                            Text(
                                it.sub,
                                fontSize = subFontSize,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                    if (it.active) {
                        Icon(
                            Icons.Default.Check, null,
                            Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

// ==================== 自绘对话框 ====================

@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    confirmText: String? = null,
    onConfirm: (() -> Unit)? = null,
    confirmEnabled: Boolean = true,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = null,
    extraButtons: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Column(
            Modifier
                .fillMaxWidth()
                .shadow(14.dp, RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    RoundedCornerShape(20.dp),
                )
                .clip(RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            if (title != null) {
                Text(
                    title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(10.dp))
            }
            content()
            if (confirmText != null || dismissText != null || extraButtons != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    extraButtons?.invoke()
                    dismissText?.let {
                        TextButton(onClick = { onDismiss?.invoke() }) { Text(it, fontSize = 13.sp) }
                    }
                    confirmText?.let {
                        TextButton(onClick = { onConfirm?.invoke() }, enabled = confirmEnabled) {
                            Text(it, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==================== ⭐ 带动画的 Popup 菜单 ====================

/**
 * ⭐ 带动画的 Popup 菜单：
 *  - 用顶层非扩展 `AnimatedVisibility`（避免被 RowScope/ColumnScope 的扩展版本抢占）
 *  - 通过 `LaunchedEffect(visible)` 延迟 200ms 销毁 Popup，让**出场动画也能播完**
 *    （直接 `if (visible) Popup { ... }` 会在 visible=false 时立刻销毁 Popup，
 *     导致 exit 动画看不到）
 *
 * 用法：把原来 `AnimatedVisibility(visible=menu) { Popup { ... } }` 换成
 *       `AnimatedPopup(visible = menu, onDismissRequest = { menu = false }) { ...内容... }`
 *
 * @param visible 是否显示
 * @param alignment 锚点对齐（默认 BottomEnd）
 * @param offset 相对锚点的偏移（默认 0）
 * @param onDismissRequest 点击外部关闭回调
 * @param focusable 是否获取焦点（true = 点击外部自动关闭；false = 不拦截外部点击）
 * @param transformOrigin 缩放动画原点：
 *        右上角 = TransformOrigin(1f, 0f)
 *        右下角 = TransformOrigin(1f, 1f)
 *        左上角 = TransformOrigin(0f, 0f)
 *        左下角 = TransformOrigin(0f, 1f)
 * @param content 菜单内容
 */
@Composable
fun AnimatedPopup(
    visible: Boolean,
    onDismissRequest: () -> Unit = {},
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.BottomEnd,
    offset: IntOffset = IntOffset.Zero,
    focusable: Boolean = true,
    transformOrigin: TransformOrigin = TransformOrigin(1f, 0f),
    content: @Composable () -> Unit,
) {
    // ⭐ kept 用来延迟 Popup 的销毁：visible=false 时继续保留 Popup 直到 exit 动画播完
    var kept by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            kept = true
        } else if (kept) {
            // exit 动画时长（scaleOut 180ms）+ 少量余量
            delay(200)
            kept = false
        }
    }
    if (!kept) return

    Popup(
        alignment = alignment,
        offset = offset,
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = focusable),
    ) {
        // ⚠️ 这里必须用「顶层非扩展」的 AnimatedVisibility。
        //   Popup 的 content 是 `@Composable () -> Unit`（无 receiver），
        //   因此这里的 AnimatedVisibility 就是顶层版本，不会被 RowScope/ColumnScope 抢占。
        AnimatedVisibility(
            visible = visible,
            modifier = modifier,
            enter = fadeIn(animationSpec = tween(150)) + scaleIn(
                animationSpec = tween(180),
                initialScale = 0.85f,
                transformOrigin = transformOrigin,
            ),
            exit = fadeOut(animationSpec = tween(120)) + scaleOut(
                animationSpec = tween(180),
                targetScale = 0.85f,
                transformOrigin = transformOrigin,
            ),
        ) {
            content()
        }
    }
}