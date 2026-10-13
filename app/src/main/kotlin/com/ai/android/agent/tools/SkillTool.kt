package com.ai.android.agent.tools

import android.content.Context
import com.ai.android.plugin.SkillManager
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 列出所有已导入的 Skill（id + name + description + tags）。
 */
class ListSkillsTool(private val context: Context) : ToolExecutor {
    override val name = "list_skills"
    override val parallelizable = true

    override suspend fun execute(args: JsonObject): String {
        val all = SkillManager.list(context)
        if (all.isEmpty()) return "（还没有导入任何 Skill）"
        return buildString {
            append("已导入 ").append(all.size).append(" 个 Skill：\n\n")
            all.forEach { s ->
                append("• ").append(s.id).append("（").append(s.name).append("）")
                if (s.version.isNotBlank()) append(" v").append(s.version)
                append('\n')
                if (s.description.isNotBlank()) append("  简介：").append(s.description).append('\n')
                if (s.tags.isNotEmpty()) append("  标签：").append(s.tags.joinToString(", ")).append('\n')
                append('\n')
            }
            append("用 load_skill skill_id=<id> 查看某个 Skill 的完整说明。")
        }
    }
}

/**
 * 加载指定 Skill 的 SKILL.md 全文。
 *
 * 返回内容：
 *  - Skill 元信息（name / version / author）
 *  - **Skill 根目录绝对路径**（AI 用 terminal_exec 时 cd 到这里）
 *  - SKILL.md 全文（含使用说明、脚本清单、参数）
 */
class LoadSkillTool(private val context: Context) : ToolExecutor {
    override val name = "load_skill"
    override val parallelizable = true

    override suspend fun execute(args: JsonObject): String {
        val skillId = args.requireStr("skill_id", "Skill id（用 list_skills 查询）")
        val all = SkillManager.list(context)
        val skill = all.firstOrNull { it.id == skillId }
            ?: all.firstOrNull { it.name.equals(skillId, ignoreCase = true) }
            ?: return "❌ 找不到 Skill：$skillId。用 list_skills 查看可用列表。"

        val skillDir = File(SkillManager.skillsDir(context), skill.id)
        val content = SkillManager.readSkillFile(context, skill.id) ?: "（SKILL.md 读取失败）"

        return buildString {
            append("# Skill: ").append(skill.name).append('\n')
            if (skill.version.isNotBlank()) append("版本：").append(skill.version).append('\n')
            if (skill.author.isNotBlank()) append("作者：").append(skill.author).append('\n')
            append('\n')
            append("**Skill 根目录**：`").append(skillDir.absolutePath).append("`\n")
            append("运行 scripts/ 里的脚本时，先 cd 到该目录（或用绝对路径）。例：\n")
            append("```\n")
            append("cd ").append(skillDir.absolutePath).append("\n")
            append("python scripts/<name>.py --args\n")
            append("```\n")
            // ⭐ 修复：用双引号字符串（'...' 是 Char，不能放多字符）
            append("\n---\n\n")
            append(content)
        }
    }
}