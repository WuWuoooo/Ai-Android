package com.ai.android.agent.tools

import com.ai.android.MainApp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * 多模态 Agent 工具（文生图 / 文生视频 / 语音生成 / 图片/音频/视频理解）。
 *
 * ⭐ 所有工具支持 extra_body / extra_headers，AI 可自由 merge 请求体/头。
 */

private val MM_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

private fun parseExtraBody(raw: String): JsonObject {
    if (raw.isBlank()) return JsonObject(emptyMap())
    return runCatching {
        MM_JSON.parseToJsonElement(raw) as? JsonObject ?: JsonObject(emptyMap())
    }.getOrDefault(JsonObject(emptyMap()))
}

private fun parseHeaders(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    return raw.lines().mapNotNull { line ->
        val i = line.indexOf(':')
        if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
    }.filter { it.first.isNotEmpty() }.toMap()
}

// ==================== 文生图 ====================
class GenerateImageTool : ToolExecutor {
    override val name = "generate_image"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasImage) {
            return "⚠️ 未配置文生图 Provider，请到 设置 → 模型与能力 → 多模态绑定 IMAGE 能力后再试。"
        }
        val prompt = args.requireStr("prompt", "例如：一只在草地上奔跑的橘猫")
        val size = args.str("size", "1024x1024")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val result = multimodal.image(prompt, size, extraBody, extraHeaders)
        if (!result.success) return "❌ 文生图失败：${result.error}"

        val dir = File("/sdcard/AiAndroidImages")
        dir.mkdirs()
        val f = File(dir, "img_${System.currentTimeMillis()}.png")
        val bytes = android.util.Base64.decode(result.base64, android.util.Base64.NO_WRAP)
        f.writeBytes(bytes)

        val extraNote = if (extraBody.isNotEmpty() || extraHeaders.isNotEmpty())
            "（已附加 ${extraBody.size} 个 body 字段、${extraHeaders.size} 个 header）" else ""

        return buildString {
            appendLine("✅ 图片已生成（已作为图片气泡插入对话）")
            appendLine("路径：${f.absolutePath}")
            appendLine("大小：${bytes.size / 1024} KB")
            if (extraNote.isNotBlank()) appendLine(extraNote)
        }
    }
}

// ==================== 文生视频 ====================
class GenerateVideoTool : ToolExecutor {
    override val name = "generate_video"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasVideo) {
            return "⚠️ 未配置视频生成 Provider，请到 设置 → 模型与能力 → 多模态绑定 VIDEO 能力后再试。"
        }
        val prompt = args.requireStr("prompt", "例如：一只在草地上奔跑的橘猫")
        val duration = args.int("duration_sec", 5)
        val size = args.str("size", "720P")
        val aspectRatio = args.str("aspect_ratio", "16:9")
        val firstFrame = args.str("first_frame", "")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val result = multimodal.video(prompt, duration, size, aspectRatio, firstFrame, extraBody, extraHeaders)
        if (!result.success) return "❌ 文生视频失败：${result.error}"

        val dir = File("/sdcard/AiAndroidVideos")
        dir.mkdirs()
        val f = File(dir, "vid_${System.currentTimeMillis()}.mp4")
        if (result.localBytes.isNotEmpty()) {
            f.writeBytes(result.localBytes)
        } else if (result.url.isNotBlank()) {
            return buildString {
                appendLine("✅ 视频已生成（URL 模式，未落盘）")
                appendLine("URL：${result.url}")
                appendLine("时长：${duration}s")
            }
        } else if (result.taskId.isNotBlank()) {
            return buildString {
                appendLine("✅ 视频生成任务已提交（异步）")
                appendLine("taskId：${result.taskId}")
                appendLine("时长：${duration}s")
                appendLine("提示：部分 Provider 异步返回，稍后查看结果。")
            }
        }

        return buildString {
            appendLine("✅ 视频已生成（已作为视频气泡插入对话）")
            appendLine("路径：${f.absolutePath}")
            appendLine("时长：${duration}s")
            if (extraBody.isNotEmpty() || extraHeaders.isNotEmpty()) {
                appendLine("附加：body ${extraBody.size} 字段、header ${extraHeaders.size} 条")
            }
        }
    }
}

