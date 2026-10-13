package com.ai.android.util

import android.graphics.Color as AndroidColor
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ru.noties.jlatexmath.JLatexMathDrawable

private const val USE_JLATEX = true

sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class CodeBlock(val code: String, val lang: String) : MdBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class LatexBlock(val formula: String) : MdBlock()
    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val aligns: List<CellAlign>,
    ) : MdBlock()
}

enum class CellAlign { LEFT, CENTER, RIGHT }

/** 常见 LaTeX 命令（用于宽松识别裸公式行） */
private val LATEX_HINT = Regex(
    """\\(frac|sqrt|int|iint|iiint|oint|sum|prod|lim|partial|nabla|alpha|beta|gamma|delta|epsilon|varepsilon|theta|lambda|mu|nu|pi|rho|sigma|phi|varphi|chi|psi|omega|vec|hat|bar|dot|ddot|tilde|times|cdot|pm|mp|leq|geq|neq|approx|equiv|infty|mathbb|mathcal|mathbf|mathrm|mathsf|textsf|texttt|text|textbf|rightarrow|leftarrow|to|left|right|begin|end)\b"""
)

/** 文本样式命令（长名在前，避免 \text 抢占 \textsf） */
private const val TEXT_STYLE_CMDS =
    "textsf|texttt|textnormal|textbf|textmd|textrm|textup|textit|textsl|textsc|" +
    "mathbf|mathsf|mathtt|mathrm|mathit|mathnormal|mathcal|mathfrak|" +
    "text"

/**
 * ⭐ LaTeX 预处理：把 JLatexMath 不支持的命令替换/剥离。
 *
 * 关键修复（v2）：
 *  1. alternation 长名在前 —— `textsf` 必须排在 `text` 之前，
 *     否则 `\textsf{好}` 会先匹配 `\text`，剥离后留下 `sf{好}` 这种无效命令。
 *  2. 剥离 `\(` `\)` —— 若公式块里混入行内公式标记（模型常见），JLatexMath 会报错。
 *  3. `\hspace*` 支持带星号版本。
 */
fun sanitizeLatex(src: String): String {
    var s = src

    // 1. \textsf{...} / \text{...} / \mathbf{...} 等 → 内层内容
    //    ⚠️ 长名必须排在前面：textsf > text
    s = s.replace(
        Regex("""\\(?:$TEXT_STYLE_CMDS)\{([^{}]*)\}"""),
        "$1"
    )
    // 剩余未闭合的（兜底）
    s = s.replace(
        Regex("""\\(?:$TEXT_STYLE_CMDS)"""),
        ""
    )

    // 2. \hspace{...} / \hspace*{...} → 剥离（任意长度均不支持）
    s = s.replace(Regex("""\\hspace\*?\{[^}]*\}"""), "")
    s = s.replace(Regex("""\\hspace\*?"""), "")

    // 3. 各种空格指令 → 普通空格 或 剥离
    s = s.replace(Regex("""\\quad|\\qquad"""), " ")
    s = s.replace(Regex("""\\[!,;:]"""), "")

    // 4. \mathop{...} / \mathrel{...} 等 → 内层
    s = s.replace(
        Regex("""\\(?:mathop|mathrel|mathbin|mathord|mathopen|mathclose|mathpunct)\{([^{}]*)\}"""),
        "$1"
    )

    // 5. ⭐ 剥离混入的 \left( \right) 内外多余标记（JLatexMath 支持 \left( 但若单独出现 \( 会报错）
    //    注意：\left( \right) 是合法命令，只剥离裸 \( 和 \)
    s = s.replace(Regex("""\\\("""), "(")
    s = s.replace(Regex("""\\\)"""), ")")

    // 6. \displaystyle / \textstyle / \scriptstyle / \scriptscriptstyle → 剥离
    s = s.replace(Regex("""\\(?:displaystyle|textstyle|scriptstyle|scriptscriptstyle)\b"""), "")

    return s
}

fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val allLines = md.lines()
    val para = StringBuilder()
    val codeBuf = StringBuilder()

    fun flushPara() {
        if (para.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(para.toString().trim()))
            para.clear()
        }
    }

    var i = 0
    while (i < allLines.size) {
        val raw = allLines[i]
        val t = raw.trim()
        when {
            // 表格
            t.startsWith("|") && t.endsWith("|") && t.length > 2 -> {
                val tableLines = mutableListOf<String>()
                var j = i
                while (j < allLines.size) {
                    val lt = allLines[j].trim()
                    if (lt.startsWith("|") && lt.endsWith("|") && lt.length > 2) {
                        tableLines.add(lt); j++
                    } else break
                }
                if (tableLines.size >= 2 && isSeparatorLine(tableLines[1])) {
                    flushPara()
                    val headers = parseTableRow(tableLines[0])
                    val aligns = parseAligns(tableLines[1])
                    val rows = tableLines.drop(2).map { parseTableRow(it) }
                    blocks.add(MdBlock.Table(headers, rows, aligns))
                    i = j - 1
                } else {
                    if (para.isNotEmpty()) para.append('\n')
                    para.append(t)
                }
            }

            // $$...$$ 块
            t.startsWith("$$") -> {
                flushPara()
                val body = StringBuilder()
                val rest = t.removePrefix("$$")
                if (rest.endsWith("$$") && rest.length >= 2) {
                    body.append(rest.removeSuffix("$$"))
                } else {
                    body.append(rest)
                    i++
                    while (i < allLines.size && !allLines[i].trim().endsWith("$$")) {
                        body.append('\n').append(allLines[i]); i++
                    }
                    if (i < allLines.size) body.append('\n').append(allLines[i].trim().removeSuffix("$$"))
                }
                blocks.add(MdBlock.LatexBlock(body.toString().trim()))
            }

            // \[ ... \] 块
            t.startsWith("\\[") -> {
                flushPara()
                val body = StringBuilder()
                val rest = t.removePrefix("\\[")
                if (rest.contains("\\]")) {
                    body.append(rest.substringBefore("\\]"))
                } else {
                    body.append(rest)
                    i++
                    while (i < allLines.size && !allLines[i].contains("\\]")) {
                        body.append('\n').append(allLines[i]); i++
                    }
                    if (i < allLines.size) body.append('\n').append(allLines[i].substringBefore("\\]"))
                }
                blocks.add(MdBlock.LatexBlock(body.toString().trim()))
            }

            // 整行 \(...\) 块
            t.startsWith("\\(") -> {
                flushPara()
                val body = StringBuilder()
                val rest = t.removePrefix("\\(")
                if (rest.contains("\\)")) {
                    body.append(rest.substringBefore("\\)"))
                } else {
                    body.append(rest)
                    i++
                    while (i < allLines.size && !allLines[i].contains("\\)")) {
                        body.append('\n').append(allLines[i]); i++
                    }
                    if (i < allLines.size) body.append('\n').append(allLines[i].substringBefore("\\)"))
                }
                blocks.add(MdBlock.LatexBlock(body.toString().trim()))
            }

            // 代码块
            t.startsWith("```") -> {
                flushPara()
                val lang = t.removePrefix("```").trim()
                codeBuf.clear(); i++
                while (i < allLines.size && !allLines[i].trim().startsWith("```")) {
                    codeBuf.append(allLines[i]).append('\n'); i++
                }
                blocks.add(MdBlock.CodeBlock(codeBuf.toString().trimEnd('\n'), lang))
            }

            // 标题
            t.startsWith("#") -> {
                flushPara()
                val level = t.takeWhile { it == '#' }.length.coerceIn(1, 4)
                blocks.add(MdBlock.Heading(level, t.dropWhile { it == '#' }.trim()))
            }

            // 引用
            t.startsWith("> ") || t == ">" -> {
                flushPara()
                blocks.add(MdBlock.Quote(t.removePrefix(">").trim()))
            }

            // 无序列表
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") -> {
                flushPara()
                blocks.add(MdBlock.ListItem(t.drop(2).trim(), false, 0))
            }

            // 有序列表
            Regex("^\\d+\\.\\s").containsMatchIn(t) -> {
                flushPara()
                val num = t.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                blocks.add(MdBlock.ListItem(t.substringAfter(' ').trim(), true, num))
            }

            // 宽松 LaTeX 单行：以 \ 开头，含数学命令，不含 \begin/\end 环境
            t.startsWith("\\") && LATEX_HINT.containsMatchIn(t)
                && !t.startsWith("\\begin") && !t.startsWith("\\end")
                && !t.startsWith("\\(") && !t.startsWith("\\[") -> {
                flushPara()
                blocks.add(MdBlock.LatexBlock(t))
            }

            t.isEmpty() -> flushPara()

            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(t)
            }
        }
        i++
    }
    flushPara()
    return blocks
}

private fun parseTableRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

private fun isSeparatorLine(line: String): Boolean {
    val cells = parseTableRow(line)
    if (cells.isEmpty()) return false
    return cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }
}

private fun parseAligns(line: String): List<CellAlign> =
    parseTableRow(line).map { c ->
        when {
            c.startsWith(":") && c.endsWith(":") -> CellAlign.CENTER
            c.endsWith(":") -> CellAlign.RIGHT
            else -> CellAlign.LEFT
        }
    }

/**
 * 行内格式：**粗体** / `代码` / $公式$ / \(公式\)
 */
