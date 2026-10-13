package com.ai.android.ui.settings

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.R
import com.ai.android.agent.PluginGuardBridge
import com.ai.android.agent.SessionLog
import com.ai.android.model.Project
import com.ai.android.plugin.PluginManager
import com.ai.android.provider.AIProvider
import com.ai.android.provider.ParamRow
import com.ai.android.provider.ProviderConfig
import com.ai.android.provider.SearchManager
import com.ai.android.service.FloatingService
import com.ai.android.ui.components.AppDialog
import com.ai.android.util.PermissionHelper
import com.ai.android.termux.BootstrapInstaller
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
// 删除：
// import androidx.compose.material.icons.outlined.HealthAndSafety

// 添加：
import androidx.compose.material.icons.outlined.Build

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val revision by vm.settings.revision.collectAsState()

    var configs by remember { mutableStateOf(vm.settings.loadConfigs()) }
    var activeId by remember { mutableStateOf(vm.settings.activeId()) }
    var editing by remember { mutableStateOf<ProviderConfig?>(null) }
    var creating by remember { mutableStateOf(false) }
    var permStatus by remember { mutableStateOf(PermissionHelper.status(context)) }
    val toastSwitched = stringResource(R.string.toast_switched)

    var selectedCategory by remember { mutableStateOf<String?>(null) }
    
// ⭐ 诊断页独立全屏（不走 AnimatedContent）
if (selectedCategory == "diagnostics") {
    DiagnosticsScreen(vm, onBack = { selectedCategory = null })
    return
}

