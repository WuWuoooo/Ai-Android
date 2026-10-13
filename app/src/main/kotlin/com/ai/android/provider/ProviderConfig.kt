package com.ai.android.provider

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class ParamRow(
    val key: String = "",
    val value: String = "",
    val type: String = "string",
)

@Serializable
data class ProviderConfig(
    val id: String = "default",
    val label: String = "OpenAI",
    val protocol: AIProvider.Protocol = AIProvider.Protocol.OPENAI,
    val baseUrl: String = "https://api.openai.com/v1",
    val apiKey: String = "",
    val model: String = "gpt-5.6-sol",
    val capabilities: List<String> = listOf("CHAT"),
    val extraBodyJson: String = "",
    val extraParams: List<ParamRow> = emptyList(),
    val extraHeaders: String = "",
    val contextWindow: Int = 0,
) {
    fun headerMap(): Map<String, String> =
        extraHeaders.lines()
            .mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun autoDetectContextWindow(model: String, current: Int = 0): Int {
            if (current > 0) return current
            val m = model.lowercase()
            return when {
                m.contains("512") && (m.contains("k") || m.contains("max") || m.contains("sol")) -> 524_288
                m.contains("524288") || m.contains("524_288") -> 524_288
                m.contains("200k") -> 204_800
                m.contains("128") && m.contains("k") -> 131_072
                m.contains("131072") -> 131_072
                m.contains("100k") -> 100_000
                else -> 32_768
            }
        }

        val PRESET_PARAMS = listOf(
            ParamRow("temperature", "0.3", "number"),
            ParamRow("top_p", "1.0", "number"),
            ParamRow("max_tokens", "4096", "number"),
            ParamRow("stream", "true", "bool"),
            ParamRow("stop", "", "string"),
        )

        // ⭐ 能力全集（对应 MultiModalProvider 接口的每个方法）
        val ALL_CAPS = listOf(
            "CHAT",             // 普通对话（OpenAI/Anthropic Provider）
            "VISION",           // 识图
            "IMAGE",            // 文生图
            "VIDEO",            // 文生视频
            "AUDIO",            // 识音频
            "VIDEO_UNDERSTAND", // 识视频
            "TTS",              // 语音合成
            "ASR",              // 语音识别
            "SEARCH",           // 联网搜索
            "REALTIME",         // 实时通话
        )

        // 语音/实时组：TTS / ASR / REALTIME
        val VOICE_CAPS = setOf("TTS", "ASR", "REALTIME")

        // 对话/多模态组：CHAT / VISION / IMAGE / VIDEO / AUDIO / VIDEO_UNDERSTAND / SEARCH
        val CHAT_CAPS = setOf(
            "CHAT", "VISION", "IMAGE", "VIDEO",
            "AUDIO", "VIDEO_UNDERSTAND", "SEARCH",
        )

        fun resolveCaps(selected: Set<String>): Set<String> {
            val pickedVoice = selected.any { it in VOICE_CAPS }
            val pickedChat = selected.any { it in CHAT_CAPS }
            return when {
                pickedVoice && pickedChat -> selected.filter { it in CHAT_CAPS }.toSet()
                else -> selected
            }
        }

        fun defaultParamsForCaps(
            caps: Set<String>,
            baseUrl: String,
            model: String,
        ): List<ParamRow> {
            val url = baseUrl.lowercase()
            val m = model.lowercase()
            val out = mutableListOf<ParamRow>()

            if ("IMAGE" in caps) {
                when {
                    url.contains("bigmodel") || m.contains("glm-image") -> {
                        out += ParamRow("size", "1280x1280", "string")
                        out += ParamRow("n", "1", "number")
                    }
                    url.contains("dashscope") || m.contains("qwen-image") -> {
                        out += ParamRow("size", "1024*1024", "string")
                        out += ParamRow("n", "1", "number")
                    }
                    else -> {
                        out += ParamRow("size", "1024x1024", "string")
                        out += ParamRow("n", "1", "number")
                    }
                }
            }

            if ("VIDEO" in caps) {
                when {
                    url.contains("agnes") || m.contains("agnes-video") -> {
                        out += ParamRow("seconds", "5", "string")
                        out += ParamRow("size", "720P", "string")
                        out += ParamRow("aspect_ratio", "16:9", "string")
                    }
                    url.contains("dashscope") || m.contains("wan2") -> {
                        out += ParamRow("duration", "5", "number")
                        out += ParamRow("size", "1280*720", "string")
                    }
                    url.contains("bigmodel") || m.contains("cogvideo") -> {
                        out += ParamRow("quality", "quality", "string")
                        out += ParamRow("with_audio", "false", "bool")
                        out += ParamRow("size", "1920x1080", "string")
                    }
                    else -> {
                        out += ParamRow("duration", "5", "number")
                        out += ParamRow("size", "720P", "string")
                    }
                }
            }

            if ("TTS" in caps) {
                if (url.contains("xiaomimimo")) {
                    out += ParamRow("voice", "冰糖", "string")
                } else {
                    out += ParamRow("voice", "alloy", "string")
                }
            }

            if ("CHAT" in caps || "VISION" in caps) {
                out += ParamRow("temperature", "0.3", "number")
                out += ParamRow("top_p", "1.0", "number")
                out += ParamRow("max_tokens", "4096", "number")
                out += ParamRow("stream", "true", "bool")
            }

            return out
        }

        fun defaultParamsFor(cfg: ProviderConfig): List<ParamRow> =
            defaultParamsForCaps(cfg.capabilities.toSet(), cfg.baseUrl, cfg.model)

        fun rowsToJson(rows: List<ParamRow>): String {
            val valid = rows.filter { r ->
                r.key.isNotBlank() && !(r.type == "string" && r.value.isBlank())
            }
            if (valid.isEmpty()) return ""
            val obj = buildJsonObject {
                valid.forEach { r ->
                    val k = r.key.trim()
                    val v = r.value.trim()
                    put(k, when (r.type) {
                        "number" -> {
                            val long = v.toLongOrNull()
                            if (long != null) JsonPrimitive(long)
                            else {
                                val dbl = v.toDoubleOrNull()
                                if (dbl != null) JsonPrimitive(dbl) else JsonPrimitive(v)
                            }
                        }
                        "bool" -> when (v) {
                            "true" -> JsonPrimitive(true)
                            "false" -> JsonPrimitive(false)
                            else -> JsonPrimitive(v)
                        }
                        "json" -> runCatching { json.parseToJsonElement(v) }.getOrNull() ?: JsonPrimitive(v)
                        else -> JsonPrimitive(v)
                    })
                }
            }
            return obj.toString()
        }

        fun jsonToRows(jsonText: String): List<ParamRow> {
            if (jsonText.isBlank()) return emptyList()
            val el = runCatching { json.parseToJsonElement(jsonText) }.getOrNull()
            val obj = el as? JsonObject ?: return emptyList()
            return obj.map { (k, v) ->
                when (v) {
                    is JsonObject, is JsonArray -> ParamRow(k, v.toString(), "json")
                    is JsonPrimitive -> when {
                        v.isString -> ParamRow(k, v.content, "string")
                        v.content.equals("true", true) -> ParamRow(k, "true", "bool")
                        v.content.equals("false", true) -> ParamRow(k, "false", "bool")
                        v.content.toDoubleOrNull() != null -> ParamRow(k, v.content, "number")
                        else -> ParamRow(k, v.content, "string")
                    }
                    else -> ParamRow(k, v.toString(), "json")
                }
            }
        }
    }
}