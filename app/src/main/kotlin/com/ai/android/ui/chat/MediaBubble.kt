package com.ai.android.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.io.File

/**
 * ⭐ 图片气泡：显示本地图片缩略图，点击用系统查看器打开。
 */
@Composable
fun ImageBubble(
    path: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val file = File(path)
    if (!file.exists()) return

    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        AsyncImage(
            model = file,
            contentDescription = "生成的图片",
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    runCatching {
                        val uri = Uri.fromFile(file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "image/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }
                },
            contentScale = ContentScale.Fit,
        )
    }
}

/**
 * ⭐ 视频气泡：显示视频首帧缩略图 + 播放按钮，点击用系统播放器打开。
 */
@Composable
fun VideoBubble(
    path: String,
    durationMs: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val file = File(path)
    if (!file.exists()) return

    Surface(
        modifier = modifier.widthIn(max = 260.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clickable {
                    runCatching {
                        val uri = Uri.fromFile(file)
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "video/*")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(intent)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 视频首帧（用 Coil 加载视频文件可拿到首帧）
            AsyncImage(
                model = file,
                contentDescription = "生成的视频",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // 播放图标覆盖层
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.PlayCircle,
                    contentDescription = "播放",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(36.dp),
                )
            }
            // 时长
            if (durationMs > 0) {
                Text(
                    "${durationMs / 1000}s",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .background(
                            MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}