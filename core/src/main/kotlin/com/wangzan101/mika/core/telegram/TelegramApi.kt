package com.wangzan101.mika.core.telegram

import com.wangzan101.mika.core.wechat.awaitWechatResponse
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

private const val DEFAULT_BASE_URL = "https://api.telegram.org"
private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val jsonBody = "application/json; charset=utf-8".toMediaType()

@Serializable
data class TgUser(
    val id: Long = 0,
    @SerialName("is_bot") val isBot: Boolean = false,
    @SerialName("first_name") val firstName: String = "",
    val username: String? = null,
)

@Serializable
data class TgChat(
    val id: Long = 0,
    val type: String = "",
) {
    fun isPrivate() = type == "private"
}

@Serializable
data class TgMessage(
    @SerialName("message_id") val messageId: Long = 0,
    val from: TgUser? = null,
    val chat: TgChat = TgChat(),
    val text: String? = null,
    @SerialName("reply_to_message") val replyTo: TgMessage? = null,
) {
    /** Only a human's non-blank text in a private chat can become a task or an answer. */
    fun userText(): String? {
        if (!chat.isPrivate() || from == null || from.isBot) return null
        return text?.takeIf { it.isNotBlank() }
    }
}

@Serializable
data class TgUpdate(
    @SerialName("update_id") val updateId: Long = 0,
    val message: TgMessage? = null,
)

@Serializable
private data class TgEnvelope<T>(
    // No default on purpose: a JSON object without Telegram's own `ok` field must fail to
    // decode here, so foreign JSON errors (e.g. a proxy's 502 body) stay HTTP errors, not
    // Telegram API errors with code 0.
    val ok: Boolean,
    val result: T? = null,
    @SerialName("error_code") val errorCode: Int = 0,
    val description: String? = null,
)

@Serializable
private data class UpdatesRequest(
    val offset: Long? = null,
    val timeout: Int = 0,
    // No default on purpose: with encodeDefaults=false a field equal to its default is left
    // out of the JSON, and getUpdates must always send allowed_updates explicitly.
    @SerialName("allowed_updates") val allowedUpdates: List<String>,
)

@Serializable
private data class SendMessageRequest(
    @SerialName("chat_id") val chatId: Long,
    val text: String,
    @SerialName("reply_to_message_id") val replyToMessageId: Long? = null,
)

class TelegramApiException(val code: Int, message: String) : IOException(message)
class TelegramHttpException(val httpCode: Int) : IOException("Telegram HTTP $httpCode")

/** Splits outbound text to Telegram's 4096-char message limit, preferring line then word boundaries. */
object TelegramText {
    const val MAX_MESSAGE_CHARS = 4000

    fun split(text: String, limit: Int = MAX_MESSAGE_CHARS): List<String> {
        // At least 2: a surrogate pair is never split, so a limit of 1 could not be honored.
        require(limit >= 2) { "limit must be at least 2" }
        if (text.length <= limit) return listOf(text)
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > limit) {
            val window = rest.substring(0, limit)
            var cut = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(' '))
                .takeIf { it > limit / 2 } ?: limit
            // Never cut inside a UTF-16 surrogate pair (emoji etc.): back off to the pair's
            // start, or take the whole pair when it starts the window.
            if (cut in 1 until rest.length && rest[cut - 1].isHighSurrogate() && rest[cut].isLowSurrogate()) {
                cut = if (cut == 1) 2 else cut - 1
            }
            rest.substring(0, cut).trimEnd().takeIf { it.isNotBlank() }?.let { parts += it }
            rest = rest.substring(cut).trimStart('\n', ' ')
        }
        if (rest.isNotBlank()) parts += rest
        if (parts.isNotEmpty()) return parts
        // Every boundary chunk was blank (e.g. a very long whitespace-only text): hard-cut the
        // original so each returned piece still respects the limit.
        val hard = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + limit, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) {
                end = if (end - i == 1) end + 1 else end - 1
            }
            hard += text.substring(i, end)
            i = end
        }
        return hard
    }
}

