package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.UnionMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.ResourceMount
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Unit tests for `ui_v1`'s Stack overlay container (JS). Mirror of [LuaUiV1StackTest].
 */
class JsUiV1StackTest {

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
    fun `Stack constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var s = ui.Stack({});
            print(s.kind + " " + s.x + " " + s.y + " " + s.width + " " + s.height + " " + s.padding);
            print(s.children.length + " " + s.visible);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Stack 0 0 0 0 0",
            "0 true",
        )
    }

    @Test
    fun `Stack gives every child the same inner rect`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ text:"A" });
            var b = ui.Label({ text:"B" });
            var c = ui.Label({ text:"C" });
            var s = ui.Stack({ x:10, y:20, width:100, height:60, children:[a,b,c] });
            s.layout();
            print(a.x + " " + a.y + " " + a.width + " " + a.height);
            print(b.x + " " + b.y + " " + b.width + " " + b.height);
            print(c.x + " " + c.y + " " + c.width + " " + c.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "10 20 100 60",
            "10 20 100 60",
            "10 20 100 60",
        )
    }

    @Test
    fun `Stack padding shrinks the inner rect for all children`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ text:"A" });
            var b = ui.Label({ text:"B" });
            var s = ui.Stack({ x:0, y:0, width:100, height:60, padding:8, children:[a,b] });
            s.layout();
            print(a.x + " " + a.y + " " + a.width + " " + a.height);
            print(b.x + " " + b.y + " " + b.width + " " + b.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "8 8 84 44",
            "8 8 84 44",
        )
    }

    @Test
    fun `Stack draws children in declaration order so last child is on top`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                ui.Stack({ x:0, y:0, width:40, height:40, children:[
                    ui.Icon({ shape:"rect", color: (0xFF0000FF | 0) }),
                    ui.Icon({ shape:"rect", color: (0xFF00FF00 | 0) }),
                    ui.Icon({ shape:"rect", color: (0xFFFF0000 | 0) }),
                ]}));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val rects = monitor.calls.filter { it.startsWith("drawRect(") }
        rects.size shouldBe 3
        val colors = rects.map { it.substringAfterLast(",").removeSuffix(")").trim() }
        colors shouldContainExactly listOf("0xFF0000FF", "0xFF00FF00", "0xFFFF0000")
    }

    @Test
    fun `Stack measure returns max of children plus padding`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Icon({ width:30, height:20 });
            var b = ui.Icon({ width:50, height:10 });
            var s = ui.Stack({ padding:4, children:[a,b] });
            var m = s.measure();
            print(m[0] + " " + m[1]);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("58 28")
    }

    @Test
    fun `Stack visible=false skips draw entirely`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                ui.Stack({ x:0, y:0, width:40, height:40, visible:false, children:[
                    ui.Icon({ shape:"rect", color:"info" }),
                ]}));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Stack bg and border emit chrome before children`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                ui.Stack({ x:0, y:0, width:40, height:40, bg:"bgCard", border:"edge",
                           children:[ ui.Icon({ shape:"rect", color:"info" }) ]}));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 2
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Stack hit-test picks the topmost child (last in list)`() {
        val (env, out) = mkEnv()
        // Pre-queue a touch at (20, 20) -- both Buttons fill the Stack inner
        // rect, but only the last child (top) should fire.
        env.events.offer(ScriptEvent("monitor_touch", listOf(2, 1, 20, 20, "tester")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var fired = "none";
            var back = ui.Button({ label:"BACK",
                onClick: function(e) { fired = "back"; ui.exit(); } });
            var top  = ui.Button({ label:"TOP",
                onClick: function(e) { fired = "top"; ui.exit(); } });
            ui.mount(peripheral.find("monitor"),
                ui.Stack({ x:0, y:0, width:80, height:40, children:[back, top] }));
            ui.run();
            print(fired);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("top")
    }

    @Test
    fun `Stack hides invisible children from draw`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                ui.Stack({ x:0, y:0, width:40, height:40, children:[
                    ui.Icon({ shape:"rect", color:"info" }),
                    ui.Icon({ shape:"rect", color:"good", visible:false }),
                ]}));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
    }

    @Test
    fun `Stack ignores flex on children (all get full rect)`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var a = ui.Label({ text:"A", flex:1 });
            var b = ui.Label({ text:"B", flex:3 });
            var s = ui.Stack({ x:0, y:0, width:80, height:40, children:[a,b] });
            s.layout();
            print(a.x + " " + a.y + " " + a.width + " " + a.height);
            print(b.x + " " + b.y + " " + b.width + " " + b.height);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "0 0 80 40",
            "0 0 80 40",
        )
    }
}