val categories = listOf(
    "model" to stringResource(R.string.set_section_provider),
    "agent" to stringResource(R.string.set_section_agent),
    "ui" to stringResource(R.string.set_section_display),
    "plugin" to stringResource(R.string.set_section_plugin),
    "harness" to "Harness",
    "system" to stringResource(R.string.set_section_permission),
    "terminal" to stringResource(R.string.set_section_terminal),
    "conversation" to stringResource(R.string.set_section_project),
    "diagnostics" to "诊断",          // ⭐ 新增
    "about" to stringResource(R.string.about_title),
)
    val catSubs = mapOf(
        "model" to stringResource(R.string.cat_sub_model),
        "agent" to stringResource(R.string.cat_sub_agent),
        "ui" to stringResource(R.string.cat_sub_ui),
        "plugin" to stringResource(R.string.cat_sub_plugin),
        "harness" to stringResource(R.string.cat_sub_harness),
        "system" to stringResource(R.string.cat_sub_system),
        "terminal" to stringResource(R.string.cat_sub_terminal),
        "conversation" to stringResource(R.string.cat_sub_conversation),
        "diagnostics" to "状态体检 / 环境检查",
        "about" to stringResource(R.string.cat_sub_about),
    )

    LaunchedEffect(revision) {
        configs = vm.settings.loadConfigs()
        activeId = vm.settings.activeId()
    }

    LaunchedEffect(Unit) {
        while (true) {
            permStatus = PermissionHelper.status(context)
            delay(3000)
        }
    }

    if (selectedCategory != null) {
        BackHandler { selectedCategory = null }
    }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = {
                        Text(
                            if (selectedCategory == null) stringResource(R.string.set_title)
                            else categories.firstOrNull { it.first == selectedCategory }?.second.orEmpty()
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selectedCategory == null) onBack()
                            else selectedCategory = null
                        }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = selectedCategory,
            label = "settings_level",
            transitionSpec = {
                when {
                    initialState == null ->
                        (slideInHorizontally(animationSpec = tween(300)) { -it } + fadeIn(animationSpec = tween(200)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { it } + fadeOut(animationSpec = tween(200)))
                    targetState == null ->
                        (slideInHorizontally(animationSpec = tween(300)) { it } + fadeIn(animationSpec = tween(200)))
                            .togetherWith(slideOutHorizontally(animationSpec = tween(300)) { -it } + fadeOut(animationSpec = tween(200)))
                    else ->
                        fadeIn(animationSpec = tween(200)).togetherWith(fadeOut(animationSpec = tween(200)))
                }
            },
        ) { level ->
            if (level == null) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(padding),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(categories) { (key, title) ->
                        Card(Modifier.clickable { selectedCategory = key }) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    categoryIcon(key),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                    Text(
                                        catSubs[key].orEmpty(),
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().padding(padding),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (selectedCategory) {
                        "model" -> {
                            item { SectionTitle(stringResource(R.string.set_section_provider)) }
                            items(configs, key = { it.id }) { cfg ->
                                ProviderCard(
                                    cfg = cfg,
                                    isActive = cfg.id == activeId,
                                    onActivate = {
                                        vm.settings.setActive(cfg.id)
                                        activeId = vm.settings.activeId()
                                        Toast.makeText(context, "$toastSwitched ${cfg.label}", Toast.LENGTH_SHORT).show()
                                    },
                                    onEdit = { editing = cfg },
                                    onDelete = {
                                        vm.settings.deleteConfig(cfg.id)
                                        configs = vm.settings.loadConfigs()
                                        activeId = vm.settings.activeId()
                                    },
                                )
                            }
                            item {
                                OutlinedButton(
                                    onClick = { creating = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(stringResource(R.string.set_add_provider)) }
                            }
                            item { SectionTitle(stringResource(R.string.set_section_multimodal)) }
                            item { MultimodalBindingCard(vm = vm, configs = configs) }
                        }
                        "agent" -> {
                            item { SectionTitle(stringResource(R.string.set_section_agent)) }
                            item { AgentParamsCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_ai_behavior)) }
                            item { AiBehaviorCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_readlimit)) }
                            item { ReadLimitCard(vm) }
                            item { SectionTitle(stringResource(R.string.tool_display_limit_desc).take(4)) }
                            item { ToolDisplayLimitCard(vm) }
                        }
                        "ui" -> {
                            item { SectionTitle(stringResource(R.string.set_section_display)) }
                            item { FloatingToggleCard(vm) }
                            item { SectionTitle(stringResource(R.string.theme_style_title)) }
                            item { ThemeStyleCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_language)) }
                            item { LanguageCard(vm, context) }
                            // ⭐ PluginManagerCard 已移至 "plugin" 分类
                        }
                        "plugin" -> {
                            // ⭐ 新增：Skill 导入/查看/删除
                            item { SectionTitle(stringResource(R.string.plugin_skill_section_title)) }
                            item { SkillSettingsCard(context) }
                            // ⭐ 原 UI 分类下的插件管理卡片移到这里
                            item { SectionTitle(stringResource(R.string.plugin_title)) }
                            item { PluginManagerCard(vm, context) }
                        }
                        "harness" -> {
                            item { SectionTitle("Harness") }
                            item { HarnessToolControlCard(vm) }
                            item { SectionTitle(stringResource(R.string.harness_log_enable_title)) }
                            item { HarnessLogCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.harness_subagent_title)) }
                            item { HarnessSubAgentCard(vm) }
                        }
                        "system" -> {
                            item { SectionTitle(stringResource(R.string.set_section_shizuku)) }
                            item { ShizukuCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_aicontrol)) }
                            item { AiControlCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_permission)) }
                            item {
                                PermissionCard(
                                    status = permStatus,
                                    onRequestStorage = { activity?.let { p -> PermissionHelper.requestStorage(p) } },
                                    onRequestNotification = { activity?.let { p -> PermissionHelper.requestNotification(p) } },
                                    onRequestOverlay = { activity?.let { p -> PermissionHelper.requestOverlay(p) } },
                                    onRequestAccessibility = { activity?.let { p -> PermissionHelper.openAccessibilitySettings(p) } },
                                )
                            }
                        }
                        "terminal" -> {
                            item { SectionTitle(stringResource(R.string.set_section_termux)) }
                            item { TermuxCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_terminal)) }
                            item { TerminalEnvCard(vm) }
                        }
                        "conversation" -> {
                            item { SectionTitle(stringResource(R.string.set_section_memory)) }
                            item { MemoryManagerCard(vm) }
                            item { SectionTitle(stringResource(R.string.set_section_token)) }
                            item { TokenStatsCard(vm, context) }
                            item { SectionTitle(stringResource(R.string.set_section_project)) }
                            item { ProjectManagerCard(vm) }
                        }
                        "about" -> {
                            item { AboutCard() }
                        }
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    if (creating) {
        EditProviderDialog(
            initial = null,
            onDismiss = { creating = false },
            onSave = { cfg ->
                vm.settings.upsertConfig(cfg)
                configs = vm.settings.loadConfigs()
                creating = false
            },
        )
    }
    editing?.let { cfg ->
        EditProviderDialog(
            initial = cfg,
            onDismiss = { editing = null },
            onSave = { updated ->
                vm.settings.upsertConfig(updated)
                configs = vm.settings.loadConfigs()
                editing = null
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

private fun categoryIcon(key: String): androidx.compose.ui.graphics.vector.ImageVector = when (key) {
    "model" -> Icons.Outlined.Cloud
    "agent" -> Icons.Outlined.SmartToy
    "ui" -> Icons.Outlined.Palette
    "plugin" -> Icons.Outlined.Extension
    "harness" -> Icons.Outlined.Lock
    "system" -> Icons.Outlined.Lock
    "terminal" -> Icons.Outlined.Terminal
    "diagnostics" -> Icons.Outlined.Build
    "about" -> Icons.Outlined.Info
    else -> Icons.Outlined.Chat
}

// ==================== 工具展示上限 ====================

@Composable
private fun ToolDisplayLimitCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var argLimit by remember { mutableStateOf(vm.settings.toolArgDisplayLimit().toString()) }
    var outputLimit by remember { mutableStateOf(vm.settings.toolOutputDisplayLimit().toString()) }

    LaunchedEffect(revision) {
        argLimit = vm.settings.toolArgDisplayLimit().toString()
        outputLimit = vm.settings.toolOutputDisplayLimit().toString()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.tool_display_limit_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = argLimit,
                onValueChange = {
                    argLimit = it.filter { ch -> ch.isDigit() }
                    argLimit.toIntOrNull()?.let { v -> vm.settings.setToolArgDisplayLimit(v) }
                },
                label = { Text(stringResource(R.string.tool_display_arg_limit), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = outputLimit,
                onValueChange = {
                    outputLimit = it.filter { ch -> ch.isDigit() }
                    outputLimit.toIntOrNull()?.let { v -> vm.settings.setToolOutputDisplayLimit(v) }
                },
                label = { Text(stringResource(R.string.tool_display_output_limit), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ==================== Harness / 安全 ====================

@Composable
private fun HarnessToolControlCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var shellFreq by remember { mutableStateOf(vm.settings.shellFrequencyLimit().toString()) }
    var cacheEnabled by remember { mutableStateOf(vm.settings.resultCacheEnabled()) }
    var clampEnabled by remember { mutableStateOf(vm.settings.uiOutputClampEnabled()) }
    var dangerousEnabled by remember { mutableStateOf(vm.settings.dangerousToolsEnabled()) }
    var pathGuard by remember { mutableStateOf(vm.settings.pathGuardEnabled()) }

    LaunchedEffect(revision) {
        shellFreq = vm.settings.shellFrequencyLimit().toString()
        cacheEnabled = vm.settings.resultCacheEnabled()
        clampEnabled = vm.settings.uiOutputClampEnabled()
        dangerousEnabled = vm.settings.dangerousToolsEnabled()
        pathGuard = vm.settings.pathGuardEnabled()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text(stringResource(R.string.harness_shell_freq_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.harness_shell_freq_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = shellFreq,
                    onValueChange = {
                        shellFreq = it.filter { ch -> ch.isDigit() }.take(4)
                        shellFreq.toIntOrNull()?.let { v -> vm.settings.setShellFrequencyLimit(v) }
                    },
                    label = { Text(stringResource(R.string.harness_shell_freq_unit), fontSize = 11.sp) },
                    singleLine = true,
                    modifier = Modifier.width(140.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    if (shellFreq == "0") stringResource(R.string.harness_shell_freq_unlimited)
                    else stringResource(R.string.harness_shell_freq_limited, shellFreq),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary,
                )
            }

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_cache_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_cache_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = cacheEnabled,
                    onCheckedChange = { vm.settings.setResultCacheEnabled(it); cacheEnabled = it },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_clamp_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_clamp_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = clampEnabled,
                    onCheckedChange = { vm.settings.setUiOutputClampEnabled(it); clampEnabled = it },
                )
            }

            HorizontalDivider()

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_dangerous_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_dangerous_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = dangerousEnabled,
                    onCheckedChange = { vm.settings.setDangerousToolsEnabled(it); dangerousEnabled = it },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_path_guard_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_path_guard_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = pathGuard,
                    onCheckedChange = { vm.settings.setPathGuardEnabled(it); pathGuard = it },
                )
            }
        }
    }
}

@Composable
private fun HarnessLogCard(vm: MainViewModel, context: android.content.Context) {
    val revision by vm.settings.revision.collectAsState()
    var logEnabled by remember { mutableStateOf(vm.settings.sessionLogEnabled()) }
    var showCurrent by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var currentLogContent by remember { mutableStateOf("") }
    var logEntries by remember { mutableStateOf(emptyList<Pair<String, Long>>()) }

    val sNone = stringResource(R.string.harness_log_none)
    val sExportedN = stringResource(R.string.harness_log_exported_n)
    val sExportEmpty = stringResource(R.string.harness_log_export_empty)

    LaunchedEffect(revision) { logEnabled = vm.settings.sessionLogEnabled() }

    LaunchedEffect(showList) {
        if (showList) {
            logEntries = runCatching { SessionLog.listConversationLogs() }.getOrDefault(emptyList())
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_log_enable_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_log_enable_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = logEnabled,
                    onCheckedChange = { vm.settings.setSessionLogEnabled(it); logEnabled = it },
                )
            }

            HorizontalDivider()

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val convId = vm.current.value?.id.orEmpty()
                        currentLogContent = SessionLog.readRecent(convId, 300)
                        showCurrent = true
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.harness_log_view_current), fontSize = 12.sp) }

                OutlinedButton(
                    onClick = { showList = true },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.harness_log_view_all), fontSize = 12.sp) }
            }

            OutlinedButton(
                onClick = {
                    val n = SessionLog.exportAll()
                    Toast.makeText(
                        context,
                        if (n > 0) sExportedN.format(n) else sExportEmpty,
                        Toast.LENGTH_LONG,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.harness_log_export_all), fontSize = 12.sp) }
        }
    }

    if (showCurrent) {
        AppDialog(
            onDismissRequest = { showCurrent = false },
            title = stringResource(R.string.harness_log_current_title),
            dismissText = stringResource(R.string.close),
            onDismiss = { showCurrent = false },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    currentLogContent.ifBlank { sNone },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showList) {
        AppDialog(
            onDismissRequest = { showList = false },
            title = stringResource(R.string.harness_log_all_title, logEntries.size),
            dismissText = stringResource(R.string.close),
            onDismiss = { showList = false },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (logEntries.isEmpty()) {
                    Text(sNone, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    logEntries.forEach { (name, size) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(name, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                                Text(
                                    fmtFileSize(size),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = {
                                val content = SessionLog.readRecent(name, 300)
                                currentLogContent = content
                                showList = false
                                showCurrent = true
                            }) { Text(stringResource(R.string.harness_log_view), fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

private fun fmtFileSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    else -> "${"%.1f".format(bytes / 1024.0 / 1024.0)} MB"
}

@Composable
private fun HarnessSubAgentCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var subEnabled by remember { mutableStateOf(vm.settings.subAgentEnabled()) }
    var subTokenCounting by remember { mutableStateOf(vm.settings.subAgentTokenCountingEnabled()) }

    LaunchedEffect(revision) {
        subEnabled = vm.settings.subAgentEnabled()
        subTokenCounting = vm.settings.subAgentTokenCountingEnabled()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_subagent_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_subagent_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = subEnabled,
                    onCheckedChange = { vm.settings.setSubAgentEnabled(it); subEnabled = it },
                )
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.harness_subagent_token_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.harness_subagent_token_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = subTokenCounting,
                    onCheckedChange = {
                        vm.settings.setSubAgentTokenCountingEnabled(it)
                        subTokenCounting = it
                    },
                )
            }

            Text(
                stringResource(R.string.harness_subagent_hint),
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ==================== 关于页 ====================

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrDefault("unknown")
    }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.about_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
            )
            AboutRow(stringResource(R.string.about_version), "v$versionName")
            AboutRow(stringResource(R.string.about_tech), stringResource(R.string.about_tech_value))
            AboutRow(stringResource(R.string.about_position), stringResource(R.string.about_position_value))
            AboutRow(stringResource(R.string.about_license), stringResource(R.string.about_license_value))
            AboutRow(
                label = stringResource(R.string.about_repo),
                value = "github.com/WuWuoooo/Ai-Android",
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://github.com/WuWuoooo/Ai-Android"),
                            )
                        )
                    }
                },
            )
            Text(
                stringResource(R.string.about_desc),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Text(
            value,
            fontSize = 13.sp,
            modifier = Modifier
                .weight(1f)
                .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
            color = if (onClick != null) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

// ==================== 读取上限 ====================

@Composable
private fun ReadLimitCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var maxChars by remember { mutableStateOf(vm.settings.maxReadChars()) }
    LaunchedEffect(revision) { maxChars = vm.settings.maxReadChars() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.readlimit_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.readlimit_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 50_000, 100_000, 200_000, 500_000).forEach { v ->
                    FilterChip(
                        selected = maxChars == v,
                        onClick = { vm.settings.setMaxReadChars(v) },
                        label = {
                            Text(
                                if (v == 0) stringResource(R.string.readlimit_none) else "${v / 1000}K",
                                fontSize = 11.sp,
                            )
                        },
                    )
                }
            }
        }
    }
}