// ==================== 语音生成（TTS）====================
class GenerateSpeechTool : ToolExecutor {
    override val name = "generate_speech"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasTts) {
            return "⚠️ 未配置 TTS Provider，请到 设置 → 模型与能力 → 多模态绑定 TTS 能力后再试。"
        }
        val text = args.requireStr("text", "要合成的正文")
        val voice = args.str("voice", "冰糖")
        val stylePrompt = args.str("style_prompt", "")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val bytes = multimodal.tts(text, voice, stylePrompt, extraBody, extraHeaders)
        if (bytes.isEmpty()) return "❌ TTS 未返回音频"

        val dir = File("/sdcard/AiAndroidAudio")
        dir.mkdirs()
        val f = File(dir, "tts_${System.currentTimeMillis()}.wav")
        f.writeBytes(bytes)

        val durationMs = estimateWavDurationMs(bytes)

        return buildString {
            appendLine("✅ 语音已生成（已作为语音气泡插入对话）")
            appendLine("路径：${f.absolutePath}")
            appendLine("时长：${durationMs}ms")
            appendLine("大小：${bytes.size / 1024} KB")
            if (stylePrompt.isNotBlank()) appendLine("音色描述：$stylePrompt")
            if (extraBody.isNotEmpty() || extraHeaders.isNotEmpty()) {
                appendLine("附加：body ${extraBody.size} 字段、header ${extraHeaders.size} 条")
            }
        }
    }

    private fun estimateWavDurationMs(bytes: ByteArray): Int {
        if (bytes.size < 44) return 0
        return runCatching {
            val br = (bytes[28].toInt() and 0xFF) or
                ((bytes[29].toInt() and 0xFF) shl 8) or
                ((bytes[30].toInt() and 0xFF) shl 16) or
                ((bytes[31].toInt() and 0xFF) shl 24)
            if (br <= 0) return 0
            val ds = (bytes[40].toInt() and 0xFF) or
                ((bytes[41].toInt() and 0xFF) shl 8) or
                ((bytes[42].toInt() and 0xFF) shl 16) or
                ((bytes[43].toInt() and 0xFF) shl 24)
            val dataLen = if (ds > 0 && ds <= bytes.size) ds else bytes.size - 44
            (dataLen.toLong() * 1000L / br).toInt()
        }.getOrDefault(0)
    }
}

// ==================== 图片理解 ====================
class AnalyzeImageTool : ToolExecutor {
    override val name = "analyze_image"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasVision) {
            return "⚠️ 未配置识图（VISION）Provider，请到 设置 → 模型与能力 → 多模态绑定 VISION 能力后再试。"
        }
        val b64Input = args.str("image_base64")
        val pathInput = args.str("image_path")
        val urlInput = args.str("image_url")

        val b64 = when {
            b64Input.isNotBlank() -> b64Input
            urlInput.isNotBlank() -> urlInput
            pathInput.isNotBlank() -> {
                val f = File(pathInput)
                if (!f.exists()) return "❌ 图片不存在：$pathInput"
                android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            }
            else -> return "❌ 缺少参数：image_base64 / image_path / image_url 至少填一个"
        }
        val question = args.str("question", "请描述这张图片的内容")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val answer = multimodal.vision(b64, question, extraBody, extraHeaders)
        return answer.ifBlank { "（识图服务返回空）" }
    }
}

// ==================== 音频理解 ====================
class AnalyzeAudioTool : ToolExecutor {
    override val name = "analyze_audio"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        if (!multimodal.hasAudio) {
            return "⚠️ 未配置音频理解 Provider，请到 设置 → 模型与能力 → 多模态绑定 AUDIO 能力后再试。"
        }
        val b64Input = args.str("audio_base64")
        val pathInput = args.str("audio_path")
        val urlInput = args.str("audio_url")

        val b64 = when {
            b64Input.isNotBlank() -> b64Input
            urlInput.isNotBlank() -> urlInput
            pathInput.isNotBlank() -> {
                val f = File(pathInput)
                if (!f.exists()) return "❌ 音频不存在：$pathInput"
                android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            }
            else -> return "❌ 缺少参数：audio_base64 / audio_path / audio_url 至少填一个"
        }
        val question = args.str("question", "请描述这段音频的内容")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val answer = multimodal.audio(b64, question, extraBody, extraHeaders)
        return answer.ifBlank { "（音频理解服务返回空）" }
    }
}

// ==================== 视频理解 ====================
class AnalyzeVideoTool : ToolExecutor {
    override val name = "analyze_video"

    override suspend fun execute(args: JsonObject): String {
        val multimodal = MainApp.instance.providerManager.multimodal
        // ⭐ 正确属性名：hasVideoUnderstand（不是 hasVisionUnderstand）
        if (!multimodal.hasVideoUnderstand) {
            return "⚠️ 未配置视频理解 Provider，请到 设置 → 模型与能力 → 多模态绑定 VIDEO_UNDERSTAND 能力后再试。"
        }
        val b64Input = args.str("video_base64")
        val pathInput = args.str("video_path")
        val urlInput = args.str("video_url")
        val fps = args.int("fps", 2)
        val resolution = args.str("media_resolution", "default")

        val b64 = when {
            b64Input.isNotBlank() -> b64Input
            urlInput.isNotBlank() -> urlInput
            pathInput.isNotBlank() -> {
                val f = File(pathInput)
                if (!f.exists()) return "❌ 视频不存在：$pathInput"
                android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            }
            else -> return "❌ 缺少参数：video_base64 / video_path / video_url 至少填一个"
        }
        val question = args.str("question", "请描述这段视频的内容")
        val extraBody = parseExtraBody(args.str("extra_body", ""))
        val extraHeaders = parseHeaders(args.str("extra_headers", ""))

        val answer = multimodal.videoUnderstand(b64, question, fps, resolution, extraBody, extraHeaders)
        return answer.ifBlank { "（视频理解服务返回空）" }
    }
}