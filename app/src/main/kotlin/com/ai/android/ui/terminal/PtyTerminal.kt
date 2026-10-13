package com.ai.android.ui.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.android.MainViewModel
import com.ai.android.agent.tools.PtySessionManager
import com.ai.android.agent.tools.PtyTerminalSession
import com.ai.android.storage.SessionMeta
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PtyMultiTerminalScreen(
    vm: MainViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val convId = vm.current.value?.id.orEmpty()
    val scope = rememberCoroutineScope()

    var sessions by remember { mutableStateOf<List<SessionMeta>>(emptyList()) }
    var activeSessionId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<SessionMeta?>(null) }

    // 首次加载：拉取会话列表，若无会话自动创建"共享(AI)"
    LaunchedEffect(convId) {
        if (convId.isBlank()) return@LaunchedEffect
        val list = runCatching { PtySessionManager.list(context, convId) }.getOrDefault(emptyList())
        if (list.isEmpty()) {
            val id = runCatching { PtySessionManager.create(context, convId, "共享(AI)") }.getOrNull()
            sessions = runCatching { PtySessionManager.list(context, convId) }.getOrDefault(emptyList())
            activeSessionId = id ?: sessions.firstOrNull()?.id
        } else {
            sessions = list
            activeSessionId = list.first().id
        }
    }

    fun refreshSessions() {
        scope.launch {
            sessions = runCatching { PtySessionManager.list(context, convId) }.getOrDefault(emptyList())
            if (activeSessionId !in sessions.map { it.id }) {
                activeSessionId = sessions.firstOrNull()?.id
            }
        }
    }

    fun addTab() {
        scope.launch {
            val id = runCatching { PtySessionManager.create(context, convId, null) }.getOrNull()
            if (id != null) activeSessionId = id
            refreshSessions()
        }
    }

    fun closeTab(sessionId: String) {
        scope.launch {
            runCatching { PtySessionManager.close(context, sessionId) }
            refreshSessions()
        }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0C1014))) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .windowInsetsPadding(WindowInsets.statusBars),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color(0xFF7FD4A8))
            }
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                sessions.forEach { meta ->
                    val active = meta.id == activeSessionId
                    Row(
                        Modifier
                            .background(
                                if (active) Color(0xFF1E2A22) else Color(0xFF14181C),
                                RoundedCornerShape(6.dp),
                            )
                            .clickable {
                                activeSessionId = meta.id
                                scope.launch { PtySessionManager.touch(context, meta.id) }
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            meta.title.take(10),
                            fontSize = 12.sp,
                            color = if (active) Color(0xFF7FD4A8) else Color(0xFF8AA89C),
                        )
                        if (sessions.size > 1) {
                            Text(
                                "✕",
                                fontSize = 11.sp,
                                color = Color(0xFFE08080),
                                modifier = Modifier.clickable { closeTab(meta.id) },
                            )
                        }
                    }
                }
            }
            IconButton(onClick = { addTab() }, enabled = sessions.size < 8) {
                Text("+", fontSize = 20.sp, color = Color(0xFF7FD4A8))
            }
        }

        val activeMeta = sessions.firstOrNull { it.id == activeSessionId }
        if (activeMeta != null) {
            PtyTerminalBody(
                sessionId = activeMeta.id,
                context = context,
                onRequestRename = { renaming = activeMeta },
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("没有活动会话", color = Color(0xFF8AA89C), fontSize = 13.sp)
            }
        }
    }

    renaming?.let { meta ->
        var text by remember(meta.id) { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { PtySessionManager.rename(context, meta.id, text.trim().ifBlank { meta.title }) }
                        refreshSessions()
                    }
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun PtyTerminalBody(
    sessionId: String,
    context: Context,
    onRequestRename: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var pty by remember(sessionId) { mutableStateOf<PtyTerminalSession?>(null) }
    var attached by remember(sessionId) { mutableStateOf<TerminalSession?>(null) }
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    val termClient = remember(sessionId) { TermViewClient(null, baseTextSize = 14) }

    LaunchedEffect(sessionId) {
        pty = runCatching { PtySessionManager.get(context, sessionId) }.getOrNull()
        pty?.ensureStarted()
        attached = pty?.uiSession()
    }
    LaunchedEffect(pty) {
        pty?.terminalSessionFlow?.collect { s -> if (s != null) attached = s }
    }
    LaunchedEffect(ctrlActive, altActive) {
        termClient.ctrlDown = ctrlActive
        termClient.altDown = altActive
    }

    val p = pty
    if (p == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("会话不可用", color = Color(0xFFE08080), fontSize = 13.sp)
        }
        return
    }

    fun triggerSelect() {
        val tv = termClient.getView() ?: return
        if (tryReflectSelecting(tv, true)) return
        simulateLongPress(tv)
        Toast.makeText(context, "已进入选择模式，拖动选择文字后点「复制」", Toast.LENGTH_SHORT).show()
    }

    fun copySelection() {
        val tv = termClient.getView() ?: return
        val text = runCatching { tv.selectedText }.getOrNull()
        if (text.isNullOrEmpty()) {
            Toast.makeText(context, "未选中任何文字", Toast.LENGTH_SHORT).show()
            return
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("term", text))
        Toast.makeText(context, "已复制 ${text.length} 字符", Toast.LENGTH_SHORT).show()
        runCatching { tryReflectSelecting(tv, false) }
    }

    fun pasteClipboard() {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, "剪贴板为空", Toast.LENGTH_SHORT).show()
            return
        }
        scope.launch { p.writeRaw(text) }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        AndroidView(
            factory = { ctx ->
                val tv = TerminalView(ctx, null)
                tv.isFocusable = true
                tv.isFocusableInTouchMode = true
                termClient.setView(tv)
                tv.setTerminalViewClient(termClient)
                tv.setBackgroundColor(0xFF101418.toInt())
                tv.setTextSize(14)
                tv.setTypeface(Typeface.MONOSPACE)
                attached?.let { tv.attachSession(it) }
                tv.requestFocus()
                tv
            },
            update = { tv ->
                val s = attached
                if (s != null && tv.mTermSession != s) tv.attachSession(s)
                p.onScreenUpdate = { tv.onScreenUpdated() }
            },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        TermKeyBar(
            onKey = { k ->
                scope.launch { p.sendKeySeq(k, ctrl = ctrlActive, alt = altActive, shift = false) }
            },
            onToggleCtrl = { ctrlActive = !ctrlActive },
            onToggleAlt = { altActive = !altActive },
            ctrlActive = ctrlActive,
            altActive = altActive,
            onClearScreen = {
                scope.launch { p.sendKeySeq("ctrl-l", ctrl = true, alt = false, shift = false) }
            },
            canRestart = p.stateFlow.collectAsState().value != PtyTerminalSession.State.RUNNING,
            onRestart = {
                scope.launch {
                    p.restart()
                    p.ensureStarted()
                    attached = p.uiSession()
                }
            },
            onSelect = { triggerSelect() },
            onCopy = { copySelection() },
            onPaste = { pasteClipboard() },
        )
    }
}

