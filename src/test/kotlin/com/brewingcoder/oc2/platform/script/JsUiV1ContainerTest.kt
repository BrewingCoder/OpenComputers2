package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for `ui_v1`'s layout containers (VBox, HBox, Spacer, Card) in JS.
 * Mirrors [LuaUiV1ContainerTest] — proves API + layout symmetry across Lua/JS.
 */
class JsUiV1ContainerTest {

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
    fun `VBox HBox Spacer Card constructors exist`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            print(typeof ui.VBox + " " + typeof ui.HBox + " " + typeof ui.Spacer + " " + typeof ui.Card);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("function function function function")
    }

    @Test
    fun `VBox stacks fixed-height children top-to-bottom`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ text:"a", height:20 });
            var b = ui.Label({ text:"b", height:30 });
            var c = ui.Label({ text:"c", height:40 });
            var box = ui.VBox({ x:0, y:0, width:200, height:200, children:[a,b,c] });
            box.layout();
            function row(w) { return w.x + " " + w.y + " " + w.width + " " + w.height; }
            print(row(a)); print(row(b)); print(row(c));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0 0 200 20",
            "0 20 200 30",
            "0 50 200 40",
        )
    }

    @Test
    fun `HBox stacks fixed-width children left-to-right`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ text:"a", width:40 });
            var b = ui.Label({ text:"b", width:60 });
            var box = ui.HBox({ x:10, y:20, width:200, height:48, children:[a,b] });
            box.layout();
            function row(w) { return w.x + " " + w.y + " " + w.width + " " + w.height; }
            print(row(a)); print(row(b));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10 20 40 48",
            "50 20 60 48",
        )
    }

    @Test
    fun `Spacer defaults to flex=1 and eats remainder in VBox`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var top = ui.Label({ text:"top", height:10 });
            var sp  = ui.Spacer({});
            var bot = ui.Label({ text:"bot", height:20 });
            var box = ui.VBox({ x:0, y:0, width:100, height:100, children:[top, sp, bot] });
            box.layout();
            print(sp.flex);
            print(top.y + " " + top.height);
            print(sp.y  + " " + sp.height);
            print(bot.y + " " + bot.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "1",
            "0 10",
            "10 70",
            "80 20",
        )
    }

    @Test
    fun `multiple flex children share the remainder proportionally`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ flex:1 });
            var b = ui.Label({ flex:3 });
            var box = ui.HBox({ x:0, y:0, width:120, height:40, children:[a,b] });
            box.layout();
            print(a.x + " " + a.width);
            print(b.x + " " + b.width);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0 30",
            "30 90",
        )
    }

    @Test
    fun `fixed + flex children subtract fixed before distributing remainder`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var fixed = ui.Label({ height:40 });
            var flex  = ui.Label({ flex:1 });
            var box = ui.VBox({ x:0, y:0, width:50, height:100, children:[fixed, flex] });
            box.layout();
            print(fixed.y + " " + fixed.height);
            print(flex.y  + " " + flex.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0 40",
            "40 60",
        )
    }

    @Test
    fun `gap prop inserts space between visible children`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ height:10 });
            var b = ui.Label({ height:10 });
            var c = ui.Label({ height:10 });
            var box = ui.VBox({ x:0, y:0, width:50, height:100, gap:5, children:[a,b,c] });
            box.layout();
            print(a.y + " " + b.y + " " + c.y);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("0 15 30")
    }

    @Test
    fun `padding shrinks the inner rect for children`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var child = ui.Label({ height:10 });
            var box = ui.VBox({ x:0, y:0, width:100, height:50, padding:8, children:[child] });
            box.layout();
            print(child.x + " " + child.y + " " + child.width + " " + child.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("8 8 84 10")
    }

    @Test
    fun `invisible children are skipped in layout`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ height:10 });
            var ghost = ui.Label({ height:999, visible:false });
            var c = ui.Label({ height:20 });
            var box = ui.VBox({ x:0, y:0, width:50, height:100, children:[a, ghost, c] });
            box.layout();
            print(a.y + " " + c.y);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("0 10")
    }

    @Test
    fun `VBox emits no chrome calls by default`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.VBox({ x:0, y:0, width:100, height:50, children:[] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Card draws bg rect then border before children`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Card({ x:0, y:0, width:80, height:40, children:[] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly listOf(
            "drawRect(0,0,80,40,0xFF121827)",
            "drawRectOutline(0,0,80,40,0xFF2E3A4E,t=1)",
        )
    }

    @Test
    fun `Card defaults padding to 4`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var child = ui.Label({ height:10 });
            var card = ui.Card({ x:0, y:0, width:80, height:40, children:[child] });
            card.layout();
            print(card.padding);
            print(child.x + " " + child.y + " " + child.width);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("4", "4 4 72")
    }

    @Test
    fun `Card padding=0 is preserved when explicitly passed`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var c = ui.Card({ padding: 0 });
            print(c.padding);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("0")
    }

    @Test
    fun `Card border=0 disables the outline`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Card({ x:0, y:0, width:80, height:40, border:0, children:[] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("drawRectOutline") } shouldBe true
    }

    @Test
    fun `mount auto-sizes a container root to the monitor pixel bounds`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var mon = peripheral.find("monitor");
            var box = ui.VBox({ children:[] });
            ui.mount(mon, box);
            print(box.x + " " + box.y + " " + box.width + " " + box.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("0 0 320 240")
    }

    @Test
    fun `nested HBox inside VBox lays out children correctly`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ width:30 });
            var b = ui.Label({ width:50 });
            var row = ui.HBox({ height:20, children:[a, b] });
            var top = ui.Label({ height:10 });
            var box = ui.VBox({ x:0, y:0, width:100, height:100, children:[top, row] });
            box.layout();
            function s(w) { return w.x + " " + w.y + " " + w.width + " " + w.height; }
            print(s(top)); print(s(row)); print(s(a)); print(s(b));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0 0 100 10",
            "0 10 100 20",
            "0 10 30 20",
            "30 10 50 20",
        )
    }

    @Test
    fun `Card with explicit bg token overrides default palette`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Card({ x:0, y:0, width:40, height:20, bg:"good", border:0, children:[] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,40,20,0xFF2ECC71)"
    }

    @Test
    fun `VBox with explicit bg draws chrome even without Card`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.VBox({ x:0, y:0, width:40, height:20, bg:"bgCard", children:[] }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,40,20,0xFF121827)"
    }
}
