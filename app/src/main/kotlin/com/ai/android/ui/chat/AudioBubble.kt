package com.ai.android.ui.chat

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ⭐ 全局音频播放器（用于语音气泡，单一播放通道）。
 * 暴露 progress（0f~1f）驱动竖条填充。
 */
object AudioPlayerHolder {
    private val _playingPath = MutableStateFlow("")
    val playingPath: StateFlow<String> = _playingPath

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress

    @Volatile private var current: MediaPlayer? = null
    @Volatile private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    fun toggle(path: String) {
        if (_playingPath.value == path) stop() else start(path)
    }

    private fun start(path: String) {
        stop()
        runCatching {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.prepare()
            mp.setOnCompletionListener { stop() }
            mp.start()
            current = mp
            _playingPath.value = path
            _progress.value = 0f

            // 启动进度轮询（每 100ms 更新一次）
            progressJob = scope.launch {
                while (true) {
                    val c = current ?: break
                    val dur = runCatching { c.duration }.getOrDefault(0)
                    val pos = runCatching { c.currentPosition }.getOrDefault(0)
                    if (dur > 0) {
                        _progress.value = (pos.toFloat() / dur).coerceIn(0f, 1f)
                    }
                    if (!c.isPlaying && _playingPath.value.isNotEmpty()) {
                        _progress.value = 0f
                        break
                    }
                    delay(100)
                }
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        current?.let { runCatching { it.stop(); it.release() } }
        current = null
        _playingPath.value = ""
        _progress.value = 0f
    }
}

/**
 * ⭐ 语音气泡（类微信发语音）：
 *  - 左：播放/暂停按钮
 *  - 中：竖条波形，从左到右按播放进度依次填充（高度固定，颜色变化）
 *  - 右：时长文本
 */
@Composable
fun AudioBubble(
    path: String,
    durationMs: Int,
    modifier: Modifier = Modifier,
) {
    val playingPath by AudioPlayerHolder.playingPath.collectAsState()
    val progress by AudioPlayerHolder.progress.collectAsState()
    val playing = playingPath == path

    val seconds = if (durationMs > 0) durationMs / 1000.0 else 3.0
    val widthDp = (120 + (seconds * 12)).coerceIn(120.0, 260.0).dp

    val barHeights = listOf(8, 14, 20, 10, 16, 12, 18, 6, 14, 20, 8, 16)
    val barCount = barHeights.size

    Surface(
        modifier = modifier.width(widthDp),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { AudioPlayerHolder.toggle(path) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "暂停" else "播放",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))

            Row(
                Modifier.weight(1f).height(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                barHeights.forEachIndexed { i, h ->
                    val barCenter = (i + 0.5f) / barCount
                    val filled = playing && progress >= barCenter
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(h.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                when {
                                    filled -> MaterialTheme.colorScheme.primary
                                    playing -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                                }
                            ),
                    )
                }
            }

            Spacer(Modifier.width(10.dp))
            Text(
                formatDuration(durationMs),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatDuration(ms: Int): String {
    if (ms <= 0) return "--\""
    val s = ms / 1000.0
    return if (s < 10) String.format("%.1f\"", s)
    else String.format("%.0f\"", s)
}