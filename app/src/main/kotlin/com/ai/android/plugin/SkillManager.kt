package com.ai.android.plugin

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Skill 管理器：zip 导入 / 扫描 / 删除 / 读取。
 *
 * 目录：filesDir/skills/<skillId>/SKILL.md + scripts/ + references/ ...
 *
 * ⭐ 导入策略：递归搜索 SKILL.md，最浅优先（支持 skills/geo-sleuth/SKILL.md 结构）。
 */
object SkillManager {

    private const val TAG = "SkillManager"
    private const val SKILL_FILE = "SKILL.md"
    private const val DIR_NAME = "skills"
    private const val MAX_SEARCH_DEPTH = 6

    fun skillsDir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    fun list(context: Context): List<SkillManifest> {
        val dir = skillsDir(context)
        return dir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { parseSkillDir(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    private fun parseSkillDir(d: File): SkillManifest? {
        val skillFile = File(d, SKILL_FILE)
        if (!skillFile.exists()) return null
        val content = runCatching { skillFile.readText() }.getOrDefault("")
        val front = parseFrontmatter(content)
        return SkillManifest(
            id = d.name,
            name = front["name"]?.ifBlank { d.name } ?: d.name,
            description = front["description"].orEmpty(),
            version = front["version"]?.ifBlank { "1.0.0" } ?: "1.0.0",
            author = front["author"].orEmpty(),
            tags = front["tags"]
                ?.split(",", " ")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList(),
            hasSkillFile = true,
            content = content,
            importedAt = d.lastModified(),
        )
    }

    private fun parseFrontmatter(md: String): Map<String, String> {
        val lines = md.lines()
        if (lines.size < 3) return emptyMap()
        if (lines.first().trim() != "---") return emptyMap()
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        if (end < 0) return emptyMap()
        return lines.subList(1, end + 1).mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null
            else line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }

    private fun findSkillFile(root: File): File? {
        var best: File? = null
        var bestDepth = Int.MAX_VALUE
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        while (queue.isNotEmpty()) {
            val (dir, depth) = queue.removeFirst()
            if (depth > MAX_SEARCH_DEPTH) continue
            val f = File(dir, SKILL_FILE)
            if (f.exists() && f.isFile && depth < bestDepth) {
                best = f
                bestDepth = depth
                if (depth == 0) break
            }
            dir.listFiles { c -> c.isDirectory }?.forEach { sub ->
                queue.add(sub to depth + 1)
            }
        }
        return best
    }

    fun importZip(context: Context, uri: Uri): String {
        val tmp = File(context.cacheDir, "skill_import_${System.currentTimeMillis()}")
        tmp.deleteRecursively()
        tmp.mkdirs()
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return "导入失败：无法打开 zip 流"
            input.use { ins ->
                ZipInputStream(ins).use { zis ->
                    while (true) {
                        val cur = zis.nextEntry ?: break
                        val target = File(tmp, cur.name)
                        if (!target.canonicalFile.path.startsWith(tmp.canonicalFile.path)) {
                            throw SecurityException("非法 zip 条目: ${cur.name}")
                        }
                        if (cur.isDirectory) {
                            target.mkdirs()
                        } else {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                        zis.closeEntry()
                    }
                }
            }

            val skillFile = findSkillFile(tmp)
                ?: return "导入失败：包内（递归 ${MAX_SEARCH_DEPTH} 层）未找到 SKILL.md"
            val srcRoot = skillFile.parentFile
                ?: return "导入失败：SKILL.md 父目录异常"

            val content = runCatching { skillFile.readText() }.getOrDefault("")
            val front = parseFrontmatter(content)
            val rawName = front["name"]?.ifBlank { null } ?: srcRoot.name
            val skillId = sanitizeId(rawName)

            val targetDir = File(skillsDir(context), skillId)
            if (targetDir.exists()) targetDir.deleteRecursively()
            runCatching { srcRoot.copyRecursively(targetDir, overwrite = true) }
                .onFailure { return "导入失败：写入 Skill 目录出错（${it.message}）" }

            val name = front["name"] ?: skillId
            val version = front["version"] ?: "1.0.0"
            Log.d(TAG, "skill imported: $skillId v$version (src=${srcRoot.name})")
            "已导入 Skill：$name（v$version）"
        } catch (e: Exception) {
            Log.e(TAG, "import failed", e)
            "导入失败：${e.message}"
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun sanitizeId(raw: String): String {
        val s = raw.trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return s.ifBlank { "skill_${System.currentTimeMillis()}" }
    }

    fun remove(context: Context, skillId: String): Boolean {
        val d = File(skillsDir(context), skillId)
        return if (d.exists()) d.deleteRecursively() else true
    }

    fun readSkillFile(context: Context, skillId: String): String? {
        val f = File(skillsDir(context), "$skillId/$SKILL_FILE")
        return if (f.exists()) runCatching { f.readText() }.getOrNull() else null
    }

    /**
     * ⭐ 系统提示词注入用：列出所有已导入 Skill。
     * 无 Skill 时返回空串（不注入）。
     */
    fun summaryBlock(context: Context): String {
        val all = list(context)
        if (all.isEmpty()) return ""
        return buildString {
            append("【可用 Skill】\n")
            append("下面是可以调用的技能。需要用某个 Skill 时：\n")
            append("  1. 先调 load_skill skill_id=<id> 看完整说明\n")
            append("  2. 按说明用 terminal_exec cd 到该 Skill 根目录，运行 scripts/ 里的脚本\n\n")
            all.forEach { s ->
                append("- ").append(s.id).append("（").append(s.name).append("）")
                if (s.description.isNotBlank()) append("：").append(s.description)
                append('\n')
            }
            append("\nSkill 根目录：").append(skillsDir(context).absolutePath).append("/<skill_id>/")
        }
    }
}