private fun tryReflectSelecting(tv: TerminalView, on: Boolean): Boolean {
    val candidatesWithBool = listOf("setSelectingText", "setIsSelectingText")
    for (name in candidatesWithBool) {
        val ok = runCatching {
            val m = tv.javaClass.getDeclaredMethod(name, Boolean::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(tv, on)
            true
        }.getOrDefault(false)
        if (ok) return true
    }
    if (on) {
        val ok = runCatching {
            val m = tv.javaClass.getDeclaredMethod("toggleSelectingText")
            m.isAccessible = true
            m.invoke(tv)
            true
        }.getOrDefault(false)
        if (ok) return true
    }
    return false
}

private fun simulateLongPress(tv: TerminalView) {
    val x = tv.width / 2f
    val y = tv.height / 2f
    if (x <= 0f || y <= 0f) return
    val downTime = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    tv.dispatchTouchEvent(down)
    Handler(Looper.getMainLooper()).postDelayed({
        val up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
        tv.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }, 620L)
}

@Composable
private fun TermKeyBar(
    onKey: (String) -> Unit,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onClearScreen: () -> Unit,
    canRestart: Boolean,
    onRestart: () -> Unit,
    onSelect: () -> Unit,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
) {
    @Composable
    fun keyRow(keys: List<Pair<String, String>>) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            keys.forEach { (label, key) ->
                KeyText(
                    label = label,
                    active = (key == "ctrl" && ctrlActive) || (key == "alt" && altActive),
                ) {
                    when (key) {
                        "ctrl" -> onToggleCtrl()
                        "alt" -> onToggleAlt()
                        "clear" -> onClearScreen()
                        "restart" -> onRestart()
                        else -> onKey(key)
                    }
                }
            }
        }
    }

    Column(Modifier.fillMaxWidth().background(Color(0xFF0C1014))) {
        keyRow(listOf(
            "ESC" to "esc", "/" to "/", "-" to "-",
            "HOME" to "home", "↑" to "up", "END" to "end", "PGUP" to "pgup",
        ))
        keyRow(listOf(
            "Tab" to "tab", "CTRL" to "ctrl", "ALT" to "alt",
            "←" to "left", "↓" to "down", "→" to "right", "PGDN" to "pgdn",
        ))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            KeyText("选择", false) { onSelect() }
            KeyText("复制", false) { onCopy() }
            KeyText("粘贴", false) { onPaste() }
            KeyText("清屏", false) { onClearScreen() }
            KeyText("重启", canRestart) { onRestart() }
            if (ctrlActive) KeyText("Ctrl", true) { onToggleCtrl() }
            if (altActive) KeyText("Alt", true) { onToggleAlt() }
        }
    }
}

