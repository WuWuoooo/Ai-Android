package com.ai.android.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

interface MultimodalProvider {
    val name: String

    suspend fun generateImage(
        prompt: String, size: String = "1024x1024",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): ImageResult

    suspend fun generateVideo(
        prompt: String, durationSec: Int = 5, size: String = "720P",
        aspectRatio: String = "16:9", firstFrame: String = "",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): VideoResult

    suspend fun visionChat(
        imageBase64: String, question: String,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String

    suspend fun audioUnderstand(
        audioBase64: String, question: String,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String

    suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int = 2, resolution: String = "default",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String

    suspend fun textToSpeech(
        text: String, voice: String = "alloy", stylePrompt: String = "",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): ByteArray

    suspend fun speechToText(
        audioBytes: ByteArray,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ): String

    data class ImageResult(
        val success: Boolean,
        val url: String = "",
        val base64: String = "",
        val error: String = "",
    )

    data class VideoResult(
        val success: Boolean,
        val url: String = "",
        val localBytes: ByteArray = ByteArray(0),
        val taskId: String = "",
        val error: String = "",
    )
}

internal val MM_JSON = "application/json; charset=utf-8".toMediaType()

internal fun mergeJson(base: JsonObject, extra: JsonObject): JsonObject {
    if (extra.isEmpty()) return base
    return buildJsonObject {
        val keys = LinkedHashSet<String>().apply { addAll(base.keys); addAll(extra.keys) }
        keys.forEach { k ->
            val bv = base[k]; val ev = extra[k]
            when {
                ev == null -> bv?.let { put(k, it) }
                bv == null -> put(k, ev)
                bv is JsonObject && ev is JsonObject -> put(k, mergeJson(bv, ev))
                else -> put(k, ev)
            }
        }
    }
}

// ==================== OpenAI ====================

class OpenAIMultimodal(
    override val name: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    private val apiKey: String,
    private val model: String = "gpt-5.6-sol",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    private fun endpoint(): String = "${baseUrl.trimEnd('/')}/chat/completions"

    override suspend fun generateImage(
        prompt: String, size: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.ImageResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "gpt-image-2.5-flare")
            put("prompt", prompt)
            put("n", 1); put("size", size); put("response_format", "b64_json")
        }
        val body = mergeJson(base, extraBody)
        val b0 = baseUrl.trimEnd('/')
        val imgUrl = when {
            b0.endsWith("/images/generations") -> b0
            b0.endsWith("/chat/completions") -> b0.removeSuffix("/chat/completions") + "/images/generations"
            else -> b0 + "/images/generations"
        }
        val b = Request.Builder().url(imgUrl).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(false, error = "HTTP ${resp.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                val item = root["data"]?.jsonArray?.firstOrNull()?.jsonObject
                MultimodalProvider.ImageResult(true,
                    url = item?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    base64 = item?.get("b64_json")?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "未知错误") }
    }

    override suspend fun generateVideo(
        prompt: String, durationSec: Int, size: String, aspectRatio: String,
        firstFrame: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.VideoResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "agnes-video-2.5-flash")
            put("prompt", prompt)
            put("seconds", durationSec.toString())
            put("size", size)
            put("aspect_ratio", aspectRatio)
            if (firstFrame.isNotBlank()) put("first_frame", firstFrame)
            put("mode", if (firstFrame.isNotBlank()) "keyframe" else "text")
        }
        val body = mergeJson(base, extraBody)
        val b0 = baseUrl.trimEnd('/').substringBefore("/v1")
        val b = Request.Builder().url("$b0/v1/videos").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.VideoResult(false, error = "HTTP ${resp.code}: ${text.take(300)}")
                val root = json.parseToJsonElement(text).jsonObject
                MultimodalProvider.VideoResult(true,
                    url = root["url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    taskId = root["video_id"]?.jsonPrimitive?.contentOrNull.orEmpty())
            }
        }.getOrElse { MultimodalProvider.VideoResult(false, error = it.message ?: "未知错误") }
    }

    override suspend fun visionChat(
        imageBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model.ifBlank { "gpt-5.6-sol" })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$imageBase64") })
                        })
                    })
                })
            })
            put("max_tokens", 4096)
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun audioUnderstand(
        audioBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model.ifBlank { "gpt-4o-audio-preview" })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", "data:audio/wav;base64,$audioBase64")
                                put("format", "wav")
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
            put("max_completion_tokens", 2048)
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int, resolution: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model.ifBlank { "gpt-5.6-sol" })
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "video_url")
                            put("video_url", buildJsonObject {
                                put("url", "data:video/mp4;base64,$videoBase64")
                            })
                            put("fps", fps)
                            put("media_resolution", resolution)
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
            put("max_completion_tokens", 4096)
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun textToSpeech(
        text: String, voice: String, stylePrompt: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "tts-1"); put("input", text.take(4096)); put("voice", voice.ifBlank { "alloy" })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/audio/speech").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw IllegalStateException("TTS HTTP ${resp.code}: ${err.take(300)}")
            }
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun speechToText(
        audioBytes: ByteArray, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart("file", "audio.m4a", audioBytes.toRequestBody("audio/m4a".toMediaType()))
            .build()
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/audio/transcriptions").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(multipart).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("ASR HTTP ${resp.code}: ${text.take(200)}")
            json.parseToJsonElement(text).jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }
}

