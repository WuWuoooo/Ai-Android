package com.ai.android.ui.chat

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.R
import com.ai.android.model.Conversation
import com.ai.android.ui.components.AnimatedPopup
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDrawer(
    vm: MainViewModel,
    currentId: String?,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val conversations by vm.conversations.collectAsState()
    val projects by vm.projects.collectAsState()

    var query by remember { mutableStateOf("") }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var sortAsc by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var deleting by remember { mutableStateOf<Conversation?>(null) }
    var exportConvId by remember { mutableStateOf<String?>(null) }

    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    var expandedProjectIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var movingConv by remember { mutableStateOf<Conversation?>(null) }

    val sExportedPrefix = stringResource(R.string.conv_toast_exported)
    val sExportFailedPrefix = stringResource(R.string.conv_toast_export_failed)
    val sDeletedN = stringResource(R.string.conv_deleted_n)
    val sPinnedN = stringResource(R.string.conv_pinned_n)
    val sUnnamed = stringResource(R.string.conv_unnamed)
    val sPin = stringResource(R.string.conv_pin)
    val sUnpin = stringResource(R.string.conv_unpin)
    val sRename = stringResource(R.string.conv_rename)
    val sMoveToProject = stringResource(R.string.conv_move_to_project)
    val sMoveOut = stringResource(R.string.conv_move_out)
    val sExport = stringResource(R.string.conv_export)
    val sDelete = stringResource(R.string.conv_delete)
    val sMore = stringResource(R.string.conv_menu_more)
    val sExpand = stringResource(R.string.conv_arrow_expand)
    val sCollapse = stringResource(R.string.conv_arrow_collapse)
    val sSortHint = stringResource(R.string.conv_sort_hint)
    val sMultiExit = stringResource(R.string.conv_multi_exit_hint)
    val sCancel = stringResource(R.string.cancel)
    val sSave = stringResource(R.string.save)

    val createDocument = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val convId = exportConvId
        exportConvId = null
        if (uri != null && convId != null) {
            runCatching {
                val c = conversations.firstOrNull { it.id == convId } ?: return@runCatching
                val json = vm.exportConversationJson(c.id) ?: return@runCatching
                context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                Toast.makeText(context, sExportedPrefix.format(c.title), Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, sExportFailedPrefix.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val (todayStart, dayMs) = remember {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        c.timeInMillis to 86400000L
    }
    val yesterdayStart = todayStart - dayMs
    val day7Start = todayStart - 6 * dayMs
    val day30Start = todayStart - 29 * dayMs

    val filtered = remember(conversations, query) {
        conversations.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .let { list -> if (sortAsc) list.sortedBy { it.updatedAt } else list.sortedByDescending { it.updatedAt } }
    }

    val labelPinned = stringResource(R.string.conv_group_pinned)
    val labelToday = stringResource(R.string.conv_group_today)
    val labelYesterday = stringResource(R.string.conv_group_yesterday)
    val label7d = stringResource(R.string.conv_group_7d)
    val label30d = stringResource(R.string.conv_group_30d)
    val labelEarlier = stringResource(R.string.conv_group_earlier)

    val groups = remember(filtered, labelPinned, labelToday, labelYesterday, label7d, label30d, labelEarlier) {
        val pinned = filtered.filter { it.pinned }.sortedByDescending { it.updatedAt }
        val unpinned = filtered.filterNot { it.pinned }

        val today = mutableListOf<Conversation>()
        val yesterday = mutableListOf<Conversation>()
        val d7 = mutableListOf<Conversation>()
        val d30 = mutableListOf<Conversation>()
        val earlier = mutableListOf<Conversation>()
        unpinned.forEach { c ->
            when {
                c.updatedAt >= todayStart -> today.add(c)
                c.updatedAt >= yesterdayStart -> yesterday.add(c)
                c.updatedAt >= day7Start -> d7.add(c)
                c.updatedAt >= day30Start -> d30.add(c)
                else -> earlier.add(c)
            }
        }
        listOf(
            labelPinned to pinned,
            labelToday to today,
            labelYesterday to yesterday,
            label7d to d7,
            label30d to d30,
            labelEarlier to earlier,
        ).filter { it.second.isNotEmpty() }
    }

    val accent = MaterialTheme.colorScheme.primary
    val accentBg = MaterialTheme.colorScheme.primaryContainer
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(stringResource(R.string.conv_search_hint), fontSize = 14.sp, color = Color.Gray)
                },
                leadingIcon = {
                    Icon(Icons.Default.Search, null, Modifier.size(20.dp), tint = Color.Gray)
                },
                singleLine = true,
                shape = RoundedCornerShape(24.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (filtered.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.conv_empty), fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            // 项目区域
            if (projects.isNotEmpty()) {
                item(key = "projects_header") {
                    Text(
                        stringResource(R.string.conv_group_projects),
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = accent,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                projects.forEach { proj ->
                    val projConvs = conversations
                        .filter { it.projectId == proj.id }
                        .let { list -> if (sortAsc) list.sortedBy { it.updatedAt } else list.sortedByDescending { it.updatedAt } }
                        .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
                    val expanded = proj.id in expandedProjectIds
                    item(key = "proj_${proj.id}") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (expanded) accentBg else Color.Transparent)
                                .clickable {
                                    expandedProjectIds = if (expanded) expandedProjectIds - proj.id
                                    else expandedProjectIds + proj.id
                                }
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (expanded) Icons.Default.FolderOpen else Icons.Default.Folder,
                                null, Modifier.size(18.dp), tint = accent,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                proj.name, fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${projConvs.size}", fontSize = 11.sp,
                                color = gray, modifier = Modifier.padding(end = 6.dp),
                            )
                            val arrowRotation by animateFloatAsState(
                                targetValue = if (expanded) 90f else 0f,
                                animationSpec = tween(180),
                                label = "project_arrow_${proj.id}",
                            )
                            Icon(
                                Icons.Default.KeyboardArrowRight,
                                contentDescription = if (expanded) sCollapse else sExpand,
                                modifier = Modifier
                                    .size(18.dp)
                                    .graphicsLayer { rotationZ = arrowRotation },
                                tint = gray,
                            )
                        }
                    }
                    if (expanded) {
                        items(projConvs, key = { "pcc_${proj.id}_${it.id}" }) { c ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (c.id == currentId) accentBg else Color.Transparent
                                    )
                                    .clickable {
                                        vm.switchConversation(c.id); onClose()
                                    }
                                    .padding(start = 28.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    c.title.ifBlank { sUnnamed },
                                    fontSize = 13.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (c.id == currentId) accent else MaterialTheme.colorScheme.onSurface,
                                    fontWeight = if (c.id == currentId) FontWeight.Medium else FontWeight.Normal,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                item(key = "projects_divider") {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }

            groups.forEachIndexed { gi, (label, items) ->
                item(key = "h_$label") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (label == labelPinned) {
                            Icon(Icons.Default.PushPin, null, Modifier.size(12.dp), tint = accent)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            label,
                            fontSize = 12.sp,
                            color = if (label == labelPinned) accent else gray,
                            fontWeight = if (label == labelPinned) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (gi == 0) {
                            IconButton(
                                onClick = {
                                    multiSelect = !multiSelect
                                    if (!multiSelect) selectedIds = emptySet()
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    if (multiSelect) Icons.Default.Check else Icons.AutoMirrored.Filled.Sort,
                                    contentDescription = if (multiSelect) sMultiExit else sSortHint,
                                    Modifier.size(18.dp),
                                    tint = if (multiSelect) accent else gray,
                                )
                            }
                        }
                    }
                }
                items(items, key = { it.id }) { c ->
                    DrawerItemRow(
                        conv = c,
                        isCurrent = c.id == currentId,
                        menuOpen = menuFor == c.id,
                        onMenuOpenChange = { open -> menuFor = if (open) c.id else null },
                        onOpen = {
                            if (multiSelect) {
                                selectedIds = if (c.id in selectedIds) selectedIds - c.id
                                else selectedIds + c.id
                            } else {
                                vm.switchConversation(c.id); onClose()
                            }
                        },
                        onRename = { renaming = c; menuFor = null },
                        onPin = { vm.togglePin(c.id); menuFor = null },
                        onExport = {
                            exportConvId = c.id; menuFor = null
                            createDocument.launch(vm.suggestedExportFileName(c))
                        },
                        onDelete = { deleting = c; menuFor = null },
                        onMoveToProject = { movingConv = c; menuFor = null },
                        multiSelect = multiSelect,
                        isSelected = c.id in selectedIds,
                        sMore = sMore, sRename = sRename, sPin = sPin, sUnpin = sUnpin,
                        sMoveToProject = sMoveToProject, sExport = sExport, sDelete = sDelete,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = multiSelect,
            enter = slideInVertically(animationSpec = tween(250), initialOffsetY = { it }) +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutVertically(animationSpec = tween(250), targetOffsetY = { it }) +
                fadeOut(animationSpec = tween(200)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.conv_multi_selected, selectedIds.size),
                    fontSize = 13.sp,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selectedIds.forEach { id -> if (id != currentId) vm.deleteConversation(id) }
                    Toast.makeText(context, sDeletedN.format(selectedIds.size), Toast.LENGTH_SHORT).show()
                    multiSelect = false; selectedIds = emptySet()
                }) { Text(sDelete, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = {
                    selectedIds.forEach { id ->
                        val c = conversations.firstOrNull { it.id == id }
                        if (c != null && !c.pinned) vm.togglePin(id)
                    }
                    Toast.makeText(context, sPinnedN.format(selectedIds.size), Toast.LENGTH_SHORT).show()
                    multiSelect = false; selectedIds = emptySet()
                }) { Text(sPin, fontSize = 13.sp, color = accent) }
                TextButton(onClick = { multiSelect = false; selectedIds = emptySet() }) {
                    Text(sCancel, fontSize = 13.sp)
                }
            }
            HorizontalDivider()
        }
    }

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = {
                Text(
                    stringResource(R.string.conv_delete_title),
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    stringResource(R.string.conv_delete_confirm, c.title.ifBlank { sUnnamed }),
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteConversation(c.id)
                    deleting = null
                }) {
                    Text(sDelete, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(sCancel, fontSize = 13.sp) }
            },
        )
    }

    renaming?.let { c ->
        var text by remember(c.id) { mutableStateOf(c.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.conv_rename_title)) },
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
                }) { Text(sSave) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(sCancel) } },
        )
    }

    movingConv?.let { c ->
        AlertDialog(
            onDismissRequest = { movingConv = null },
            title = { Text(stringResource(R.string.conv_move_project_title), fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (projects.isEmpty()) {
                        Text(
                            stringResource(R.string.conv_move_project_empty),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        projects.forEach { p ->
                            TextButton(
                                onClick = {
                                    vm.moveConversationToProject(c.id, p.id)
                                    expandedProjectIds = expandedProjectIds + p.id
                                    movingConv = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    p.name + if (c.projectId == p.id)
                                        stringResource(R.string.conv_move_project_current) else "",
                                    fontSize = 13.sp,
                                    color = if (c.projectId == p.id) accent else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    if (c.projectId.isNotBlank()) {
                        TextButton(
                            onClick = {
                                vm.moveConversationToProject(c.id, "")
                                movingConv = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(sMoveOut, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { movingConv = null }) { Text(sCancel, fontSize = 13.sp) } },
        )
    }
}

@Composable
private fun DrawerItemRow(
    conv: Conversation,
    isCurrent: Boolean,
    menuOpen: Boolean,
    onMenuOpenChange: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onPin: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    onMoveToProject: () -> Unit = {},
    multiSelect: Boolean = false,
    isSelected: Boolean = false,
    sMore: String,
    sRename: String,
    sPin: String,
    sUnpin: String,
    sMoveToProject: String,
    sExport: String,
    sDelete: String,
) {
    val accent = MaterialTheme.colorScheme.primary
    val accentBg = MaterialTheme.colorScheme.primaryContainer
    val gray = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    val sUnnamed = stringResource(R.string.conv_unnamed)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    multiSelect && isSelected -> accentBg
                    isCurrent -> accentBg
                    else -> Color.Transparent
                }
            )
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multiSelect) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onOpen() },
                modifier = Modifier.size(24.dp),
                colors = CheckboxDefaults.colors(checkedColor = accent, uncheckedColor = gray),
            )
            Spacer(Modifier.width(8.dp))
        }
        if (isCurrent && !multiSelect) {
            Box(
                Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
        }

        Text(
            conv.title.ifBlank { sUnnamed },
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (multiSelect && isSelected || isCurrent) accent else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (multiSelect && isSelected || isCurrent) FontWeight.Medium else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )

        if (isCurrent && !multiSelect) {
            // ⭐ 用 AppMenuPanel（自定义菜单，风格统一），动画从右下角展开
            Box {
                IconButton(
                    onClick = { onMenuOpenChange(true) },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(Icons.Default.MoreVert, sMore, Modifier.size(18.dp), tint = accent)
                }
                AnimatedPopup(
                    visible = menuOpen,
                    alignment = Alignment.BottomEnd,
                    offset = IntOffset(0, 6),
                    onDismissRequest = { onMenuOpenChange(false) },
                    transformOrigin = TransformOrigin(1f, 1f),   // 右下角
                ) {
                    AppMenuPanel(
                        items = listOf(
                            AppMenuItem(label = sRename, icon = Icons.Default.Edit),
                            AppMenuItem(
                                label = if (conv.pinned) sUnpin else sPin,
                                icon = Icons.Default.PushPin,
                            ),
                            AppMenuItem(label = sMoveToProject, icon = Icons.Default.Folder),
                            AppMenuItem(label = sExport, icon = Icons.Default.FileOpen),
                            AppMenuItem(label = sDelete, icon = Icons.Default.Delete),
                        ),
                        onPick = { idx ->
                            onMenuOpenChange(false)
                            when (idx) {
                                0 -> onRename()
                                1 -> onPin()
                                2 -> onMoveToProject()
                                3 -> onExport()
                                4 -> onDelete()
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