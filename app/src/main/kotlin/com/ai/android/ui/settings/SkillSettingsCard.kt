package com.ai.android.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.android.R
import com.ai.android.plugin.SkillManager
import com.ai.android.plugin.SkillManifest
import com.ai.android.ui.components.AppDialog

/**
 * Skill 管理卡片：
 *  - 导入 .zip 技能包
 *  - 列表 + 详情弹窗 + 删除
 *
 * ⭐ 只做"导入/查看/删除"，AI 加载 Skill 的逻辑放在后续版本。
 */
@Composable
fun SkillSettingsCard(context: Context) {
    var skills by remember { mutableStateOf(SkillManager.list(context)) }
    var viewing by remember { mutableStateOf<SkillManifest?>(null) }
    var deleting by remember { mutableStateOf<SkillManifest?>(null) }

    fun refresh() { skills = SkillManager.list(context) }

    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = SkillManager.importZip(context, uri)
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        refresh()
    }

    LaunchedEffect(Unit) { refresh() }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.plugin_skill_section_title),
                fontSize = 13.sp, fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.plugin_skill_desc),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = {
                    openDocument.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.plugin_skill_import), fontSize = 13.sp)
            }

            if (skills.isEmpty()) {
                Text(
                    stringResource(R.string.plugin_skill_empty),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                skills.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().clickable { viewing = s },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(
                                stringResource(R.string.plugin_detail_id, s.id) + " · " +
                                    stringResource(R.string.plugin_detail_version, s.version) +
                                    if (s.author.isNotBlank()) " · ${s.author}" else "",
                                fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (s.description.isNotBlank()) {
                                Text(
                                    s.description.take(80) + if (s.description.length > 80) "…" else "",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                        IconButton(onClick = { viewing = s }) {
                            Icon(Icons.Default.Info, stringResource(R.string.plugin_detail_btn),
                                Modifier.width(16.dp))
                        }
                        IconButton(onClick = { deleting = s }) {
                            Icon(Icons.Default.Delete, stringResource(R.string.delete),
                                Modifier.width(16.dp))
                        }
                    }
                }
            }
        }
    }

    viewing?.let { s ->
        AppDialog(
            onDismissRequest = { viewing = null },
            title = s.name,
            dismissText = stringResource(R.string.close),
            onDismiss = { viewing = null },
        ) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.plugin_detail_id, s.id),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.plugin_detail_version, s.version), fontSize = 11.sp)
                if (s.author.isNotBlank()) {
                    Text(stringResource(R.string.plugin_detail_author, s.author), fontSize = 11.sp)
                }
                if (s.tags.isNotEmpty()) {
                    Text(stringResource(R.string.plugin_detail_tags, s.tags.joinToString(", ")),
                        fontSize = 11.sp)
                }
                HorizontalDivider()
                Text(
                    s.content.ifBlank { stringResource(R.string.plugin_skill_no_content) },
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = {
                Text(
                    stringResource(R.string.plugin_skill_delete_title),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            },
            text = { Text(stringResource(R.string.plugin_skill_delete_confirm, s.name), fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    SkillManager.remove(context, s.id)
                    deleting = null
                    refresh()
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}