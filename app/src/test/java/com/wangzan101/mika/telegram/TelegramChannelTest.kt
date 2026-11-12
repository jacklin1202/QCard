package com.wangzan101.mika.telegram

import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.telegram.TelegramApi
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class TelegramChannelTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }

    private fun response(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun updates(body: String) = response("""{"ok":true,"result":[$body]}""")

    private fun message(updateId: Long, messageId: Long, fromId: Long, chatId: Long, chatType: String, text: String, replyTo: Long? = null): String {
        val reply = if (replyTo != null) ""","reply_to_message":{"message_id":$replyTo,"from":{"id":42,"is_bot":true},"chat":{"id":$chatId,"type":"$chatType"}}""" else ""
        return """{"update_id":$updateId,"message":{"message_id":$messageId,"from":{"id":$fromId,"is_bot":false,"first_name":"User"},"chat":{"id":$chatId,"type":"$chatType"},"text":"$text"$reply}}"""
    }

    private class Fixture(val store: TelegramStore, val channel: TelegramChannel, val inputs: MutableList<ChannelInput>)

    private fun fixture(server: MockWebServer, bindOwner: Boolean = true, activeRunId: (String) -> RunId? = { null }): Fixture {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(TelegramBot("token", 42, "qcard_test_bot"))
        store.setEnabled(true)
        if (bindOwner) {
            val code = store.newBindCode(0)
            assertTrue(store.tryBind(555, code, 1))
        }
        val api = TelegramApi(OkHttpClient(), server.url("/").toString())
        val inputs = mutableListOf<ChannelInput>()
        val channel = TelegramChannel(store, api, activeRunId = activeRunId,
            imageBytes = { byteArrayOf(1, 2, 3) }, log = {}, now = { 5_000 })
        channel.start { input -> synchronized(inputs) { inputs += input } }
        return Fixture(store, channel, inputs)
    }

    private fun withServer(block: (MockWebServer) -> Unit) = MockWebServer().use { server ->
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        block(server)
    }

    @Test fun ownerTextBecomesTaskInputAndOffsetAdvances() = withServer { server ->
        val f = fixture(server)
        server.enqueue(updates(message(100, 7, 555, 555, "private", "do it")))
        runBlocking { f.channel.pollOnce() }
        val text = f.inputs.filterIsInstance<ChannelInput.Text>().single()
        assertEquals("do it", text.text)
        assertEquals("tg42-100", text.messageId)
        assertEquals(101L, f.store.offset())
        assertEquals(1, f.store.pending().size)
    }

    @Test fun strangersAndGroupsAreIgnoredButOffsetAdvances() = withServer { server ->
        val f = fixture(server)
        server.enqueue(updates(
            message(100, 7, 777, 777, "private", "stranger") + "," +
            message(101, 8, 555, -1001, "supergroup", "group text")))
        runBlocking { f.channel.pollOnce() }
        assertTrue(f.inputs.isEmpty())
        assertEquals(102L, f.store.offset())
        assertTrue(f.store.pending().isEmpty())
    }

    @Test fun unboundStoreBindsViaCodeAndIgnoresPlainText() = withServer { server ->
        val f = fixture(server, bindOwner = false)
        val code = f.store.newBindCode(4_000)
        server.enqueue(updates(
            message(100, 7, 555, 555, "private", "hello") + "," +
            message(101, 8, 555, 555, "private", "/bind $code")))
        server.enqueue(response("""{"ok":true,"result":{"message_id":50,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        runBlocking { f.channel.pollOnce() }
        assertTrue(f.store.isBound())
        assertEquals(555L, f.store.ownerId())
        assertTrue(f.inputs.isEmpty())
        // Once bound, the owner's next message flows as a task input.
        server.enqueue(updates(message(102, 9, 555, 555, "private", "now work")))
        runBlocking { f.channel.pollOnce() }
        assertEquals("now work", f.inputs.filterIsInstance<ChannelInput.Text>().single().text)
    }

    @Test fun replyToAskRoutesToAskId() = withServer { server ->
        val f = fixture(server)
        val peer = f.store.peer(555)
        server.enqueue(response("""{"ok":true,"result":{"message_id":88,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        val outcome = runBlocking { f.channel.send(peer, Delivery.Ask("d1", peer.threadId, "ask-1", Delivery.Ask.Kind.Input, "which one?")) }
        assertEquals(SendOutcome.Delivered, outcome)
        server.enqueue(updates(message(100, 9, 555, 555, "private", "the second", replyTo = 88)))
        runBlocking { f.channel.pollOnce() }
        val text = f.inputs.filterIsInstance<ChannelInput.Text>().single()
        assertEquals("ask-1", text.replyToAskId)
        assertTrue(text.requireMatchingAsk)
    }

    @Test fun stopEmitsStopInputOnlyWhenRunActive() = withServer { server ->
        val active = fixture(server, activeRunId = { RunId("run-9") })
        server.enqueue(updates(message(100, 7, 555, 555, "private", "/stop")))
        runBlocking { active.channel.pollOnce() }
        val stop = active.inputs.filterIsInstance<ChannelInput.Stop>().single()
        assertEquals(RunId("run-9"), stop.runId)

        val idle = fixture(server, activeRunId = { null })
        server.enqueue(updates(message(100, 7, 555, 555, "private", "/stop")))
        server.enqueue(response("""{"ok":true,"result":{"message_id":51,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        runBlocking { idle.channel.pollOnce() }
        assertTrue(idle.inputs.filterIsInstance<ChannelInput.Stop>().isEmpty())
    }

    @Test fun boundOwnerBindTextIsAcknowledgedNotJournaled() = withServer { server ->
        val f = fixture(server)
        server.enqueue(updates(message(100, 7, 555, 555, "private", "/bind 123456")))
        runBlocking { f.channel.pollOnce() }
        assertTrue(f.inputs.isEmpty())
        assertTrue(f.store.pending().isEmpty())
        assertTrue(f.store.hasMessage("tg42-100"))
    }

    @Test fun staleAcknowledgeFromPreviousBotIsIgnored() = withServer { server ->
        val f = fixture(server)
        val peer = f.store.peer(555)
        assertTrue(f.store.journal("tg42-100", peer, "do it"))
        // The bot is replaced; a late ack carrying the old bot's scope must not touch the new ledger.
        f.store.saveBot(TelegramBot("other-token", 99, "other_bot"))
        f.channel.acknowledge("tg42-100")
        // journal() in the old scope is gone with the reset state, and the ack added nothing anywhere:
        assertTrue(f.store.pending().isEmpty())
        assertTrue(!f.store.hasMessage("tg42-100"))
    }

    @Test fun stopWithBotSuffixStopsAndBindLikeTextJournals() = withServer { server ->
        val f = fixture(server, activeRunId = { RunId("run-9") })
        // /stop@botname is still a stop command.
        server.enqueue(updates(message(100, 7, 555, 555, "private", "/stop@qcard_test_bot")))
        runBlocking { f.channel.pollOnce() }
        val stop = f.inputs.single() as ChannelInput.Stop
        assertEquals(RunId("run-9"), stop.runId)
        // A task whose text merely starts with /bind letters is a task, not a re-bind.
        server.enqueue(updates(message(101, 7, 555, 555, "private", "/binding of isaac 心得")))
        runBlocking { f.channel.pollOnce() }
        assertEquals("/binding of isaac 心得", f.store.pending().single().text)
    }

    @Test fun askSendStaysDeliveredWhenLocalMappingWriteFails() = withServer { server ->
        val dir = tmp.newFolder()
        val store = TelegramStore(dir, cipher)
        store.saveBot(TelegramBot("token", 42, "qcard_test_bot"))
        store.setEnabled(true)
        val bindCode = store.newBindCode(0)
        assertTrue(store.tryBind(555, bindCode, 1))
        val peer = store.peer(555)
        val api = TelegramApi(OkHttpClient(), server.url("/").toString())
        val channel = TelegramChannel(store, api, activeRunId = { null },
            imageBytes = { byteArrayOf(1) }, log = {}, now = { 5_000 })
        server.enqueue(response("""{"ok":true,"result":{"message_id":88,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"},"text":"which one?"}}"""))
        // Break local persistence: replace the store directory with a plain file,
        // so rememberAsk's state write fails after the API send has succeeded.
        dir.deleteRecursively()
        java.io.File(dir.path).writeText("blocked")
        try {
            val outcome = runBlocking {
                channel.send(peer, Delivery.Ask("d1", peer.threadId, "ask-1", Delivery.Ask.Kind.Input, "which one?"))
            }
            assertEquals(SendOutcome.Delivered, outcome)
        } finally {
            java.io.File(dir.path).delete()
        }
    }

    @Test fun imageSendReturnsFailedWhenLocalReadThrows() = withServer { server ->
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(TelegramBot("token", 42, "qcard_test_bot"))
        store.setEnabled(true)
        val bindCode = store.newBindCode(0)
        assertTrue(store.tryBind(555, bindCode, 1))
        val peer = store.peer(555)
        val api = TelegramApi(OkHttpClient(), server.url("/").toString())
        val channel = TelegramChannel(store, api, activeRunId = { null },
            imageBytes = { throw java.io.IOException("gone") }, log = {}, now = { 5_000 })
        val outcome = runBlocking {
            channel.send(peer, Delivery.Image("d2", peer.threadId, "content://exports/shot.png", "shot.png", 3))
        }
        assertEquals(SendOutcome.Failed, outcome)
    }

    @Test fun drainKeepsPendingWhenChannelDisabledMidDrain() = withServer { server ->
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(TelegramBot("token", 42, "qcard_test_bot"))
        store.setEnabled(true)
        val bindCode = store.newBindCode(0)
        assertTrue(store.tryBind(555, bindCode, 1))
        val peer = store.peer(555)
        assertTrue(store.journal("tg42-1", peer, "first"))
        assertTrue(store.journal("tg42-2", peer, "second"))
        val received = mutableListOf<ChannelInput>()
        val api = TelegramApi(OkHttpClient(), server.url("/").toString())
        val channel = TelegramChannel(store, api, activeRunId = { null }, log = {}, now = { 5_000 })
        val sink = ChannelInputSink { input ->
            received += input
            store.setEnabled(false) // 使用者在补交途中关掉渠道：第二笔必须保留，不能被丢弃
        }
        channel.start(sink)
        runBlocking { channel.pollOnce() }
        assertEquals(1, received.size)
        // 第一笔已分派但没人确认（at-least-once 本来就会留着），第二笔在中途停用后必须保留、不能被丢弃。
        assertEquals(listOf("tg42-1", "tg42-2"), store.pending().map { it.messageId })
    }

    @Test fun resultAndImageSendThroughBotApi() = withServer { server ->
        val f = fixture(server)
        val peer = f.store.peer(555)
        server.enqueue(response("""{"ok":true,"result":{"message_id":60,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        assertEquals(SendOutcome.Delivered, runBlocking { f.channel.send(peer, Delivery.Result("d1", peer.threadId, "done")) })
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)?.path?.endsWith("/sendMessage") == true)
        server.enqueue(response("""{"ok":true,"result":{"message_id":61,"from":{"id":42,"is_bot":true},"chat":{"id":555,"type":"private"}}}"""))
        val image = Delivery.Image("d2", peer.threadId, "content://exports/shot.png", "shot.png", 3)
        assertEquals(SendOutcome.Delivered, runBlocking { f.channel.send(peer, image) })
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS)?.path?.endsWith("/sendPhoto") == true)
    }
}
