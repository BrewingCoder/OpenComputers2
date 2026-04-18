package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.network.NetworkInboxes
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Cross-host parity tests for the `network` API. Lua and JS share the same
 * surface; verifying both bindings here catches divergence at compile-fail or
 * test-fail time instead of in-game.
 */
class NetworkBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    /** In-process [NetworkAccess] backed by a real [NetworkInboxes] for the host id. */
    private class FakeNetwork(private val myId: Int, private val channel: String) : NetworkAccess {
        override fun id(): Int = myId
        override fun send(message: String, channel: String?) {
            // Tests want to observe a self-loop too — the BE filters self at production time.
            // Here we deliver to `myId` so a single-script test can read its own message.
            NetworkInboxes.deliver(myId, NetworkInboxes.Message(from = myId, body = message))
        }
        override fun recv(): NetworkInboxes.Message? = NetworkInboxes.pop(myId)
        override fun peek(): NetworkInboxes.Message? = NetworkInboxes.peek(myId)
        override fun size(): Int = NetworkInboxes.size(myId)
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        override val network: NetworkAccess,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = null
    }

    private fun mount(): WritableMount = InMemoryMount(4096)
    private fun env(net: NetworkAccess, out: ShellOutput) = FakeEnv(mount(), "", out, net)

    // ---------- Lua ----------

    @Test
    fun `lua network_id returns host computer id`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("print(network.id())", "id.lua",
            env(FakeNetwork(42, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("42")
    }

    @Test
    fun `lua network_send then network_recv round-trips`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            network.send("hello there")
            local m = network.recv()
            print(m.from, m.body)
            print(network.recv() == nil)
        """.trimIndent(), "rt.lua", env(FakeNetwork(7, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("7\thello there", "true")
    }

    @Test
    fun `lua network_peek does not consume`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            network.send("x")
            print(network.size())
            local p = network.peek()
            print(p.body)
            print(network.size())
        """.trimIndent(), "peek.lua", env(FakeNetwork(1, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("1", "x", "1")
    }

    @Test
    fun `lua json_encode then json_decode round-trips a table`() {
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local s = json.encode({a = 1, b = "hi", c = true})
            local t = json.decode(s)
            print(t.a, t.b, t.c)
        """.trimIndent(), "json.lua", env(NetworkAccess.NOOP, out))
        r.ok shouldBe true
        out.lines.size shouldBe 1
        out.lines[0].shouldContain("1")
        out.lines[0].shouldContain("hi")
        out.lines[0].shouldContain("true")
    }

    @Test
    fun `lua json_encode emits an array for integer-keyed tables`() {
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            print(json.encode({"a", "b", "c"}))
        """.trimIndent(), "arr.lua", env(NetworkAccess.NOOP, out))
        r.ok shouldBe true
        out.lines shouldBe listOf("""["a","b","c"]""")
    }

    @Test
    fun `lua json_decode on malformed input raises a Lua error caught by pcall`() {
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local ok, err = pcall(json.decode, "not json")
            print(ok)
        """.trimIndent(), "bad.lua", env(NetworkAccess.NOOP, out))
        r.ok shouldBe true
        out.lines shouldBe listOf("false")
    }

    // ---------- JS ----------

    @Test
    fun `js network_id returns host computer id`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = RhinoJSHost().eval("print(network.id());", "id.js",
            env(FakeNetwork(42, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("42")
    }

    @Test
    fun `js network_send then network_recv round-trips`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            network.send("hello there");
            var m = network.recv();
            print(m.from + " " + m.body);
            print(network.recv() == null);
        """.trimIndent(), "rt.js", env(FakeNetwork(7, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("7 hello there", "true")
    }

    @Test
    fun `js network_peek does not consume`() {
        NetworkInboxes.resetForTest()
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            network.send("x");
            print(network.size());
            var p = network.peek();
            print(p.body);
            print(network.size());
        """.trimIndent(), "peek.js", env(FakeNetwork(1, "default"), out))
        r.ok shouldBe true
        out.lines shouldBe listOf("1", "x", "1")
    }

    @Test
    fun `js JSON_stringify works (built-in, no oc2 binding)`() {
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            print(JSON.stringify({a: 1, b: "hi", c: true}));
        """.trimIndent(), "json.js", env(NetworkAccess.NOOP, out))
        r.ok shouldBe true
        out.lines shouldBe listOf("""{"a":1,"b":"hi","c":true}""")
    }
}
