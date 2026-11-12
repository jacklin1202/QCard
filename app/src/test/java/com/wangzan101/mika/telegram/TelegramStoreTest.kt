package com.wangzan101.mika.telegram

import java.io.File
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.settings.KeyCipher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TelegramStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = object : KeyCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(data: ByteArray): ByteArray = data
    }
    private val bot = TelegramBot("token", 42, "qcard_test_bot")

    private fun boundStore(dir: java.io.File): TelegramStore {
        val store = TelegramStore(dir, cipher)
        store.saveBot(bot)
        store.setEnabled(true)
        val code = store.newBindCode(1_000)
        assertTrue(store.tryBind(555, code, 1_001))
        return store
    }

    @Test fun botBindingOffsetAndPendingSurviveReopen() {
        val dir = tmp.newFolder()
        val first = boundStore(dir)
        val peer = first.peer(555)
        assertTrue(first.journal("100", peer, "do it"))
        assertFalse(first.journal("100", peer, "do it"))
        first.saveOffset(101)

        val reopened = TelegramStore(dir, cipher)
        assertEquals(bot, reopened.bot())
        assertTrue(reopened.enabled())
        assertTrue(reopened.isBound())
        assertEquals(555L, reopened.ownerId())
        assertEquals(101L, reopened.offset())
        assertEquals(peer, reopened.peer(555))
        assertEquals(listOf(PendingTelegramMessage("100", peer, "do it")), reopened.pending())

        reopened.acknowledge("100")
        assertEquals(emptyList<PendingTelegramMessage>(), TelegramStore(dir, cipher).pending())
        assertFalse(reopened.journal("100", peer, "do it"))
    }

    @Test fun bindCodeExpiresAndBindsOnlyOnce() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        val code = store.newBindCode(10_000)
        // 错误码由实际验证码推导，保证不撞（SecureRandom 有百万分之一机会正好生成硬编码的错码）。
        val wrong = if (code == "000000") "000001" else "000000"
        assertFalse(store.tryBind(555, wrong, 10_001))
        assertFalse(store.tryBind(555, code, 10_000 + TelegramStore.BIND_CODE_TTL_MS + 1))
        assertFalse(store.isBound())
        val fresh = store.newBindCode(20_000)
        assertTrue(store.tryBind(555, fresh, 20_001))
        assertTrue(store.isBound())
        // Already bound: a second code does nothing, even a fresh valid one.
        val another = store.newBindCode(30_000)
        assertFalse(store.tryBind(777, another, 30_001))
        assertEquals(555L, store.ownerId())
    }

    @Test fun changingBotResetsBindingButTokenRotationKeepsIt() {
        val dir = tmp.newFolder()
        val store = boundStore(dir)
        val peer = store.peer(555)
        assertTrue(store.journal("100", peer, "do it"))
        store.saveOffset(101)

        store.saveBot(TelegramBot("rotated-token", 42, "qcard_test_bot"))
        assertTrue(store.isBound())
        assertEquals(101L, store.offset())
        assertEquals(1, store.pending().size)

        store.saveBot(TelegramBot("other-token", 99, "other_bot"))
        assertFalse(store.isBound())
        assertEquals(0L, store.offset())
        assertEquals(emptyList<PendingTelegramMessage>(), store.pending())
    }

    @Test fun bindFailuresThrottlePerUserAndAtomicAckIsRecorded() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        val code = store.newBindCode(0)
        // One source burns its own attempts...
        val wrong = if (code == "999999") "999998" else "999999"
        repeat(TelegramStore.MAX_BIND_FAILURES) { assertFalse(store.tryBind(555, wrong, 1)) }
        // ...and is then refused even with the right code, while the code itself stays alive:
        assertFalse(store.tryBind(555, code, 2))
        assertFalse(store.isBound())
        // A different source can still bind, and the bind update is recorded in the same write.
        assertTrue(store.tryBind(777, code, 3, messageId = "tg42-100"))
        assertTrue(store.isBound())
        assertTrue(store.hasMessage("tg42-100"))
        assertEquals(emptyList<PendingTelegramMessage>(), store.pending())
    }

    @Test fun orphanedStateWithoutCredentialDoesNotResurrectOnResave() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        assertTrue(first.journal("tg42-7", first.peer(555), "secret task"))
        // Simulate clear() being killed after the credential delete but before the state wipe.
        assertTrue(java.io.File(dir, "bot.enc").delete())
        val reopened = TelegramStore(dir, cipher)
        assertNull(reopened.bot())
        assertTrue(reopened.isBound()) // the leftover hazard is really on disk
        // Re-saving the same bot must not inherit the orphaned binding or pending messages.
        reopened.saveBot(bot)
        assertFalse(reopened.isBound())
        assertEquals(0L, reopened.ownerId())
        assertTrue(reopened.pending().isEmpty())
    }

    @Test fun credentialStateMismatchReconcilesOnOpen() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        assertTrue(first.journal("tg42-7", first.peer(555), "secret task"))
        // Simulate a bot switch killed after the new credential landed but before the state reset:
        // disk now holds bot 99's credential next to bot 42's state.
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val cred = TelegramCredentials("token-b", 99, "other_bot")
        java.io.File(dir, "bot.enc").writeBytes(
            cipher.encrypt(json.encodeToString(TelegramCredentials.serializer(), cred).toByteArray()))
        val reopened = TelegramStore(dir, cipher)
        assertEquals(TelegramBot("token-b", 99, "other_bot"), reopened.bot())
        assertFalse(reopened.isBound())
        assertTrue(reopened.pending().isEmpty())
    }

    @Test fun corruptStateIsBackedUpNotSilentlyDropped() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        java.io.File(dir, "state.json").writeText("{not json")
        val reopened = TelegramStore(dir, cipher)
        assertFalse(reopened.isBound())
        assertEquals(bot, reopened.bot()) // the credential survives; only the state was reset
        val backups = dir.listFiles()?.filter { it.name.startsWith("state.json.corrupt-") }.orEmpty()
        assertEquals(1, backups.size)
        assertEquals("{not json", backups.single().readText())
    }

    @Test fun orphanStateWithZeroBotIdIsResetOnSaveBot() {
        val dir = tmp.newFolder()
        // Leftover state whose botId is 0 but which still carries an owner binding,
        // with no credential anywhere to back it.
        java.io.File(dir, "state.json").writeText("{\"ownerId\":555,\"threads\":{\"555\":\"telegram-x\"}}")
        val store = TelegramStore(dir, cipher)
        assertTrue(store.isBound()) // the orphan really is on disk
        store.saveBot(bot)
        assertFalse(store.isBound())
        assertEquals(0L, store.ownerId())
        assertTrue(store.pending().isEmpty())
        assertEquals(bot, store.bot())
    }

    @Test fun clearRemovesCorruptBackupsToo() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        java.io.File(dir, "state.json").writeText("{broken")
        val reopened = TelegramStore(dir, cipher)
        assertEquals(1, dir.listFiles()?.count { it.name.startsWith("state.json.corrupt-") })
        reopened.clear()
        assertEquals(0, dir.listFiles()?.count { it.name.startsWith("state.json.corrupt-") } ?: -1)
    }

    @Test fun reconcileResetAlsoDeletesCorruptBackups() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        java.io.File(dir, "state.json.corrupt-1").writeText("old secrets")
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; explicitNulls = false }
        val cred = TelegramCredentials("token-b", 99, "other_bot")
        java.io.File(dir, "bot.enc").writeBytes(
            cipher.encrypt(json.encodeToString(TelegramCredentials.serializer(), cred).toByteArray()))
        TelegramStore(dir, cipher)
        assertEquals(0, dir.listFiles()?.count { it.name.startsWith("state.json.corrupt-") } ?: -1)
    }

    @Test fun saveBotRollsBackWhenCredentialWriteFails() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        val sabotage = object : KeyCipher {
            override fun encrypt(plain: ByteArray): ByteArray =
                if (plain.decodeToString().contains("token-b")) throw java.io.IOException("boom") else plain
            override fun decrypt(data: ByteArray): ByteArray = data
        }
        val second = TelegramStore(dir, sabotage)
        try {
            second.saveBot(TelegramBot("token-b", 99, "other_bot"))
            throw AssertionError("saveBot should have thrown")
        } catch (expected: java.io.IOException) {
        }
        // In-memory and on-disk state both still belong to the old bot.
        assertEquals(bot, second.bot())
        assertTrue(second.isBound())
        val reopened = TelegramStore(dir, cipher)
        assertEquals(bot, reopened.bot())
        assertTrue(reopened.isBound())
        assertEquals(555L, reopened.ownerId())
    }

    @Test fun unreadableCredentialBlocksSaveBotInsteadOfResetting() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        val goodEnc = java.io.File(dir, "bot.enc").readBytes()
        java.io.File(dir, "bot.enc").writeText("garbage")
        val second = TelegramStore(dir, cipher)
        assertNull(second.bot())
        try {
            second.saveBot(bot)
            throw AssertionError("saveBot should have refused")
        } catch (expected: IllegalStateException) {
        }
        java.io.File(dir, "bot.enc").writeBytes(goodEnc)
        val reopened = TelegramStore(dir, cipher)
        assertTrue(reopened.isBound())
        assertEquals(555L, reopened.ownerId())
    }

    @Test fun clearResetsCredentialLoadFailureSoNewBotCanBeSaved() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        java.io.File(dir, "bot.enc").writeText("garbage")
        val second = TelegramStore(dir, cipher)
        assertNull(second.bot())
        second.clear()
        // Saving a fresh bot after clearing the damaged credential must not be refused.
        second.saveBot(bot)
        assertEquals(bot, second.bot())
    }

    @Test fun dropPendingRemovesAndLedgers() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        store.setEnabled(true)
        val code = store.newBindCode(0)
        assertTrue(store.tryBind(555, code, 1))
        val peer = store.peer(555)
        assertTrue(store.journal("tg42-9", peer, "stuck"))
        store.dropPending("tg42-9", expectedBot = TelegramBot("other", 99, "other_bot"))
        assertEquals(1, store.pending().size) // wrong bot: no-op
        store.dropPending("tg42-9", expectedBot = bot)
        assertTrue(store.pending().isEmpty())
        assertTrue(store.hasMessage("tg42-9")) // ledgered, never returns
    }

    @Test fun emptyCredentialFileCountsAsNoCredential() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        File(dir, "bot.enc").writeBytes(ByteArray(0)) // clear() 清空后删不掉的残档形态
        val reopened = TelegramStore(dir, cipher)
        assertNull(reopened.bot())
        reopened.saveBot(TelegramBot("tok2", 43, "next_bot")) // 不得被「读取失败」挡下
        assertEquals(43L, reopened.bot()?.botId)
    }

    @Test fun corruptStateBackupSurvivesReanchorToCredentialBot() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(TelegramBot("tok99", 99, "other_bot"))
        first.setEnabled(true)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        File(dir, "state.json").writeText("{ not json")
        val reopened = TelegramStore(dir, cipher)
        assertEquals(99L, reopened.bot()?.botId)
        // 坏档无法判读属于哪个 Bot：取证备份保留（已知事项），等 clear() 一并清。
        assertTrue(dir.listFiles()?.any { it.name.startsWith("state.json.corrupt-") } == true)
    }

    @Test fun crashedBotSwapRestoresPreviousBotState() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot) // bot 42
        first.setEnabled(true)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        val peer = first.peer(555)
        assertTrue(first.journal("tg42-7", peer, "do not lose me"))
        // 模拟 saveBot(新 Bot) 写到一半进程死亡：state 已是新 Bot 的空状态、旁边留着
        // 旧状态备份、凭证却还是旧 Bot。
        File(dir, "state.json").copyTo(File(dir, "state.json.swapbak")) // saveBot 会先留的旧状态备份
        val otherDir = tmp.newFolder()
        val other = TelegramStore(otherDir, cipher)
        other.saveBot(TelegramBot("tok99", 99, "other_bot"))
        File(otherDir, "state.json").copyTo(File(dir, "state.json"), overwrite = true)
        val restored = TelegramStore(dir, cipher)
        assertEquals(42L, restored.bot()?.botId)
        assertTrue(restored.isBound())
        assertEquals(listOf("tg42-7"), restored.pending().map { it.messageId })
    }

    @Test fun staleSwapBackupRemovedWhenStateConsistent() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        File(dir, "state.json.swapbak").writeText("{}")
        val reopened = TelegramStore(dir, cipher)
        assertEquals(42L, reopened.bot()?.botId)
        assertTrue(!File(dir, "state.json.swapbak").exists())
    }

    @Test fun interruptedClearDoesNotResurrectBinding() {
        val dir = tmp.newFolder()
        val first = TelegramStore(dir, cipher)
        first.saveBot(bot)
        first.setEnabled(true)
        val code = first.newBindCode(0)
        assertTrue(first.tryBind(555, code, 1))
        val peer = first.peer(555)
        assertTrue(first.journal("tg42-8", peer, "wiped"))
        // 模拟 clear() 中断在「已写空状态、还没删备份与凭证」：空状态 + 旧备份 + 旧凭证同在。
        File(dir, "state.json").copyTo(File(dir, "state.json.swapbak"), overwrite = true)
        val otherDir = tmp.newFolder()
        TelegramStore(otherDir, cipher).clear() // 产生一份真正的空状态 state.json
        File(otherDir, "state.json").copyTo(File(dir, "state.json"), overwrite = true)
        val reopened = TelegramStore(dir, cipher)
        assertTrue(!reopened.isBound()) // 已清除的绑定不得复活
        assertTrue(reopened.pending().isEmpty())
        assertTrue(!File(dir, "state.json.swapbak").exists())
    }

    @Test fun askMappingSurvivesReopen() {
        val dir = tmp.newFolder()
        val store = TelegramStore(dir, cipher)
        store.saveBot(bot)
        store.rememberAsk("ask-1", 88)
        store.rememberAsk("ask-2", 89)
        val reopened = TelegramStore(dir, cipher)
        assertEquals("ask-1", reopened.askIdForMessage(88))
        assertEquals("ask-2", reopened.askIdForMessage(89))
        assertNull(reopened.askIdForMessage(90))
    }

    @Test fun rememberAskIgnoresStaleBotWrites() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        store.rememberAsk("ask-old", 88, expectedBot = TelegramBot("gone", 77, "gone_bot"))
        assertNull(store.askIdForMessage(88))
        store.rememberAsk("ask-1", 88, expectedBot = bot)
        assertEquals("ask-1", store.askIdForMessage(88))
    }

    @Test fun updatingExistingAskDoesNotEvictOldest() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        repeat(TelegramStore.MAX_ASK_MESSAGES) { i -> store.rememberAsk("ask-$i", 1000L + i) }
        // Updating an existing ask must not push the oldest other mapping out.
        store.rememberAsk("ask-0", 2000)
        assertEquals("ask-0", store.askIdForMessage(2000))
        assertEquals("ask-1", store.askIdForMessage(1001))
    }

    @Test fun acceptsNeedsEnabledBoundOwnerThread() {
        val store = TelegramStore(tmp.newFolder(), cipher)
        store.saveBot(bot)
        val stranger = ChannelPeer(TelegramChannel.ID, "555", "telegram-x")
        assertFalse(store.journal("1", stranger, "hi", expectedBot = bot))
        store.setEnabled(true)
        assertFalse(store.journal("1", stranger, "hi", expectedBot = bot))
        val code = store.newBindCode(0)
        assertTrue(store.tryBind(555, code, 1))
        val peer = store.peer(555)
        assertTrue(store.accepts(peer, bot))
        assertFalse(store.accepts(peer.copy(threadId = "telegram-other"), bot))
        assertFalse(store.accepts(peer.copy(peerId = "777"), bot))
        assertFalse(store.accepts(peer.copy(channelId = "wechat"), bot))
        assertTrue(store.journal("1", peer, "hi", expectedBot = bot))
    }
}
