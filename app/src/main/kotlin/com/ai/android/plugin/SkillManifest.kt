package com.ai.android.plugin

import kotlinx.serialization.Serializable

/**
 * Skill 元信息（从 SKILL.md 的 YAML frontmatter 解析）。
 *
 * frontmatter 示例：
 *   ---
 *   name: geo-sleuth
 *   description: 图像地理定位
 *   version: 1.0.0
 *   author: someone
 *   tags: geo, image
 *   ---
 */
@Serializable
data class SkillManifest(
    /** 目录 id（sanitize 后的 name 或目录名） */
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val author: String = "",
    val tags: List<String> = emptyList(),
    val hasSkillFile: Boolean = true,
    /** SKILL.md 全文（详情查看用） */
    val content: String = "",
    val importedAt: Long = System.currentTimeMillis(),
)