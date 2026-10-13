package com.ai.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ai.android.R
import com.ai.android.model.ToolCall
import com.ai.android.model.ToolResult
import com.ai.android.ui.theme.HardShadowWrapper
import com.ai.android.ui.theme.LocalThemeStyleConfig
import com.ai.android.ui.theme.themeColorOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun ToolCallCard(
    call: ToolCall,
    result: ToolResult? = null,
    modifier: Modifier = Modifier,
) {
    val theme = LocalThemeStyleConfig.current
    var expanded by remember { mutableStateOf(false) }
    var showFullDialog by remember { mutableStateOf(false) }

    val settings = com.ai.android.MainApp.instance.settings
    val argLimit = remember { settings.toolArgDisplayLimit() }
    val outputLimit = remember { settings.toolOutputDisplayLimit() }

    val running = result == null
    val success = result?.success ?: true

    val accent = when {
        running -> MaterialTheme.colorScheme.primary
        success -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    val statusMark = when {
        running -> "…"
        success -> "✓"
        else -> "✕"
    }
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(180),
        label = "tool_arrow",
    )

    val displayName = toolDisplayName(call.name)
    val summary = remember(call.arguments) { toolSummary(call.name, call.arguments) }

    val sArg = stringResource(R.string.tool_arg)
    val sResult = stringResource(R.string.tool_result)
    val sError = stringResource(R.string.tool_error)
    val sNoArgs = stringResource(R.string.tool_no_args)
    val sTruncatedN = stringResource(R.string.tool_truncated_n)
    val sViewAll = stringResource(R.string.tool_view_all)
    val sTruncatedHint = stringResource(R.string.tool_truncated_hint)
    val sFullTitle = stringResource(R.string.tool_full_result_title)

    val shape = RoundedCornerShape(theme.toolRadius.dp)
    val border = if (theme.toolBorderWidth > 0)
        BorderStroke(theme.toolBorderWidth.dp, themeColorOf(theme.toolBorderRole))
    else null

    HardShadowWrapper(
        enabled = theme.useHardShadow,
        offsetX = theme.hardShadowOffsetX,
        offsetY = theme.hardShadowOffsetY,
        shape = shape,
        color = themeColorOf(theme.hardShadowColorRole),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .background(themeColorOf(theme.toolBgRole))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = if (expanded) stringResource(R.string.conv_arrow_collapse)
                    else stringResource(R.string.conv_arrow_expand),
                    modifier = Modifier
                        .size(14.dp)
                        .graphicsLayer { rotationZ = arrowRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    displayName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    summary.ifBlank { sNoArgs },
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                if (running) {
                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp, color = accent)
                } else {
                    Text(statusMark, fontSize = 11.sp, color = accent, fontWeight = FontWeight.Bold)
                    if (result != null && result.durationMs > 0) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "${result.durationMs}ms",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(120)),
            ) {
                Column(Modifier.padding(top = 6.dp)) {
                    val argsPretty = remember(call.arguments) { prettyJson(call.arguments) }
                    if (argsPretty.isNotBlank() && argsPretty != "{}") {
                        val argsTruncated = argsPretty.length > argLimit
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sArg, fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.weight(1f))
                            if (argsTruncated) {
                                Text(
                                    sTruncatedN.format(argsPretty.length),
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            if (argsTruncated) argsPretty.take(argLimit) + "\n" + sTruncatedHint else argsPretty,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp, bottom = 6.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp))
                                .padding(6.dp),
                        )
                    }
                    if (result != null) {
                        val outputTruncated = result.output.length > outputLimit
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (success) sResult else sError,
                                fontSize = 10.sp,
                                color = if (success) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.weight(1f))
                            if (outputTruncated) {
                                TextButton(
                                    onClick = { showFullDialog = true },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                ) {
                                    Icon(Icons.Default.Fullscreen, null, Modifier.size(12.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(sViewAll.format(formatCount(result.output.length)), fontSize = 10.sp)
                                }
                            }
                        }
                        Text(
                            if (outputTruncated) result.output.take(outputLimit) + "\n" + sTruncatedHint
                            else result.output,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = if (success) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 2.dp)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                                    RoundedCornerShape(4.dp))
                                .padding(6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showFullDialog && result != null) {
        FullTextDialog(
            title = sFullTitle.format(displayName),
            text = result.output,
            onDismiss = { showFullDialog = false },
        )
    }
}