// ==================== AnthropicVision ====================

class AnthropicVision(
    override val name: String,
    private val baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String = "claude-opus-5",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    override suspend fun generateImage(
        prompt: String, size: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ) = MultimodalProvider.ImageResult(false, error = "Anthropic 不支持文生图")

    override suspend fun generateVideo(
        prompt: String, durationSec: Int, size: String, aspectRatio: String,
        firstFrame: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ) = MultimodalProvider.VideoResult(false, error = "Anthropic 不支持文生视频")

    override suspend fun visionChat(
        imageBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", imageBase64)
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            val root = json.parseToJsonElement(text).jsonObject
            root["content"]?.jsonArray
                ?.firstOrNull { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
                ?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun audioUnderstand(
        audioBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("Anthropic 不支持音频理解")

    override suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int, resolution: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("Anthropic 不支持视频理解")

    override suspend fun textToSpeech(
        text: String, voice: String, stylePrompt: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): ByteArray = throw IllegalStateException("Anthropic 不支持 TTS")

    override suspend fun speechToText(
        audioBytes: ByteArray, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("Anthropic 不支持 ASR")
}

// ==================== QwenMultimodal ====================

class QwenMultimodal(
    override val name: String,
    private val baseUrl: String = "https://dashscope.aliyuncs.com/compatible-mode/v1",
    private val apiKey: String,
    private val model: String = "qwen3.8-max",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    override suspend fun generateImage(
        prompt: String, size: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.ImageResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "qwen-image-3.0-pro")
            put("input", buildJsonObject { put("prompt", prompt) })
            put("parameters", buildJsonObject {
                put("size", size.replace("x", "*")); put("n", 1)
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/text2image/image-synthesis")
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(false, error = "HTTP ${resp.code}: ${text.take(300)}")
                val taskId = json.parseToJsonElement(text).jsonObject["output"]
                    ?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                MultimodalProvider.ImageResult(true, url = "task:$taskId")
            }
        }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "未知错误") }
    }

    override suspend fun generateVideo(
        prompt: String, durationSec: Int, size: String, aspectRatio: String,
        firstFrame: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.VideoResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "wan2.5-t2v")
            put("input", buildJsonObject {
                put("prompt", prompt)
                if (firstFrame.isNotBlank()) put("img_url", firstFrame)
            })
            put("parameters", buildJsonObject {
                put("duration", durationSec); put("size", size); put("aspect_ratio", aspectRatio)
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/video-generation/video-synthesis")
            .header("Authorization", "Bearer $apiKey")
            .header("X-DashScope-Async", "enable")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.VideoResult(false, error = "HTTP ${resp.code}: ${text.take(300)}")
                val taskId = json.parseToJsonElement(text).jsonObject["output"]
                    ?.jsonObject?.get("task_id")?.jsonPrimitive?.contentOrNull.orEmpty()
                MultimodalProvider.VideoResult(true, taskId = taskId)
            }
        }.getOrElse { MultimodalProvider.VideoResult(false, error = it.message ?: "未知错误") }
    }

    override suspend fun visionChat(
        imageBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$imageBase64") })
                        })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun audioUnderstand(
        audioBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "qwen3.8-omni-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", "data:audio/wav;base64,$audioBase64")
                                put("format", "wav")
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int, resolution: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "qwen3.8-omni-flash")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "video_url")
                            put("video_url", buildJsonObject {
                                put("url", "data:video/mp4;base64,$videoBase64")
                            })
                            put("fps", fps)
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url("${baseUrl.trimEnd('/')}/chat/completions").header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun textToSpeech(
        text: String, voice: String, stylePrompt: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "qwen3-tts-flash")
            put("input", buildJsonObject { put("text", text.take(2000)) })
            put("parameters", buildJsonObject {
                put("voice", voice.ifBlank { "Cherry" }); put("format", "wav")
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                val err = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                throw IllegalStateException("TTS HTTP ${resp.code}: ${err.take(300)}")
            }
            resp.body?.bytes() ?: ByteArray(0)
        }
    }

    override suspend fun speechToText(
        audioBytes: ByteArray, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "qwen-asr")
            put("input", buildJsonObject {
                put("audio", "data:audio/m4a;base64," +
                    android.util.Base64.encodeToString(audioBytes, android.util.Base64.NO_WRAP))
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation")
            .header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("ASR HTTP ${resp.code}")
            text.take(5000)
        }
    }
}

