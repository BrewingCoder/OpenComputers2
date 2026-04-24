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
 * Unit tests for `ui_v1`'s Button widget (JS). Mirror of [LuaUiV1ButtonTest].
 *
 * Event tests go through `ui.run()` (JS has no `ui.tick` — Phase C design):
 * we pre-queue a `monitor_touch` on the event queue, the script's onClick
 * calls `ui.exit()` to break the `__uiRun` loop.
 */
class JsUiV1ButtonTest {

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
    fun `Button constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Button({});
            print(b.kind + " " + b.x + " " + b.y + " " + b.width + " " + b.height);
            print(b.label + "|" + b.style + "|" + b.onClick);
            print(b.enabled + " " + b.visible + " " + b.borderThickness);
            print(b.textColor + " " + b.color + " " + b.borderColor);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Button 0 0 0 0",
            "|primary|null",
            "true true 2",
            "fg null null",
        )
    }

    @Test
    fun `Button primary style draws base + gradient + border + label`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:120, height:16, label:"GO" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,120,12,0xFF3498DB)"
        monitor.calls shouldContain "drawGradientV(0,0,120,6,0xFF52B6F9->0xFF3498DB)"
        monitor.calls shouldContain "drawRectOutline(0,0,120,12,0xFF5CC0FF,t=2)"
        monitor.calls shouldContain "setCursorPos(6,0)"
        monitor.calls shouldContain "write(GO)"
    }

    @Test
    fun `Button ghost style uses ghost theme token`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"", style:"ghost" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF2E3A4E)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF566276,t=2)"
    }

    @Test
    fun `Button danger style uses danger theme token`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"", style:"danger" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFFE74C3C)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFFFF7464,t=2)"
    }

    @Test
    fun `Button color override beats style mapping`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, style:"primary",
                                 color:0xFF123456|0, label:"" }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF123456)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF3A5C7E,t=2)"
    }

    @Test
    fun `Button enabled=false dims base + border + text`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"X", enabled:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF1A4C6D)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF2E607F,t=2)"
        monitor.calls shouldContain "setFg(0xFF7F7F7F)"
    }

    @Test
    fun `Button borderThickness=0 skips outline`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"", borderThickness:0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls.none { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Button empty label draws chrome and only blank-fills the text row`() {
        // Empty label still clears the text row's cell band (so a prior longer
        // label doesn't linger on the grid), but emits no glyph writes.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF3498DB)"
        monitor.calls.any { it.startsWith("drawGradientV(") } shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
        monitor.calls.filter { it.startsWith("write(") }
            .none { it.substring(6, it.length - 1).any { ch -> ch != ' ' } } shouldBe true
    }

    @Test
    fun `Button blanks its label cell-band before writing so a shrunk label does not leave stale glyphs`() {
        // Reproduces the POWER: ON/OFF bug -- mirror of the Lua test.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var btn = ui.Button({ x:0, y:0, width:180, height:16, label:"POWER: OFF" });
            ui.mount(peripheral.find("monitor"), btn);
            ui.render();
            btn.set({ label: "POWER: ON" });
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        val blanks = " ".repeat(23)
        monitor.calls shouldContain "write($blanks)"
        monitor.calls shouldContain "write(POWER: ON)"
    }

    @Test
    fun `Button visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"X", visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Button custom borderColor overrides the auto-lightened default`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:16, label:"",
                                 borderColor:0xFFFF00FF|0 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFFFF00FF,t=2)"
    }

    @Test
    fun `Button onClick fires on ui run touch within bounds then exits`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(5, 2, 40, 20, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var clicks = 0;
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:10, y:10, width:80, height:20, label:"X",
                                 onClick: function(e) { clicks++; ui.exit(); } }));
            ui.run();
            print("clicks=" + clicks);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=1")
    }

    @Test
    fun `Button onClick does not fire when touch is outside bounds`() {
        val (env, out) = mkEnv()
        // Touch at px=1,py=1 -- outside the button at (10,10,80,20).
        env.events.offer(ScriptEvent("monitor_touch", listOf(0, 0, 1, 1, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var clicks = 0;
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:10, y:10, width:80, height:20, label:"X",
                                 onClick: function(e) { clicks++; } }));
            setTimeout(function() { ui.exit(); }, 30);
            ui.run();
            print("clicks=" + clicks);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=0")
    }

    @Test
    fun `Button enabled=false suppresses onClick dispatch`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(5, 2, 40, 20, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var clicks = 0;
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:10, y:10, width:80, height:20, label:"X",
                                 enabled:false,
                                 onClick: function(e) { clicks++; } }));
            setTimeout(function() { ui.exit(); }, 30);
            ui.run();
            print("clicks=" + clicks);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("clicks=0")
    }

    @Test
    fun `Button event object carries widget + player + coords`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(3, 1, 24, 8, "alice")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Button({ x:0, y:0, width:80, height:20, label:"T",
                                 onClick: function(e) {
                                   print(e.type + "|" + e.widget.kind + "|" + e.player + "|" +
                                         e.col + "|" + e.row + "|" + e.px + "|" + e.py);
                                   ui.exit();
                                 } }));
            ui.run();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("click|Button|alice|3|1|24|8")
    }

    @Test
    fun `Button topmost-first routing beats overlapping earlier widgets`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(1, 0, 8, 8, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var who = "";
            var m = peripheral.find("monitor");
            ui.mount(m, ui.Button({ x:0, y:0, width:80, height:20, label:"A",
                                    onClick: function() { who = "A"; ui.exit(); } }));
            ui.mount(m, ui.Button({ x:0, y:0, width:80, height:20, label:"B",
                                    onClick: function() { who = "B"; ui.exit(); } }));
            ui.run();
            print("who=" + who);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("who=B")
    }

    @Test
    fun `Button set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var b = ui.Button({ style:"primary", label:"OK" });
            b.set({ style:"danger", label:"STOP", enabled:false });
            print(b.get("style") + "|" + b.get("label") + "|" + b.get("enabled"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("danger|STOP|false")
    }
}
