package com.ai.android.ui.chat

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ImgItem(val id: Long, val uri: Uri, val album: String, val dateTaken: Long)

/**
 * 自绘全量相册。
 *
 * ⭐ 颜色修复：使用主题色 surface（亮色=白，暗色=深），不再硬编码 #FFFFFF。
 */
@Composable
fun ImagePickDrawer(
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    fun mediaPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    var images by remember { mutableStateOf<List<ImgItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var album by remember { mutableStateOf<String?>("全部") }
    var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }

    fun loadImages(ctx: Context, sink: (List<ImgItem>) -> Unit) {
        loading = true
        scope.launch {
            val list = withContext(Dispatchers.IO) { queryAllImages(ctx) }
            sink(list)
            loading = false
        }
    }

    var permGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, mediaPerm()) == PackageManager.PERMISSION_GRANTED)
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permGranted = ok
        if (ok) loadImages(context, { images = it })
    }

    LaunchedEffect(Unit) {
        if (permGranted) loadImages(context, { images = it })
    }

    val albums = remember(images) {
        images.map { it.album }.distinct().sorted()
    }
    val visible = remember(images, tab, album) {
        when {
            tab == 1 && album != null && album != "全部" -> images.filter { it.album == album }
            else -> images
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.heightIn(max = 440.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = tab == 0,
                    onClick = { tab = 0; selected = emptySet() },
                    label = { Text("全部", fontSize = 12.sp) },
                )
                Spacer(Modifier.width(6.dp))
                FilterChip(
                    selected = tab == 1,
                    onClick = { tab = 1; selected = emptySet() },
                    label = { Text("相册", fontSize = 12.sp) },
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, "关闭")
                }
            }

            if (tab == 1) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    FilterChip(selected = album == "全部", onClick = { album = "全部"; selected = emptySet() },
                        label = { Text("全部相册", fontSize = 11.sp) })
                    Spacer(Modifier.width(6.dp))
                    albums.forEach { a ->
                        FilterChip(selected = album == a, onClick = { album = a; selected = emptySet() },
                            label = { Text(a, fontSize = 11.sp) })
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            when {
                !permGranted -> {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.PhotoLibrary, null, Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(10.dp))
                        Text("需要照片权限才能读取相册", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { requestPermission.launch(mediaPerm()) }) {
                            Text("授予照片权限", fontSize = 13.sp)
                        }
                    }
                }
                loading -> {
                    Row(Modifier.fillMaxWidth().padding(32.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("正在读取相册…", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                visible.isEmpty() -> {
                    Text("（相册为空或无权限读取）", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp))
                }
                else -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(visible, key = { it.id }) { item ->
                            MediaGridItem(
                                item = item,
                                selected = selected.contains(item.id),
                                onToggle = {
                                    selected = if (selected.contains(item.id)) selected - item.id
                                    else selected + item.id
                                },
                            )
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 14.sp)
                }
                TextButton(
                    onClick = {
                        val chosen = visible.filter { it.id in selected }
                        scope.launch {
                            val b64List = withContext(Dispatchers.IO) {
                                chosen.mapNotNull { item ->
                                    runCatching {
                                        context.contentResolver.openInputStream(item.uri)?.use { it.readBytes() }
                                            ?.let { bytes ->
                                                android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                                            }
                                    }.getOrNull()
                                }
                            }
                            if (b64List.isNotEmpty()) onConfirm(b64List)
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f),
                    enabled = selected.isNotEmpty(),
                ) {
                    Text("确认（已选 ${selected.size}）", fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun MediaGridItem(
    item: ImgItem,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .clickable { onToggle() },
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp)
            }
        }
    }
}

private fun queryAllImages(ctx: Context): List<ImgItem> {
    val out = mutableListOf<ImgItem>()
    val proj = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
    )
    runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            proj, null, null,
            MediaStore.Images.Media.DATE_TAKEN + " DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                out.add(ImgItem(id, uri, c.getString(bucketCol).orEmpty(), c.getLong(dateCol)))
            }
        }
    }
    return out
}