package com.wangzan101.mika.core.telegram

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class TelegramApiTest {
    private fun server(block: (MockWebServer, TelegramApi) -> Unit) {
        MockWebServer().use { server ->
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            val api = TelegramApi(OkHttpClient(), server.url("/").toString())
            block(server, api)
        }
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    @Test fun getMeParsesBotIdentity() = server { server, api ->
        server.enqueue(response("""{"ok":true,"result":{"id":42,"is_bot":true,"first_name":"QCard Test","username":"qcard_test_bot"}}"""))
        val me = runBlocking { api.getMe("token") }
        assertEquals(42L, me.id)
        assertEquals("qcard_test_bot", me.username)
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)?.path?.endsWith("/getMe") == true)
    }

    @Test fun getUpdatesParsesPrivateTextReplyAndSkipsGroupText() = server { server, api ->
        server.enqueue(response("""{"ok":true,"result":[
          {"update_id":100,"message":{"message_id":7,"from":{"id":555,"is_bot":false,"first_name":"Owner"},
            "chat":{"id":555,"type":"private"},"text":"reply text",
            "reply_to_message":{"message_id":6,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"},"text":"question?"}}},
          {"update_id":101,"message":{"message_id":8,"from":{"id":777,"is_bot":false},
            "chat":{"id":-1001,"type":"supergroup"},"text":"group text"}}
        ]}"""))
        val updates = runBlocking { api.getUpdates("token", 99, 0) }
        assertEquals(2, updates.size)
        assertEquals("reply text", updates[0].message?.userText())
        assertEquals(6L, updates[0].message?.replyTo?.messageId)
        assertNull(updates[1].message?.userText())
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        val body = Json.parseToJsonElement(request?.body?.readUtf8().orEmpty()).jsonObject
        assertEquals(99L, body.getValue("offset").jsonPrimitive.content.toLong())
        assertEquals("[\"message\"]", body.getValue("allowed_updates").toString())
    }

    @Test fun apiErrorThrowsWithDescription() = server { server, api ->
        // Telegram delivers API errors as non-2xx responses that still carry the JSON envelope.
        server.enqueue(MockResponse().setResponseCode(401).setHeader("Content-Type", "application/json")
            .setBody("""{"ok":false,"error_code":401,"description":"Unauthorized"}"""))
        try {
            runBlocking { api.getMe("bad") }
            fail("expected TelegramApiException")
        } catch (e: TelegramApiException) {
            assertEquals(401, e.code)
            assertEquals("Unauthorized", e.message)
        }
    }

    @Test fun httpErrorThrows() = server { server, api ->
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        try {
            runBlocking { api.getMe("token") }
            fail("expected TelegramHttpException")
        } catch (e: TelegramHttpException) {
            assertEquals(500, e.httpCode)
        }
    }

    @Test fun httpErrorWithForeignJsonBodyStaysHttpException() = server { server, api ->
        // A proxy-style JSON error without Telegram's `ok` field is an HTTP error, not an API error.
        server.enqueue(MockResponse().setResponseCode(502).setHeader("Content-Type", "application/json")
            .setBody("{\"error\":\"bad gateway\"}"))
        try {
            runBlocking { api.getMe("token") }
            fail("expected TelegramHttpException")
        } catch (e: TelegramHttpException) {
            assertEquals(502, e.httpCode)
        }
    }

    @Test fun sendMessageSendsJsonAndReturnsId() = server { server, api ->
        server.enqueue(response("""{"ok":true,"result":{"message_id":88,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"},"text":"done"}}"""))
        val sent = runBlocking { api.sendMessage("token", 555, "done", replyToMessageId = 7) }
        assertEquals(88L, sent.messageId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        val body = Json.parseToJsonElement(request?.body?.readUtf8().orEmpty()).jsonObject
        assertEquals("555", body.getValue("chat_id").jsonPrimitive.content)
        assertEquals("done", body.getValue("text").jsonPrimitive.content)
        assertEquals("7", body.getValue("reply_to_message_id").jsonPrimitive.content)
    }

    @Test fun longTextSplitsIntoOrderedChunks() = server { server, api ->
        val text = (1..200).joinToString("\n") { "line-$it" }
        val chunks = TelegramText.split(text, limit = 200)
        assertTrue(chunks.size > 3)
        assertTrue(chunks.all { it.length <= 200 })
        assertEquals(text.replace("\n", ""), chunks.joinToString("").replace("\n", "").replace(" ", ""))
        repeat(2) {
            server.enqueue(response("""{"ok":true,"result":{"message_id":${90 + it},"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        }
        val long = "a".repeat(4500)
        val last = runBlocking { api.sendLongText("token", 555, long) }
        assertEquals(91L, last.messageId)
        assertEquals(2, server.requestCount)
    }

    @Test fun splitPrefersNewlineBoundary() {
        val text = "x".repeat(90) + "\n" + "y".repeat(90)
        val chunks = TelegramText.split(text, limit = 100)
        assertEquals(2, chunks.size)
        assertEquals(90, chunks[0].length)
        assertEquals(90, chunks[1].length)
    }

    @Test fun sendPhotoMultipartContainsChatAndFile() = server { server, api ->
        server.enqueue(response("""{"ok":true,"result":{"message_id":99,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        val sent = runBlocking { api.sendPhoto("token", 555, byteArrayOf(1, 2, 3), "shot.jpg", "image/jpeg".toMediaType(), caption = "cap") }
        assertEquals(99L, sent.messageId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        val bodyText = request?.body?.readUtf8().orEmpty()
        assertTrue(bodyText.contains("chat_id"))
        assertTrue(bodyText.contains("555"))
        assertTrue(bodyText.contains("shot.jpg"))
        assertTrue(request?.getHeader("Content-Type")?.startsWith("multipart/form-data") == true)
    }

    @Test fun splitNeverBreaksSurrogatePairs() {
        val text = "\uD83D\uDE00".repeat(50) // 100 UTF-16 units of emoji
        val chunks = TelegramText.split(text, limit = 51)
        assertTrue(chunks.size >= 2)
        for (chunk in chunks) {
            val chars = chunk.toCharArray()
            assertTrue(!chars.last().isHighSurrogate())
            assertTrue(!chars.first().isLowSurrogate())
        }
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun splitWhitespaceOnlyLongTextHardChunks() {
        val text = " ".repeat(9001)
        val chunks = TelegramText.split(text)
        assertEquals(3, chunks.size)
        assertTrue(chunks.all { it.length <= TelegramText.MAX_MESSAGE_CHARS })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun splitRejectsNonPositiveLimitAndSkipsBlankChunks() {
        try {
            TelegramText.split("hello", limit = 0)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
        try {
            TelegramText.split("hello", limit = 1)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
        val chunks = TelegramText.split("   \n   word", limit = 4)
        assertTrue(chunks.none { it.isBlank() })
        assertEquals("word", chunks.joinToString(""))
    }
}
