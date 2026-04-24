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
 * Unit tests for `ui_v1`'s Toggle widget (Lua). Mirror of [JsUiV1ToggleTest].
 *
 * Default colors:
 *   value=false -> offColor "ghost"  = 0xFF2E3A4E; border lighten(+40) = 0xFF566276
 *   value=true  -> onColor  "good"   = 0xFF2ECC71; border lighten(+40) = 0xFF56F499
 */
class LuaUiV1ToggleTest {

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
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{}
            print(t.kind, tostring(t.value), t.onLabel, t.offLabel)
            print(t.onColor, t.offColor, tostring(t.label))
            print(tostring(t.enabled), tostring(t.visible), tostring(t.onChange))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Toggle\tfalse\tON\tOFF",
            "good\tghost\tnil",
            "true\ttrue\tnil",
        )
    }

    @Test
    fun `Toggle OFF state uses ghost color and draws OFF label`() {
        // width=80, height=16 -> snappedH=12, textRow=0. Label "OFF" (3 chars)
        // at pxPerCol=8 -> textPx=24. startPx = (80-24)/2 = 28. textCol = 3.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=80, height=16 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
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
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=80, height=16, value=true })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
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
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=160, height=16, label="POWER", value=true })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "write(POWER: ON)"
    }

    @Test
    fun `Toggle custom onLabel and offLabel override defaults`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=120, height=16,
                                onLabel="RUNNING", offLabel="STOPPED", value=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "write(STOPPED)"
    }

    @Test
    fun `Toggle click flips value from false to true`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{ x=0, y=0, width=80, height=20 }
            ui.mount(peripheral.find("monitor"), t)
            print("before=" .. tostring(t.value))
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            print("after=" .. tostring(t.value))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("before=false", "after=true")
    }

    @Test
    fun `Toggle click fires onChange with new value and event`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{ x=0, y=0, width=80, height=20,
                                 onChange=function(v, e)
                                    print("change=" .. tostring(v) .. " type=" .. e.type)
                                 end }
            ui.mount(peripheral.find("monitor"), t)
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("change=true type=click")
    }

    @Test
    fun `Toggle two clicks flip value back to false`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{ x=0, y=0, width=80, height=20 }
            ui.mount(peripheral.find("monitor"), t)
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            print("value=" .. tostring(t.value))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("value=false")
    }

    @Test
    fun `Toggle enabled=false suppresses click state change and onChange`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local changes = 0
            local t = ui.Toggle{ x=0, y=0, width=80, height=20, enabled=false,
                                 onChange=function(v, e) changes = changes + 1 end }
            ui.mount(peripheral.find("monitor"), t)
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            print("value=" .. tostring(t.value) .. " changes=" .. changes)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("value=false changes=0")
    }

    @Test
    fun `Toggle user-supplied onClick prop is ignored - Toggle owns its click`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local userClicks = 0
            local t = ui.Toggle{ x=0, y=0, width=80, height=20,
                                 onClick=function(e) userClicks = userClicks + 1 end }
            ui.mount(peripheral.find("monitor"), t)
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            print("value=" .. tostring(t.value) .. " user=" .. userClicks)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Value flipped (Toggle's own onClick ran); user's onClick was overridden.
        out.lines shouldBe listOf("value=true user=0")
    }

    @Test
    fun `Toggle enabled=false dims base border and text`() {
        // good = 0xFF2ECC71 -> dim: R=46/2=23=0x17, G=204/2=102=0x66, B=113/2=56=0x38 -> 0xFF176638
        // border 0xFF56F499 -> dim: R=86/2=43=0x2B, G=244/2=122=0x7A, B=153/2=76=0x4C -> 0xFF2B7A4C
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=80, height=16, value=true, enabled=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFF176638)"
        monitor.calls shouldContain "drawRectOutline(0,0,80,12,0xFF2B7A4C,t=2)"
    }

    @Test
    fun `Toggle visible=false emits no monitor calls and no click`() {
        val monitor = RecordingMonitor()
        val (env, out) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{ x=0, y=0, width=80, height=16, visible=false }
            ui.mount(peripheral.find("monitor"), t)
            ui.render()
            ui.tick({"monitor_touch", 2, 0, 20, 8, "scott"})
            print("value=" .. tostring(t.value))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldBe emptyList()
        out.lines shouldBe listOf("value=false")
    }

    @Test
    fun `Toggle set and get`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local t = ui.Toggle{ label="POWER" }
            t:set{ value=true, onLabel="LIVE" }
            print(t:get("label"), tostring(t:get("value")), t:get("onLabel"))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldBe listOf("POWER\ttrue\tLIVE")
    }

    @Test
    fun `Toggle custom onColor and offColor override defaults`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Toggle{ x=0, y=0, width=80, height=16,
                                onColor=0xFF123456, offColor=0xFFABCDEF, value=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContain "drawRect(0,0,80,12,0xFFABCDEF)"
    }
}