// ==================== Termux ====================

@Composable
private fun TermuxCard(vm: MainViewModel, context: android.content.Context) {
    var installed by remember { mutableStateOf(BootstrapInstaller.isInstalled(context)) }
    var installing by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.termux_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.termux_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (statusText.isNotBlank()) {
                Text(statusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }

            if (!installed) {
                Button(
                    enabled = !installing,
                    onClick = {
                        installing = true
                        statusText = "…"
                        scope.launch {
                            val r = BootstrapInstaller.install(context) { statusText = it }
                            installing = false
                            installed = r.success
                            statusText = r.message
                        }
                    },
                ) {
                    Text(if (installing) stringResource(R.string.termux_installing) else stringResource(R.string.termux_install))
                }
            } else {
                Text("✅ ${stringResource(R.string.termux_install)}", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
private fun TerminalEnvCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var pathPrefix by remember { mutableStateOf(vm.settings.terminalPathPrefix()) }
    var home by remember { mutableStateOf(vm.settings.terminalHome()) }
    var initScript by remember { mutableStateOf(vm.settings.terminalInitScript()) }
    var usePty by remember { mutableStateOf(vm.settings.terminalUsePty()) }
    LaunchedEffect(revision) {
        pathPrefix = vm.settings.terminalPathPrefix()
        home = vm.settings.terminalHome()
        initScript = vm.settings.terminalInitScript()
        usePty = vm.settings.terminalUsePty()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.terminal_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.terminal_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.terminal_pty), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.terminal_pty_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = usePty, onCheckedChange = { vm.settings.setTerminalUsePty(it); usePty = it })
            }

            OutlinedTextField(
                value = home, onValueChange = { home = it; vm.settings.setTerminalHome(it) },
                label = { Text(stringResource(R.string.terminal_home), fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pathPrefix,
                onValueChange = { pathPrefix = it; vm.settings.setTerminalPathPrefix(it) },
                label = { Text(stringResource(R.string.terminal_path), fontSize = 11.sp) },
                placeholder = { Text("/data/data/com.termux/files/usr/bin", fontSize = 11.sp) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = initScript,
                onValueChange = { initScript = it; vm.settings.setTerminalInitScript(it) },
                label = { Text(stringResource(R.string.terminal_init), fontSize = 11.sp) },
                placeholder = { Text("export FOO=bar\nalias ll='ls -la'", fontSize = 11.sp) },
                modifier = Modifier.fillMaxWidth().height(100.dp),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
            )
        }
    }
}

// ==================== Provider ====================

@Composable
private fun ProviderCard(
    cfg: ProviderConfig,
    isActive: Boolean,
    onActivate: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = isActive, onClick = onActivate)
            Column(Modifier.weight(1f)) {
                Text(cfg.label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    "${cfg.protocol.name} · ${cfg.model}",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                if (cfg.capabilities.isNotEmpty()) {
                    Text(
                        cfg.capabilities.joinToString(" · "),
                        fontSize = 9.sp, color = MaterialTheme.colorScheme.tertiary, maxLines = 1,
                    )
                }
                Text(
                    cfg.baseUrl,
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
                Text(
                    if (cfg.apiKey.isBlank()) stringResource(R.string.provider_no_key)
                    else stringResource(R.string.provider_key_prefix) + " ${cfg.apiKey.take(6)}••••",
                    fontSize = 10.sp,
                    color = if (cfg.apiKey.isBlank()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, stringResource(R.string.rename)) }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

// ==================== 多模态绑定 ====================

@Composable
private fun MultimodalBindingCard(vm: MainViewModel, configs: List<ProviderConfig>) {
    val caps = listOf(
        "IMAGE" to stringResource(R.string.mm_image),
        "VIDEO" to stringResource(R.string.mm_video),
        "VISION" to stringResource(R.string.mm_vision),
        "AUDIO" to stringResource(R.string.mm_audio),
        "VIDEO_UNDERSTAND" to stringResource(R.string.mm_video_understand),
        "TTS" to stringResource(R.string.mm_tts),
        "ASR" to stringResource(R.string.mm_asr),
        "SEARCH" to stringResource(R.string.mm_search),
        "CALL" to stringResource(R.string.mm_call),
    )

    var pickerCap by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.multimodal_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.multimodal_search) + "：${SearchManager.currentName()}",
                fontSize = 10.sp, color = MaterialTheme.colorScheme.tertiary,
            )

            caps.forEach { (cap, label) ->
                val boundId = vm.settings.multimodalBinding(cap)
                val bound = configs.firstOrNull { it.id == boundId }
                val display = bound?.let { "${it.label} · ${it.model}" }
                    ?: if (configs.none { cap in it.capabilities }) stringResource(R.string.multimodal_none)
                    else stringResource(R.string.multimodal_auto)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 13.sp, modifier = Modifier.width(90.dp))
                    Box(Modifier.weight(1f)) {
                        TextButton(onClick = { pickerCap = cap }, modifier = Modifier.fillMaxWidth()) {
                            Text(display, fontSize = 12.sp, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    pickerCap?.let { cap ->
        val label = caps.firstOrNull { it.first == cap }?.second ?: cap
        AppDialog(
            onDismissRequest = { pickerCap = null },
            title = label,
            dismissText = stringResource(R.string.cancel),
            onDismiss = { pickerCap = null },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                TextButton(
                    onClick = {
                        vm.settings.setMultimodalBinding(cap, "")
                        pickerCap = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.multimodal_auto), fontSize = 13.sp) }
                configs.filter { cap in it.capabilities }.forEach { cfg ->
                    TextButton(
                        onClick = {
                            vm.settings.setMultimodalBinding(cap, cfg.id)
                            pickerCap = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("${cfg.label} · ${cfg.model}", fontSize = 13.sp) }
                }
            }
        }
    }
}

// ==================== Agent 参数 ====================

@Composable
private fun AgentParamsCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var cfg by remember { mutableStateOf(vm.settings.agentConfig()) }
    val sUnlimited = stringResource(R.string.agent_unlimited)
    val sTimes = stringResource(R.string.agent_rounds_times)

    LaunchedEffect(revision) { cfg = vm.settings.agentConfig() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(
                    R.string.agent_max_rounds_label,
                    if (cfg.maxRounds == 0) sUnlimited else cfg.maxRounds.toString(),
                    sTimes,
                ),
                fontSize = 13.sp,
            )
            Slider(
                value = cfg.maxRounds.toFloat(),
                onValueChange = { vm.settings.setMaxRounds(it.toInt()) },
                valueRange = 0f..100f,
            )
            Text(
                stringResource(R.string.agent_temp_label, String.format("%.1f", cfg.temperature)),
                fontSize = 13.sp,
            )
            Slider(
                value = cfg.temperature,
                onValueChange = { vm.settings.setTemperature(it) },
                valueRange = 0f..2f,
            )
            Text(
                stringResource(R.string.agent_retries_label, cfg.networkRetries),
                fontSize = 13.sp,
            )
            Slider(
                value = cfg.networkRetries.toFloat(),
                onValueChange = { vm.settings.setStreamRetries(it.toInt()) },
                valueRange = 0f..10f,
            )
            Text(
                stringResource(R.string.agent_compress_label, cfg.contextCompressRounds),
                fontSize = 13.sp,
            )
            Text(
                stringResource(R.string.agent_compress_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = cfg.contextCompressRounds.toFloat(),
                onValueChange = { vm.settings.setContextCompressRounds(it.toInt()) },
                valueRange = 0f..50f,
            )
            Text(stringResource(R.string.agent_reasoning), fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val chips = listOf(
                    "" to stringResource(R.string.agent_reasoning_default),
                    "low" to stringResource(R.string.agent_reasoning_low),
                    "medium" to stringResource(R.string.agent_reasoning_medium),
                    "high" to stringResource(R.string.agent_reasoning_high),
                )
                chips.forEach { (v, label) ->
                    FilterChip(
                        selected = cfg.reasoningEffort == v,
                        onClick = { vm.settings.setReasoningEffort(v) },
                        label = { Text(label, fontSize = 12.sp) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            PromptExtraEditor(vm)
        }
    }
}

@Composable
private fun PromptExtraEditor(vm: MainViewModel) {
    var editing by remember { mutableStateOf(false) }
    val current = vm.settings.agentConfig().systemPromptExtra
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (current.isBlank()) stringResource(R.string.agent_prompt_unset)
            else stringResource(R.string.agent_prompt_title) + "：${current.take(20)}…",
            fontSize = 12.sp, modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.agent_prompt_edit), fontSize = 12.sp) }
    }
    if (editing) {
        var text by remember { mutableStateOf(current) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.agent_prompt_title)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(160.dp),
                    placeholder = { Text(stringResource(R.string.agent_prompt_example), fontSize = 12.sp) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.settings.setSystemPromptExtra(text)
                    editing = false
                }) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

// ==================== 主题风格 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeStyleCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    val context = LocalContext.current
    var currentStyle by remember { mutableStateOf(vm.settings.themeStyle()) }
    LaunchedEffect(revision) { currentStyle = vm.settings.themeStyle() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.theme_style_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.theme_style_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                com.ai.android.ui.theme.ThemePreset.all().forEach { preset ->
                    FilterChip(
                        selected = currentStyle == preset.key,
                        onClick = {
                            vm.settings.setThemeStyle(preset.key)
                            currentStyle = preset.key
                            runCatching { FloatingService.rebuildWidgets(context) }
                        },
                        label = { Text(preset.label, fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}

// ==================== 悬浮窗开关 ====================

@Composable
private fun FloatingToggleCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var enabled by remember { mutableStateOf(vm.settings.floatingEnabled()) }

    LaunchedEffect(revision) { enabled = vm.settings.floatingEnabled() }

    Card {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.floating_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.floating_desc),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = {
                    vm.settings.setFloatingEnabled(it)
                    enabled = it
                },
            )
        }
    }
}

// ==================== 项目管理 ====================

@Composable
private fun ProjectManagerCard(vm: MainViewModel) {
    val projects by vm.projects.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var managingMemory by remember { mutableStateOf<Project?>(null) }
    val projectUnnamed = stringResource(R.string.project_unnamed)
    val sMemoryBtn = stringResource(R.string.memory_btn)

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.project_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (projects.isEmpty()) {
                Text(stringResource(R.string.project_empty), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                projects.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { managingMemory = p }) {
                            Text(sMemoryBtn, fontSize = 12.sp)
                        }
                        IconButton(onClick = { renaming = p }) {
                            Icon(Icons.Default.Edit, stringResource(R.string.rename), Modifier.width(16.dp))
                        }
                        IconButton(onClick = {
                            vm.deleteProject(p.id)
                        }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.project_new), fontSize = 12.sp)
            }
        }
    }

    if (creating) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text(stringResource(R.string.project_new)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(stringResource(R.string.project_name), fontSize = 13.sp) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.createProject(text.trim().ifBlank { projectUnnamed })
                        creating = false
                    },
                    enabled = text.isNotBlank(),
                ) { Text(stringResource(R.string.project_create)) }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }

    renaming?.let { p ->
        var text by remember(p.id) { mutableStateOf(p.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.project_rename)) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(p.id, text.trim().ifBlank { p.name })
                    renaming = null
                }) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }

    managingMemory?.let { p ->
        ProjectMemoryDialog(vm = vm, project = p, onDismiss = { managingMemory = null })
    }
}

