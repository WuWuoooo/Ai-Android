package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.R
import com.ai.android.model.ChatMessage
import com.ai.android.model.ToolResult
import com.ai.android.plugin.ChatBubbleStyle
import com.ai.android.ui.components.AnimatedPopup
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import com.ai.android.ui.theme.HardShadowWrapper
import com.ai.android.ui.theme.LocalThemeStyleConfig
import com.ai.android.ui.theme.themeColorOf
import com.ai.android.util.MarkdownText
import java.io.File

internal fun activeChatBubbleStyle(): ChatBubbleStyle = runCatching {
    com.ai.android.MainApp.instance.pluginRegistry.activeChatBubbleStyle
}.getOrDefault(ChatBubbleStyle.DEFAULT)

internal fun pluginColor(v: Int?): Color? =
    if (v != null) Color(v.toLong() and 0xFFFFFFFFL) else null

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult> = emptyMap(),
    isLastAssistant: Boolean = false,
    showContinue: Boolean = false,
    onRetry: () -> Unit = {},
    onContinue: () -> Unit = {},
    onCopy: () -> Unit = {},
    onQuote: () -> Unit = {},
    onSpeak: () -> Unit = {},
    displayContentOverride: String? = null,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onEditUser: (String) -> Unit = {},
    onResendUser: (String) -> Unit = {},
    onDeleteMessage: () -> Unit = {},
    onEnterMultiSelect: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (message.role) {
        ChatMessage.Role.USER -> UserBubble(
            message, displayContentOverride, selectionMode, isSelected,
            onToggleSelect, onEditUser, onResendUser, onDeleteMessage, onEnterMultiSelect, modifier,
        )
        ChatMessage.Role.ASSISTANT -> AssistantBubble(
            message, toolResults, isLastAssistant, showContinue,
            onRetry, onContinue, onCopy, onQuote, onSpeak,
            selectionMode, isSelected, onToggleSelect,
            modifier,
        )
        ChatMessage.Role.TOOL, ChatMessage.Role.SYSTEM -> Unit
    }
}

