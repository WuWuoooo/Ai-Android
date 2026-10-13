package com.ai.android.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.MainViewModel
import com.ai.android.util.CrashHandler
import com.ai.android.util.HealthCheck
import com.ai.android.util.HealthItem
import com.ai.android.util.HealthLevel
import com.ai.android.util.HealthSection
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results = remember { mutableStateListOf<HealthSection>() }
    var loading by remember { mutableStateOf(true) }
    var fatalError by remember { mutableStateOf<String?>(null) }
    var safeMode by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf<String?>(null) }
    // ⭐ 崩溃日志展开状态（默认折叠）
    var crashExpanded by remember { mutableStateOf(false) }

    BackHandler { onBack() }

    LaunchedEffect(Unit) {
        lastCrash = CrashHandler.readLast(context)
    }

    fun refresh() {
        results.clear()
        loading = true
        fatalError = null
        scope.launch {
            try {
                HealthCheck.runStreaming(
                    context = context,
                    convId = vm.current.value?.id.orEmpty(),
                    safeMode = safeMode,
                ) { section ->
                    results.add(section)
                }
            } catch (e: Throwable) {
                fatalError = "${e.javaClass.simpleName}: ${e.message}"
                e.printStackTrace()
            }
            loading = false
        }
    }

    LaunchedEffect(safeMode) { refresh() }

    Scaffold(
        topBar = {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                TopAppBar(
                    title = { Text("诊断") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {

            // ⭐ 上次崩溃日志（默认折叠，可展开，支持长按复制）
            lastCrash?.let { crash ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Error, null, Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(6.dp))
                            Text("上次崩溃日志", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { crashExpanded = !crashExpanded },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    if (crashExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = if (crashExpanded) "收起" else "展开",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                            IconButton(
                                onClick = {
                                    CrashHandler.clearLast(context)
                                    lastCrash = null
                                    crashExpanded = false
                                },
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(Icons.Default.Delete, "清除", Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error)
                            }
                        }
                        if (crashExpanded) {
                            SelectionContainer {
                                Text(
                                    crash,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                )
                            }
                        } else {
                            Text(
                                crash.lineSequence().take(3).joinToString("\n"),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("简化模式（跳过终端 / Shizuku）", fontSize = 12.sp)
                    Text(
                        "排查用：这两项最容易触发系统层问题。",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = safeMode, onCheckedChange = { safeMode = it })
            }

            OutlinedButton(
                onClick = { refresh() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                enabled = !loading,
            ) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (loading) "检查中…" else "重新检查", fontSize = 13.sp)
            }

            fatalError?.let { msg ->
                Card(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Text(
                        "诊断异常：$msg",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            if (loading && results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(results, key = { it.title }) { s -> SectionCard(s) }
                    if (loading) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) { CircularProgressIndicator(Modifier.size(20.dp)) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(section: HealthSection) {
    var expanded by remember { mutableStateOf(false) }
    val (icon, tint) = when (section.level) {
        HealthLevel.OK -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
        HealthLevel.WARN -> Icons.Default.Warning to Color(0xFFE8A33D)
        HealthLevel.ERROR -> Icons.Default.Error to MaterialTheme.colorScheme.error
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), tint = tint)
                Spacer(Modifier.width(8.dp))
                Text(section.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起" else "展开",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                section.summary,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 26.dp, top = 2.dp),
            )
            AnimatedVisibility(visible = expanded) {
                // ⭐ 关键修复：不再套 verticalScroll。
                //    LazyColumn 已给 item 无限高度约束，里面再套 verticalScroll 会崩。
                //    这里直接 Column 铺开，滚动交给外层 LazyColumn。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, start = 4.dp),
                ) {
                    section.items.forEach { item -> ItemRow(item) }
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: HealthItem) {
    val color = when (item.level) {
        HealthLevel.OK -> MaterialTheme.colorScheme.onSurface
        HealthLevel.WARN -> Color(0xFFE8A33D)
        HealthLevel.ERROR -> MaterialTheme.colorScheme.error
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            item.label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(120.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            item.value,
            fontSize = 11.sp,
            color = color,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
    }
}