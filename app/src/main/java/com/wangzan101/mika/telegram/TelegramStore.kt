package com.wangzan101.mika.telegram

import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.settings.KeyCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.UUID

@Serializable
data class PendingTelegramMessage(
    val messageId: String,
    val peer: ChannelPeer,
    val text: String,
    val replyToAskId: String? = null,
)

@Serializable
private data class TelegramState(
    val enabled: Boolean = false,
    val botId: Long = 0,
    val botUsername: String = "",
    val ownerId: Long = 0,
    val updateOffset: Long = 0,
    val threads: Map<String, String> = emptyMap(),
    val pending: List<PendingTelegramMessage> = emptyList(),
    /** 按确认先后保留最近一段，offset 回退或进程恢复时也不会再次登记同一消息。 */
    val acknowledgedMessages: List<String> = emptyList(),
    /** askId -> 已发送提问的 Telegram message_id；入站回复靠它找回 askId。 */
    val askMessages: Map<String, Long> = emptyMap(),
)

@Serializable
internal data class TelegramCredentials(val token: String, val botId: Long = 0, val botUsername: String = "")

/** 已验证的 Bot 身份；token 只从加密凭证读出，绝不写日志或明文落盘。 */
data class TelegramBot(val token: String, val botId: Long, val username: String)