// ==================== 用户消息气泡 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(
    message: ChatMessage,
    displayContentOverride: String?,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEditUser: (String) -> Unit,
    onResendUser: (String) -> Unit,
    onDeleteMessage: () -> Unit,
    onEnterMultiSelect: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val theme = LocalThemeStyleConfig.current
    val bubble = activeChatBubbleStyle()

    var showMenu by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var selectingText by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(displayContentOverride ?: extractUserDisplayContent(message.content)) }

    val displayText = displayContentOverride?.takeIf { it.isNotBlank() }
        ?: extractUserDisplayContent(message.content)

    // ⭐ 预取字符串
    val sMenuEdit = stringResource(R.string.menu_edit)
    val sMenuDelete = stringResource(R.string.menu_delete)
    val sMenuMulti = stringResource(R.string.menu_multi)
    val sMenuCopy = stringResource(R.string.menu_copy)
    val sMenuSelectText = stringResource(R.string.menu_select_text)
    val sMenuTitle = stringResource(R.string.edit)
    val sSelecting = stringResource(R.string.menu_selecting)
    val sEditTitle = stringResource(R.string.menu_edit_title)
    val sEditDesc = stringResource(R.string.menu_edit_desc)
    val sEditSave = stringResource(R.string.menu_edit_save)
    val sEditResend = stringResource(R.string.menu_edit_resend)
    val sEditPlaceholder = stringResource(R.string.menu_edit_placeholder)
    val sDeleteTitle = stringResource(R.string.menu_delete_title)
    val sDeleteConfirm = stringResource(R.string.menu_delete_confirm)
    val sDelete = stringResource(R.string.delete)
    val sCancel = stringResource(R.string.cancel)
    val sFinishSelect = stringResource(R.string.menu_finish_select)
    val sQuoted = stringResource(R.string.bubble_quoted)
    val sCopied = stringResource(R.string.chat_copied)
    val sImageLabel = stringResource(R.string.image_label)

    val radius = if (bubble.cornerRadius > 0) bubble.cornerRadius else theme.bubbleRadius
    val bubbleShape = RoundedCornerShape(
        topStart = radius.dp,
        topEnd = radius.dp,
        bottomEnd = if (radius >= 20) radius.dp else 6.dp,
        bottomStart = radius.dp,
    )

    val isDeep = theme.useInverseUserBubble
    val bubbleColor: Color = pluginColor(bubble.userBg)
        ?: when {
            selectionMode && isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            isDeep -> MaterialTheme.colorScheme.inverseSurface
            else -> MaterialTheme.colorScheme.primaryContainer
        }
    val textColor: Color = pluginColor(bubble.textColor)
        ?: if (isDeep) MaterialTheme.colorScheme.inverseOnSurface
        else MaterialTheme.colorScheme.onPrimaryContainer

    val bubbleFontSize = if (bubble.fontSize > 0) bubble.fontSize.sp else 15.sp
    val bubbleHPad = if (bubble.horizontalPadding > 0) bubble.horizontalPadding.dp else 12.dp
    val bubbleVPad = if (bubble.verticalPadding > 0) bubble.verticalPadding.dp else 9.dp
    val borderColor = themeColorOf(theme.bubbleBorderRole)
    val border = if (theme.bubbleBorderWidth > 0)
        BorderStroke(theme.bubbleBorderWidth.dp, borderColor) else null

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(Modifier.wrapContentWidth()) {
            Column(horizontalAlignment = Alignment.End) {
                if (message.files.isNotEmpty() || message.images.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(end = 4.dp, bottom = 4.dp),
                    ) {
                        message.files.forEach { path ->
                            val name = runCatching { File(path).name }.getOrDefault(path).take(14)
                            AttachChip(icon = Icons.Default.InsertDriveFile, text = name)
                        }
                        message.images.forEachIndexed { idx, _ ->
                            AttachChip(icon = Icons.Default.Image, text = "$sImageLabel ${idx + 1}")
                        }
                    }
                }

                HardShadowWrapper(
                    enabled = theme.useHardShadow,
                    offsetX = theme.hardShadowOffsetX,
                    offsetY = theme.hardShadowOffsetY,
                    shape = bubbleShape,
                    color = themeColorOf(theme.hardShadowColorRole),
                    modifier = Modifier.widthIn(max = 320.dp),
                ) {
                    Surface(
                        shape = bubbleShape,
                        color = bubbleColor,
                        border = border,
                        modifier = Modifier
                            .widthIn(max = 320.dp)
                            .combinedClickable(
                                onClick = { if (selectionMode) onToggleSelect() },
                                onLongClick = { if (!selectionMode) showMenu = true },
                            ),
                    ) {
                        Column(Modifier.padding(horizontal = bubbleHPad, vertical = bubbleVPad)) {
                            if (selectionMode) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                                    Text(sSelecting, fontSize = 11.sp, color = textColor)
                                    Spacer(Modifier.weight(1f))
                                }
                            }

                            if (message.quotedContent.isNotBlank()) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isDeep) Color.White.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                                ) {
                                    Column(Modifier.padding(6.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.AutoMirrored.Filled.Reply, null, Modifier.size(10.dp), tint = textColor)
                                            Spacer(Modifier.width(4.dp))
                                            Text(sQuoted, fontSize = 10.sp, color = textColor)
                                        }
                                        Text(
                                            message.quotedContent.take(120) +
                                                if (message.quotedContent.length > 120) "…" else "",
                                            fontSize = 11.sp,
                                            color = if (isDeep) textColor.copy(alpha = 0.85f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 3,
                                        )
                                    }
                                }
                            }

                            if (displayText.isNotBlank()) {
                                if (selectingText) {
                                    SelectionContainer {
                                        Text(displayText, fontSize = bubbleFontSize, color = textColor,
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                        TextButton(onClick = { selectingText = false }) {
                                            Text(sFinishSelect, fontSize = 11.sp, color = textColor)
                                        }
                                    }
                                } else {
                                    Text(displayText, fontSize = bubbleFontSize, color = textColor)
                                }
                            }
                        }
                    }
                }
            }

            AnimatedPopup(
                visible = showMenu,
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, 8),
                onDismissRequest = { showMenu = false },
                transformOrigin = TransformOrigin(1f, 0f),
            ) {
                AppMenuPanel(
                    items = listOf(
                        AppMenuItem(label = sMenuEdit, icon = Icons.Default.Edit),
                        AppMenuItem(label = sMenuDelete, icon = Icons.Default.Delete),
                        AppMenuItem(label = sMenuMulti, icon = Icons.Default.CheckCircle),
                        AppMenuItem(label = sMenuCopy, icon = Icons.Default.ContentCopy),
                        AppMenuItem(label = sMenuSelectText, icon = Icons.Default.TextFields),
                    ),
                    onPick = { idx ->
                        when (idx) {
                            0 -> {
                                showMenu = false; editOpen = true
                                editText = displayContentOverride ?: extractUserDisplayContent(message.content)
                            }
                            1 -> { showMenu = false; confirmDelete = true }
                            2 -> { showMenu = false; onEnterMultiSelect() }
                            3 -> {
                                showMenu = false
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                cm.setPrimaryClip(ClipData.newPlainText("ai", displayText))
                                Toast.makeText(context, sCopied, Toast.LENGTH_SHORT).show()
                            }
                            4 -> { showMenu = false; selectingText = true }
                        }
                    },
                    chrome = true,
                    title = sMenuTitle,
                    compact = true,
                    maxWidth = 180.dp,
                )
            }

            if (editOpen) {
                AlertDialog(
                    onDismissRequest = { editOpen = false },
                    title = { Text(sEditTitle) },
                    text = {
                        Column {
                            Text(sEditDesc,
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editText,
                                onValueChange = { editText = it },
                                modifier = Modifier.fillMaxWidth().height(140.dp),
                                placeholder = { Text(sEditPlaceholder, fontSize = 13.sp) },
                            )
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                onEditUser(editText.trim()); editOpen = false
                            }) { Text(sEditSave, fontSize = 13.sp) }
                            TextButton(onClick = {
                                onResendUser(editText.trim()); editOpen = false
                            }) { Text(sEditResend, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary) }
                        }
                    },
                    dismissButton = { TextButton(onClick = { editOpen = false }) { Text(sCancel, fontSize = 13.sp) } },
                )
            }

            if (confirmDelete) {
                AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text(sDeleteTitle) },
                    text = { Text(sDeleteConfirm, fontSize = 14.sp) },
                    confirmButton = {
                        TextButton(onClick = { confirmDelete = false; onDeleteMessage() }) {
                            Text(sDelete, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(sCancel, fontSize = 13.sp) } },
                )
            }
        }
    }
}