@Composable
private fun ProjectMemoryDialog(vm: MainViewModel, project: Project, onDismiss: () -> Unit) {
    val memories by vm.projectMemoryList.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(project.id) { vm.refreshProjectMemory(project.id) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.project_memory_title, project.name),
                fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.project_memory_desc),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (memories.isEmpty()) {
                    Text(stringResource(R.string.project_memory_empty),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    memories.forEach { (k, v) ->
                        Row(
                            verticalAlignment = Alignment.Top,
                            modifier = Modifier.fillMaxWidth().clickable { viewing = (k to v) },
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary)
                                Text(
                                    if (v.length > 80) v.take(80) + "…" else v,
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { vm.deleteProjectMemory(project.id, k) }) {
                                Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                            }
                        }
                    }
                }
                TextButton(onClick = { adding = true }) {
                    Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.project_memory_add), fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), fontSize = 13.sp) } },
    )

    if (adding) {
        var key by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.project_memory_add), fontSize = 14.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text(stringResource(R.string.project_memory_key_hint), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text(stringResource(R.string.project_memory_content_label), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveProjectMemory(project.id, key.trim(), content)
                        adding = false
                    },
                    enabled = key.isNotBlank() && content.isNotBlank(),
                ) { Text(stringResource(R.string.set_save), fontSize = 13.sp) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.set_cancel), fontSize = 13.sp) } },
        )
    }

    viewing?.let { (vk, vv) ->
        AlertDialog(
            onDismissRequest = { viewing = null },
            title = { Text(stringResource(R.string.project_memory_view_title, vk),
                fontSize = 14.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(vv, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProjectMemory(project.id, vk)
                    viewing = null
                }) { Text(stringResource(R.string.delete), fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { viewing = null }) { Text(stringResource(R.string.close), fontSize = 13.sp) } },
        )
    }
}