/** 凭证与运行状态分开保存：令牌加密，游标和未交任务的消息原子落盘（镜像 WechatStore）。 */
class TelegramStore(
    private val dir: File,
    private val cipher: KeyCipher,
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val credentials = File(dir, "bot.enc")
    private val stateFile = File(dir, "state.json")
    private val swapBackup = File(dir, "state.json.swapbak")
    // state.json 连读都读不到时置位：原档可能完好，保存前必须先把它备份下来，否则不许覆盖。
    @Volatile private var stateLoadFailed = false
    // 凭证档存在却读不出（IO/解密失败）时置位：不能把「暂时读不到」当成「没有凭证」去重置状态。
    @Volatile private var credentialsLoadFailed = false
    @Volatile private var storedCredentials: TelegramCredentials? = readCredentials()
    @Volatile private var token: String? = storedCredentials?.token
    private var state = reconcile(readState())

    /**
     * 凭证与状态分属两个文件，换 Bot 写一半被中断时会对不起来：以凭证为准重置状态
     * （凭证代表较新的意图；旧 state 里的绑定与 pending 属于另一个 Bot，绝不能继承）。
     * 旧版凭证没有 botId（=0）时信任 state —— 那是同一个 Bot 的延续。
     */
    private fun reconcile(loaded: TelegramState): TelegramState {
        val cred = storedCredentials ?: return loaded
        if (swapBackup.isFile) {
            val backup = runCatching {
                json.decodeFromString(TelegramState.serializer(), swapBackup.readText())
            }.getOrNull()
            when {
                // 换 Bot 写到一半进程死亡：凭证还是旧 Bot、state 已是新 Bot 的空状态，
                // 而备份正是旧 Bot 的完整状态 —— 恢复它，旧绑定与 pending 不丢，换 Bot 由用户重试。
                cred.botId != 0L && loaded.botId != 0L && loaded.botId != cred.botId && backup?.botId == cred.botId -> {
                    saveState(backup)
                    swapBackup.delete()
                    return backup
                }
                // 状态与凭证已一致（或备份对不上）：备份是旧账，清掉即可。
                else -> swapBackup.delete()
            }
        }
        if (cred.botId == 0L || cred.botId == loaded.botId) return loaded
        val fresh = TelegramState(botId = cred.botId, botUsername = cred.botUsername)
        saveState(fresh)
        // 残留状态真的属于另一个 Bot 时才连坏档备份一起清；loaded.botId==0 是同 Bot 的
        // 空状态或读坏后的恢复状态，它的坏档备份是本 Bot 的取证副本，留给 clear() 决定。
        if (loaded.botId != 0L) deleteCorruptBackups()
        return fresh
    }

    // 绑定码只在内存里活十分钟，不落盘；进程重启即作废，设置页重新生成即可。
    @Volatile private var bindCode: String? = null
    @Volatile private var bindCodeExpiry: Long = 0
    // 猜错次数按来源用户分别计算（另有全域总上限兜底分布式猜测）；不落盘，与验证码同生共死。
    @Volatile private var bindFailuresByUser: MutableMap<Long, Int> = mutableMapOf()
    @Volatile private var bindFailuresTotal: Int = 0
    private val secureRandom = SecureRandom()

    @Synchronized fun bot(): TelegramBot? {
        val current = token ?: return null
        if (state.botId == 0L) return null
        return TelegramBot(current, state.botId, state.botUsername)
    }

    @Synchronized fun hasToken() = token != null

    @Synchronized fun saveBot(value: TelegramBot) {
        // 凭证档读不出来时拒绝保存：把暂时性失败当孤儿状态重置，会毁掉仍然有效的绑定。
        if (credentialsLoadFailed) throw IllegalStateException("stored bot credential is unreadable; refusing to overwrite it")
        // 保存凭证时先作废任何未用完的绑定码：它属于旧凭证语境，绝不能拿来绑定新 Bot。
        bindCode = null
        bindCodeExpiry = 0
        bindFailuresByUser = mutableMapOf()
        bindFailuresTotal = 0
        val hadCredential = storedCredentials != null
        // 换了另一个 Bot、或旧状态根本没有凭证支撑（孤儿状态，哪怕它自己的 botId 是 0），
        // 旧主人的绑定、游标与账本一律作废；同 Bot 换令牌则保留状态。
        val resetting = !hadCredential || state.botId != value.botId
        val base = if (resetting) TelegramState() else state
        val next = base.copy(botId = value.botId, botUsername = value.username)
        // 两步落盘做成可回滚的交易：任一步抛错都把磁盘 state 恢复成旧值、异常向上抛，
        // 换 Bot 失败时旧配置（凭证+状态+内存）完整保留；两个都成功才更新内存。
        // （writeAtomic 本身是 tmp+原子替换，写失败不会弄坏它要覆盖的旧档。）
        val previous = state
        try {
            // 换 Bot 重置前先把旧 state 原样备份：进程若在「写新 state → 写新凭证」之间死亡，
            // 磁盘会剩下「旧凭证 + 新 Bot 空状态」，reconcile 靠这份备份把旧状态恢复回来。
            if (resetting && stateFile.isFile) writeAtomic(swapBackup, stateFile.readBytes())
            if (next != state) saveState(next)
            val cred = TelegramCredentials(value.token, value.botId, value.username)
            writeAtomic(credentials, cipher.encrypt(json.encodeToString(TelegramCredentials.serializer(), cred).toByteArray()))
            storedCredentials = cred
            token = value.token
        } catch (e: Exception) {
            val rollback = runCatching { saveState(previous) }
            if (rollback.isFailure) {
                throw IllegalStateException(
                    "saveBot failed and its rollback failed too; on-disk telegram state may be inconsistent", e,
                ).apply { addSuppressed(rollback.exceptionOrNull()!!) }
            }
            throw e
        }
        state = next
        if (resetting) {
            swapBackup.delete() // 两步都落盘完成：崩溃备份使命结束
            deleteCorruptBackups()
        }
    }

    @Synchronized fun clear() {
        swapBackup.delete() // 先删崩溃备份：清除开始后，任何中断都不能让旧状态借备份复活
        // 先把空状态落盘成功，再清内存与凭证：saveState 若因备份保护抛错，内存与磁盘都维持
        // 原状，不会出现「内存已清、磁盘复活」的半清状态。
        // 先把空状态落盘再删凭证：反序的话，进程在两步之间被杀会留下「有绑定、无凭证」的残局，
        // 日后重存同一 Bot 时旧主人会复活。删凭证放最后，残留的只有无害的过期凭证配空状态。
        val empty = TelegramState()
        saveState(empty)
        state = empty
        token = null
        storedCredentials = null
        bindCode = null
        bindCodeExpiry = 0
        bindFailuresByUser = mutableMapOf()
        bindFailuresTotal = 0
        if (credentials.exists() && !credentials.delete()) {
            // 删不掉就覆写成空档再删：重启时解密失败即视为无凭证，旧令牌不会被套回来。
            runCatching { writeAtomic(credentials, ByteArray(0)) }
            if (credentials.exists() && credentials.length() > 0L && !credentials.delete()) {
                throw java.io.IOException("telegram credential file could not be removed")
            }
        }
        // 凭证已确实清除，加载失败旗标随之作废：不清掉的话，存新 Bot 会被误拒到重启为止。
        credentialsLoadFailed = false
        swapBackup.delete()
        deleteCorruptBackups()
    }

    /** 坏档备份含旧主人与 pending 明文；身份清除或重置时必须一并删掉，不能残留。 */
    private fun deleteCorruptBackups() {
        runCatching {
            dir.listFiles()?.filter { it.name.startsWith(stateFile.name + ".corrupt-") }
                ?.forEach { runCatching { it.delete() } }
        }
    }

    private fun corruptBackupFile() =
        File(stateFile.parentFile, stateFile.name + ".corrupt-" + System.currentTimeMillis())

    @Synchronized fun enabled() = state.enabled
    @Synchronized fun setEnabled(value: Boolean) {
        val next = state.copy(enabled = value)
        saveState(next)
        state = next
    }

    @Synchronized fun ownerId() = state.ownerId
    @Synchronized fun isBound() = state.ownerId != 0L

    @Synchronized fun newBindCode(nowMillis: Long): String {
        // Locale.US 保底 ASCII 数字：某些装置的默认 locale 会把 %d 输出成非 ASCII 数字，用户没法在 Telegram 里输入。
        val code = String.format(java.util.Locale.US, "%06d", secureRandom.nextInt(1_000_000))
        bindCode = code
        bindCodeExpiry = nowMillis + BIND_CODE_TTL_MS
        bindFailuresByUser = mutableMapOf()
        bindFailuresTotal = 0
        return code
    }

    /**
     * 只有未绑定时有效；绑定成功即消费掉验证码。传入 [messageId] 时同一次落盘把该消息记为已处理：
     * 进程在绑定后、游标推进前死掉，消息重送时也不会被当成任务文字（渠道层另有 /bind 忽略兜底）。
     * 猜错按来源限流、全域总量达上限才作废当前验证码，防暴力尝试又不让单个陌生人锁死主人。
     */
    @Synchronized fun tryBind(userId: Long, code: String, nowMillis: Long, messageId: String? = null): Boolean {
        if (isBound() || userId == 0L) return false
        val current = bindCode ?: return false
        if (nowMillis > bindCodeExpiry) return false
        // 单一来源猜错达上限后，该来源在本码有效期内直接拒绝，不再比对（主人换个来源仍可绑定）。
        if ((bindFailuresByUser[userId] ?: 0) >= MAX_BIND_FAILURES) return false
        if (code.trim() != current) {
            if (bindFailuresByUser.size >= MAX_BIND_FAILURE_USERS) bindFailuresByUser.remove(bindFailuresByUser.keys.first())
            bindFailuresByUser[userId] = (bindFailuresByUser[userId] ?: 0) + 1
            bindFailuresTotal++
            if (bindFailuresTotal >= MAX_BIND_FAILURES_TOTAL) {
                bindCode = null
                bindCodeExpiry = 0
            }
            return false
        }
        val next = state.copy(
            ownerId = userId,
            acknowledgedMessages = if (messageId == null) state.acknowledgedMessages else
                (state.acknowledgedMessages.filterNot { it == messageId } + messageId).takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        // 先落盘成功再消费验证码：落盘失败时内存与磁盘保持一致，同一条 /bind 可以重试。
        saveState(next)
        state = next
        bindCode = null
        bindCodeExpiry = 0
        bindFailuresByUser = mutableMapOf()
        bindFailuresTotal = 0
        return true
    }

    @Synchronized fun offset() = state.updateOffset
    @Synchronized fun saveOffset(value: Long, expectedBot: TelegramBot? = null) {
        if (expectedBot != null && (bot() != expectedBot || !state.enabled)) return
        if (value <= state.updateOffset) return
        val next = state.copy(updateOffset = value)
        saveState(next)
        state = next
    }

    @Synchronized fun accepts(peer: ChannelPeer, expectedBot: TelegramBot? = null): Boolean {
        val current = bot() ?: return false
        return (expectedBot == null || expectedBot == current) && state.enabled && isBound() &&
            peer.channelId == TelegramChannel.ID && peer.peerId == state.ownerId.toString() &&
            state.threads[peer.peerId] == peer.threadId
    }

    @Synchronized fun hasMessage(messageId: String) =
        messageId in state.acknowledgedMessages || state.pending.any { it.messageId == messageId }

    @Synchronized fun peer(chatId: Long): ChannelPeer {
        val key = chatId.toString()
        val thread = state.threads[key] ?: "telegram-${UUID.randomUUID()}".also {
            val next = state.copy(threads = state.threads + (key to it))
            saveState(next)
            state = next
        }
        return ChannelPeer(TelegramChannel.ID, key, thread)
    }

    /** 同一消息只入站一次；未确认的会在下次启动由 [pending] 补交。 */
    @Synchronized fun journal(messageId: String, peer: ChannelPeer, text: String, replyToAskId: String? = null,
        expectedBot: TelegramBot? = null): Boolean {
        if (expectedBot != null && !accepts(peer, expectedBot)) return false
        if (hasMessage(messageId)) return false
        val next = state.copy(pending = state.pending + PendingTelegramMessage(messageId, peer, text, replyToAskId))
        saveState(next)
        state = next
        return true
    }

    @Synchronized fun pending(): List<PendingTelegramMessage> = state.pending

    /** 丢弃一笔永远无法再被接受的 pending（会话已失效）：移出队列并记入已确认账本，避免每轮重复检查。 */
    @Synchronized fun dropPending(messageId: String, expectedBot: TelegramBot? = null) {
        if (expectedBot != null && bot() != expectedBot) return
        if (state.pending.none { it.messageId == messageId }) return
        val next = state.copy(
            pending = state.pending.filterNot { it.messageId == messageId },
            acknowledgedMessages = (state.acknowledgedMessages.filterNot { it == messageId } + messageId)
                .takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        saveState(next)
        state = next
    }

    @Synchronized fun acknowledge(messageId: String, expectedBot: TelegramBot? = null) {
        if (expectedBot != null && bot() != expectedBot) return
        if (messageId in state.acknowledgedMessages && state.pending.none { it.messageId == messageId }) return
        val next = state.copy(
            pending = state.pending.filterNot { it.messageId == messageId },
            acknowledgedMessages = (state.acknowledgedMessages.filterNot { it == messageId } + messageId)
                .takeLast(MAX_ACKNOWLEDGED_MESSAGES),
        )
        saveState(next)
        state = next
    }

    @Synchronized fun rememberAsk(askId: String, telegramMessageId: Long, expectedBot: TelegramBot? = null) {
        if (expectedBot != null && bot() != expectedBot) return
        val entries = state.askMessages.entries.toList()
        // 更新既有 askId 不该挤掉最旧的一笔：只有新增且已满时才淘汰。
        val kept = if (askId !in state.askMessages && entries.size >= MAX_ASK_MESSAGES)
            entries.drop(entries.size - MAX_ASK_MESSAGES + 1) else entries
        val next = state.copy(askMessages = (kept.associate { it.key to it.value } + (askId to telegramMessageId)))
        saveState(next)
        state = next
    }

    @Synchronized fun askIdForMessage(telegramMessageId: Long): String? =
        state.askMessages.entries.firstOrNull { it.value == telegramMessageId }?.key

    private fun readState(): TelegramState {
        if (!stateFile.isFile) return TelegramState()
        val text = try {
            stateFile.readText()
        } catch (e: Exception) {
            // 连读都失败（IO 错误）时原档可能完好：置位保护，保存前先备份原档，备份不成就不覆盖。
            stateLoadFailed = true
            return TelegramState()
        }
        return runCatching {
            json.decodeFromString(TelegramState.serializer(), text)
        }.getOrElse {
            // 解析坏了才算坏档：先把坏档原样备份再回空状态，避免下一次保存把原资料无声覆盖掉。
            // 备份若失败，置位同一道保护：后续保存必须先保住原档，否则抛错而不是覆盖。
            if (runCatching { stateFile.copyTo(corruptBackupFile()) }.isFailure) stateLoadFailed = true
            TelegramState()
        }
    }

    private fun readCredentials(): TelegramCredentials? {
        if (!credentials.isFile) return null
        if (credentials.length() == 0L) return null // clear() 已覆写清空但删不掉的残档 = 没有凭证，不是读取失败
        return runCatching {
            json.decodeFromString(TelegramCredentials.serializer(), cipher.decrypt(credentials.readBytes()).decodeToString())
        }.getOrElse {
            credentialsLoadFailed = true
            null
        }
    }

    private fun saveState(value: TelegramState = state) {
        if (stateLoadFailed) {
            // 原档当初读不到：先把它原样备份下来才许覆盖；备份失败必须让调用方知道，
            // 静默跳过会让内存与磁盘分家（绑定看似成功、重启后消失）。
            val preserved = runCatching { stateFile.copyTo(corruptBackupFile()) }.isSuccess
            if (!preserved) throw java.io.IOException("telegram state file could not be read earlier and cannot be backed up; refusing to overwrite it")
            stateLoadFailed = false
        }
        writeAtomic(stateFile, json.encodeToString(TelegramState.serializer(), value).toByteArray())
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // 个别文件系统不支持原子替换：降级为普通替换（内容已先 fsync 到临时档）。
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        internal const val MAX_ACKNOWLEDGED_MESSAGES = 512
        internal const val MAX_ASK_MESSAGES = 64
        internal const val BIND_CODE_TTL_MS = 10 * 60 * 1000L
        internal const val MAX_BIND_FAILURES = 5
        internal const val MAX_BIND_FAILURES_TOTAL = 50
        internal const val MAX_BIND_FAILURE_USERS = 256
    }
}
