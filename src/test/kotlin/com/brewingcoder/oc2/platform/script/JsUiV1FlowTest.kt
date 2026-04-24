package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** JS mirror of [LuaUiV1FlowTest]. */
class JsUiV1FlowTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() = Unit
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String = "",
        override val out: ShellOutput,
        private val monitor: RecordingMonitor,
        override val events: ScriptEventQueue = ScriptEventQueue(),
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? =
            if (kind == "monitor") monitor else null
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null || kind == "monitor") listOf(monitor) else emptyList()
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mkEnv(monitor: RecordingMonitor = RecordingMonitor()): Pair<FakeEnv, CapturingOut> {
        val rom = ResourceMount("assets/oc2/rom")
        val mount = UnionMount(InMemoryMount(), rom)
        val out = CapturingOut()
        return FakeEnv(mount, out = out, monitor = monitor) to out
    }

    @Test
    fun `Flow packs three short cards into one row when width fits`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var a = ui.Card({ children: [ ui.Label({ text: "A" }) ] });
            var b = ui.Card({ children: [ ui.Label({ text: "B" }) ] });
            var c = ui.Card({ children: [ ui.Label({ text: "C" }) ] });
            var root = ui.Flow({ padding: 0, gap: 4, children: [ a, b, c ] });
            ui.mount(mon, root);
            print(a.x + " " + a.y + " " + b.x + " " + b.y + " " + c.x + " " + c.y);
            print(root.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // a=24, gap snaps to 8 → b at x=32, c at x=64. Row h=36.
        out.lines shouldBe listOf("0 0 32 0 64 0", "36")
    }

    @Test
    fun `Flow wraps to a second row when cursor would overflow`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var children = [];
            for (var i = 0; i < 4; i++) {
                children.push(ui.Card({ children: [ ui.Label({ text: "XXXXXXXXXXX" }) ] }));
            }
            var root = ui.Flow({ padding: 0, gap: 4, children: children });
            ui.mount(mon, root);
            for (var j = 0; j < 4; j++) {
                print(children[j].x + " " + children[j].y);
            }
            print(root.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // N=11 Card → 104×36. Snapped gap=8. 104+8+104=216 fits; third
        // (+8+104=328) overflows 320. So 2 per row, 4 cards = 2 rows.
        out.lines shouldBe listOf(
            "0 0",
            "112 0",
            "0 44",
            "112 44",
            "80",
        )
    }

    @Test
    fun `Flow measure returns single-row estimate`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var f = ui.Flow({ padding: 0, gap: 2, children: [
                ui.Card({ children: [ ui.Label({ text: "AB" }) ] }),
                ui.Card({ children: [ ui.Label({ text: "CD" }) ] }),
            ]});
            var m = f.measure(mon);
            print(m[0] + " " + m[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // 32 + 2 + 32 = 66 wide, 36 tall (measure doesn't snap gap).
        out.lines shouldBe listOf("66 36")
    }

    @Test
    fun `Flow explicit height is preserved when rows fit`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var a = ui.Card({ children: [ ui.Label({ text: "A" }) ] });
            var f = ui.Flow({ height: 60, padding: 0, gap: 0, children: [ a ] });
            ui.mount(mon, f);
            print(f.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("60")
    }
}