private fun formatCount(n: Int): String = when {
    n < 1000 -> "$n 字符"
    n < 1_000_000 -> "${"%.1f".format(n / 1000.0)}K 字符"
    else -> "${"%.1f".format(n / 1_000_000.0)}M 字符"
}

@Composable
private fun FullTextDialog(title: String, text: String, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            Modifier.fillMaxSize().padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, stringResource(R.string.close))
                    }
                }
                HorizontalDivider()
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

private fun toolDisplayName(name: String): String = when (name) {
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "edit_file" -> "编辑文件"
    "list_files" -> "列出文件"
    "list_dir_tree" -> "目录树"
    "search_files" -> "搜索文件"
    "create_dir" -> "创建目录"
    "delete_file" -> "删除文件"
    "move_file" -> "移动文件"
    "copy_file" -> "复制文件"
    "rename_file" -> "重命名"
    "run_shell_command" -> "运行命令"
    "run_js" -> "运行 JS"
    "terminal_exec" -> "终端命令"
    "terminal_read" -> "读取终端"
    "terminal_read_bg" -> "读取后台"
    "terminal_wait" -> "等待终端"
    "terminal_list_bg" -> "后台任务"
    "get_current_time" -> "当前时间"
    "web_search" -> "联网搜索"
    "fetch_url" -> "抓取网页"
    "accessibility_control" -> "操控手机"
    "create_scheduled_task" -> "创建任务"
    "list_scheduled_tasks" -> "任务列表"
    "cancel_scheduled_task" -> "取消任务"
    "save_memory" -> "保存记忆"
    "read_memory" -> "读取记忆"
    "list_memory" -> "记忆列表"
    "delete_memory" -> "删除记忆"
    "ask_user" -> "询问用户"
    "generate_image" -> "生成图片"
    "generate_video" -> "生成视频"
    "generate_speech" -> "生成语音"
    "analyze_image" -> "识图"
    "analyze_audio" -> "识音频"
    "analyze_video" -> "识视频"
    "project_memory" -> "项目记忆"
    "subagent" -> "子代理"
    "subagent_status" -> "子代理状态"
    "subagent_result" -> "子代理结果"
    "subagent_list" -> "子代理列表"
    else -> name
}

private fun toolSummary(name: String, argsJson: String): String {
    if (argsJson.isBlank()) return ""
    return runCatching {
        val obj = Json.parseToJsonElement(argsJson) as? JsonObject ?: return ""
        val keys = when (name) {
            "read_file", "write_file", "edit_file", "list_files", "list_dir_tree",
            "search_files", "create_dir", "delete_file", "move_file", "copy_file", "rename_file"
                -> listOf("path", "src", "dst", "keyword", "new_name")
            "run_shell_command", "terminal_exec" -> listOf("command", "cmd")
            "terminal_read", "terminal_read_bg" -> listOf("task", "lines")
            "terminal_wait" -> listOf("task", "pattern")
            "web_search" -> listOf("query")
            "fetch_url" -> listOf("url")
            "accessibility_control" -> listOf("action", "text", "package", "app_name")
            "save_memory", "read_memory", "delete_memory", "project_memory" -> listOf("key", "action")
            "create_scheduled_task" -> listOf("name", "prompt")
            "cancel_scheduled_task" -> listOf("id")
            "ask_user" -> listOf("question")
            "generate_image", "generate_video" -> listOf("prompt")
            "generate_speech" -> listOf("text", "style_prompt")
            "analyze_image", "analyze_audio", "analyze_video" -> listOf("question", "image_path", "audio_path", "video_path")
            "run_js" -> listOf("code")
            "subagent" -> listOf("task")
            "subagent_status", "subagent_result" -> listOf("taskId")
            else -> listOf("path", "command", "query", "prompt", "text")
        }
        for (k in keys) {
            val v = obj[k]?.jsonPrimitive?.contentOrNull
            if (!v.isNullOrBlank()) return@runCatching v.replace("\n", " ").take(120)
        }
        obj.entries.firstOrNull()?.let { (k, v) ->
            val s = v.jsonPrimitive.contentOrNull ?: v.toString()
            if (s.isNotBlank()) return@runCatching "$k=$s".replace("\n", " ").take(120)
        }
        ""
    }.getOrDefault("")
}

internal fun prettyJson(raw: String): String {
    if (raw.isBlank()) return ""
    return runCatching {
        val el: JsonElement = Json.parseToJsonElement(raw)
        if (el is JsonPrimitive) el.content
        else Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), el)
    }.getOrDefault(raw)
}