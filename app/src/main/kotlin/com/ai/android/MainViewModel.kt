package com.ai.android

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ai.android.agent.AgentEvent
import com.ai.android.agent.ApprovalManager
import com.ai.android.agent.ResultCache
import com.ai.android.agent.SubAgentTaskStore
import com.ai.android.agent.UserApprovalTokens
import com.ai.android.model.AgentState
import com.ai.android.model.ChatMessage
import com.ai.android.model.Conversation
import com.ai.android.model.Project
import com.ai.android.model.SessionStats
import com.ai.android.model.TokenEntry
import com.ai.android.model.TokenExportReport
import com.ai.android.model.TokenTotals
import com.ai.android.service.FloatingService
import com.ai.android.storage.ConversationEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class MainViewModel(private val app: MainApp) : ViewModel() {

    private val TAG = "MainViewModel"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val maxPayloadChars = 1_000_000
    private val payloadChunkSize = 400_000

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _current = MutableStateFlow<Conversation?>(null)
    val current: StateFlow<Conversation?> = _current

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState

    private val _sessionStats = MutableStateFlow(SessionStats())
    val sessionStats: StateFlow<SessionStats> = _sessionStats

    private val _attachedFiles = MutableStateFlow<List<String>>(emptyList())
    val attachedFiles: StateFlow<List<String>> = _attachedFiles

    private val _attachedImages = MutableStateFlow<List<String>>(emptyList())
    val attachedImages: StateFlow<List<String>> = _attachedImages

    private val _memoryList = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val memoryList: StateFlow<List<Pair<String, String>>> = _memoryList

    private val _projectMemoryList = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val projectMemoryList: StateFlow<List<Pair<String, String>>> = _projectMemoryList

    private val _quotedMessage = MutableStateFlow<ChatMessage?>(null)
    val quotedMessage: StateFlow<ChatMessage?> = _quotedMessage

    private val _lastGenerationIncomplete = MutableStateFlow(false)
    val lastGenerationIncomplete: StateFlow<Boolean> = _lastGenerationIncomplete

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError

    data class PendingQuestion(val question: String, val options: List<String>)

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion

    private var pendingAnswer: CompletableDeferred<String>? = null
    private var agentJob: Job? = null

    private val subAgentTokensAccum = AtomicInteger(0)

    private val titleSummarized = java.util.Collections.synchronizedSet(
        java.util.HashSet<String>()
    )

    val settings get() = app.settings
    val providerManager get() = app.providerManager
    val projects get() = app.projectStore.projects

    init {
        app.toolRegistry.askUserHandler = { question, options -> askUser(question, options) }
        app.toolRegistry.convIdProvider = { current.value?.id.orEmpty() }
        app.toolRegistry.projectIdProvider = { current.value?.projectId.orEmpty() }
        app.toolRegistry.convPermissionLevelProvider = { current.value?.permissionLevel ?: 2 }
        app.toolRegistry.onSubAgentToken = { p, c, _ ->
            subAgentTokensAccum.addAndGet(p + c)
        }
        app.toolRegistry.onSubAgentTaskCompleted = { task ->
            viewModelScope.launch { handleSubAgentCompletion(task) }
        }
        app.agentStopCallback = { stop() }
        AgentBridge.setViewModel(this)
        val bootstrap = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = listOf(bootstrap)
        _current.value = bootstrap
        _sessionStats.value = bootstrap.lastSessionStats

        viewModelScope.launch {
            runCatching { app.projectStore.init(); refreshMemoryList() }
            try {
                val metas = withContext(Dispatchers.IO) { app.database.conversationDao().allMeta() }
                if (metas.isNotEmpty()) {
                    _conversations.value = metas.map {
                        Conversation(id = it.id, title = it.title,
                            createdAt = it.createdAt, updatedAt = it.updatedAt, projectId = it.projectId)
                    }
                    _current.value = _conversations.value.firstOrNull()
                    _sessionStats.value = _current.value?.lastSessionStats ?: SessionStats()
                    bump()
                }
                if (metas.isNotEmpty()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        var filled = 0
                        for (meta in metas) {
                            val full = runCatching { loadOneConversation(meta.id) }.getOrNull() ?: continue
                            filled++
                            _conversations.value = _conversations.value.map { if (it.id == full.id) full else it }
                            if (_current.value?.id == full.id) {
                                _current.value = full
                                _sessionStats.value = full.lastSessionStats
                            }
                        }
                        Log.d(TAG, "filled $filled/${metas.size} conversation payloads in background")
                        mergeEmptyConversations()
                        bump()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "loadConversations 失败", e)
                _loadError.value = "加载历史对话失败：${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private fun handleSubAgentCompletion(task: SubAgentTaskStore.Task) {
        val conv = _conversations.value.firstOrNull { it.id == task.convId } ?: return
        val statusText = when (task.status) {
            SubAgentTaskStore.Status.DONE -> "✅ 后台子代理已完成"
            SubAgentTaskStore.Status.ERROR -> "❌ 后台子代理失败"
            else -> "🔄 后台子代理状态更新"
        }
        val body = if (task.status == SubAgentTaskStore.Status.DONE) {
            task.finalText.take(800).ifBlank { "（无输出）" }
        } else {
            task.error.take(400).ifBlank { "（无错误信息）" }
        }
        val text = buildString {
            append("【后台子代理通知】\n")
            append(statusText).append(" · ").append(task.taskId).append('\n')
            append("任务：").append(task.description).append("\n\n")
            append(body)
        }
        val msg = ChatMessage(role = ChatMessage.Role.ASSISTANT, content = text)
        conv.addMessage(msg)
        _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
        if (_current.value?.id == conv.id) _current.value = conv
        persist(conv)
        bump()
    }

    fun mergeEmptyConversations() {
        val empties = _conversations.value.filter { it.messages.isEmpty() }
        if (empties.size <= 1) return
        val keep = if (_current.value in empties) _current.value!! else empties.first()
        val toRemove = empties.filter { it.id != keep.id }
        _conversations.value = _conversations.value.filterNot { it.id in toRemove.map { r -> r.id } }
        toRemove.forEach { c ->
            app.appScope.launch { runCatching { app.database.conversationDao().delete(c.id) } }
        }
    }

    // ==================== 持久化 ====================

    private suspend fun loadOneConversation(id: String): Conversation? {
        val dao = app.database.conversationDao()
        val len = dao.payloadLength(id) ?: return null
        val payload: String = if (len <= payloadChunkSize) {
            dao.payload(id) ?: return null
        } else {
            val sb = StringBuilder(len)
            var start = 1
            while (start <= len) {
                val part = dao.payloadChunk(id, start, payloadChunkSize) ?: break
                sb.append(part)
                if (part.length < payloadChunkSize) break
                start += payloadChunkSize
            }
            sb.toString()
        }
        return json.decodeFromString(Conversation.serializer(), payload)
    }

    private fun persist(conv: Conversation) {
        app.appScope.launch {
            runCatching {
                val safe = shrinkForStorage(conv)
                app.database.conversationDao().upsert(
                    ConversationEntity(
                        id = safe.id, title = safe.title,
                        createdAt = safe.createdAt, updatedAt = safe.updatedAt,
                        payload = json.encodeToString(Conversation.serializer(), safe),
                        projectId = safe.projectId,
                    )
                )
            }.onFailure { Log.e(TAG, "persist failed", it) }
        }
    }

    private fun shrinkForStorage(conv: Conversation): Conversation {
        var msgs = conv.messages.map { m ->
            if (m.images.sumOf { it.length } > 500_000) m.copy(images = emptyList()) else m
        }.toMutableList()

        var encoded = json.encodeToString(Conversation.serializer(), conv.copy(messages = msgs))
        if (encoded.length <= maxPayloadChars) return conv.copy(messages = msgs)

        for (i in msgs.indices) {
            if (msgs[i].images.isEmpty()) continue
            msgs[i] = msgs[i].copy(images = emptyList())
            encoded = json.encodeToString(Conversation.serializer(), conv.copy(messages = msgs))
            if (encoded.length <= maxPayloadChars) break
        }
        return conv.copy(messages = msgs)
    }

    // ==================== 会话管理 ====================

    fun newConversation() {
        val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
        _conversations.value = _conversations.value + c
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        _sessionStats.value = SessionStats()
        subAgentTokensAccum.set(0)
        clearDrafts()
        persist(c); bump()
    }

    fun switchConversation(id: String) {
        val c = _conversations.value.firstOrNull { it.id == id } ?: return
        _current.value?.id?.let {
            ApprovalManager.clearSession(it)
            UserApprovalTokens.clear(it)
        }
        _current.value = c
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        _sessionStats.value = c.lastSessionStats
        subAgentTokensAccum.set(0)
        clearDrafts()
        bump()
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filterNot { it.id == id }
        if (_current.value?.id == id) {
            _current.value = _conversations.value.firstOrNull() ?: run {
                val c = Conversation(id = UUID.randomUUID().toString(), title = "新会话")
                _conversations.value = listOf(c); persist(c); c
            }
            _sessionStats.value = _current.value?.lastSessionStats ?: SessionStats()
            subAgentTokensAccum.set(0)
        }
        app.appScope.launch { runCatching { app.database.conversationDao().delete(id) } }
        bump()
    }

    // ⭐ 修复：rename 用 copy 生成新对象，StateFlow 才会发射
    fun renameConversation(id: String, newTitle: String) {
        val now = System.currentTimeMillis()
        val old = _conversations.value
        val updated = old.map {
            if (it.id == id) it.copy(title = newTitle, updatedAt = now) else it
        }
        _conversations.value = updated
        updated.firstOrNull { it.id == id }?.let { conv ->
            if (_current.value?.id == id) _current.value = conv
            persist(conv)
        }
        bump()
    }

    // ⭐ 修复：togglePin 用 copy 生成新对象，StateFlow 才会发射
    fun togglePin(id: String) {
        val old = _conversations.value
        val updated = old.map {
            if (it.id == id) it.copy(pinned = !it.pinned) else it
        }
        _conversations.value = updated
        updated.firstOrNull { it.id == id }?.let { conv ->
            if (_current.value?.id == id) _current.value = conv
            persist(conv)
        }
        bump()
    }

    // ⭐ 修复：setPermissionLevel 用 copy
    fun setPermissionLevel(level: Int) {
        val cur = _current.value ?: return
        val v = level.coerceIn(0, 3)
        if (cur.permissionLevel == v) return
        val old = _conversations.value
        val updated = old.map {
            if (it.id == cur.id) it.copy(permissionLevel = v) else it
        }
        _conversations.value = updated
        updated.firstOrNull { it.id == cur.id }?.let { conv ->
            _current.value = conv
            persist(conv)
        }
        bump()
    }

    // ==================== 项目 ====================

    fun createProject(name: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.upsert(Project(id = UUID.randomUUID().toString().take(8), name = name))
        } }
    }

    fun renameProject(id: String, newName: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.get(id)?.let { app.projectStore.upsert(it.copy(name = newName)) }
        } }
    }

    fun deleteProject(id: String) {
        viewModelScope.launch { runCatching {
            app.projectStore.delete(id)
            val old = _conversations.value
            val updated = old.map {
                if (it.projectId == id) it.copy(projectId = "") else it
            }
            _conversations.value = updated
            // 持久化有变化的
            old.zip(updated).forEach { (o, n) ->
                if (o != n) persist(n)
            }
            if (_current.value?.projectId == id) {
                _current.value = updated.firstOrNull { it.id == _current.value?.id }
            }
            bump()
        } }
    }

    // ⭐ 修复：moveConversationToProject 用 copy
    fun moveConversationToProject(convId: String, projectId: String) {
        val old = _conversations.value
        val updated = old.map {
            if (it.id == convId) it.copy(projectId = projectId) else it
        }
        _conversations.value = updated
        updated.firstOrNull { it.id == convId }?.let { conv ->
            if (_current.value?.id == convId) _current.value = conv
            persist(conv)
        }
        bump()
    }

    // ==================== 对话导出 / 导入 ====================

    fun exportConversationJson(convId: String): String? {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return null
        return json.encodeToString(Conversation.serializer(), conv)
    }

    fun parseConversationFromImport(text: String): Conversation? {
        val trimmed = text.trim()
        runCatching { json.decodeFromString(Conversation.serializer(), trimmed) }
            .onSuccess { return it }
        return runCatching {
            val root = json.parseToJsonElement(trimmed)
            val element = if (root is kotlinx.serialization.json.JsonObject &&
                root["data"] != null) root["data"]!! else root
            json.decodeFromJsonElement(Conversation.serializer(), element)
        }.getOrNull()
    }

    fun importConversationJson(text: String): Conversation? {
        val conv = parseConversationFromImport(text) ?: return null
        val newId = UUID.randomUUID().toString()
        val imported = conv.copy(
            id = newId,
            title = conv.title.ifBlank { "导入的对话" },
            messages = conv.messages.map { m -> m.copy(id = UUID.randomUUID().toString()) }.toMutableList(),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        _conversations.value = _conversations.value + imported
        _current.value = imported
        _quotedMessage.value = null
        _lastGenerationIncomplete.value = false
        _sessionStats.value = imported.lastSessionStats
        viewModelScope.launch {
            runCatching {
                app.database.conversationDao().upsert(
                    ConversationEntity(
                        id = imported.id, title = imported.title,
                        createdAt = imported.createdAt, updatedAt = imported.updatedAt,
                        payload = json.encodeToString(Conversation.serializer(), imported),
                        projectId = imported.projectId,
                    )
                )
            }.onFailure { Log.e(TAG, "import persist failed", it) }
        }
        bump()
        return imported
    }

    fun suggestedExportFileName(conv: Conversation): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        val safe = conv.title.replace(Regex("[\\/:*?\"<>\\\\]"), "_").trim().ifBlank { "对话" }
        return "对话-$safe-$stamp.json"
    }

    // ==================== Token 统计 ====================

    fun tokenTotalsForCurrent(): TokenTotals {
        val c = _current.value ?: return TokenTotals()
        return c.totalTokens()
    }

    fun tokenTotalsFor(convId: String): TokenTotals {
        val c = _conversations.value.firstOrNull { it.id == convId } ?: return TokenTotals()
        return c.totalTokens()
    }

    fun exportTokenJson(convId: String): String? {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return null
        val totals = conv.totalTokens()
        val entries = conv.messagesWithStats().map { m ->
            TokenEntry(messageId = m.id, role = m.role.name, stats = m.tokenStats!!)
        }
        val report = TokenExportReport(
            conversationId = conv.id,
            title = conv.title,
            totals = totals,
            exportedAt = System.currentTimeMillis(),
            perMessage = entries,
        )
        return json.encodeToString(TokenExportReport.serializer(), report)
    }

    fun clearTokenStats(convId: String) {
        val conv = _conversations.value.firstOrNull { it.id == convId } ?: return
        for (i in conv.messages.indices) {
            val m = conv.messages[i]
            if (m.tokenStats != null) conv.messages[i] = m.copy(tokenStats = null)
        }
        _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
        if (_current.value?.id == conv.id) _current.value = conv
        persist(conv)
        bump()
        runCatching { FloatingService.setTokenText(app, "") }
    }

    // ==================== 记忆 ====================

    fun refreshMemoryList() { _memoryList.value = app.memory.list().toList() }
    fun saveMemory(key: String, content: String) { app.memory.save(key, content); refreshMemoryList() }
    fun deleteMemory(key: String) { app.memory.delete(key); refreshMemoryList() }

    fun refreshProjectMemory(projectId: String) {
        _projectMemoryList.value = if (projectId.isBlank()) emptyList()
        else app.memory.projectMemoryList(projectId).toList()
    }

    fun saveProjectMemory(projectId: String, key: String, content: String) {
        if (projectId.isBlank() || key.isBlank()) return
        app.memory.saveProjectMemory(projectId, key, content)
        refreshProjectMemory(projectId)
    }

    fun deleteProjectMemory(projectId: String, key: String) {
        if (projectId.isBlank()) return
        app.memory.deleteProjectMemory(projectId, key)
        refreshProjectMemory(projectId)
    }

    // ==================== 引用 ====================

    fun quoteMessage(msg: ChatMessage) { _quotedMessage.value = msg }
    fun clearQuote() { _quotedMessage.value = null }

    // ==================== 消息编辑 / 删除 ====================

    private val draftEdits = mutableMapOf<String, String>()

    fun draftEdit(msgId: String): String? = draftEdits[msgId]

    fun editUserMessage(msgId: String, newContent: String) {
        draftEdits[msgId] = newContent
        bump()
    }

    fun resendUserMessage(msgId: String, newContent: String) {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val idx = conv.messages.indexOfFirst { it.id == msgId }
        if (idx < 0) return
        val kept = conv.messages.subList(0, idx + 1).toMutableList()
        val edited = kept[idx].copy(content = newContent, isStreaming = false)
        kept[idx] = edited
        conv.messages.clear()
        conv.messages.addAll(kept)
        draftEdits.remove(msgId)
        agentJob?.cancel()
        persist(conv); bump()
        _lastGenerationIncomplete.value = false
        agentJob = viewModelScope.launch { runCatching { runAgent(conv) } }
    }

    fun deleteMessage(msgId: String) {
        val conv = _current.value ?: return
        conv.messages.removeAll { it.id == msgId }
        draftEdits.remove(msgId)
        persist(conv); bump()
    }

    fun deleteMessages(ids: Set<String>) {
        if (ids.isEmpty()) return
        val conv = _current.value ?: return
        conv.messages.removeAll { it.id in ids }
        ids.forEach { draftEdits.remove(it) }
        persist(conv); bump()
    }

    private fun clearDrafts() { draftEdits.clear() }

    // ==================== 重试 / 继续 ====================

    fun retryLast() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastUserIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.USER }
        if (lastUserIdx < 0) return
        val kept = conv.messages.take(lastUserIdx + 1).toMutableList()
        conv.messages.clear(); conv.messages.addAll(kept)
        bump(); persist(conv)
        _lastGenerationIncomplete.value = false
        agentJob = viewModelScope.launch { runCatching { runAgent(conv) } }
    }

    fun continueGeneration() {
        val conv = _current.value ?: return
        if (agentJob?.isActive == true) return
        val lastAssistantIdx = conv.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT }
        if (lastAssistantIdx < 0) return
        val lastAssistant = conv.messages[lastAssistantIdx]
        if (lastAssistant.content.isBlank()) return

        agentJob = viewModelScope.launch {
            runCatching {
                val continuation = app.agentCore.continueFrom(conv)
                val idx = conv.messages.indexOfFirst { it.id == lastAssistant.id }
                if (idx >= 0) {
                    val old = conv.messages[idx]
                    conv.messages[idx] = old.copy(content = old.content + continuation)
                }
                persist(conv); bump()
                _lastGenerationIncomplete.value = false
            }
        }
    }

    // ==================== 附件 ====================

    fun attachFile(path: String) { _attachedFiles.value = _attachedFiles.value + path }
    fun attachImage(b64: String) { _attachedImages.value = _attachedImages.value + b64 }
    fun removeAttachment(path: String) { _attachedFiles.value = _attachedFiles.value - path }
    fun removeImage(b64: String) { _attachedImages.value = _attachedImages.value - b64 }

    // ==================== 发送 ====================

    fun send(text: String) {
        val conv = _current.value ?: return
        val quoted = _quotedMessage.value
        if (text.isBlank() && _attachedImages.value.isEmpty() && _attachedFiles.value.isEmpty()) return
        if (agentJob?.isActive == true) return

        val filesSnapshot = _attachedFiles.value.toList()
        val imagesSnapshot = _attachedImages.value.toList()

        agentJob = viewModelScope.launch {
            try {
                val fileContents = withContext(Dispatchers.IO) {
                    buildString {
                        filesSnapshot.forEach { path ->
                            runCatching {
                                val f = File(path)
                                if (f.exists() && f.isFile) {
                                    appendLine("【附件文件: ${f.name}】")
                                    appendLine("路径: $path")
                                    appendLine("内容:")
                                    appendLine("```")
                                    val maxChars = app.settings.maxReadChars()
                                    val content = if (maxChars > 0) f.readText().take(maxChars) else f.readText()
                                    appendLine(content)
                                    if (maxChars > 0 && f.length() > maxChars) {
                                        appendLine("...（已截断，原 ${f.length()} 字符）")
                                    }
                                    appendLine("```")
                                    appendLine()
                                }
                            }.onFailure { Log.e(TAG, "read file failed", it) }
                        }
                    }
                }

                val fullText = buildString {
                    if (quoted != null) {
                        appendLine("【引用消息】")
                        appendLine(quoted.content.take(500))
                        appendLine()
                    }
                    if (text.isNotBlank()) append(text.trim())
                    if (fileContents.isNotBlank()) {
                        if (text.isNotBlank() || quoted != null) appendLine().appendLine()
                        append("--- 以下是用户附加的文件内容 ---\n")
                        append(fileContents)
                    }
                }

                if (conv.messages.none { it.role == ChatMessage.Role.USER }) {
                    conv.title = text.take(16).ifBlank { "文件分析" }
                    _conversations.value = _conversations.value.map { if (it.id == conv.id) conv else it }
                    _current.value = conv
                }

                val uiContent = text.trim().ifBlank {
                    if (quoted != null) "（引用）" else "（附件）"
                }
                val msg = ChatMessage.user(uiContent).copy(
                    images = imagesSnapshot,
                    files = filesSnapshot,
                    quotedContent = quoted?.content.orEmpty(),
                )
                conv.addMessage(msg)
                _attachedFiles.value = emptyList()
                _attachedImages.value = emptyList()
                _quotedMessage.value = null

                val idx = conv.messages.indexOfFirst { it.id == msg.id }
                if (idx >= 0) conv.messages[idx] = msg.copy(content = fullText)
                persist(conv); bump()

                runAgent(conv)
            } catch (e: Throwable) {
                Log.e(TAG, "send failed", e)
                conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = "发送失败: ${e.message}"))
                bump()
            }
        }
    }

    // ==================== Agent ====================

    private suspend fun runAgent(conv: Conversation) {
        if (app.settings.floatingEnabled()) {
            runCatching {
                FloatingService.showWork(app, AgentState(status = AgentState.Status.THINKING, progressText = "思考中…"))
            }
        }
        runCatching { app.toolRegistry.aiControlGuard.beginSession() }

        var localStats = conv.lastSessionStats.copy(tokensPerSec = 0.0)
        _sessionStats.value = localStats

        val sessionStartMs = System.currentTimeMillis()
        var sessionCompletion = 0

        fun pushStats() {
            if (_current.value?.id == conv.id) {
                _sessionStats.value = localStats
            }
        }

        var placeholderId: String? = null
        fun ensurePlaceholder(): String {
            placeholderId?.let { return it }
            val np = ChatMessage(role = ChatMessage.Role.ASSISTANT, isStreaming = true)
            conv.messages.add(np); placeholderId = np.id; bump()
            return np.id
        }
        fun dropPlaceholder() {
            placeholderId?.let { pid -> conv.messages.removeAll { it.id == pid } }
            placeholderId = null
        }

        val content = StringBuilder()
        val reasoning = StringBuilder()
        var incomplete = false

        try {
            app.agentCore.run(conv).collect { ev ->
                when (ev) {
                    is AgentEvent.RoundStart -> {
                        localStats = localStats.copy(round = localStats.round + 1)
                        pushStats()
                    }
                    is AgentEvent.Stats -> {
                        val subAgentExtra = subAgentTokensAccum.getAndSet(0)
                        sessionCompletion += ev.stats.completionTokens
                        localStats = localStats.copy(
                            totalTokens = localStats.totalTokens +
                                ev.stats.promptTokens + ev.stats.completionTokens + subAgentExtra,
                        )
                        val elapsedSec = ((System.currentTimeMillis() - sessionStartMs) / 1000.0)
                            .coerceAtLeast(0.1)
                        val tps = sessionCompletion / elapsedSec
                        val convTotals = conv.totalTokens()
                        val rate = if (convTotals.promptTokens > 0)
                            convTotals.cacheHitTokens * 100.0 / convTotals.promptTokens else 0.0
                        localStats = localStats.copy(tokensPerSec = tps, cacheHitRate = rate)
                        pushStats()

                        val lastId = conv.messages.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id ?: ""
                        updateMessage(conv, lastId) { it.copy(tokenStats = ev.stats) }
                        bump()
                        val totalConv = conv.totalTokens()
                        runCatching {
                            FloatingService.setTokenText(
                                app,
                                "Token: 输入 ${totalConv.promptTokens} / 输出 ${totalConv.completionTokens} / " +
                                    "缓存 ${totalConv.cacheHitTokens} / 共 ${totalConv.totalTokens}",
                            )
                        }
                        runCatching {
                            app.jsPluginRuntime.pushEventAll(
                                "{\"type\":\"OnTokenUpdate\",\"payload\":{\"prompt\":${totalConv.promptTokens}," +
                                    "\"completion\":${totalConv.completionTokens}," +
                                    "\"cache\":${totalConv.cacheHitTokens}," +
                                    "\"total\":${totalConv.totalTokens}}}"
                            )
                        }
                    }
                    is AgentEvent.ToolStart -> {
                        localStats = localStats.copy(steps = localStats.steps + 1)
                        pushStats()
                        persist(conv); bump()
                        runCatching {
                            val argsJson = kotlinx.serialization.json.JsonPrimitive(ev.call.arguments.take(200)).toString()
                            val toolJson = kotlinx.serialization.json.JsonPrimitive(ev.call.name).toString()
                            app.jsPluginRuntime.pushEventAll(
                                "{\"type\":\"OnToolCall\",\"payload\":{\"tool\":$toolJson,\"args\":$argsJson}}"
                            )
                        }
                    }
                    is AgentEvent.ReasoningDelta -> {
                        reasoning.append(ev.text)
                        updateMessage(conv, ensurePlaceholder()) { it.copy(reasoning = reasoning.toString()) }
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.pushStreaming(app, "reasoning", ev.text) }
                        }
                        bump()
                    }
                    is AgentEvent.ContentDelta -> {
                        content.append(ev.text)
                        updateMessage(conv, ensurePlaceholder()) { it.copy(content = content.toString()) }
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.pushStreaming(app, "content", ev.text) }
                        }
                        bump()
                    }
                    is AgentEvent.MessageDone -> {
                        dropPlaceholder()
                        content.clear(); reasoning.clear()
                        persist(conv)
                        if (app.settings.autoTitleSummary() &&
                            !titleSummarized.contains(conv.id) &&
                            conv.messages.count { it.role == ChatMessage.Role.USER } == 1
                        ) {
                            val firstUser = conv.messages.firstOrNull { it.role == ChatMessage.Role.USER }?.content.orEmpty()
                            val aiText = ev.message.content
                            if (firstUser.isNotBlank()) {
                                titleSummarized.add(conv.id)
                                viewModelScope.launch {
                                    runCatching {
                                        val newTitle = app.agentCore.summarizeTitle(firstUser, aiText)
                                        conv.title = newTitle
                                        conv.updatedAt = System.currentTimeMillis()
                                        _conversations.value = _conversations.value.map {
                                            if (it.id == conv.id) conv.copy(title = newTitle) else it
                                        }
                                        if (_current.value?.id == conv.id) _current.value = conv
                                        persist(conv); bump()
                                    }
                                }
                            }
                        }
                        bump()
                    }
                    is AgentEvent.ToolEnd -> {
                        if (ev.result.name == "generate_speech" && ev.result.success) {
                            val path = extractSpeechPath(ev.result.output)
                            val duration = extractSpeechDuration(ev.result.output)
                            if (path.isNotBlank()) {
                                conv.messages.add(ChatMessage(
                                    role = ChatMessage.Role.ASSISTANT,
                                    audioPath = path,
                                    audioDurationMs = duration,
                                ))
                            }
                        }
                        if (ev.result.name == "generate_image" && ev.result.success) {
                            val path = extractImagePath(ev.result.output)
                            if (path.isNotBlank()) {
                                conv.messages.add(ChatMessage(
                                    role = ChatMessage.Role.ASSISTANT,
                                    imagePath = path,
                                ))
                            }
                        }
                        if (ev.result.name == "generate_video" && ev.result.success) {
                            val path = extractVideoPath(ev.result.output)
                            val durationSec = extractVideoDuration(ev.result.output)
                            if (path.isNotBlank()) {
                                conv.messages.add(ChatMessage(
                                    role = ChatMessage.Role.ASSISTANT,
                                    videoPath = path,
                                    videoDurationMs = durationSec * 1000,
                                ))
                            }
                        }
                        persist(conv); bump()
                    }
                    is AgentEvent.ToolMessage -> {
                        persist(conv); bump()
                    }
                    is AgentEvent.State -> {
                        _agentState.value = ev.state
                        if (app.settings.floatingEnabled()) {
                            runCatching { FloatingService.update(ev.state) }
                        }
                        runCatching {
                            app.jsPluginRuntime.pushEventAll(
                                "{\"type\":\"OnAgentState\",\"payload\":{\"state\":\"${ev.state.status.name}\"," +
                                    "\"tool\":\"${ev.state.currentTool}\"," +
                                    "\"progress\":\"${ev.state.progressText}\"}}"
                            )
                        }
                    }
                    is AgentEvent.Error -> {
                        dropPlaceholder()
                        incomplete = true
                        conv.messages.add(ChatMessage(role = ChatMessage.Role.ASSISTANT, content = "", error = ev.msg))
                        persist(conv); bump()
                    }
                    AgentEvent.Done -> Unit
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "runAgent failed", e)
            incomplete = true
        } finally {
            dropPlaceholder()
            val tail = subAgentTokensAccum.getAndSet(0)
            if (tail > 0) {
                localStats = localStats.copy(totalTokens = localStats.totalTokens + tail)
            }
            conv.lastSessionStats = localStats.copy(tokensPerSec = 0.0)
            persist(conv); bump()
            _agentState.value = AgentState()
            _lastGenerationIncomplete.value = incomplete
            if (app.settings.floatingEnabled()) {
                runCatching { FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED)) }
            }
            runCatching { app.toolRegistry.stopAiMirror() }
        }
    }

    private fun extractSpeechPath(output: String): String {
        val m = Regex("路径：\\s*(\\S+\\.wav)").find(output)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractSpeechDuration(output: String): Int {
        val m = Regex("时长：\\s*(\\d+)ms").find(output)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun extractImagePath(output: String): String {
        val m = Regex("路径：\\s*(\\S+\\.(png|jpg|jpeg|webp))").find(output)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractVideoPath(output: String): String {
        val m = Regex("路径：\\s*(\\S+\\.(mp4|mov|avi|webm))").find(output)
        return m?.groupValues?.get(1) ?: ""
    }

    private fun extractVideoDuration(output: String): Int {
        val m = Regex("时长：\\s*(\\d+)s").find(output)
        return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun stop() {
        agentJob?.cancel(); agentJob = null
        pendingAnswer?.let {
            if (!it.isCompleted) it.complete("（用户中断）")
        }
        pendingAnswer = null
        _pendingQuestion.value = null
        _current.value?.id?.let {
            ApprovalManager.clearSession(it)
            UserApprovalTokens.clear(it)
        }
        ResultCache.clear()
        subAgentTokensAccum.set(0)
        _agentState.value = AgentState()
        _lastGenerationIncomplete.value = true
        runCatching { app.toolRegistry.stopAiMirror() }
        if (app.settings.floatingEnabled()) {
            runCatching { FloatingService.finishWork(app, AgentState(status = AgentState.Status.COMPLETED)) }
        }
    }

    override fun onCleared() {
        agentJob?.cancel()
        pendingAnswer?.let { if (!it.isCompleted) it.complete("（ViewModel 销毁）") }
        pendingAnswer = null
        super.onCleared()
    }

    private suspend fun askUser(question: String, options: String): String {
        val deferred = CompletableDeferred<String>()
        pendingAnswer = deferred
        _pendingQuestion.value = PendingQuestion(
            question = question,
            options = options.split(",", "，").map { it.trim() }.filter { it.isNotEmpty() },
        )
        _agentState.value = _agentState.value.copy(status = AgentState.Status.WAITING_USER, progressText = "等待用户回答…")
        if (app.settings.floatingEnabled()) runCatching { FloatingService.update(_agentState.value) }
        val answer = deferred.await()
        _pendingQuestion.value = null
        pendingAnswer = null
        _agentState.value = _agentState.value.copy(status = AgentState.Status.THINKING, progressText = "继续执行…")
        return if (answer.isBlank()) "（用户未回答）" else answer
    }

    fun answerQuestion(text: String) {
        pendingAnswer?.complete(text); pendingAnswer = null; _pendingQuestion.value = null
    }

    private fun updateMessage(conv: Conversation, id: String, transform: (ChatMessage) -> ChatMessage) {
        if (id.isBlank()) return
        val idx = conv.messages.indexOfFirst { it.id == id }
        if (idx >= 0) conv.messages[idx] = transform(conv.messages[idx])
    }

    private fun bump() { _version.value++ }

    companion object {
        val Factory = viewModelFactory { initializer { MainViewModel(MainApp.instance) } }
    }
}