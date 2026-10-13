package com.ai.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.ai.android.MainViewModel
import com.ai.android.R
import com.ai.android.agent.ToolCatalog
import com.ai.android.model.ChatMessage
import com.ai.android.ui.components.AppMenuPanel
import com.ai.android.ui.components.AppMenuItem
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenConversations: () -> Unit,
    onOpenCall: (videoMode: Boolean) -> Unit = { },
    onOpenTerminal: () -> Unit = { },
) {
    val context = LocalContext.current
    val current by vm.current.collectAsState()
    val version by vm.version.collectAsState()
    val agentState by vm.agentState.collectAsState()
    val sessionStats by vm.sessionStats.collectAsState()
    val pending by vm.pendingQuestion.collectAsState()
    val attachedFiles by vm.attachedFiles.collectAsState()
    val attachedImages by vm.attachedImages.collectAsState()
    val quoted by vm.quotedMessage.collectAsState()
    val lastIncomplete by vm.lastGenerationIncomplete.collectAsState()

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    var speakingId by remember { mutableStateOf<String?>(null) }
    var mediaTtsPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    fun enterMultiSelect() {
        selectionMode = true
        selectedIds = emptySet()
    }
    fun exitMultiSelect() {
        selectionMode = false
        selectedIds = emptySet()
    }

    var showConvDrawer by remember { mutableStateOf(false) }
    var showFilePick by remember { mutableStateOf(false) }
    var showImagePick by remember { mutableStateOf(false) }
    var showPermissionDialog by remember { mutableStateOf(false) }

    val tts = remember {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) engine?.language = Locale.CHINA
        }
        engine
    }
    DisposableEffect(Unit) { onDispose { tts?.stop(); tts?.shutdown() } }

    LaunchedEffect(current?.id) {
        selectionMode = false
        selectedIds = emptySet()
        speakingId = null
        input = current?.id?.let { vm.settings.inputDraft(it) } ?: ""
    }

    LaunchedEffect(input) {
        val convId = current?.id ?: return@LaunchedEffect
        kotlinx.coroutines.delay(400)
        runCatching { vm.settings.setInputDraft(convId, input) }
    }

    val messages = current?.messages?.toList() ?: emptyList()
    val toolResults = remember(version, messages.size) {
        messages.flatMap { it.toolResults }.associateBy { it.toolCallId }
    }
    val lastAssistantId = remember(messages) {
        messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
    }

    var userScrolledUp by remember { mutableStateOf(false) }
    val messagesRef = rememberUpdatedState(messages)

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (scrolling) {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    val lastIndex: Int = messagesRef.value.lastIndex
                    userScrolledUp = lastVisible < lastIndex
                }
            }
    }

    LaunchedEffect(messages.size) {
        if (messages.lastOrNull()?.role == ChatMessage.Role.USER) {
            userScrolledUp = false
        }
    }

    LaunchedEffect(
        version,
        messages.size,
        messages.lastOrNull()?.content?.length ?: 0,
        messages.lastOrNull()?.reasoning?.length ?: 0,
    ) {
        if (messages.isNotEmpty() && !userScrolledUp) {
            runCatching { listState.scrollToItem(messages.size) }
        }
    }

    val asrManager = remember { vm.providerManager.multimodal }
    val voiceController = remember(asrManager) {
        VoiceInputController(context, asrManager)
    }

    val activeProvider = vm.providerManager.active
    val modelLabel = activeProvider?.let { "${it.name} ${it.model}" } ?: ""
    var showModelPicker by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }

    val ttsScope = rememberCoroutineScope()

    // 预取字符串（避免在 lambda 里调 stringResource）
    val sNewChat = stringResource(R.string.chat_new)
    val sSettings = stringResource(R.string.chat_settings)
    val sTerminal = stringResource(R.string.chat_terminal)
    val sConversations = stringResource(R.string.chat_conversations)
    val sCopied = stringResource(R.string.chat_copied)
    val sQuoted = stringResource(R.string.chat_quoted)
    val sSwitchedPrefix = stringResource(R.string.toast_switched)
    val sDeletedN = stringResource(R.string.conv_deleted_n)
    val sDeleteLabel = stringResource(R.string.chat_delete)
    val sCancel = stringResource(R.string.chat_cancel)
    val sSelected = stringResource(R.string.chat_selected)
    val sItems = stringResource(R.string.chat_items)
    val sSwitchModel = stringResource(R.string.input_switch_model)
    val sNoChatModel = stringResource(R.string.input_no_chat_model)
    val sAdd = stringResource(R.string.chat_add)
    val sPickFile = stringResource(R.string.chat_pick_file)
    val sPickImage = stringResource(R.string.chat_pick_image)
    val sPermission = stringResource(R.string.chat_permission)
    val sVoiceCall = stringResource(R.string.chat_voice_call)
    val sVideoCall = stringResource(R.string.chat_video_call)
    val sPermissionSaved = stringResource(R.string.perm_saved)

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)) {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = {
                                runCatching {
                                    (context as? android.app.Activity)
                                        ?.window
                                        ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
                                }
                                showConvDrawer = true
                            }) {
                                Icon(Icons.Default.Menu, sConversations)
                            }
                        },
                        title = {
                            Column {
                                Text(current?.title ?: stringResource(R.string.chat_default_title),
                                    fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    vm.providerManager.active?.let { "${it.name} · ${it.model}" }
                                        ?: stringResource(R.string.chat_unconfigured),
                                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onOpenTerminal) { Icon(Icons.Default.Terminal, sTerminal) }
                            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.Add, sNewChat) }
                            IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, sSettings) }
                        },
                    )
                }
            },
            bottomBar = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    if (selectionMode) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.extraLarge)
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "$sSelected ${selectedIds.size} $sItems",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                if (selectedIds.isNotEmpty()) {
                                    vm.deleteMessages(selectedIds.toSet())
                                    Toast.makeText(
                                        context,
                                        sDeletedN.format(selectedIds.size),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                exitMultiSelect()
                            }) { Text(sDeleteLabel, fontSize = 14.sp) }
                            TextButton(onClick = { exitMultiSelect() }) { Text(sCancel, fontSize = 14.sp) }
                        }
                    } else {
                        InputBar(
                            value = input,
                            onValueChange = { input = it },
                            onSend = { t -> input = ""; vm.send(t) },
                            onStop = { vm.stop() },
                            isRunning = agentState.isActive,
                            stateText = agentState.progressText,
                            onPickFile = {
                                showFilePick = !showFilePick
                                if (showFilePick) showImagePick = false
                            },
                            onPickImage = {
                                showImagePick = !showImagePick
                                if (showImagePick) showFilePick = false
                            },
                            attachedFiles = attachedFiles,
                            attachedImages = attachedImages,
                            onRemoveAttachment = { vm.removeAttachment(it) },
                            onRemoveImage = { vm.removeImage(it) },
                            quotedContent = quoted?.content.orEmpty(),
                            onClearQuote = { vm.clearQuote() },
                            onStartVoiceCall = { onOpenCall(false) },
                            onStartVideoCall = { onOpenCall(true) },
                            voiceController = voiceController,
                            onVoiceAutoSend = { text -> vm.send(text) },
                            modelLabel = modelLabel,
                            onSwitchModel = { showModelPicker = true },
                            onToggleAddMenu = { showAddMenu = !showAddMenu },
                            modelMenuOpen = showModelPicker,
                            sessionStats = sessionStats,
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (messages.isEmpty()) {
                    EmptyHint(Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        itemsIndexed(messages, key = { _, m -> m.id }) { _, m ->
                            MessageBubble(
                                message = m,
                                toolResults = toolResults,
                                isLastAssistant = m.id == lastAssistantId,
                                showContinue = m.id == lastAssistantId && lastIncomplete,
                                onRetry = { vm.retryLast() },
                                onContinue = { vm.continueGeneration() },
                                onCopy = {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText("ai", m.content))
                                    Toast.makeText(context, sCopied, Toast.LENGTH_SHORT).show()
                                },
                                onQuote = {
                                    vm.quoteMessage(m)
                                    Toast.makeText(context, sQuoted, Toast.LENGTH_SHORT).show()
                                },
                                onSpeak = {
                                    val text = m.content.take(2000)
                                    if (speakingId == m.id) {
                                        mediaTtsPlayer?.let { runCatching { it.stop(); it.release() } }
                                        mediaTtsPlayer = null
                                        runCatching { tts?.stop() }
                                        speakingId = null
                                    } else {
                                        ttsScope.launch {
                                            if (vm.providerManager.multimodal.hasTts) {
                                                runCatching {
                                                    val audio = vm.providerManager.multimodal.tts(text)
                                                    if (audio.isEmpty()) {
                                                        Toast.makeText(context, "TTS 未返回音频", Toast.LENGTH_SHORT).show()
                                                        return@runCatching
                                                    }
                                                    val wavFile = File(context.cacheDir, "tts_${m.id}.wav")
                                                    wavFile.writeBytes(audio)
                                                    mediaTtsPlayer?.let { runCatching { it.stop(); it.release() } }
                                                    mediaTtsPlayer = MediaPlayer().apply {
                                                        setDataSource(wavFile.absolutePath)
                                                        prepare()
                                                        setOnCompletionListener {
                                                            release()
                                                            mediaTtsPlayer = null
                                                            speakingId = null
                                                        }
                                                        start()
                                                    }
                                                    speakingId = m.id
                                                }.onFailure {
                                                    runCatching {
                                                        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
                                                        speakingId = m.id
                                                    }
                                                }
                                            } else {
                                                runCatching {
                                                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ai_speak")
                                                    speakingId = m.id
                                                }
                                            }
                                        }
                                    }
                                },
                                displayContentOverride = vm.draftEdit(m.id),
                                selectionMode = selectionMode,
                                isSelected = selectedIds.contains(m.id),
                                onToggleSelect = {
                                    if (selectedIds.contains(m.id)) selectedIds = selectedIds - m.id
                                    else selectedIds = selectedIds + m.id
                                },
                                onEditUser = { newText -> vm.editUserMessage(m.id, newText) },
                                onResendUser = { newText -> vm.resendUserMessage(m.id, newText) },
                                onDeleteMessage = {
                                    vm.deleteMessage(m.id)
                                    selectedIds = selectedIds - m.id
                                },
                                onEnterMultiSelect = { enterMultiSelect() },
                            )
                        }
                    }
                }

                AnimatedVisibility(
                    visible = showFilePick,
                    enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { showFilePick = false },
                        )
                        FilePickDrawer(
                            onDismiss = { showFilePick = false },
                            onConfirm = { paths -> paths.forEach { vm.attachFile(it) } },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = showImagePick,
                    enter = slideInVertically(animationSpec = tween(300), initialOffsetY = { it }) +
                        fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(animationSpec = tween(300), targetOffsetY = { it }) +
                        fadeOut(animationSpec = tween(200)),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.45f))
                                .clickable { showImagePick = false },
                        )
                        ImagePickDrawer(
                            onDismiss = { showImagePick = false },
                            onConfirm = { b64List -> b64List.forEach { vm.attachImage(it) } },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = showConvDrawer,
            enter = slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it }) +
                fadeIn(animationSpec = tween(200)),
            exit = slideOutHorizontally(animationSpec = tween(300), targetOffsetX = { -it }) +
                fadeOut(animationSpec = tween(200)),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f))
                    .clickable { showConvDrawer = false },
            ) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .width(280.dp)
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    ConversationDrawer(
                        vm = vm,
                        currentId = current?.id,
                        onClose = { showConvDrawer = false },
                    )
                }
            }
        }
    }

    AnimatedVisibility(
        visible = showModelPicker,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        val allConfigs = remember(showModelPicker) {
            vm.settings.loadConfigs().filter { "CHAT" in it.capabilities }
        }
        val curActive = vm.settings.activeId()
        Box(Modifier.fillMaxSize().imePadding().zIndex(10f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable { showModelPicker = false },
            )
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(animationSpec = tween(200), initialOffsetY = { it / 8 }) +
                    fadeIn(animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomEnd),
            ) {
                AppMenuPanel(
                    items = allConfigs.map { cfg ->
                        AppMenuItem(
                            label = cfg.label,
                            sub = cfg.model,
                            active = cfg.id == curActive,
                        )
                    },
                    onPick = { idx ->
                        val cfg = allConfigs[idx]
                        vm.settings.setActive(cfg.id)
                        showModelPicker = false
                        Toast.makeText(context, "$sSwitchedPrefix ${cfg.label}", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(bottom = 80.dp, end = 12.dp, start = 12.dp),
                    title = sSwitchModel,
                    emptyText = sNoChatModel,
                )
            }
        }
    }

    AnimatedVisibility(
        visible = showAddMenu,
        enter = fadeIn(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(150)),
    ) {
        Box(Modifier.fillMaxSize().imePadding().zIndex(11f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.15f))
                    .clickable { showAddMenu = false },
            )
            AnimatedVisibility(
                visible = true,
                enter = slideInVertically(animationSpec = tween(200), initialOffsetY = { it / 8 }) +
                    fadeIn(animationSpec = tween(150)),
                modifier = Modifier.align(Alignment.BottomStart),
            ) {
                AppMenuPanel(
                    items = listOf(
                        AppMenuItem(label = sPickFile),
                        AppMenuItem(label = sPickImage),
                        AppMenuItem(
                            label = sPermission,
                            sub = ToolCatalog.levelName(current?.permissionLevel ?: 2),
                        ),
                        AppMenuItem(label = sVoiceCall),
                        AppMenuItem(label = sVideoCall),
                    ),
                    onPick = { idx ->
                        showAddMenu = false
                        when (idx) {
                            0 -> {
                                showFilePick = !showFilePick
                                if (showFilePick) showImagePick = false
                            }
                            1 -> {
                                showImagePick = !showImagePick
                                if (showImagePick) showFilePick = false
                            }
                            2 -> showPermissionDialog = true
                            3 -> onOpenCall(false)
                            4 -> onOpenCall(true)
                        }
                    },
                    modifier = Modifier.padding(bottom = 80.dp, start = 12.dp),
                    title = sAdd,
                )
            }
        }
    }

    if (showPermissionDialog) {
        val initialLevel = current?.permissionLevel ?: 2
        var level by remember(initialLevel) { mutableStateOf(initialLevel) }

        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = {
                Text(
                    stringResource(R.string.perm_dialog_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.perm_dialog_desc),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        ToolCatalog.levelName(level),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { level = it.toInt().coerceIn(0, 3) },
                        valueRange = 0f..3f,
                        steps = 2,
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        listOf(
                            stringResource(R.string.perm_level_0),
                            stringResource(R.string.perm_level_1),
                            stringResource(R.string.perm_level_2),
                            stringResource(R.string.perm_level_3),
                        ).forEachIndexed { i, name ->
                            Text(
                                name,
                                fontSize = 10.sp,
                                color = if (i == level) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (i == level) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(10.dp))
                    Text(
                        stringResource(R.string.perm_dialog_allowed),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        permissionDescription(level),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 17.sp,
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setPermissionLevel(level)
                    showPermissionDialog = false
                    Toast.makeText(
                        context,
                        sPermissionSaved.format(ToolCatalog.levelName(level)),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text(stringResource(R.string.save), fontSize = 13.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }) {
                    Text(stringResource(R.string.cancel), fontSize = 13.sp)
                }
            },
        )
    }

    pending?.let { q -> AskUserDialog(q = q, onAnswer = { vm.answerQuestion(it) }) }
}

private fun permissionDescription(level: Int): String = when (level) {
    0 -> "读类：read_file / list_files / list_dir_tree / search_files / web_search / " +
        "fetch_url / get_current_time / read_memory / list_memory / list_scheduled_tasks / " +
        "analyze_image / analyze_audio / analyze_video / terminal_read / terminal_read_bg / terminal_list_bg\n" +
        "写类：write_file / edit_file / create_dir / save_memory / delete_memory / project_memory / " +
        "generate_image / generate_video / generate_speech\n" +
        "交互：ask_user / subagent\n" +
        "（读写档不支持 shell、终端执行、操控手机）"
    1 -> "包含「读写」档全部工具，额外允许：\n" +
        "accessibility_control（无障碍操控手机）"
    2 -> "包含「设备」档全部工具，额外允许：\n" +
        "run_shell_command / run_js / terminal_exec / terminal_wait / " +
        "delete_file / move_file / copy_file / rename_file / " +
        "create_scheduled_task / cancel_scheduled_task\n" +
        "（系统档：首次调用会弹窗确认，可「本会话始终允许」）"
    else -> "包含「系统」档全部工具，且：\n" +
        "✅ 跳过所有审批弹窗\n" +
        "✅ 跳过危险命令 / 路径白名单 / shell 频率限制\n" +
        "✅ 跳过 AI 操控前确认\n" +
        "⚠️ AI 将完全自主执行任何操作，包括删除文件、执行任意 shell 命令。请谨慎使用。"
}

private fun copyUriToCache(context: Context, uri: Uri): String? = runCatching {
    val f = File(context.cacheDir, "attach_${System.currentTimeMillis()}")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(f).use { output -> input.copyTo(output) }
    }
    f.absolutePath
}.getOrNull()

private fun uriToBase64(context: Context, uri: Uri): String? = runCatching {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}.getOrNull()

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Column(modifier.padding(36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.chat_empty_title), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.chat_empty_sub), fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text(stringResource(R.string.chat_empty_hint),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, lineHeight = 18.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AskUserDialog(q: MainViewModel.PendingQuestion, onAnswer: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var optionsExpanded by remember { mutableStateOf(false) }
    val visibleOptions = if (q.options.size > 6 && !optionsExpanded) q.options.take(6) else q.options
    val hasMore = q.options.size > 6

    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.chat_ask_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Text(q.question, fontSize = 14.sp)
                if (q.options.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    visibleOptions.forEach { opt ->
                        TextButton(onClick = { onAnswer(opt) }, modifier = Modifier.fillMaxWidth()) {
                            Text(opt, fontSize = 13.sp)
                        }
                    }
                    if (hasMore && !optionsExpanded) {
                        TextButton(
                            onClick = { optionsExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                stringResource(R.string.chat_ask_more).format(q.options.size),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.chat_ask_input), fontSize = 13.sp) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAnswer(text) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.chat_ask_reply))
            }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer("") }) { Text(stringResource(R.string.chat_ask_skip)) }
        },
    )
}