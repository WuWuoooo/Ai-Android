package com.ai.android.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.ai.android.MainViewModel

/**
 * 终端入口：始终走 PTY 多会话屏（[PtyMultiTerminalScreen]）。
 *
 * ⭐ 3A 方案：彻底删除管道版 TerminalSessionManager / PipelineTerminalScreen，
 * 只保留 PTY 路径（com.termux.terminal.TerminalSession + proot）。
 */
@Composable
fun TerminalScreen(vm: MainViewModel, onBack: () -> Unit) {
    // 兼容旧设置项：terminalUsePty() 已不再影响分支，永远走 PTY
    remember { vm.settings.terminalUsePty() }
    PtyMultiTerminalScreen(vm = vm, onBack = onBack)
}