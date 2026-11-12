package com.wangzan101.mika.telegram

import android.util.Log
import com.wangzan101.mika.core.run.Channel
import com.wangzan101.mika.core.run.ChannelInput
import com.wangzan101.mika.core.run.ChannelInputSink
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.SendOutcome
import com.wangzan101.mika.core.telegram.TelegramApi
import com.wangzan101.mika.core.telegram.TelegramApiException
import com.wangzan101.mika.core.telegram.TelegramHttpException
import com.wangzan101.mika.core.telegram.TgUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException

/** Telegram 只适配收发；主人校验、提问路由和任务执行都在通用渠道层（镜像 WechatChannel）。 */
class TelegramChannel(
    private val store: TelegramStore,
    private val api: TelegramApi,
    private val activeRunId: (String) -> RunId? = { null },
    private val imageBytes: (Delivery.Image) -> ByteArray? = { null },
    private val onBound: () -> Unit = {},
    private val log: (String) -> Unit = { Log.i(TAG, it) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val bindSuccessText: () -> String = { "Bound. Send me a message to start a task." },
    private val stopIdleText: () -> String = { "No task is running right now." },
) : Channel {
    override val id = ID
    override val confirms = false
    override val supportsImages = true
    @Volatile private var sink: ChannelInputSink? = null
    private val pollLock = Mutex()

    override fun isAlive(peer: ChannelPeer): Boolean = store.accepts(peer)

    override fun start(sink: ChannelInputSink): AutoCloseable {
        this.sink = sink
        return AutoCloseable { if (this.sink === sink) this.sink = null }
    }

    /**
     * 消息 id 带 Bot 作用域（tg<botId>-<updateId>）：换 Bot 或清绑后迟到的确认，
     * 不能删新 Bot 的 pending、也不能污染新 Bot 的已确认账本（updateId 各 Bot 独立计数、必然撞号）。
     */
    override fun acknowledge(messageId: String) {
        val bot = store.bot() ?: return
        if (!messageId.startsWith("tg${bot.botId}-")) return
        store.acknowledge(messageId, bot)
    }

    override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome {
        // 先取 bot、再用同一个 bot 授权：反序时两步之间换 Bot 会变成「旧 Bot 授权、新 Bot 令牌发送」。
        val bot = store.bot() ?: return SendOutcome.Failed
        if (peer.channelId != id || !store.accepts(peer, bot)) return SendOutcome.Failed
        val chatId = peer.peerId.toLongOrNull() ?: return SendOutcome.Failed
        return withContext(Dispatchers.IO) {
            val started = System.nanoTime()
            val outcome = try {
                when (delivery) {
                    is Delivery.Image -> {
                        // 本地读图失败是确定性失败：回 Failed，不能让例外逃出 send 的结果契约。
                        val bytes = try { imageBytes(delivery) } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null } ?: return@withContext SendOutcome.Failed
                        api.sendPhoto(bot.token, chatId, bytes, delivery.name, mediaTypeFor(delivery.name))
                    }
                    is Delivery.Ask -> {
                        val sent = api.sendLongText(bot.token, chatId, delivery.text)
                        // Only Input asks route replies; Confirm/Takeover are notice-only here
                        // (this channel cannot confirm, by design). Local bookkeeping failure
                        // must not flip an already-delivered send to Uncertain.
                        if (delivery.kind == Delivery.Ask.Kind.Input) {
                            try {
                                store.rememberAsk(delivery.askId, sent.messageId, bot)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // 记录 ask 与消息编号供人工补救；发送结果本身不受影响。
                                log("telegram ask mapping failed ask=${delivery.askId} message=${sent.messageId}: ${e.message}")
                            }
                        }
                    }
                    is Delivery.Result -> api.sendLongText(bot.token, chatId, delivery.text)
                    is Delivery.Notice -> api.sendLongText(bot.token, chatId, delivery.text)
                    is Delivery.Progress -> api.sendLongText(bot.token, chatId, delivery.text)
                }
                SendOutcome.Delivered
            } catch (e: CancellationException) {
                throw e
            } catch (_: TelegramApiException) {
                SendOutcome.Failed
            } catch (_: TelegramHttpException) {
                SendOutcome.Failed
            } catch (_: IOException) {
                SendOutcome.Uncertain
            }
            log("${delivery::class.simpleName} send $outcome in ${(System.nanoTime() - started) / 1_000_000} ms")
            outcome
        }
    }

    /** 先补交上次未登记的消息，再取新消息；任务登记处做最终幂等。长轮询随调用协程取消。 */
    suspend fun pollOnce(): List<TgUpdate>? = pollLock.withLock {
        val input = sink ?: return@withLock null
        val bot = store.bot()?.takeIf { store.enabled() } ?: return@withLock null
        for (pending in store.pending()) {
            // 会话中途变了就整轮作废；但单笔 pending 失效（例如 thread 被改）只跳过它，
            // 不能让一笔坏数据把整个轮询卡死。
            if (!current(bot, input)) return@withLock null
            if (!store.accepts(pending.peer, bot)) {
                // 会话已失效的 pending 直接丢弃并记账，不留在队列里每轮重复检查。
                if (!store.enabled() || store.bot() != bot) return@withLock null // 暂时状态（停用/换 token）：保留 pending，不丢弃
                store.dropPending(pending.messageId, bot)
                log("telegram pending ${pending.messageId} no longer accepted; dropped")
                continue
            }
            dispatchPending(pending, bot, input)
        }
        val updates = api.getUpdates(bot.token, store.offset().takeIf { it > 0 }, POLL_TIMEOUT_SEC)
        // 换绑、解绑或停止后，旧请求的结果不能再写入新会话，也不能推进新 Bot 的游标。
        if (store.bot() != bot || sink !== input || !store.enabled()) return@withLock null
        var nextOffset = store.offset()
        for (update in updates) {
            if (update.updateId + 1 > nextOffset) nextOffset = update.updateId + 1
            val message = update.message ?: continue
            val from = message.from ?: continue
            if (!message.chat.isPrivate() || from.isBot) continue
            val text = message.text?.takeIf { it.isNotBlank() } ?: continue
            if (!current(bot, input)) return@withLock null
            if (!store.isBound()) {
                val (command, rest) = commandOf(text)
                if (command == "/bind") {
                    if (store.tryBind(from.id, rest, now(), scopedId(bot, update.updateId))) {
                        onBound()
                        val peer = store.peer(message.chat.id)
                        replyDirect(bot, message.chat.id, bindSuccessText())
                        log("owner bound via /bind for chat ${peer.peerId}")
                    }
                }
                continue
            }
            if (from.id != store.ownerId()) continue
            val messageId = scopedId(bot, update.updateId)
            if (store.hasMessage(messageId)) continue
            val peer = store.peer(message.chat.id)
            val command = commandOf(text).first
            if (command == "/bind") {
                // 已绑定后重送的 /bind（多半是绑定瞬间进程中断）只是确认，不入任务；
                // 精确解析命令名，/binding 这类任务文字不会被误吞。
                store.acknowledge(messageId, bot)
                continue
            }
            if (command == "/stop") {
                val runId = activeRunId(peer.threadId)
                if (runId != null) {
                    input.receive(ChannelInput.Stop(peer, runId))
                } else {
                    replyDirect(bot, message.chat.id, stopIdleText())
                }
                if (current(bot, input)) store.acknowledge(messageId, bot)
                continue
            }
            val replyToAskId = message.replyTo?.messageId?.let { store.askIdForMessage(it) }
            if (!store.journal(messageId, peer, text, replyToAskId, expectedBot = bot)) {
                // 会话在登记瞬间变了：这则消息被有意消费掉（不入任务），留一条日志而不是静默消失。
                log("telegram message $messageId not journaled (session changed); skipped")
                continue
            }
            if (!current(bot, input)) return@withLock null
            store.pending().firstOrNull { it.messageId == messageId }?.let { dispatchPending(it, bot, input) }
        }
        if (current(bot, input)) store.saveOffset(nextOffset, bot)
        updates
    }

    /** 解析 Telegram 命令：首个词去掉 @botname 后缀即命令名，其余为参数（/stop、/stop@bot、/bind 123456 都覆盖）。 */
    private fun commandOf(text: String): Pair<String, String> {
        val parts = text.trim().split(Regex("\\s+"), 2)
        return parts[0].substringBefore('@') to (parts.getOrNull(1)?.trim().orEmpty())
    }

    /** updateId 只在单个 Bot 内唯一；带上 botId 前缀后，账本与确认天然按 Bot 隔离。 */
    private fun scopedId(bot: TelegramBot, updateId: Long) = "tg${bot.botId}-$updateId"

    private fun current(bot: TelegramBot, input: ChannelInputSink) =
        store.bot() == bot && sink === input && store.enabled()

    private suspend fun dispatchPending(pending: PendingTelegramMessage, bot: TelegramBot, input: ChannelInputSink) {
        if (!current(bot, input) || !store.accepts(pending.peer, bot)) return
        input.receive(ChannelInput.Text(pending.peer, pending.messageId, pending.text,
            pending.replyToAskId, requireMatchingAsk = pending.replyToAskId != null))
    }

    /** Best-effort direct reply outside the delivery ledger (bind confirmation, idle /stop). */
    private suspend fun replyDirect(bot: TelegramBot, chatId: Long, text: String) {
        try {
            api.sendMessage(bot.token, chatId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The binding/stop itself already took effect; a lost courtesy reply changes nothing.
        }
    }

    private fun mediaTypeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png".toMediaType()
        "webp" -> "image/webp".toMediaType()
        "gif" -> "image/gif".toMediaType()
        else -> "image/jpeg".toMediaType()
    }

    companion object {
        const val ID = "telegram"
        private const val TAG = "MikaTelegram"
        private const val POLL_TIMEOUT_SEC = 50
    }
}