// ==================== 长期记忆 ====================

@Composable
private fun MemoryManagerCard(vm: MainViewModel) {
    val memories by vm.memoryList.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) { vm.refreshMemoryList() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.memory_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (memories.isEmpty()) {
                Text(stringResource(R.string.memory_empty), fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                memories.forEach { (k, v) ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        modifier = Modifier.fillMaxWidth().clickable { viewing = (k to v) },
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(k, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (v.length > 120) v.take(120) + "…" else v,
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.deleteMemory(k) }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                        }
                    }
                }
            }

            TextButton(onClick = { adding = true }) {
                Icon(Icons.Default.Add, null, Modifier.width(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.memory_add), fontSize = 12.sp)
            }
        }
    }

    viewing?.let { (vk, vv) ->
        ViewMemoryDialog(
            key = vk, content = vv,
            onDismiss = { viewing = null },
            onDelete = {
                vm.deleteMemory(vk)
                viewing = null
            },
        )
    }

    if (adding) {
        var key by remember { mutableStateOf("") }
        var content by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(R.string.memory_add_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = key, onValueChange = { key = it },
                        label = { Text(stringResource(R.string.memory_key), fontSize = 12.sp) },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = content, onValueChange = { content = it },
                        label = { Text(stringResource(R.string.memory_content), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth().height(120.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.saveMemory(key.trim(), content)
                        adding = false
                    },
                    enabled = key.isNotBlank() && content.isNotBlank(),
                ) { Text(stringResource(R.string.set_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.set_cancel)) } },
        )
    }
}

@Composable
private fun ViewMemoryDialog(
    key: String,
    content: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val sCopied = stringResource(R.string.memory_copied)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.memory_view_title, key), fontSize = 14.sp, fontWeight = FontWeight.Medium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(content, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("memory_$key", content))
                Toast.makeText(context, sCopied, android.widget.Toast.LENGTH_SHORT).show()
            }) { Text(stringResource(R.string.copy), fontSize = 13.sp) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close), fontSize = 13.sp) }
            }
        },
    )
}

// ==================== 权限 ====================

@Composable
private fun PermissionCard(
    status: PermissionHelper.Status,
    onRequestStorage: () -> Unit,
    onRequestNotification: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PermissionRow(stringResource(R.string.perm_storage), status.storage, onRequestStorage)
            PermissionRow(stringResource(R.string.perm_notification), status.notification, onRequestNotification)
            PermissionRow(stringResource(R.string.perm_overlay), status.overlay, onRequestOverlay)
            PermissionRow(stringResource(R.string.perm_accessibility), status.accessibility, onRequestAccessibility)
        }
    }
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, onRequest: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (granted) stringResource(R.string.perm_granted) else stringResource(R.string.perm_ungranted),
            fontSize = 11.sp,
            color = if (granted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (!granted) {
            TextButton(onClick = onRequest) { Text(stringResource(R.string.perm_go), fontSize = 12.sp) }
        }
    }
}