// ==================== MimoMultimodal ====================

class MimoMultimodal(
    override val name: String,
    private val baseUrl: String = "https://api.xiaomimimo.com/v1",
    private val apiKey: String,
    private val model: String = "mimo-v2.5-tts",
    private val defaultVoice: String = "冰糖",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    private val VALID_VOICES = setOf(
        "mimo_default", "冰糖", "茉莉", "苏打", "白桦", "Mia", "Chloe", "Milo", "Dean",
    )

    private fun isVoiceDesign(): Boolean =
        model.contains("voicedesign", ignoreCase = true) || model.contains("voice_design", ignoreCase = true)

    private fun endpoint(): String {
        val b = baseUrl.trimEnd('/')
        return when {
            b.endsWith("/chat/completions") -> b
            b.endsWith("/v1") -> "$b/chat/completions"
            else -> "$b/v1/chat/completions"
        }
    }

    override suspend fun textToSpeech(
        text: String, voice: String, stylePrompt: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): ByteArray = withContext(Dispatchers.IO) {
        val vd = isVoiceDesign()
        val extraMessages = extraBody["messages"] as? JsonArray
        val messages: JsonArray = extraMessages ?: buildJsonArray {
            if (vd) {
                val content = stylePrompt.ifBlank { "自然朗读，语气平稳" }
                add(buildJsonObject { put("role", "user"); put("content", content) })
            }
            add(buildJsonObject { put("role", "assistant"); put("content", text.take(4096)) })
        }
        val extraAudio = extraBody["audio"] as? JsonObject
        val requested = voice.ifBlank { defaultVoice }
        val validVoice = if (requested in VALID_VOICES) requested else defaultVoice
        val audio: JsonObject = extraAudio ?: buildJsonObject {
            put("format", "wav")
            if (!vd) put("voice", validVoice)
        }
        val base = buildJsonObject {
            put("model", model)
            put("messages", messages)
            put("audio", audio)
        }
        val finalBody = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(finalBody.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val respText = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException(
                "MiMo TTS HTTP ${resp.code}（model=$model, voice=$validVoice）: ${respText.take(400)}"
            )
            val root = json.parseToJsonElement(respText).jsonObject
            val b64 = root["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject
                ?.get("audio")?.jsonObject
                ?.get("data")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (b64.isEmpty()) throw IllegalStateException("MiMo TTS 响应缺少 choices[0].message.audio.data")
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        }
    }

    override suspend fun visionChat(
        imageBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "mimo-v2.6-vl")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject { put("url", "data:image/jpeg;base64,$imageBase64") })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun audioUnderstand(
        audioBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "mimo-v2.6-omni")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_audio")
                            put("input_audio", buildJsonObject {
                                put("data", "data:audio/wav;base64,$audioBase64")
                                put("format", "wav")
                            })
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int, resolution: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "mimo-v2.6-omni")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "video_url")
                            put("video_url", buildJsonObject {
                                put("url", "data:video/mp4;base64,$videoBase64")
                            })
                            put("fps", fps)
                            put("media_resolution", resolution)
                        })
                        add(buildJsonObject { put("type", "text"); put("text", question) })
                    })
                })
            })
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(endpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}: ${text.take(300)}")
            json.parseToJsonElement(text).jsonObject["choices"]?.jsonArray
                ?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
    }

    override suspend fun generateImage(
        prompt: String, size: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ) = MultimodalProvider.ImageResult(false, error = "MiMo 不支持文生图")

    override suspend fun generateVideo(
        prompt: String, durationSec: Int, size: String, aspectRatio: String,
        firstFrame: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ) = MultimodalProvider.VideoResult(false, error = "MiMo 不支持文生视频")

    override suspend fun speechToText(
        audioBytes: ByteArray, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("MiMo 不支持 ASR")
}

