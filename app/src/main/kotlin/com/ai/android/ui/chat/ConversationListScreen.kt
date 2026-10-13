package com.ai.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import com.ai.android.MainViewModel
import com.ai.android.model.Conversation
import com.ai.android.model.Project
import com.ai.android.ui.components.AnimatedPopup
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val conversations by vm.conversations.collectAsState()
    val current by vm.current.collectAsState()
    val projects by vm.projects.collectAsState()

    var query by remember { mutableStateOf("") }
    var selectedProjectId by remember { mutableStateOf("") }
    var multiSelectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var moveToProjectFor by remember { mutableStateOf<String?>(null) }
    var tokenStatsFor by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }

    val context = LocalContext.current

    var exportTarget by remember { mutableStateOf<Conversation?>(null) }
    var exporting by remember { mutableStateOf(false) }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        exporting = false
        if (target != null && uri != null) {
            runCatching {
                val json = vm.exportConversationJson(target.id) ?: return@runCatching
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "已导出对话：${target.title}", Toast.LENGTH_LONG).show()
            }.onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.bufferedReader().readText() }
            val imported = text?.let { vm.importConversationJson(it) }
            if (imported != null) {
                Toast.makeText(context, "已导入对话：${imported.title}", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "导入失败：文件不是有效的对话 JSON", Toast.LENGTH_LONG).show()
            }
        }.onFailure { Toast.makeText(context, "读取文件失败：${it.message}", Toast.LENGTH_LONG).show() }
    }

    fun doExport(conv: Conversation) {
        exportTarget = conv
        exporting = true
        createDocument.launch(vm.suggestedExportFileName(conv))
    }

    fun doImport() {
        openDocument.launch(arrayOf("application/json", "application/octet-stream", "*/*"))
    }

    val filtered = remember(conversations, query, selectedProjectId) {
        conversations
            .filter {
                when (selectedProjectId) {
                    "" -> true
                    "_none" -> it.projectId.isBlank()
                    else -> it.projectId == selectedProjectId
                }
            }
            .filter {
                query.isBlank() || it.title.contains(query, ignoreCase = true)
            }
    }

    val grouped = remember(filtered) {
        val pinned = filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
        val normal = filtered.filterNot { it.pinned }.sortedByDescending { it.updatedAt }
        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayStart = todayCal.timeInMillis
        val yesterdayStart = todayStart - 86400000L
        val sevenDaysStart = todayStart - 7 * 86400000L

        val today = mutableListOf<Conversation>()
        val yesterday = mutableListOf<Conversation>()
        val last7 = mutableListOf<Conversation>()
        val earlier = mutableListOf<Conversation>()
        normal.forEach { c ->
            val t = c.updatedAt
            when {
                t >= todayStart -> today.add(c)
                t >= yesterdayStart -> yesterday.add(c)
                t >= sevenDaysStart -> last7.add(c)
                else -> earlier.add(c)
            }
        }
        listOf(
            "置顶" to pinned,
            "今天" to today,
            "昨天" to yesterday,
            "7 天内" to last7,
            "更早" to earlier,
        ).filter { it.second.isNotEmpty() }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = { Text(if (multiSelectMode) "已选 ${selected.size} 项" else "对话") },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (multiSelectMode) { multiSelectMode = false; selected = emptySet() }
                            else onBack()
                        }) {
                            Icon(
                                if (multiSelectMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回",
                            )
                        }
                    },
                    actions = {
                        if (multiSelectMode) {
                            IconButton(
                                onClick = {
                                    selected.forEach { vm.deleteConversation(it) }
                                    multiSelectMode = false; selected = emptySet()
                                },
                                enabled = selected.isNotEmpty(),
                            ) { Icon(Icons.Default.Delete, "删除所选") }
                        } else {
                            IconButton(onClick = { doImport() }) {
                                Icon(Icons.Default.FileOpen, "导入对话")
                            }
                            IconButton(onClick = { vm.newConversation(); onBack() }) {
                                Icon(Icons.Default.Add, "新会话")
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                placeholder = { Text("搜索对话…", fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )

            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ProjectChip("全部", selectedProjectId == "") { selectedProjectId = "" }
                projects.forEach { p ->
                    ProjectChip(p.name, selectedProjectId == p.id) { selectedProjectId = p.id }
                }
                ProjectChip("未归入", selectedProjectId == "_none") { selectedProjectId = "_none" }
            }

            LazyColumn(Modifier.fillMaxSize()) {
                if (grouped.isEmpty()) {
                    item { Text("没有找到对话", modifier = Modifier.padding(24.dp)) }
                }
                grouped.forEach { (label, items) ->
                    item(key = "h_$label") {
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 18.dp, top = 12.dp, bottom = 6.dp),
                        )
                    }
                    items(items, key = { it.id }) { c ->
                        ConversationRow(
                            conv = c,
                            isCurrent = c.id == current?.id,
                            multiSelectMode = multiSelectMode,
                            selected = c.id in selected,
                            menuOpen = menuFor == c.id,
                            onMenuOpenChange = { menuFor = if (it) c.id else null },
                            onOpen = {
                                if (multiSelectMode) {
                                    selected = if (c.id in selected) selected - c.id else selected + c.id
                                } else {
                                    vm.switchConversation(c.id); onBack()
                                }
                            },
                            onRename = { renaming = c; menuFor = null },
                            onPin = { vm.togglePin(c.id); menuFor = null },
                            onMultiSelect = {
                                multiSelectMode = true; selected = setOf(c.id); menuFor = null
                            },
                            onMoveToProject = { moveToProjectFor = c.id; menuFor = null },
                            onExport = { doExport(c); menuFor = null },
                            onDelete = { deleting = c; menuFor = null },
                            onViewTokenStats = { tokenStatsFor = c; menuFor = null },
                        )
                    }
                }
            }
        }
    }

    // 删除确认
    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除对话", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
            text = { Text("确定删除对话「${c.title.ifBlank { "未命名" }}」吗？此操作不可恢复。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(c.id)
                    deleting = null
                }) { Text("删除", fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消", fontSize = 13.sp) } },
        )
    }

    renaming?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameConversation(c.id, text.trim().ifBlank { c.title })
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    moveToProjectFor?.let { convId ->
        AlertDialog(
            onDismissRequest = { moveToProjectFor = null },
            title = { Text("移动到项目") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        vm.moveConversationToProject(convId, "")
                        moveToProjectFor = null
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("（未归入项目）", fontSize = 13.sp)
                    }
                    projects.forEach { p ->
                        TextButton(onClick = {
                            vm.moveConversationToProject(convId, p.id)
                            moveToProjectFor = null
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(p.name, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveToProjectFor = null }) { Text("取消") } },
        )
    }

    tokenStatsFor?.let { c ->
        val totals = c.totalTokens()
        AlertDialog(
            onDismissRequest = { tokenStatsFor = null },
            title = { Text("Token 统计 · ${c.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TokenStatDialogRow("输入（prompt）", totals.promptTokens)
                    TokenStatDialogRow("输出（completion）", totals.completionTokens)
                    TokenStatDialogRow("缓存命中（cache）", totals.cacheHitTokens)
                    TokenStatDialogRow("总消耗", totals.totalTokens)
                    Text(
                        "共 ${c.messagesWithStats().size} 条消息携带 Token 数据",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { tokenStatsFor = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun TokenStatDialogRow(label: String, value: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.toString(), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ProjectChip(name: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(name, fontSize = 12.sp) },
    )
}

@Composable
private fun ConversationRow(
    conv: Conversation,
    isCurrent: Boolean,
    multiSelectMode: Boolean,
    selected: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onMultiSelect: () -> Unit,
    onMoveToProject: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onViewTokenStats: () -> Unit = {},
) {
    val bg = when {
        multiSelectMode && selected -> MaterialTheme.colorScheme.primaryContainer
        isCurrent -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiSelectMode) {
            Checkbox(checked = selected, onCheckedChange = { onOpen() })
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (conv.pinned) {
                    Icon(
                        Icons.Default.PushPin, null, Modifier.width(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    conv.title,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                "${conv.messages.size} 条 · ${fmtTime(conv.updatedAt)}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!multiSelectMode) {
            Box {
                IconButton(onClick = { onMenuOpenChange(true) }) {
                    Icon(Icons.Default.MoreVert, "更多", Modifier.width(18.dp))
                }
                AnimatedPopup(
                    visible = menuOpen,
                    alignment = Alignment.BottomEnd,
                    offset = IntOffset(0, 6),
                    onDismissRequest = { onMenuOpenChange(false) },
                    transformOrigin = TransformOrigin(1f, 1f),
                ) {
                    AppMenuPanel(
                        items = listOf(
                            AppMenuItem(label = "重命名", icon = Icons.Default.Edit),
                            AppMenuItem(
                                label = if (conv.pinned) "取消置顶" else "置顶",
                                icon = Icons.Default.PushPin,
                            ),
                            AppMenuItem(label = "移动到项目", icon = Icons.Default.Folder),
                            AppMenuItem(label = "导出对话", icon = Icons.Default.FileOpen),
                            AppMenuItem(label = "查看 Token 统计", icon = Icons.Default.DataObject),
                            AppMenuItem(label = "多选", icon = Icons.Default.CheckCircle),
                            AppMenuItem(
                                label = "删除",
                                icon = Icons.Default.Delete,
                                // ⭐ 用 enabled=true，颜色靠主题 error 表达由 AppMenuPanel 统一处理
                            ),
                        ),
                        onPick = { idx ->
                            onMenuOpenChange(false)
                            when (idx) {
                                0 -> onRename()
                                1 -> onPin()
                                2 -> onMoveToProject()
                                3 -> onExport()
                                4 -> onViewTokenStats()
                                5 -> onMultiSelect()
                                6 -> onDelete()
                            }
                        },
                        chrome = true,
                        title = null,
                        compact = true,
                        maxWidth = 200.dp,
                    )
                }
            }
        }
    }
}

private val rowTimeFmt = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)
private fun fmtTime(ms: Long): String = rowTimeFmt.format(Date(ms))