package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 项目 3 的链行为:主模型失败自动换备援、换过去就留在备援、不该换的原样失败。 */
class ModelCallerChainTest {
    private class Scripted(override val name: String, var fail: ModelError?) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
        var calls = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            fail?.let { throw it }
            return answerReply("ok-$name")
        }
    }

    private val history = listOf(Message(Role.User, listOf(Part.Text("你好")), Author.User))

    private fun caller(main: ChatModel, fallbacks: List<ChatModel>, tags: List<String?> = emptyList()) =
        ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 },
            fallbacks = fallbacks, chainTags = tags).also { it.reset("") }

    @Test fun quotaFallsToFallbackAndSticks() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
        // 切过去之后下一步直接问备援,不再先撞一次坏掉的主模型
        caller.ask(history, emptyList(), step = 2)
        assertEquals(1, main.calls)
        assertEquals(2, backup.calls)
    }

    @Test fun taskBudgetDoesNotSwitch() = runTest {
        val main = Scripted("main", ModelError.TaskBudgetExceeded(100, 100))
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.TaskBudgetExceeded)
        assertEquals(0, backup.calls)
    }

    @Test fun noFallbackBehavesLikeBefore() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val caller = caller(main, emptyList())
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.QuotaExceeded)
        assertEquals(1, main.calls)
    }

    @Test fun chainExhaustionSurfacesPrimaryError() = runTest {
        // 整条链都失败:交出的是主模型最初的错误,不是最后一家备援的(MK-1 A-D1 第二点)。
        val mainErr = ModelError.Server(500)
        val main = Scripted("main", mainErr)
        val backup = Scripted("backup", ModelError.Server(503))
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(mainErr, thrown)
        assertEquals(1, backup.calls)
    }

    @Test fun fallbackBudgetErrorPropagatesAsItself() = runTest {
        // 备援报的是任务级预算:说的是整个任务,照它自己交出,不被主模型的错盖掉。
        val budgetErr = ModelError.TaskBudgetExceeded(100, 100)
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", budgetErr)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertSame(budgetErr, thrown)
    }

    @Test fun server4xxSwitchesWithoutSameModelRetry() = runTest {
        // 4xx 是这份请求本身被拒:同一家重试也没用,直接换下一家(MK-1 A-D1 第一点)。
        val main = Scripted("main", ModelError.Server(400))
        val backup = Scripted("backup", null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF,
            RetryPolicy(maxRetries = 3, jitter = { 0.0 }), {}, { 0 }, fallbacks = listOf(backup)).also { it.reset("") }
        caller.ask(history, emptyList(), step = 1)
        assertEquals(1, main.calls)
        assertEquals(1, backup.calls)
        assertSame(backup, caller.lastMainUsed)
    }

    @Test fun server5xxRetriesSameModelFirst() = runTest {
        // 5xx 是一时的故障:先在同一家把重试用完,才换备援。
        val main = Scripted("main", ModelError.Server(500))
        val backup = Scripted("backup", null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF,
            RetryPolicy(maxRetries = 3, jitter = { 0.0 }), {}, { 0 }, fallbacks = listOf(backup)).also { it.reset("") }
        caller.ask(history, emptyList(), step = 1)
        assertEquals(4, main.calls)
        assertEquals(1, backup.calls)
        assertSame(backup, caller.lastMainUsed)
    }

    @Test fun dailyBudgetSkipsSameProvider() = runTest {
        val main = Scripted("main", ModelError.DailyBudgetExceeded("OpenRouter", 100, 100))
        val same = Scripted("same", null)
        val other = Scripted("other", null)
        val caller = caller(main, listOf(same, other), tags = listOf("OpenRouter", "OpenRouter", "APInex"))
        caller.ask(history, emptyList(), step = 1)
        assertSame(other, caller.lastMainUsed)
        assertEquals(0, same.calls)
        assertEquals(1, other.calls)
    }

    @Test fun nonSwitchableErrorPropagates() = runTest {
        val main = Scripted("main", ModelError.ContentRefused())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        var thrown: Throwable? = null
        try { caller.ask(history, emptyList(), step = 1) } catch (e: ModelError) { thrown = e }
        assertEquals(true, thrown is ModelError.ContentRefused)
        assertEquals(0, backup.calls)
    }

    @Test fun resetReturnsToMain() = runTest {
        val main = Scripted("main", ModelError.QuotaExceeded())
        val backup = Scripted("backup", null)
        val caller = caller(main, listOf(backup))
        caller.ask(history, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        main.fail = null
        caller.reset("")
        caller.ask(history, emptyList(), step = 2)
        assertSame(main, caller.lastMainUsed)
    }

    /** 先给一条带私有字段的回复、之后按 [thenFail] 失败的假模型;顺手记下最后一次收到的历史。 */
    private class Recorder(override val name: String, val first: ModelReply?, val thenFail: ModelError?) : ChatModel {
        override val capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false)
        var calls = 0
        var seen: List<Message> = emptyList()
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls++
            seen = messages
            if (calls == 1 && first != null) return first
            thenFail?.let { throw it }
            return answerReply("ok-$name")
        }
    }

    private val richReply = ModelReply(
        listOf(
            Part.Thinking("inner", opaque = "think-opaque"),
            Part.ToolCall("c1", "tap", "{}", opaque = "call-opaque"),
            Part.Text("answer with tools"),
        ), StopReason.EndTurn, null)

    @Test fun switchStripsPreviousMainPrivateParts() = runTest {
        val main = Recorder("main", richReply, ModelError.QuotaExceeded())
        val backup = Recorder("backup", null, null)
        val caller = caller(main, listOf(backup))
        val first = caller.ask(history, emptyList(), step = 1)
        val h2 = history + Message(Role.Assistant, first.parts, Author.Mika)
            .also { caller.noteReply(it, caller.lastMainUsed, first.usage) }
        caller.ask(h2, emptyList(), step = 2)
        assertSame(backup, caller.lastMainUsed)
        val sent = backup.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }

    @Test fun ownRepliesKeepPrivateParts() = runTest {
        val main = Recorder("main", richReply, null)
        val caller = caller(main, emptyList())
        val first = caller.ask(history, emptyList(), step = 1)
        val h2 = history + Message(Role.Assistant, first.parts, Author.Mika)
            .also { caller.noteReply(it, caller.lastMainUsed, first.usage) }
        caller.ask(h2, emptyList(), step = 2)
        val sent = main.seen.single { it.role == Role.Assistant }
        assertEquals("think-opaque", sent.parts.filterIsInstance<Part.Thinking>().single().opaque)
        assertEquals("call-opaque", sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
    }

    @Test fun firstFailoverStripsUnrecordedReplies() = runTest {
        // 任务第一次调用就 fallover:history 里有一条未记录来源的 assistant 回复(视同主模型给的),
        // 发给备援时必须剥除私有字段,不能因为 replyOrigin 为空就整份原样直送(审查 T-1112-b649d2 第 3 轮 D1)。
        val main = Recorder("main", null, ModelError.QuotaExceeded())
        val backup = Recorder("backup", null, null)
        val caller = caller(main, listOf(backup))
        val h = history + Message(Role.Assistant, richReply.parts, Author.Mika)
        caller.ask(h, emptyList(), step = 1)
        assertSame(backup, caller.lastMainUsed)
        val sent = backup.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }

    @Test fun lightViewStripsUnrecordedReplies() = runTest {
        // 一条来源未被记录的 assistant 回复(任务刚开始、replyOrigin 还是空的)发给轻量模型时,
        // 仍须剥除私有字段,不能因为 map 为空就原样直送(审查 T-1112-b649d2 第 2 轮 D1)。
        val main = Recorder("main", null, null)
        val light = Recorder("light", null, null)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), ModelRouter(main, light),
            SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 }).also { it.reset("") }
        val h = history + Message(Role.Assistant, richReply.parts, Author.Mika)
        caller.ask(h, emptyList(), step = 1, using = light)
        val sent = light.seen.single { it.role == Role.Assistant }
        assertEquals(false, sent.parts.any { it is Part.Thinking })
        assertEquals(null, sent.parts.filterIsInstance<Part.ToolCall>().single().opaque)
        assertEquals("answer with tools", sent.parts.filterIsInstance<Part.Text>().single().text)
    }
}