fun buildInline(text: String, boldStyle: SpanStyle, codeStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        val re = Regex(
            """(\*\*[^*]+\*\*)|(`[^`]+`)|(\\\([^\n]*?\\\))|(\$[^$\n]+\$)"""
        )
        var last = 0
        re.findAll(text).forEach { m ->
            if (m.range.first > last) append(text.substring(last, m.range.first))
            val g = m.value
            when {
                g.startsWith("**") -> withStyle(boldStyle) { append(g.removeSurrounding("**")) }
                g.startsWith("`") -> withStyle(codeStyle) { append(g.removeSurrounding("`")) }
                g.startsWith("\\(") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeStyle.background,
                            color = codeStyle.color,
                            fontWeight = FontWeight.Medium,
                        )
                    ) {
                        append(" ")
                        append(g.removeSurrounding("\\(", "\\)"))
                        append(" ")
                    }
                }
                g.startsWith("$") -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = codeStyle.background,
                            color = codeStyle.color,
                            fontWeight = FontWeight.Medium,
                        )
                    ) {
                        append(" ")
                        append(g.removeSurrounding("$"))
                        append(" ")
                    }
                }
                else -> append(g)
            }
            last = m.range.last + 1
        }
        if (last < text.length) append(text.substring(last))
    }

@Composable
fun LatexView(formula: String, modifier: Modifier = Modifier, inline: Boolean = false) {
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant

    if (!USE_JLATEX) {
        Box(
            modifier.fillMaxWidth().background(codeBg, MaterialTheme.shapes.small)
                .padding(10.dp).horizontalScroll(rememberScrollState())
        ) {
            Text("$formula", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = codeColor)
        }
        return
    }

    val sanitized = remember(formula) { sanitizeLatex(formula) }

    // ⭐ 预处理后若只剩空白或普通文本，直接走文本渲染，不再交给 JLatexMath
    val looksLikePlainText = remember(sanitized) {
        sanitized.isBlank() || !sanitized.contains('\\') && sanitized.none { it in "_{}^" }
    }

    if (looksLikePlainText) {
        if (inline) {
            Text(
                sanitized.trim(),
                modifier = modifier,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
        } else {
            Box(
                modifier.fillMaxWidth().background(codeBg, MaterialTheme.shapes.small)
                    .padding(8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    sanitized.trim(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        return
    }

    val drawable = remember(sanitized) {
        runCatching {
            JLatexMathDrawable.builder(sanitized)
                .textSize(if (inline) 36f else 48f)
                .padding(if (inline) 4 else 12)
                .background(AndroidColor.TRANSPARENT)
                .build()
        }.getOrNull()
    }

    if (inline) {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (drawable != null) {
                AndroidView(
                    factory = { ctx ->
                        ImageView(ctx).apply {
                            setImageDrawable(drawable)
                            adjustViewBounds = true
                        }
                    },
                    modifier = Modifier.wrapContentWidth(),
                )
            } else {
                // ⭐ 渲染失败 → 用普通文本兜底，不再显示 $$ 源码
                Text(
                    sanitized,
                    fontSize = 14.sp,
                    color = codeColor,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        return
    }

    Box(
        modifier.fillMaxWidth().background(codeBg, MaterialTheme.shapes.small)
            .padding(8.dp).horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (drawable != null) {
            AndroidView(
                factory = { ctx ->
                    ImageView(ctx).apply {
                        setImageDrawable(drawable)
                        adjustViewBounds = true
                    }
                },
                modifier = Modifier.wrapContentWidth(),
            )
        } else {
            // ⭐ 渲染失败 → 用普通文本兜底，不再显示 "$$ ... $$（渲染失败，显示源码）"
            Text(
                sanitized,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = codeColor,
            )
        }
    }
}

@Composable
private fun TableView(
    headers: List<String>,
    rows: List<List<String>>,
    aligns: List<CellAlign>,
    bold: SpanStyle,
    codeSpan: SpanStyle,
    baseColor: Color,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    val cellWidth = 150.dp

    @Composable
    fun cell(content: String, align: TextAlign, isHeader: Boolean) {
        val dollarCount = content.count { it == '$' }
        val parenCount = Regex("""\\\(""").findAll(content).count()
        if (dollarCount >= 2 || parenCount >= 1) {
            Row(
                Modifier
                    .width(cellWidth)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                splitMath(content).forEach { seg ->
                    when (seg.kind) {
                        SegKind.TEXT -> Text(
                            text = seg.value,
                            fontSize = 13.sp,
                            color = baseColor,
                            fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                            textAlign = align,
                        )
                        SegKind.MATH -> LatexView(formula = seg.value, inline = true)
                    }
                }
            }
        } else {
            Text(
                text = buildInline(content, bold, codeSpan),
                modifier = Modifier.width(cellWidth).padding(8.dp),
                fontSize = 13.sp,
                color = baseColor,
                fontWeight = if (isHeader) FontWeight.Bold else FontWeight.Normal,
                textAlign = align,
            )
        }
    }

    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Column(Modifier.background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.small)) {
            Row(Modifier.background(headerBg)) {
                headers.forEachIndexed { idx, h -> cell(h, aligns.getOrNull(idx).toTextAlign(), isHeader = true) }
            }
            HorizontalDivider(color = borderColor)
            rows.forEachIndexed { rIdx, row ->
                Row {
                    row.forEachIndexed { cIdx, c -> cell(c, aligns.getOrNull(cIdx).toTextAlign(), isHeader = false) }
                }
                if (rIdx < rows.size - 1) HorizontalDivider(color = borderColor)
            }
        }
    }
}

enum class SegKind { TEXT, MATH }
data class Seg(val kind: SegKind, val value: String)

private fun splitMath(s: String): List<Seg> {
    val out = mutableListOf<Seg>()
    val buf = StringBuilder()
    var i = 0
    var inMath = false
    var closer = ""

    while (i < s.length) {
        val c = s[i]

        if (!inMath && c == '\\' && i + 1 < s.length && s[i + 1] == '(') {
            if (buf.isNotEmpty()) { out.add(Seg(SegKind.TEXT, buf.toString())); buf.clear() }
            inMath = true; closer = "\\)"
            i += 2; continue
        }
        if (!inMath && c == '$') {
            if (buf.isNotEmpty()) { out.add(Seg(SegKind.TEXT, buf.toString())); buf.clear() }
            inMath = true; closer = "$"
            i += 1; continue
        }
        if (inMath) {
            if (closer == "\\)" && c == '\\' && i + 1 < s.length && s[i + 1] == ')') {
                out.add(Seg(SegKind.MATH, buf.toString().trim()))
                buf.clear(); inMath = false; closer = ""
                i += 2; continue
            }
            if (closer == "$" && c == '$') {
                out.add(Seg(SegKind.MATH, buf.toString().trim()))
                buf.clear(); inMath = false; closer = ""
                i += 1; continue
            }
        }
        buf.append(c)
        i++
    }
    if (buf.isNotEmpty()) out.add(Seg(if (inMath) SegKind.MATH else SegKind.TEXT, buf.toString()))
    return out
}

private fun CellAlign?.toTextAlign(): TextAlign = when (this) {
    CellAlign.CENTER -> TextAlign.Center
    CellAlign.RIGHT -> TextAlign.End
    else -> TextAlign.Start
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = 15.sp,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val baseColor = if (color == Color.Unspecified) MaterialTheme.colorScheme.onSurface else color
    val codeBg = MaterialTheme.colorScheme.surfaceVariant
    val codeColor = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primary

    val bold = SpanStyle(fontWeight = FontWeight.Bold)
    val codeSpan = SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg, color = codeColor)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocks.forEach { b ->
                    when (b) {
                        is MdBlock.Heading -> Text(
                            text = buildInline(b.text, bold, codeSpan),
                            color = baseColor,
                            fontSize = when (b.level) {
                                1 -> (fontSize.value + 6).sp
                                2 -> (fontSize.value + 3).sp
                                else -> (fontSize.value + 1).sp
                            },
                            fontWeight = FontWeight.Bold,
                        )
                        is MdBlock.Paragraph -> Text(
                            text = buildInline(b.text, bold, codeSpan),
                            color = baseColor,
                            fontSize = fontSize,
                            lineHeight = (fontSize.value + 6).sp,
                        )
                        is MdBlock.CodeBlock -> Box(
                            Modifier.fillMaxWidth().background(codeBg, MaterialTheme.shapes.small)
                                .padding(10.dp).horizontalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = b.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = (fontSize.value - 2).sp,
                                color = codeColor,
                                lineHeight = (fontSize.value + 3).sp,
                            )
                        }
                        is MdBlock.ListItem -> Row(Modifier.fillMaxWidth()) {
                            Text(
                                text = if (b.ordered) "${b.index}. " else "• ",
                                color = accent, fontSize = fontSize, fontWeight = FontWeight.Bold,
                            )
                            Text(
                                text = buildInline(b.text, bold, codeSpan),
                                color = baseColor, fontSize = fontSize,
                                lineHeight = (fontSize.value + 6).sp,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        is MdBlock.Quote -> Text(
                            text = buildAnnotatedString {
                                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                                    append("▎ ")
                                }
                                append(buildInline(b.text, bold, codeSpan))
                            },
                            color = baseColor.copy(alpha = 0.85f),
                            fontSize = fontSize,
                            lineHeight = (fontSize.value + 6).sp,
                        )
                        is MdBlock.LatexBlock -> LatexView(formula = b.formula)
                        is MdBlock.Table -> TableView(
                            headers = b.headers, rows = b.rows, aligns = b.aligns,
                            bold = bold, codeSpan = codeSpan, baseColor = baseColor,
                        )
                    }
                }
            }
        }
    }
}