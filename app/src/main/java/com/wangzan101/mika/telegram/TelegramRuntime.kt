package com.wangzan101.mika.telegram

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.util.Log
import com.wangzan101.mika.MikaApp
import com.wangzan101.mika.R
import com.wangzan101.mika.i18n.tr
import com.wangzan101.mika.core.action.ConfirmChoice
import com.wangzan101.mika.core.net.NetProxy
import com.wangzan101.mika.core.run.ChannelGateway
import com.wangzan101.mika.core.run.ChannelPeer
import com.wangzan101.mika.core.run.Delivery
import com.wangzan101.mika.core.run.MemoryOwnerIdentity
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.telegram.TelegramApi
import com.wangzan101.mika.core.telegram.TelegramApiException
import com.wangzan101.mika.core.telegram.TelegramHttpException
import com.wangzan101.mika.settings.FeatureFlags
import com.wangzan101.mika.task.TaskService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

sealed interface TelegramConnectionState {
    data object Off : TelegramConnectionState
    data object NeedsBinding : TelegramConnectionState
    data object Connecting : TelegramConnectionState
    data object Connected : TelegramConnectionState
    data class Failed(val message: String) : TelegramConnectionState
}

/** 单 Bot、私聊文字与图片的 Telegram 运行时。监听存活由独立前台服务负责，任务仍只进 TaskService。 */
class TelegramRuntime(
    private val context: Context,
    val store: TelegramStore,
    client: OkHttpClient? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // 没有 logging interceptor：Bot token 就在请求 URL 路径里，任何 URL 日志都会泄露它（P1 审查 D1）。
    // readTimeout 必须大于渠道的 50 秒长轮询。
    private val http = client ?: NetProxy.shared.client(OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).writeTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS))
    private val api = TelegramApi(http)
    private val owners = MemoryOwnerIdentity()
    private val app get() = context.applicationContext as MikaApp
    private val gateway = ChannelGateway(owners,
        { threadId -> app.taskState.run(threadId)?.activeAsk },
        object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) =
                TaskService.startChannel(context, peer, messageId, text)

            override suspend fun answer(threadId: String, askId: String, text: String): Boolean =
                app.taskState.run(threadId)?.answerFromChannel(text, askId) == true

            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice) = false
            override suspend fun stop(runId: RunId) = TaskService.cancel(context, runId.value)
            override suspend fun cancelAsk(threadId: String) {
                val run = app.taskState.run(threadId) ?: return
                run.activeAsk?.askId?.let(run::cancelAsk)
            }
        })

    val channel = TelegramChannel(store, api,
        activeRunId = { threadId -> app.taskState.run(threadId)?.runId?.value?.let { RunId(it) } },
        imageBytes = telegramImageBytes(context),
        onBound = { sync() },
        bindSuccessText = { tr(R.string.telegram_bind_success) },
        stopIdleText = { tr(R.string.telegram_stop_idle) })

    private var registration: AutoCloseable? = null
    private var monitor: Job? = null
    private val network = TelegramNetwork(context) { restartMonitor() }
    private val _state = MutableStateFlow<TelegramConnectionState>(
        if (store.bot() == null) TelegramConnectionState.NeedsBinding else TelegramConnectionState.Off)
    val state: StateFlow<TelegramConnectionState> = _state.asStateFlow()

    fun channelFor(id: String) = channel.takeIf { id == TelegramChannel.ID }
    fun acknowledge(messageId: String) = channel.acknowledge(messageId)

    @Synchronized fun sync(): Boolean {
        if (FeatureFlags.get(context).quitRequested()) return false
        val bot = store.bot()
        if (!store.enabled() || bot == null) {
            _state.value = TelegramConnectionState.NeedsBinding.takeIf { bot == null } ?: TelegramConnectionState.Off
            return false
        }
        if (registration == null) registration = gateway.start(channel)
        if (store.isBound()) owners.bind(ChannelPeer(TelegramChannel.ID, store.ownerId().toString(), ""))
        network.start()
        if (monitor?.isActive != true) {
            _state.value = TelegramConnectionState.Connecting
            monitor = scope.launch { monitorLoop() }
        }
        return true
    }

    @Synchronized fun stop() {
        monitor?.cancel()
        monitor = null
        registration?.close()
        registration = null
        network.stop()
        _state.value = if (store.bot() == null) TelegramConnectionState.NeedsBinding else TelegramConnectionState.Off
    }

    fun ensureRunning() {
        if (FeatureFlags.get(context).quitRequested() || !store.enabled() || store.bot() == null) return
        _state.value = TelegramConnectionState.Connecting
        runCatching { context.startForegroundService(Intent(context, TelegramService::class.java)) }
            .onFailure { _state.value = TelegramConnectionState.Failed(it.message ?: it::class.simpleName.orEmpty()) }
    }

    fun setEnabled(enabled: Boolean) {
        if (enabled) check(store.bot() != null) { "Save a Telegram bot token before enabling it" }
        store.setEnabled(enabled)
        if (enabled) ensureRunning() else {
            context.stopService(Intent(context, TelegramService::class.java))
            stop()
        }
    }

    /** 设置页保存前用 getMe 验证令牌并拿到 Bot 身份；失败会把 Telegram 的错误原样抛给界面显示。 */
    suspend fun validateToken(token: String): TelegramBot = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        val me = api.getMe(trimmed)
        TelegramBot(trimmed, me.id, me.username.orEmpty())
    }

    fun saveBot(bot: TelegramBot) {
        val old = store.bot()
        if (old != null && old.botId != bot.botId) owners.unbind(ChannelPeer(TelegramChannel.ID, store.ownerId().toString(), ""))
        store.saveBot(bot)
        store.setEnabled(true)
        sync()
        ensureRunning()
    }

    fun unbind() {
        context.stopService(Intent(context, TelegramService::class.java))
        if (store.isBound()) owners.unbind(ChannelPeer(TelegramChannel.ID, store.ownerId().toString(), ""))
        stop()
        store.clear()
        _state.value = TelegramConnectionState.NeedsBinding
    }

    @Synchronized private fun restartMonitor() {
        if (registration == null || !store.enabled()) return
        monitor?.cancel()
        monitor = scope.launch { monitorLoop() }
    }

    private suspend fun monitorLoop() {
        var failures = 0
        while (currentCoroutineContext().isActive && store.enabled()) {
            try {
                channel.pollOnce()
                failures = 0
                _state.value = TelegramConnectionState.Connected
            } catch (e: CancellationException) {
                throw e
            } catch (e: TelegramHttpException) {
                if (e.httpCode == 401 || e.httpCode == 403) return invalidateToken()
                failures++
                _state.value = TelegramConnectionState.Failed(e.message.orEmpty())
                delay(backoff(failures))
            } catch (e: TelegramApiException) {
                when (e.code) {
                    401 -> return invalidateToken()
                    // 同一个 Bot 只能有一个长轮询：多半是旧的外部桥还在跑。保持启用、退避重试，
                    // 让状态把冲突显示出来，而不是静默丢消息。
                    409 -> {
                        failures++
                        _state.value = TelegramConnectionState.Failed("409: another client is polling this bot")
                        delay(backoff(failures))
                    }
                    else -> {
                        failures++
                        _state.value = TelegramConnectionState.Failed(e.message.orEmpty())
                        delay(backoff(failures))
                    }
                }
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Telegram polling failed", e)
                _state.value = TelegramConnectionState.Failed(e.message ?: e::class.simpleName.orEmpty())
                delay(backoff(failures))
            }
        }
    }

    private fun invalidateToken() {
        store.setEnabled(false)
        stop() // 先做进程内清理（registration/network/monitor），不能只依赖 service 的 onDestroy
        _state.value = TelegramConnectionState.NeedsBinding
        context.stopService(Intent(context, TelegramService::class.java))
    }

    private fun backoff(failures: Int) = min(30_000L, 1_000L * (1L shl (failures - 1).coerceAtMost(5)))

    companion object { private const val TAG = "MikaTelegram" }
}

/** Delivery.Image 的 uri 是任务导出的不透明地址；宿主在这里解析成字节（content/file/本地路径）。 */
private fun telegramImageBytes(context: Context): (Delivery.Image) -> ByteArray? = { image ->
    runCatching {
        val uri = Uri.parse(image.uri)
        val stream = when (uri.scheme) {
            "content" -> context.contentResolver.openInputStream(uri)
            "file" -> uri.path?.let { FileInputStream(File(it)) }
            null, "" -> FileInputStream(File(image.uri))
            else -> null
        }
        stream?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_IMAGE_BYTES) return@runCatching null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }.getOrNull()
}

private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

private class TelegramNetwork(context: Context, private val changed: () -> Unit) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private var started = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
    }

    @Synchronized fun start() {
        if (started) return
        runCatching { manager.registerDefaultNetworkCallback(callback) }.onSuccess { started = true }
    }

    @Synchronized fun stop() {
        if (!started) return
        runCatching { manager.unregisterNetworkCallback(callback) }
        started = false
    }
}
