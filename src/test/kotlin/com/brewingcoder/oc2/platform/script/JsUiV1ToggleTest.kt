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
 * Unit tests for `ui_v1`'s Toggle widget (JS). Mirror of [LuaUiV1ToggleTest].
 *
 * Event tests go through `ui.run()`; we pre-queue a `monitor_touch`, and
 * the script's `onChange` calls `ui.exit()` to break the `__uiRun` loop.
 */
class JsUiV1ToggleTest {

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
    fun `Toggle constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var t = ui.Toggle({});
            print(t.kind + " " + t.value + " " + t.onLabel + " " + t.offLabel);
            print(t.onColor + " " + t.offColor + " " + t.label);
            print(t.enabled + " " + t.visible + " " + t.onChange);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Toggle false ON OFF",
            "good ghost null",
            "true true null",
        )
    }

    @Test
    fun `Toggle OFF state uses ghost color and draws OFF label`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:80, height:16 }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF2E3A4E)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF566276,t=2)"
        monitor.calls shouldContain "write(OFF)"
    }

    @Test
    fun `Toggle ON state uses good color and draws ON label`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:80, height:16, value:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF2ECC71)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF56F499,t=2)"
        monitor.calls shouldContain "write(ON)"
    }

    @Test
    fun `Toggle label prefix composes with state label`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:160, height:16, label:"POWER", value:true }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "write(POWER: ON)"
    }

    @Test
    fun `Toggle custom onLabel and offLabel override defaults`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:120, height:16,
                                 onLabel:"RUNNING", offLabel:"STOPPED", value:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "write(STOPPED)"
    }

    @Test
    fun `Toggle click flips value and fires onChange`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(2, 0, 20, 8, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var t = ui.Toggle({ x:0, y:0, width:80, height:20,
                                onChange: function(v, e) {
                                    print("change=" + v + " type=" + e.type);
                                    ui.exit();
                                } });
            ui.mount(peripheral.find("monitor"), t);
            ui.run();
            print("value=" + t.value);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("change=true type=click", "value=true")
    }

    @Test
    fun `Toggle enabled=false suppresses click state change`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(2, 0, 20, 8, "scott")))
        // Enabled=false still routes through ui.run -- need an independent exit.
        // The dispatcher suppresses before calling onClick (event.enabled check),
        // so the Toggle's onClick never runs; queue a second event to exit.
        env.events.offer(ScriptEvent("monitor_touch", listOf(0, 0, 0, 0, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var changes = 0;
            var t = ui.Toggle({ x:10, y:10, width:80, height:20, enabled:false,
                                onChange: function(v, e) { changes++; } });
            // A second widget outside the toggle that exits on touch.
            var exiter = ui.Button({ x:0, y:0, width:5, height:5, label:"",
                                     onClick: function(e) { ui.exit(); } });
            ui.mount(peripheral.find("monitor"), t);
            ui.mount(peripheral.find("monitor"), exiter);
            ui.run();
            print("value=" + t.value + " changes=" + changes);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("value=false changes=0")
    }

    @Test
    fun `Toggle user-supplied onClick is ignored - Toggle owns its click`() {
        val (env, out) = mkEnv()
        env.events.offer(ScriptEvent("monitor_touch", listOf(2, 0, 20, 8, "scott")))
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var userClicks = 0;
            var t = ui.Toggle({ x:0, y:0, width:80, height:20,
                                onClick: function(e) { userClicks++; },
                                onChange: function(v, e) { ui.exit(); } });
            ui.mount(peripheral.find("monitor"), t);
            ui.run();
            print("value=" + t.value + " user=" + userClicks);
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("value=true user=0")
    }

    @Test
    fun `Toggle enabled=false dims base border and text`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:80, height:16, value:true, enabled:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF176638)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF2B7A4C,t=2)"
    }

    @Test
    fun `Toggle visible=false emits no monitor calls`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:80, height:16, visible:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
    }

    @Test
    fun `Toggle set and get`() {
        val (env, out) = mkEnv()
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            var t = ui.Toggle({ label:"POWER" });
            t.set({ value:true, onLabel:"LIVE" });
            print(t.get("label") + " " + t.get("value") + " " + t.get("onLabel"));
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("POWER true LIVE")
    }

    @Test
    fun `Toggle custom onColor and offColor override defaults`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = RhinoJSHost().eval(
            """
            var ui = require("ui_v1");
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle({ x:0, y:0, width:80, height:16,
                                 onColor:0xFF123456|0, offColor:0xFFABCDEF|0, value:false }));
            ui.render();
            """.trimIndent(),
            "test.js", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFFABCDEF)"
    }
}