@Composable
private fun KeyText(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        color = if (active) Color(0xFF7FD4A8) else Color(0xFF8AA89C),
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private class TermViewClient(
    view: TerminalView?,
    private val baseTextSize: Int,
) : TerminalViewClient {
    private var viewRef: WeakReference<TerminalView> = WeakReference(view)

    @Volatile var ctrlDown: Boolean = false
    @Volatile var altDown: Boolean = false

    fun setView(view: TerminalView) { viewRef = WeakReference(view) }
    fun getView(): TerminalView? = viewRef.get()

    override fun onScale(scale: Float): Float {
        val tv = viewRef.get()
        if (tv != null && scale > 0f) {
            val newSize = (baseTextSize * scale).toInt().coerceIn(8, 40)
            runCatching { tv.setTextSize(newSize) }
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val tv = viewRef.get() ?: return
        tv.requestFocus()
        tv.postDelayed({
            val imm = tv.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(tv, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun shouldBackButtonBeMappedToEscape() = true
    override fun shouldEnforceCharBasedInput() = true
    override fun shouldUseCtrlSpaceWorkaround() = false
    override fun isTerminalViewSelected() = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, s: TerminalSession) = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent) = false
    override fun readControlKey() = ctrlDown
    override fun readAltKey() = altDown
    override fun readShiftKey() = false
    override fun readFnKey() = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, s: TerminalSession) = false
    override fun onEmulatorSet() {}
    override fun logError(tag: String, message: String) { Log.e("PtyTV", "$tag: $message") }
    override fun logWarn(tag: String, message: String) { Log.w("PtyTV", "$tag: $message") }
    override fun logInfo(tag: String, message: String) { Log.i("PtyTV", "$tag: $message") }
    override fun logDebug(tag: String, message: String) { Log.d("PtyTV", "$tag: $message") }
    override fun logVerbose(tag: String, message: String) { Log.v("PtyTV", "$tag: $message") }
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) { Log.e("PtyTV", "$tag: $message", e) }
    override fun logStackTrace(tag: String, e: Exception) { Log.e("PtyTV", "${e.message}", e) }
}