private fun extractUserDisplayContent(fullContent: String): String {
    var s = fullContent
    if (s.startsWith("【引用消息】")) {
        val end = s.indexOf("\n\n", startIndex = 6)
        if (end >= 0) s = s.substring(end + 2)
    }
    val attachIdx = s.indexOf("--- 以下是用户附加的文件内容 ---")
    if (attachIdx >= 0) s = s.substring(0, attachIdx).trimEnd()
    return s.trim()
}

@Composable
private fun AttachChip(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(12.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 10.sp)
        }
    }
}

// ==================== AI 消息气泡 ====================

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantBubble(
    message: ChatMessage,
    toolResults: Map<String, ToolResult>,
    isLastAssistant: Boolean,
    showContinue: Boolean,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSpeak: () -> Unit,
    selectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    modifier: Modifier,
) {
    val theme = LocalThemeStyleConfig.current
    val bubble = activeChatBubbleStyle()
    val sSelecting = stringResource(R.string.menu_selecting)

    val radius = if (bubble.cornerRadius > 0) bubble.cornerRadius else theme.bubbleRadius
    val aiShape = RoundedCornerShape(
        topStart = if (radius >= 20) radius.dp else 6.dp,
        topEnd = radius.dp,
        bottomEnd = radius.dp,
        bottomStart = radius.dp,
    )
    val aiColor = pluginColor(bubble.assistantBg)
        ?: when {
            selectionMode && isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else -> MaterialTheme.colorScheme.surface
        }
    val aiFontSize = if (bubble.fontSize > 0) bubble.fontSize.sp else 15.sp
    val aiHPad = if (bubble.horizontalPadding > 0) bubble.horizontalPadding.dp else 12.dp
    val aiVPad = if (bubble.verticalPadding > 0) bubble.verticalPadding.dp else 9.dp

    val borderColor = themeColorOf(theme.bubbleBorderRole)
    val border = if (theme.bubbleBorderWidth > 0 && theme.showAiBubble)
        BorderStroke(theme.bubbleBorderWidth.dp, borderColor) else null

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {

        if (message.reasoning.isNotBlank()) ReasoningPanel(message.reasoning, streaming = message.isStreaming)

        if (message.audioPath.isNotBlank()) {
            AudioBubble(path = message.audioPath, durationMs = message.audioDurationMs)
        }
        if (message.imagePath.isNotBlank()) {
            ImageBubble(path = message.imagePath)
        }
        if (message.videoPath.isNotBlank()) {
            VideoBubble(path = message.videoPath, durationMs = message.videoDurationMs)
        }

        if (message.content.isNotBlank() ||
            (message.isStreaming && message.toolCalls.isEmpty() && message.reasoning.isBlank())) {

            if (!theme.showAiBubble) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp, vertical = 2.dp)
                        .combinedClickable(
                            onClick = { if (selectionMode) onToggleSelect() },
                            onLongClick = { if (selectionMode) onToggleSelect() },
                        ),
                ) {
                    if (selectionMode) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                            Text(sSelecting, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    MarkdownText(
                        text = if (message.isStreaming) message.content + " ▍" else message.content,
                        fontSize = aiFontSize,
                    )
                }
            } else {
                HardShadowWrapper(
                    enabled = theme.useHardShadow,
                    offsetX = theme.hardShadowOffsetX,
                    offsetY = theme.hardShadowOffsetY,
                    shape = aiShape,
                    color = themeColorOf(theme.hardShadowColorRole),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Surface(
                        shape = aiShape,
                        color = aiColor,
                        border = border,
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { if (selectionMode) onToggleSelect() },
                                onLongClick = { if (selectionMode) onToggleSelect() },
                            ),
                    ) {
                        Column(Modifier.padding(horizontal = aiHPad, vertical = aiVPad)) {
                            if (selectionMode) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })
                                    Text(sSelecting, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Box {
                                MarkdownText(
                                    text = if (message.isStreaming) message.content + " ▍" else message.content,
                                    fontSize = aiFontSize,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (message.toolCalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                message.toolCalls.forEach { call -> ToolCallCard(call = call, result = toolResults[call.id]) }
            }
        }

        message.error?.let { err ->
            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text("❌ $err", modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        message.tokenStats?.let { s ->
            Text("↑${s.promptTokens} ↓${s.completionTokens} · 缓存命中 ${s.cacheRate}",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }

        if (isLastAssistant && !message.isStreaming && message.content.isNotBlank() && !selectionMode) {
            AssistantToolbar(
                showContinue = showContinue,
                onQuote = onQuote, onSpeak = onSpeak, onCopy = onCopy,
                onRetry = onRetry, onContinue = onContinue,
            )
        }
    }
}

@Composable
private fun AssistantToolbar(
    showContinue: Boolean,
    onQuote: () -> Unit,
    onSpeak: () -> Unit,
    onCopy: () -> Unit,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    var showMore by remember { mutableStateOf(false) }
    val sQuote = stringResource(R.string.toolbar_quote)
    val sSpeak = stringResource(R.string.toolbar_speak)
    val sCopy = stringResource(R.string.toolbar_copy)
    val sRetry = stringResource(R.string.toolbar_retry)
    val sMore = stringResource(R.string.toolbar_more)
    val sContinue = stringResource(R.string.toolbar_continue)
    val sNone = stringResource(R.string.toolbar_none)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        ToolbarBtn(sQuote, Icons.AutoMirrored.Filled.Reply, onQuote)
        ToolbarBtn(sSpeak, Icons.Default.VolumeUp, onSpeak)
        ToolbarBtn(sCopy, Icons.Default.ContentCopy, onCopy)
        ToolbarBtn(sRetry, Icons.Default.Refresh, onRetry)
        Box {
            ToolbarBtn(sMore, Icons.Default.MoreHoriz) { showMore = !showMore }
            AnimatedPopup(
                visible = showMore,
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, -8),
                onDismissRequest = { showMore = false },
                transformOrigin = TransformOrigin(1f, 1f),
            ) {
                AppMenuPanel(
                    items = if (showContinue) {
                        listOf(AppMenuItem(label = sContinue))
                    } else {
                        listOf(AppMenuItem(label = sNone, enabled = false))
                    },
                    onPick = { _ ->
                        showMore = false
                        if (showContinue) onContinue()
                    },
                    chrome = true,
                    title = sMore,
                    compact = true,
                    maxWidth = 160.dp,
                )
            }
        }
    }
}

@Composable
private fun ToolbarBtn(text: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        ),
    ) {
        Icon(icon, contentDescription = text, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp)
    }
}

// ==================== 思考过程 ====================

@Composable
private fun ReasoningPanel(text: String, streaming: Boolean = false) {
    val theme = LocalThemeStyleConfig.current
    var expanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val sReasoning = stringResource(R.string.reasoning_title)

    LaunchedEffect(text, expanded) {
        if (expanded && streaming && text.isNotEmpty()) {
            runCatching { scrollState.scrollTo(scrollState.maxValue) }
        }
    }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "reasoning_arrow",
    )
    val preview = text.replace("\n", " ").take(100)

    val panelBg = themeColorOf(theme.toolBgRole)
    val panelBorder = if (theme.toolBorderWidth > 0)
        BorderStroke(theme.toolBorderWidth.dp, themeColorOf(theme.toolBorderRole)) else null

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(theme.toolRadius.dp))
            .then(if (panelBorder != null) Modifier.border(panelBorder, RoundedCornerShape(theme.toolRadius.dp)) else Modifier)
            .background(panelBg)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.KeyboardArrowRight,
                contentDescription = if (expanded) stringResource(R.string.conv_arrow_collapse)
                else stringResource(R.string.conv_arrow_expand),
                modifier = Modifier
                    .size(12.dp)
                    .graphicsLayer { rotationZ = arrowRotation },
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text(sReasoning, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text(
                preview + if (text.length > 100) "…" else "",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (streaming) {
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(
                    Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
            exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = text,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}