// ==================== ZhipuImageMultimodal ====================

class ZhipuImageMultimodal(
    override val name: String,
    private val baseUrl: String = "https://open.bigmodel.cn/api/paas/v4",
    private val apiKey: String,
    private val model: String = "glm-image",
) : MultimodalProvider {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS).build()

    private fun imageEndpoint(): String {
        val base = baseUrl.trimEnd('/')
        val root = base
            .substringBefore("/web_search")
            .substringBefore("/chat/completions")
            .substringBefore("/images/generations")
            .trimEnd('/')
        return root + "/images/generations"
    }

    override suspend fun generateImage(
        prompt: String, size: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.ImageResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", model.ifBlank { "glm-image" })
            put("prompt", prompt)
            if (size.isNotBlank()) put("size", size)
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder().url(imageEndpoint()).header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.ImageResult(
                    false, error = "GLM-Image HTTP ${resp.code}: ${text.take(300)}"
                )
                val root = json.parseToJsonElement(text).jsonObject
                val url = root["data"]?.jsonArray?.firstOrNull()
                    ?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: root["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.isBlank()) return@use MultimodalProvider.ImageResult(
                    false, url = url, error = "GLM-Image 响应缺少图片 URL"
                )
                val dlReq = Request.Builder().url(url).build()
                client.newCall(dlReq).execute().use { dl ->
                    if (!dl.isSuccessful) return@use MultimodalProvider.ImageResult(
                        false, url = url, error = "下载图片 URL 失败 HTTP ${dl.code}"
                    )
                    val bytes = dl.body?.bytes() ?: ByteArray(0)
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    MultimodalProvider.ImageResult(true, url = url, base64 = b64)
                }
            }
        }.getOrElse { MultimodalProvider.ImageResult(false, error = it.message ?: "GLM-Image 未知错误") }
    }

    override suspend fun generateVideo(
        prompt: String, durationSec: Int, size: String, aspectRatio: String,
        firstFrame: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): MultimodalProvider.VideoResult = withContext(Dispatchers.IO) {
        val base = buildJsonObject {
            put("model", "cogvideox-3")
            put("prompt", prompt)
            if (firstFrame.isNotBlank()) put("image_url", firstFrame)
            put("with_audio", false); put("quality", "quality")
            put("size", size); put("fps", 30)
        }
        val body = mergeJson(base, extraBody)
        val b = Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/videos/generations")
            .header("Authorization", "Bearer $apiKey")
        extraHeaders.forEach { (k, v) -> b.header(k, v) }
        runCatching {
            client.newCall(b.post(body.toString().toRequestBody(MM_JSON)).build()).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return@use MultimodalProvider.VideoResult(false, error = "HTTP ${resp.code}: ${text.take(300)}")
                val id = json.parseToJsonElement(text).jsonObject["id"]
                    ?.jsonPrimitive?.contentOrNull.orEmpty()
                MultimodalProvider.VideoResult(true, taskId = id)
            }
        }.getOrElse { MultimodalProvider.VideoResult(false, error = it.message ?: "未知错误") }
    }

    override suspend fun visionChat(
        imageBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("智谱 GLM-Image 不支持识图")

    override suspend fun audioUnderstand(
        audioBase64: String, question: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("智谱 GLM-Image 不支持音频理解")

    override suspend fun videoUnderstand(
        videoBase64: String, question: String, fps: Int, resolution: String,
        extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("智谱 GLM-Image 不支持视频理解")

    override suspend fun textToSpeech(
        text: String, voice: String, stylePrompt: String, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): ByteArray = throw IllegalStateException("智谱 GLM-Image 不支持 TTS")

    override suspend fun speechToText(
        audioBytes: ByteArray, extraBody: JsonObject, extraHeaders: Map<String, String>,
    ): String = throw IllegalStateException("智谱 GLM-Image 不支持 ASR")
}

// ==================== MultimodalManager（⭐ 新增 audio/videoUnderstand provider）====================

class MultimodalManager {
    private var imageProvider: MultimodalProvider? = null
    private var videoProvider: MultimodalProvider? = null
    private var visionProvider: MultimodalProvider? = null
    private var audioProvider: MultimodalProvider? = null
    private var videoUnderstandProvider: MultimodalProvider? = null
    private var ttsProvider: MultimodalProvider? = null
    private var asrProvider: MultimodalProvider? = null

    fun setImage(p: MultimodalProvider?) { imageProvider = p }
    fun setVideo(p: MultimodalProvider?) { videoProvider = p }
    fun setVision(p: MultimodalProvider?) { visionProvider = p }
    fun setAudio(p: MultimodalProvider?) { audioProvider = p }
    fun setVideoUnderstand(p: MultimodalProvider?) { videoUnderstandProvider = p }
    fun setTts(p: MultimodalProvider?) { ttsProvider = p }
    fun setAsr(p: MultimodalProvider?) { asrProvider = p }

    val hasImage get() = imageProvider != null
    val hasVideo get() = videoProvider != null
    val hasVision get() = visionProvider != null
    val hasAudio get() = audioProvider != null
    val hasVideoUnderstand get() = videoUnderstandProvider != null
    val hasTts get() = ttsProvider != null
    val hasAsr get() = asrProvider != null

    suspend fun image(
        prompt: String, size: String = "1024x1024",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = imageProvider?.generateImage(prompt, size, extraBody, extraHeaders)
        ?: MultimodalProvider.ImageResult(false, error = "未配置文生图 Provider")

    suspend fun video(
        prompt: String, durationSec: Int = 5, size: String = "720P",
        aspectRatio: String = "16:9", firstFrame: String = "",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = videoProvider?.generateVideo(prompt, durationSec, size, aspectRatio, firstFrame, extraBody, extraHeaders)
        ?: MultimodalProvider.VideoResult(false, error = "未配置视频生成 Provider")

    suspend fun vision(
        b64: String, q: String,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = visionProvider?.visionChat(b64, q, extraBody, extraHeaders)
        ?: throw IllegalStateException("未配置识图 Provider")

    suspend fun audio(
        b64: String, q: String,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = audioProvider?.audioUnderstand(b64, q, extraBody, extraHeaders)
        ?: throw IllegalStateException("未配置音频理解 Provider")

    suspend fun videoUnderstand(
        b64: String, q: String, fps: Int, resolution: String,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = videoUnderstandProvider?.videoUnderstand(b64, q, fps, resolution, extraBody, extraHeaders)
        ?: throw IllegalStateException("未配置视频理解 Provider")

    suspend fun tts(
        text: String, voice: String = "", stylePrompt: String = "",
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = ttsProvider?.textToSpeech(text, voice, stylePrompt, extraBody, extraHeaders)
        ?: throw IllegalStateException("未配置 TTS Provider")

    suspend fun asr(
        bytes: ByteArray,
        extraBody: JsonObject = JsonObject(emptyMap()),
        extraHeaders: Map<String, String> = emptyMap(),
    ) = asrProvider?.speechToText(bytes, extraBody, extraHeaders)
        ?: throw IllegalStateException("未配置 ASR Provider")
}