package com.wangzan101.mika.cap.phone.display

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 常驻虚拟屏仓库(ldplayer-adapt 分支):沿用、占用、释放、助手换人、建屏失败的账要算清。 */
class AgentDisplayStoreTest {
    private class Rig {
        var holds = 0
        var drops = 0
        var creates = 0
        val closed = mutableListOf<String>()
        val store = AgentDisplayStore<String>(
            holdBinding = { holds++ },
            dropBinding = { drops++ },
            closeDisplay = { closed += it },
        )

        suspend fun create(name: String): String {
            creates++
            return name
        }
    }

    private val binderA = Any()
    private val binderB = Any()

    @Test
    fun firstObtainCreatesAndHoldsBindingOnce() = runTest {
        val rig = Rig()
        val got = rig.store.obtain("run1", binderA) { rig.create("d1") }
        assertTrue(got is AgentDisplayStore.Obtain.Shared<*>)
        assertEquals("d1", (got as AgentDisplayStore.Obtain.Shared<*>).display)
        assertEquals(1, rig.holds)
        assertEquals(0, rig.drops)
        assertTrue(rig.store.hasDisplay())
        assertTrue(rig.store.inUse())
        assertEquals(AgentDisplayStore.Snapshot(hasDisplay = true, inUse = true), rig.store.state.value)
    }

    @Test
    fun secondRunAdoptsSameDisplayAfterFinish() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        rig.store.finishUse("run1", binderA, shellDied = false)
        assertFalse(rig.store.inUse())
        val got = rig.store.obtain("run2", binderA) { rig.create("d2") }
        assertEquals("d1", (got as AgentDisplayStore.Obtain.Shared<*>).display)
        assertEquals(1, rig.creates)
        assertEquals(1, rig.holds)
        assertTrue(rig.closed.isEmpty())
    }

    @Test
    fun obtainWhileAnotherRunUsesItIsBusyAndCreatesNothing() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        val got = rig.store.obtain("run2", binderA) { rig.create("d2") }
        assertSame(AgentDisplayStore.Obtain.Busy, got)
        assertEquals(1, rig.creates)
    }

    @Test
    fun releaseNowRefusedWhileInUseThenSucceedsWhenIdle() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        assertFalse(rig.store.releaseNow())
        rig.store.finishUse("run1", binderA, shellDied = false)
        assertTrue(rig.store.releaseNow())
        assertEquals(listOf("d1"), rig.closed)
        assertEquals(1, rig.drops)
        assertFalse(rig.store.hasDisplay())
        assertEquals(AgentDisplayStore.Snapshot(hasDisplay = false, inUse = false), rig.store.state.value)
        assertFalse(rig.store.releaseNow())
    }

    @Test
    fun binderChangeClosesOldAndCreatesNew() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        rig.store.finishUse("run1", binderA, shellDied = false)
        val got = rig.store.obtain("run2", binderB) { rig.create("d2") }
        assertEquals("d2", (got as AgentDisplayStore.Obtain.Shared<*>).display)
        assertEquals(listOf("d1"), rig.closed)
        // 绑定是 App 这一侧的租约,助手重启不重新租
        assertEquals(1, rig.holds)
        assertEquals(0, rig.drops)
    }

    @Test
    fun shellDeathDropsEverything() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        rig.store.finishUse("run1", binderA, shellDied = true)
        assertFalse(rig.store.hasDisplay())
        assertEquals(listOf("d1"), rig.closed)
        assertEquals(1, rig.drops)
    }

    @Test
    fun lateShellDeathFromOldRunDoesNotDropNewDisplay() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        // run1 的 shell 死了、它的收尾还没到;run2 先用新 binder 重建了常驻屏并占用中
        rig.store.obtain("run2", binderB) { rig.create("d2") }
        assertEquals(listOf("d1"), rig.closed)
        // run1 晚到的 shellDied 收尾:不许关 run2 的新屏、不许放 binding
        rig.store.finishUse("run1", binderA, shellDied = true)
        assertTrue(rig.store.hasDisplay())
        assertTrue(rig.store.inUse())
        assertEquals(listOf("d1"), rig.closed)
        assertEquals(0, rig.drops)
        // run2 正常收尾后屏还留着
        rig.store.finishUse("run2", binderB, shellDied = false)
        assertTrue(rig.store.hasDisplay())
        assertFalse(rig.store.inUse())
    }

    @Test
    fun binderChangeThenCreateFailureLeavesEmptySnapshot() = runTest {
        val rig = Rig()
        rig.store.obtain("run1", binderA) { rig.create("d1") }
        rig.store.finishUse("run1", binderA, shellDied = false)
        try {
            rig.store.obtain("run2", binderB) { throw IllegalStateException("boom") }
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        // 旧屏已关、新屏没建成:快照必须是空的,不能停留在 hasDisplay=true
        assertFalse(rig.store.hasDisplay())
        assertEquals(AgentDisplayStore.Snapshot(hasDisplay = false, inUse = false), rig.store.state.value)
        assertEquals(listOf("d1"), rig.closed)
    }

    @Test
    fun createFailureReturnsBindingAndRetriesClean() = runTest {
        val rig = Rig()
        try {
            rig.store.obtain("run1", binderA) { throw IllegalStateException("boom") }
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, rig.holds)
        assertEquals(1, rig.drops)
        assertFalse(rig.store.hasDisplay())
        val got = rig.store.obtain("run1", binderA) { rig.create("d1") }
        assertTrue(got is AgentDisplayStore.Obtain.Shared<*>)
        assertEquals(2, rig.holds)
    }
}
