package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 常驻的 Agent 虚拟屏(ldplayer-adapt 分支)。
 *
 * 上游每轮任务结束都释放虚拟屏,屏上的 App 随屏一起关掉,而且去留取决于模型有没有标对
 * (见 AppsAfterTaskEnd)。模拟器是常开的机器,没有手机要省电、怕后台屏占资源的问题,
 * 所以本分支把第一轮建好的屏连同 shell 绑定一起留住(解绑会撤掉 shell 一侧的虚拟屏,
 * 见 SharedShell 的说明),后续任务沿用同一块屏:任务收尾不释放、不分流,App 原地活着。
 *
 * 边界:
 * - 别的任务正用着这块屏时不抢:调用方拿 [Obtain.Busy],走上游老路自建自放。
 * - 内置助手重启过(binder 换人)时旧屏随旧助手一起作废,清掉本地残留重建。
 * - 用户在设置里手动释放,或助手已死,才真的把屏放掉。
 *
 * 泛型只为让单元测试能换成假对象;产品里 [T] 是 [AgentDisplay],[holdBinding] /
 * [dropBinding] 接 SharedShell 的 acquire / release,[closeDisplay] 是 AgentDisplay.release。
 */
class AgentDisplayStore<T : Any>(
    private val holdBinding: () -> Unit,
    private val dropBinding: () -> Unit,
    private val closeDisplay: (T) -> Unit,
) {
    sealed interface Obtain<out T> {
        class Shared<T>(val display: T) : Obtain<T>
        object Busy : Obtain<Nothing>
    }

    /** 给设置页看的快照:有没有常驻屏、是不是正被任务用着。 */
    data class Snapshot(val hasDisplay: Boolean, val inUse: Boolean)

    private val _state = MutableStateFlow(Snapshot(hasDisplay = false, inUse = false))
    val state: StateFlow<Snapshot> = _state

    private fun publish() {
        _state.value = Snapshot(hasDisplay = display != null, inUse = userKey != null)
    }

    private val mutex = Mutex()
    private var display: T? = null
    /** 建屏时那条 shell 连接的身份令牌(产品里是 IMikaShell 的 binder);只比同一性,不调用。 */
    private var shellBinder: Any? = null
    private var bindingHeld = false

    /** 正在用这块屏的任务 key;空闲为 null。 */
    private var userKey: String? = null

    /**
     * 取常驻屏:已有一块且助手没换人就沿用(空闲时由 [key] 认领;同一个 key 重入直接给)。
     * 别的任务正用着返回 [Obtain.Busy]。还没有时先留住绑定,再用 [create] 建(建屏要挂起),
     * 建失败时把绑定还回去,不留半成品。
     */
    suspend fun obtain(key: String, binder: Any, create: suspend () -> T): Obtain<T> = mutex.withLock {
        val existing = display
        if (existing != null && shellBinder === binder) {
            if (userKey != null && userKey != key) return Obtain.Busy
            userKey = key
            publish()
            return Obtain.Shared(existing)
        }
        if (existing != null) {
            // 助手换人:旧屏已经不在了,本地的取帧管线关掉即可,不必再对新助手发释放
            runCatching { closeDisplay(existing) }
            display = null
            shellBinder = null
            userKey = null
            publish()
        }
        if (!bindingHeld) {
            holdBinding()
            bindingHeld = true
        }
        val made = try {
            create()
        } catch (t: Throwable) {
            if (bindingHeld) {
                bindingHeld = false
                runCatching { dropBinding() }
            }
            publish()
            throw t
        }
        display = made
        shellBinder = binder
        userKey = key
        publish()
        Obtain.Shared(made)
    }

    /**
     * 任务收尾:屏留着,只解除占用。助手已死时只清「自己那一代」的屏——若仓库里的屏
     * 已经是新助手重建的、或正被别的任务用着,晚到的收尾不许碰它(竞态:旧任务的
     * shell 死了,新任务先一步重建了常驻屏,旧任务的收尾才到)。[binder] 是这轮
     * 建屏/用屏时那条 shell 连接的身份令牌;传 null 时只解除占用、不放屏
     * (放屏必须出示令牌且对得上,免得晚到的收尾误关新一代的屏)。
     */
    suspend fun finishUse(key: String, binder: Any?, shellDied: Boolean) = mutex.withLock {
        if (userKey == key) userKey = null
        if (shellDied && userKey == null && binder != null && shellBinder === binder) dropLocked()
        publish()
    }

    /**
     * 设置里手动释放:使用中拒绝。返回 true 表示真的放掉了(本来就没有的情况算 false)。
     * 调用方常在主线程(设置页),而真正释放要对助手发 IPC、关取帧管线,切去 IO 线程做,
     * 不让界面等它。
     */
    suspend fun releaseNow(): Boolean = mutex.withLock {
        if (display == null) return false
        if (userKey != null) return false
        withContext(Dispatchers.IO) { dropLocked() }
        publish()
        true
    }

    /** 此刻有没有常驻屏。一律读已发布的快照:单一事实来源,任何线程读都一致。 */
    fun hasDisplay(): Boolean = _state.value.hasDisplay

    /** 此刻是不是有任务正用着。同样只读快照。 */
    fun inUse(): Boolean = _state.value.inUse

    private fun dropLocked() {
        val d = display
        display = null
        shellBinder = null
        userKey = null
        // 即使助手已死也要关:本地的取帧管线(ImageReader、线程)要收干净;
        // 对死助手发释放的部分由 closeDisplay 自己的实现吞掉失败。
        d?.let { runCatching { closeDisplay(it) } }
        if (bindingHeld) {
            bindingHeld = false
            runCatching { dropBinding() }
        }
    }
}
