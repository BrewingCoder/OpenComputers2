package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

class EventQueueTest {

    @Test
    fun `poll returns offered event`() {
        val q = ScriptEventQueue()
        q.offer(ScriptEvent("hello", listOf(1, "two")))
        val e = q.poll(filter = null, timeoutMs = 100)
        e?.name shouldBe "hello"
        e?.args shouldBe listOf(1, "two")
    }

    @Test
    fun `poll with filter waits past non-matching events`() {
        val q = ScriptEventQueue()
        q.offer(ScriptEvent("noise"))
        q.offer(ScriptEvent("noise"))
        q.offer(ScriptEvent("target", listOf("payload")))
        val e = q.poll(filter = "target", timeoutMs = 100)
        e?.name shouldBe "target"
        e?.args shouldBe listOf("payload")
    }

    @Test
    fun `poll returns null on timeout`() {
        val q = ScriptEventQueue()
        val start = System.currentTimeMillis()
        val e = q.poll(filter = null, timeoutMs = 50)
        val elapsed = System.currentTimeMillis() - start
        e shouldBe null
        (elapsed >= 40) shouldBe true
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    fun `concurrent producer + consumer round-trip`() {
        val q = ScriptEventQueue()
        val producer = Thread {
            Thread.sleep(20)
            q.offer(ScriptEvent("ping", listOf(42)))
        }.apply { isDaemon = true; start() }
        val received = q.poll(filter = "ping", timeoutMs = 1000)
        producer.join()
        received?.args shouldBe listOf(42)
    }

    /** End-to-end: Lua os.queueEvent → os.pullEvent round-trip. */
    @Test
    fun `lua os queueEvent and pullEvent round-trip`() {
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            os.queueEvent("custom", 7, "tag")
            local n, a, b = os.pullEvent("custom")
            print(n, a, b)
        """.trimIndent(), "ev.lua", FakeEnv(InMemoryMount(4096), "", out))
        r.ok shouldBe true
        out.lines shouldBe listOf("custom\t7\ttag")
    }

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = null
        override val network: NetworkAccess = NetworkAccess.NOOP
        override val events: ScriptEventQueue = ScriptEventQueue()
    }
}