// ==================== Provider 编辑对话框 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditProviderDialog(
    initial: ProviderConfig?,
    onDismiss: () -> Unit,
    onSave: (ProviderConfig) -> Unit,
) {
    val context = LocalContext.current
    var label by remember { mutableStateOf(initial?.label ?: "") }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: "https://") }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var protocol by remember { mutableStateOf(initial?.protocol ?: AIProvider.Protocol.OPENAI) }
    var caps by remember { mutableStateOf(initial?.capabilities?.toSet() ?: setOf("CHAT")) }
    var extraBodyJson by remember { mutableStateOf(initial?.extraBodyJson ?: "") }

    var extraParams by remember {
        mutableStateOf(
            when {
                initial != null && initial.extraParams.isNotEmpty() -> initial.extraParams.toList()
                initial != null && initial.extraBodyJson.isNotBlank() -> ProviderConfig.jsonToRows(initial.extraBodyJson)
                else -> {
                    val initCaps = (initial?.capabilities?.toSet() ?: setOf("CHAT"))
                    val initUrl = initial?.baseUrl ?: "https://"
                    val initModel = initial?.model ?: ""
                    ProviderConfig.defaultParamsForCaps(initCaps, initUrl, initModel)
                }
            }
        )
    }
    var extraHeaders by remember { mutableStateOf(initial?.extraHeaders ?: "") }
    var advOpen by remember { mutableStateOf(false) }
    var jsonDirty by remember { mutableStateOf(false) }
    var typeMenuIdx by remember { mutableStateOf(-1) }
    val paramTypes = listOf("string", "number", "bool", "json")

    val sBuiltinModel = stringResource(R.string.provider_builtin_model_hint)
    val sBuiltinMessages = stringResource(R.string.provider_builtin_messages_hint)
    val sBuiltinTools = stringResource(R.string.provider_builtin_tools_hint)
    val sHide = stringResource(R.string.provider_hide)
    val sFillEmpty = stringResource(R.string.provider_fill_empty)
    val sFillAllExist = stringResource(R.string.provider_fill_all_exist)
    val sFillAdded = stringResource(R.string.provider_fill_added)

    val builtinParams = remember(sBuiltinModel, sBuiltinMessages, sBuiltinTools) {
        mutableStateListOf(
            "model" to sBuiltinModel,
            "messages" to sBuiltinMessages,
            "stream" to "true",
            "tools" to sBuiltinTools,
        )
    }

    fun updateRow(idx: Int, f: (ParamRow) -> ParamRow) {
        extraParams = extraParams.mapIndexed { i, r -> if (i == idx) f(r) else r }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) stringResource(R.string.provider_new_title) else stringResource(R.string.provider_edit_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text(stringResource(R.string.provider_name), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.OPENAI,
                        onClick = { protocol = AIProvider.Protocol.OPENAI },
                        label = { Text(stringResource(R.string.provider_openai), fontSize = 11.sp) },
                    )
                    FilterChip(
                        selected = protocol == AIProvider.Protocol.ANTHROPIC,
                        onClick = { protocol = AIProvider.Protocol.ANTHROPIC },
                        label = { Text(stringResource(R.string.provider_anthropic), fontSize = 11.sp) },
                    )
                }
                OutlinedTextField(
                    value = baseUrl, onValueChange = { baseUrl = it },
                    label = { Text(stringResource(R.string.provider_baseurl), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text(stringResource(R.string.provider_key), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text(stringResource(R.string.provider_model), fontSize = 12.sp) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                Text(stringResource(R.string.provider_caps), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.provider_caps_mutex_hint),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                CapabilityChips(
                    selected = caps,
                    onToggle = { c ->
                        val willAdd = c !in caps
                        val newCaps: Set<String> = if (!willAdd) {
                            caps - c
                        } else {
                            val withNew = caps + c
                            when {
                                c in ProviderConfig.VOICE_CAPS -> withNew - ProviderConfig.CHAT_CAPS
                                c in ProviderConfig.CHAT_CAPS -> withNew - ProviderConfig.VOICE_CAPS
                                else -> withNew
                            }
                        }
                        caps = newCaps
                        if (willAdd) {
                            val defaults = ProviderConfig.defaultParamsForCaps(newCaps, baseUrl, model)
                            val existingKeys = extraParams.map { it.key }.toSet()
                            val toAdd = defaults.filter { it.key !in existingKeys }
                            if (toAdd.isNotEmpty()) {
                                extraParams = extraParams + toAdd
                                extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                                jsonDirty = false
                            }
                        }
                    },
                )

                if (builtinParams.isNotEmpty()) {
                    Text(stringResource(R.string.provider_builtin_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.provider_builtin_desc),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    builtinParams.forEachIndexed { idx, (k, v) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Surface(
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        k,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        v,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = { builtinParams.removeAt(idx) },
                                modifier = Modifier.size(24.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = sHide,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(stringResource(R.string.param_title), fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text(
                    stringResource(R.string.param_desc),
                    fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ProviderConfig.PRESET_PARAMS.forEach { preset ->
                        val exists = extraParams.any { it.key == preset.key }
                        FilterChip(
                            selected = exists,
                            onClick = {
                                if (exists) return@FilterChip
                                extraParams = extraParams + preset
                                extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                                jsonDirty = false
                            },
                            label = { Text(preset.key + " ＋", fontSize = 11.sp) },
                        )
                    }
                }

                TextButton(
                    onClick = {
                        val defaults = ProviderConfig.defaultParamsForCaps(caps, baseUrl, model)
                        if (defaults.isEmpty()) {
                            Toast.makeText(context, sFillEmpty, Toast.LENGTH_SHORT).show()
                        } else {
                            val existingKeys = extraParams.map { it.key }.toSet()
                            val toAdd = defaults.filter { it.key !in existingKeys }
                            if (toAdd.isEmpty()) {
                                Toast.makeText(context, sFillAllExist, Toast.LENGTH_SHORT).show()
                            } else {
                                extraParams = extraParams + toAdd
                                extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                                jsonDirty = false
                                Toast.makeText(context, sFillAdded.format(toAdd.size), Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.provider_fill_defaults), fontSize = 12.sp)
                }

                if (extraParams.isEmpty()) {
                    Text(stringResource(R.string.param_empty), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                extraParams.forEachIndexed { idx, r ->
                    ParamRowEditor(
                        row = r,
                        index = idx,
                        count = extraParams.size,
                        types = paramTypes,
                        typeMenuIdx = typeMenuIdx,
                        onTypeMenu = { typeMenuIdx = if (typeMenuIdx == idx) -1 else idx },
                        onChange = { new -> updateRow(idx) { new } },
                        onDelete = {
                            extraParams = extraParams.filterIndexed { i, _ -> i != idx }
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                            if (typeMenuIdx == idx) typeMenuIdx = -1
                        },
                        onMoveUp = {
                            if (idx == 0) return@ParamRowEditor
                            val list = extraParams.toMutableList()
                            val t = list.removeAt(idx); list.add(idx - 1, t)
                            extraParams = list
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                        },
                        onMoveDown = {
                            if (idx == extraParams.size - 1) return@ParamRowEditor
                            val list = extraParams.toMutableList()
                            val t = list.removeAt(idx); list.add(idx + 1, t)
                            extraParams = list
                            extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                            jsonDirty = false
                        },
                    )
                }

                OutlinedButton(
                    onClick = {
                        extraParams = extraParams + ParamRow()
                        extraBodyJson = ProviderConfig.rowsToJson(extraParams)
                        jsonDirty = false
                    },
                ) {
                    Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.param_add), fontSize = 12.sp)
                }

                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { advOpen = !advOpen },
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) {
                    Icon(
                        if (advOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, Modifier.width(16.dp).height(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.param_adv), fontSize = 12.sp)
                }

                if (advOpen) {
                    OutlinedTextField(
                        value = extraBodyJson,
                        onValueChange = { extraBodyJson = it; jsonDirty = true },
                        label = { Text(stringResource(R.string.param_json_label), fontSize = 11.sp) },
                        placeholder = { Text("{\"top_p\":0.9}", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    if (jsonDirty) {
                        OutlinedButton(
                            onClick = {
                                val parsed = ProviderConfig.jsonToRows(extraBodyJson)
                                if (parsed.isNotEmpty()) {
                                    extraParams = parsed
                                    jsonDirty = false
                                }
                            },
                        ) { Text(stringResource(R.string.param_sync), fontSize = 11.sp) }
                    }
                    OutlinedTextField(
                        value = extraHeaders, onValueChange = { extraHeaders = it },
                        label = { Text(stringResource(R.string.param_headers_label), fontSize = 11.sp) },
                        placeholder = { Text("X-Custom: abc", fontSize = 11.sp) },
                        modifier = Modifier.fillMaxWidth().height(90.dp),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    )
                    Text(
                        stringResource(R.string.param_merge_hint),
                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalJson = extraBodyJson.trim()
                    val finalRows = if (jsonDirty) ProviderConfig.jsonToRows(finalJson) else extraParams
                    onSave(
                        ProviderConfig(
                            id = initial?.id ?: UUID.randomUUID().toString().take(8),
                            label = label.ifBlank { "Untitled" },
                            protocol = protocol,
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            model = model.trim(),
                            capabilities = caps.toList().ifEmpty { listOf("CHAT") },
                            extraBodyJson = finalJson,
                            extraParams = finalRows,
                            extraHeaders = extraHeaders.trim(),
                            contextWindow = initial?.contextWindow ?: 0,
                        )
                    )
                },
                enabled = label.isNotBlank() && baseUrl.length > 8,
            ) { Text(stringResource(R.string.set_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.set_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityChips(
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ProviderConfig.ALL_CAPS.forEach { c ->
            FilterChip(
                selected = c in selected,
                onClick = { onToggle(c) },
                label = { Text(c, fontSize = 11.sp) },
            )
        }
    }
}

// ==================== 逐条请求参数编辑器 ====================

@Composable
private fun ParamRowEditor(
    row: ParamRow,
    index: Int,
    count: Int,
    types: List<String>,
    typeMenuIdx: Int,
    onTypeMenu: () -> Unit,
    onChange: (ParamRow) -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = row.key,
                    onValueChange = { onChange(row.copy(key = it)) },
                    label = { Text(stringResource(R.string.param_key), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("temperature", fontSize = 10.sp) },
                )
                OutlinedTextField(
                    value = row.value,
                    onValueChange = { onChange(row.copy(value = it)) },
                    label = { Text(stringResource(R.string.param_value), fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("0.3", fontSize = 10.sp) },
                )
                Box {
                    OutlinedButton(
                        onClick = onTypeMenu,
                        modifier = Modifier.width(76.dp),
                    ) {
                        Text(row.type, fontSize = 10.sp)
                    }
                }
            }
            if (typeMenuIdx == index) {
                AppDialog(
                    onDismissRequest = onTypeMenu,
                    title = stringResource(R.string.param_type_title),
                    dismissText = stringResource(R.string.cancel),
                    onDismiss = onTypeMenu,
                ) {
                    types.forEach { t ->
                        TextButton(
                            onClick = {
                                onChange(row.copy(type = t))
                                onTypeMenu()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(t, fontSize = 13.sp) }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = onMoveUp, enabled = index > 0,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("↑", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onMoveDown, enabled = index < count - 1,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("↓", fontSize = 11.sp)
                }
                OutlinedButton(onClick = onDelete,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Icon(Icons.Default.Delete, null, Modifier.size(14.dp))
                }
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ==================== AI 行为 ====================

@Composable
private fun AiBehaviorCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    var autoTitle by remember { mutableStateOf(vm.settings.autoTitleSummary()) }
    LaunchedEffect(revision) { autoTitle = vm.settings.autoTitleSummary() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_behavior_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_title), fontSize = 13.sp)
                    Text(
                        stringResource(R.string.auto_title_desc),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoTitle,
                    onCheckedChange = { vm.settings.setAutoTitleSummary(it); autoTitle = it },
                )
            }
        }
    }
}

// ==================== AI 操控 ====================

@Composable
private fun AiControlCard(vm: MainViewModel) {
    val revision by vm.settings.revision.collectAsState()
    val context = LocalContext.current
    var aiControl by remember { mutableStateOf(vm.settings.aiControlEnabled()) }
    var mirror by remember { mutableStateOf(vm.settings.mirrorEnabled()) }
    var confirm by remember { mutableStateOf(vm.settings.confirmBeforeControl()) }
    var showGuide by remember { mutableStateOf(false) }
    var accessibilityGranted by remember {
        mutableStateOf(PermissionHelper.isAccessibilityEnabled(context))
    }

    LaunchedEffect(revision) {
        aiControl = vm.settings.aiControlEnabled()
        mirror = vm.settings.mirrorEnabled()
        confirm = vm.settings.confirmBeforeControl()
        accessibilityGranted = PermissionHelper.isAccessibilityEnabled(context)
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.ai_control_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.ai_control_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            AiControlRow(
                title = stringResource(R.string.ai_control_toggle),
                desc = if (aiControl && !accessibilityGranted)
                    stringResource(R.string.ai_control_not_enabled)
                else stringResource(R.string.ai_control_toggle_desc),
                checked = aiControl,
                onChange = { v ->
                    vm.settings.setAiControlEnabled(v); aiControl = v
                    if (v && !accessibilityGranted) showGuide = true
                },
            )
            AiControlRow(
                title = stringResource(R.string.ai_control_mirror),
                desc = stringResource(R.string.ai_control_mirror_desc),
                checked = mirror,
                onChange = { vm.settings.setMirrorEnabled(it); mirror = it },
            )
            AiControlRow(
                title = stringResource(R.string.ai_control_confirm),
                desc = stringResource(R.string.ai_control_confirm_desc),
                checked = confirm,
                onChange = { vm.settings.setConfirmBeforeControl(it); confirm = it },
            )
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            title = { Text(stringResource(R.string.a11y_guide_title), fontSize = 15.sp, fontWeight = FontWeight.Medium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.a11y_guide_body),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    PermissionHelper.openAccessibilitySettings(context as? Activity ?: return@TextButton)
                    showGuide = false
                }) {
                    Text(stringResource(R.string.a11y_guide_go), fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showGuide = false }) {
                    Text(stringResource(R.string.a11y_guide_later), fontSize = 13.sp)
                }
            },
        )
    }
}

@Composable
private fun AiControlRow(
    title: String,
    desc: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp)
            Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

// ==================== Shizuku ====================

@Composable
private fun ShizukuCard(vm: MainViewModel, context: android.content.Context) {
    var installed by remember { mutableStateOf(com.ai.android.service.ShizukuManager.isInstalled(context)) }
    var available by remember { mutableStateOf(com.ai.android.service.ShizukuManager.isAvailable(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            installed = com.ai.android.service.ShizukuManager.isInstalled(context)
            available = com.ai.android.service.ShizukuManager.isAvailable(context)
            runCatching { vm.settings.setShizukuEnabled(available) }
            delay(3000)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.shizuku_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.shizuku_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            when {
                !installed -> {
                    Text(stringResource(R.string.shizuku_not_installed),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = {
                        com.ai.android.service.ShizukuManager.ensureAuthorized(context)
                    }) { Text(stringResource(R.string.shizuku_request), fontSize = 12.sp) }
                }
                !available -> {
                    Text(stringResource(R.string.shizuku_installed),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                    OutlinedButton(onClick = {
                        com.ai.android.service.ShizukuManager.ensureAuthorized(context)
                        available = com.ai.android.service.ShizukuManager.isAvailable(context)
                        vm.settings.setShizukuEnabled(available)
                    }) { Text(stringResource(R.string.shizuku_request), fontSize = 12.sp) }
                    TroubleshootingTip()
                }
                else -> {
                    Text(stringResource(R.string.shizuku_granted),
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.tertiary)
                    Text(stringResource(R.string.shizuku_running_as),
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TroubleshootingTip() {
    var showHelp by remember { mutableStateOf(false) }
    TextButton(onClick = { showHelp = true }) {
        Text(stringResource(R.string.shizuku_help_link), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(stringResource(R.string.shizuku_help_title), fontSize = 14.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.shizuku_help_step1), fontSize = 12.sp)
                    Text(stringResource(R.string.shizuku_help_step2), fontSize = 12.sp)
                    Text(stringResource(R.string.shizuku_help_step3), fontSize = 12.sp)
                    Text(stringResource(R.string.shizuku_help_step4), fontSize = 12.sp)
                    Text(stringResource(R.string.shizuku_help_more), fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(stringResource(R.string.shizuku_got_it)) } },
        )
    }
}

// ==================== 语言 ====================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageCard(vm: MainViewModel, context: android.content.Context) {
    val revision by vm.settings.revision.collectAsState()
    var lang by remember { mutableStateOf(vm.settings.appLanguage()) }
    var hasPack by remember { mutableStateOf(com.ai.android.i18n.I18nManager.hasPack()) }

    val sImported = stringResource(R.string.lang_imported)
    val sFailed = stringResource(R.string.lang_failed)
    val sCleared = stringResource(R.string.lang_cleared)

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = com.ai.android.i18n.I18nManager.importPack(uri, context)
        hasPack = com.ai.android.i18n.I18nManager.hasPack()
        Toast.makeText(context, if (ok) sImported else sFailed, Toast.LENGTH_LONG).show()
        (context as? Activity)?.let { com.ai.android.i18n.I18nManager.applyLocale(it) }
    }

    LaunchedEffect(revision, Unit) {
        lang = vm.settings.appLanguage()
        hasPack = com.ai.android.i18n.I18nManager.hasPack()
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.lang_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.lang_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                com.ai.android.i18n.I18nManager.supportedLanguages().forEach { (code, label) ->
                    FilterChip(
                        selected = lang == code,
                        onClick = {
                            vm.settings.setAppLanguage(code)
                            lang = code
                            (context as? Activity)?.let {
                                com.ai.android.i18n.I18nManager.applyLocale(it)
                            }
                        },
                        label = {
                            Text(
                                if (code == com.ai.android.i18n.I18nManager.FOLLOW)
                                    stringResource(R.string.lang_follow) else label,
                                fontSize = 12.sp,
                            )
                        },
                    )
                }
            }

            OutlinedButton(
                onClick = { openDocument.launch(arrayOf("application/json")) },
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.lang_import_pack), fontSize = 12.sp)
            }

            if (hasPack) {
                TextButton(onClick = {
                    com.ai.android.i18n.I18nManager.clearPack(context)
                    hasPack = false
                    (context as? Activity)?.let { com.ai.android.i18n.I18nManager.applyLocale(it) }
                    Toast.makeText(context, sCleared, Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.lang_remove_pack), fontSize = 12.sp)
                }
            }
        }
    }
}

// ==================== Token 统计 ====================

@Composable
private fun TokenStatsCard(vm: MainViewModel, context: android.content.Context) {
    var totals by remember { mutableStateOf(vm.tokenTotalsForCurrent()) }
    var exporting by remember { mutableStateOf(false) }

    val sNoneToExport = stringResource(R.string.token_none_to_export)
    val sExported = stringResource(R.string.token_exported)
    val sExportFailed = stringResource(R.string.token_export_failed)
    val sCleared = stringResource(R.string.token_cleared)

    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        exporting = false
        if (uri != null) {
            runCatching {
                val jsonStr = vm.exportTokenJson(vm.current.value?.id.orEmpty())
                if (jsonStr == null) {
                    Toast.makeText(context, sNoneToExport, Toast.LENGTH_SHORT).show()
                } else {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(jsonStr.toByteArray(Charsets.UTF_8))
                    }
                    Toast.makeText(context, sExported, Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(context, sExportFailed.format(it.message ?: ""), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            totals = vm.tokenTotalsForCurrent()
            delay(1500)
        }
    }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.token_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.token_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TokenStatItem(stringResource(R.string.token_input), totals.promptTokens)
                TokenStatItem(stringResource(R.string.token_output), totals.completionTokens)
                TokenStatItem(stringResource(R.string.token_cache), totals.cacheHitTokens)
                TokenStatItem(stringResource(R.string.token_total), totals.totalTokens)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    exporting = true
                    createDocument.launch("token-stats-${System.currentTimeMillis()}.json")
                }) { Text(stringResource(R.string.token_export), fontSize = 12.sp) }
                OutlinedButton(onClick = {
                    val cur = vm.current.value ?: return@OutlinedButton
                    vm.clearTokenStats(cur.id)
                    totals = vm.tokenTotalsForCurrent()
                    Toast.makeText(context, sCleared, Toast.LENGTH_SHORT).show()
                }) { Text(stringResource(R.string.token_clear), fontSize = 12.sp) }
            }
        }
    }
}

@Composable
private fun TokenStatItem(label: String, value: Int) {
    Column(Modifier.width(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 插件管理（原有，保留在 "plugin" 分类里）====================

@Composable
private fun PluginManagerCard(vm: MainViewModel, context: android.content.Context) {
    val revision by vm.settings.revision.collectAsState()
    var plugins by remember { mutableStateOf(PluginManager.list(context).toList()) }
    var guardCounts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    val sDeleted = stringResource(R.string.plugin_delete_toast)
    val sImportError = stringResource(R.string.plugin_import_error)

    fun refreshPlugins() {
        val list = PluginManager.list(context).toList()
        plugins = list
        guardCounts = list.associate { p -> p.id to PluginGuardBridge.guardCountOf(p.id) }
    }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val msg = PluginManager.importZip(context, uri)
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            com.ai.android.MainApp.instance.pluginRegistry.refresh()
            FloatingService.rebuildWidgets(context)
            refreshPlugins()
        }.onFailure {
            Toast.makeText(context, sImportError.format(it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) { refreshPlugins() }
    LaunchedEffect(revision) { refreshPlugins() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plugin_title), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                stringResource(R.string.plugin_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    openDocument.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.plugin_import), fontSize = 13.sp) }

            if (plugins.isEmpty()) {
                Text(
                    stringResource(R.string.plugin_empty),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                plugins.forEach { p ->
                    val enabled = PluginManager.isEnabled(context, p.id)
                    val guardCount = guardCounts[p.id] ?: 0
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp)
                            val capsText = p.capabilities.joinToString(",").ifBlank { "—" }
                            val guardText = if (guardCount > 0) " · Guard×$guardCount" else ""
                            Text(
                                "id=${p.id} · v${p.version} · $capsText$guardText",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { v ->
                                PluginManager.setEnabled(context, p.id, v)
                                runCatching {
                                    com.ai.android.MainApp.instance.pluginRegistry.refresh()
                                    FloatingService.rebuildWidgets(context)
                                }
                                refreshPlugins()
                            },
                        )
                        IconButton(onClick = {
                            PluginManager.remove(context, p.id)
                            runCatching {
                                com.ai.android.MainApp.instance.pluginRegistry.refresh()
                                FloatingService.rebuildWidgets(context)
                            }
                            Toast.makeText(context, sDeleted.format(p.name), Toast.LENGTH_SHORT).show()
                            refreshPlugins()
                        }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete), Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }
}