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

/**
 * JS mirror of [LuaUiV1FitTest] — verifies hug-content measure/layout
 * behavior is symmetric across Cobalt and Rhino.
 */
class JsUiV1FitTest {

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
    fun `Label_measure returns text-pixel width and one row height`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var lbl = ui.Label({ text: "Hello" });
            var m = lbl.measure(mon);
            print(m[0] + " " + m[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("40 12")
    }

    @Test
    fun `Card_measure wraps child plus padding`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var card = ui.Card({ children: [ ui.Label({ text: "Hi" }) ] });
            var m = card.measure(mon);
            print(m[0] + " " + m[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // Cell-aligned: 2-char content → 4-cell-wide Card (32 px), 3 rows (36 px).
        out.lines shouldBe listOf("32 36")
    }

    @Test
    fun `VBox hug-sizes a Card child to its label height`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var card = ui.Card({ children: [ ui.Label({ text: "Hi" }) ] });
            var root = ui.VBox({ padding: 0, gap: 0, children: [ card ] });
            ui.mount(mon, root);
            print(card.x + " " + card.y + " " + card.width + " " + card.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // Hug both axes with cell-aligned snap → 32×36.
        out.lines shouldBe listOf("0 0 32 36")
    }

    @Test
    fun `VBox stacks two hug-sized Cards with gap`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var a = ui.Card({ children: [ ui.Label({ text: "A" }) ] });
            var b = ui.Card({ children: [ ui.Label({ text: "Bee" }) ] });
            var root = ui.VBox({ padding: 0, gap: 4, children: [ a, b ] });
            ui.mount(mon, root);
            print(a.y + " " + a.height + " " + b.y + " " + b.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // Card("A") h=36 at y=0. gap=4. Card("Bee") h=36 at y=40.
        out.lines shouldBe listOf("0 36 40 36")
    }

    @Test
    fun `explicit height still wins over hug-content`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var card = ui.Card({ height: 60, children: [ ui.Label({ text: "Hi" }) ] });
            var root = ui.VBox({ padding: 0, gap: 0, children: [ card ] });
            ui.mount(mon, root);
            print(card.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("60")
    }

    @Test
    fun `flex still wins over hug-content`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var fit = ui.Card({ children: [ ui.Label({ text: "Hi" }) ] });
            var fill = ui.Card({ flex: 1, children: [ ui.Label({ text: "Rest" }) ] });
            var root = ui.VBox({ padding: 0, gap: 0, children: [ fit, fill ] });
            ui.mount(mon, root);
            print(fit.height + " " + fill.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        // fit=36 (cell-aligned), remainder=240-36=204.
        out.lines shouldBe listOf("36 204")
    }
}