/**
 * Telegram Bot API 的 HTTPS 客户端。只负责协议，不持有任务或安卓生命周期。
 * Bot token 按调用传入（比照 WechatApi 的 account 参数），绝不写日志——注意 token 本身在请求 URL 路径里，
 * 因此本类从不记录 URL。长轮询与发送都随调用协程取消（复用 core 模块内的可取消等待工具）。
 */
class TelegramApi(
    private val client: OkHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun getMe(token: String): TgUser =
        executeCancellable(post(token, "getMe", null), TgUser.serializer())

    suspend fun getUpdates(token: String, offset: Long?, timeoutSec: Int): List<TgUpdate> {
        val body = UpdatesRequest(offset, timeoutSec, listOf("message"))
        val payload = json.encodeToString(UpdatesRequest.serializer(), body).toRequestBody(jsonBody)
        val request = Request.Builder().url(url(token, "getUpdates")).post(payload).build()
        return executeCancellable(request, kotlinx.serialization.builtins.ListSerializer(TgUpdate.serializer()))
    }

    suspend fun sendMessage(token: String, chatId: Long, text: String, replyToMessageId: Long? = null): TgMessage {
        val body = SendMessageRequest(chatId, text, replyToMessageId)
        val payload = json.encodeToString(SendMessageRequest.serializer(), body).toRequestBody(jsonBody)
        val request = Request.Builder().url(url(token, "sendMessage")).post(payload).build()
        return executeCancellable(request, TgMessage.serializer())
    }

    /** Sends text of any length as ordered chunks; returns the last chunk's message. */
    suspend fun sendLongText(token: String, chatId: Long, text: String, replyToMessageId: Long? = null): TgMessage {
        val parts = TelegramText.split(text)
        var last: TgMessage? = null
        for ((index, part) in parts.withIndex()) {
            last = sendMessage(token, chatId, part, if (index == 0) replyToMessageId else null)
        }
        return checkNotNull(last)
    }

    suspend fun sendPhoto(token: String, chatId: Long, bytes: ByteArray, fileName: String,
        mediaType: MediaType?, caption: String? = null): TgMessage {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId.toString())
            .apply { if (caption != null) addFormDataPart("caption", caption) }
            .addFormDataPart("photo", fileName, bytes.toRequestBody(mediaType))
            .build()
        val request = Request.Builder().url(url(token, "sendPhoto")).post(body).build()
        return executeCancellable(request, TgMessage.serializer())
    }

    private fun post(token: String, method: String, body: okhttp3.RequestBody?): Request =
        Request.Builder().url(url(token, method)).post(body ?: ByteArray(0).toRequestBody(null)).build()

    private suspend fun <T> executeCancellable(request: Request,
        serializer: KSerializer<T>): T =
        awaitWechatResponse(client, request) { decode(it, serializer) }

    private fun <T> decode(response: Response, serializer: KSerializer<T>): T {
        val text = response.body?.string().orEmpty()
        // Telegram reports API errors as non-2xx WITH a JSON envelope (ok=false, error_code,
        // description); parse it first so callers keep the precise code, and only fall back to a
        // bare HTTP error when the body is not a Telegram envelope at all.
        val envelope = text.takeIf { it.isNotBlank() }?.let {
            runCatching { json.decodeFromString(TgEnvelope.serializer(serializer), it) }.getOrNull()
        }
        if (envelope != null && !envelope.ok) throw TelegramApiException(envelope.errorCode,
            envelope.description ?: "Telegram API error ${envelope.errorCode}")
        if (!response.isSuccessful) throw TelegramHttpException(response.code)
        if (text.isBlank()) throw IOException("Telegram returned an empty response")
        if (envelope == null) throw IOException("Telegram returned an unreadable response")
        return envelope.result ?: throw IOException("Telegram returned no result")
    }

    private fun url(token: String, method: String) = "${baseUrl.trimEnd('/')}/bot$token/$method"
}
