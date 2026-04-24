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
 * Unit tests for `ui_v1`'s ItemSlot widget (Lua). Mirror of [JsUiV1ItemSlotTest].
 */
class LuaUiV1ItemSlotTest {

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
    fun `ItemSlot constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local s = ui.ItemSlot{}
            print(s.kind .. " " .. s.x .. " " .. s.y .. " " .. s.width .. " " .. s.height)
            print(tostring(s.item) .. " " .. tostring(s.fluid) .. " " .. tostring(s.chemical))
            print(tostring(s.count) .. " " .. tostring(s.caption) .. " " .. s.size)
            print(s.bg .. " " .. s.border .. " " .. s.countColor .. " " .. s.captionColor)
            print(tostring(s.visible))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "ItemSlot 0 0 0 0",
            "nil nil nil",
            "nil nil 72",
            "bgCard edge hi muted",
            "true",
        )
    }

    @Test
    fun `ItemSlot ctor applies props`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local s = ui.ItemSlot{
                x=3, y=5, width=40, height=50,
                item="minecraft:iron_ingot", count=42, caption="IRON",
                size=36, bg="bgTerm", border="good",
                countColor="good", captionColor="warn",
            }
            print(s.x .. " " .. s.y .. " " .. s.width .. " " .. s.height)
            print(s.item .. " " .. s.count .. " " .. s.caption)
            print(s.size .. " " .. s.bg .. " " .. s.border)
            print(s.countColor .. " " .. s.captionColor)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "3 5 40 50",
            "minecraft:iron_ingot 42 IRON",
            "36 bgTerm good",
            "good warn",
        )
    }

    @Test
    fun `ItemSlot with item renders clearIcons then drawItem with aspect-corrected rect`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  item="minecraft:redstone" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 32x32 widget with 2px icon inset: inner 28x28. iw*9=252, ih*20=560.
        // shrink ih = floor(28*9/20 + 0.5) = floor(13.1) = 13. iw=28.
        // ix = 2 + floor((28-28)/2) = 2. iy = 2 + floor((28-13)/2) = 9.
        monitor.calls shouldContainExactly listOf(
            "drawRect(0,0,32,32,0xFF121827)",
            "clearIcons()",
            "drawItem(2,9,28,13,minecraft:redstone)",
            "drawRectOutline(0,0,32,32,0xFF2E3A4E,t=1)",
        )
    }

    @Test
    fun `ItemSlot without resource emits only slot chrome`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32 })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("clearIcons(") } shouldBe false
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1 // just the bg
        monitor.calls.count { it.startsWith("drawRectOutline(") } shouldBe 1
    }

    @Test
    fun `ItemSlot with fluid routes through drawFluid`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  fluid="minecraft:water" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("clearIcons(") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") && it.contains("minecraft:water") } shouldBe true
    }

    @Test
    fun `ItemSlot fluid takes the same inset rect as item`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  fluid="minecraft:lava", bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Inset 2px from slot edges, aspect-corrected: (2, 9, 28, 13) — matches item test.
        monitor.calls.any { it == "drawFluid(2,9,28,13,minecraft:lava)" } shouldBe true
    }

    @Test
    fun `ItemSlot with chemical routes through drawChemical`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  chemical="mekanism:hydrogen", bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Same inset rect as item+fluid: (2, 9, 28, 13).
        monitor.calls.any { it == "drawChemical(2,9,28,13,mekanism:hydrogen)" } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") } shouldBe false
    }

    @Test
    fun `Icon shape=chemical routes through drawChemical`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=40,
                              shape="chemical", chemical="mekanism:oxygen" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawChemical(") && it.contains("mekanism:oxygen") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
        monitor.calls.any { it.startsWith("drawFluid(") } shouldBe false
    }

    @Test
    fun `Icon shape=fluid routes through drawFluid`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=40,
                              shape="fluid", fluid="minecraft:water" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawFluid(") && it.contains("minecraft:water") } shouldBe true
        monitor.calls.any { it.startsWith("drawItem(") } shouldBe false
    }

    @Test
    fun `ItemSlot count overlay draws centered below icon`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  item="minecraft:redstone",
                                  count=5, bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // pxPerCol=8, pxPerRow=12. Cell-snap: firstCol=0, lastCol=3, cols=4.
        // Icon with 2px inset: iw=28, ih=13, iy=9, iy+ih=22.
        // countRow = ceil(22/12) = 2, clamped to lastRow = floor(32/12)-1 = 1.
        // startCol (centered "5" len 1 in 4 cols) = 0 + floor(3/2) = 1.
        val setCursor = monitor.calls.filter { it.startsWith("setCursorPos(") }
        setCursor shouldContainExactly listOf("setCursorPos(1,1)")
        monitor.calls.any { it == "write(5)" } shouldBe true
    }

    @Test
    fun `ItemSlot count formatter abbreviates large numbers`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            -- Four slots with counts 5, 9876, 2500000, "CUSTOM" — each at its own
            -- x so the write-cursor calls don't collide ambiguously in the capture.
            -- width=64 → 8 cells wide, so no clipping for these count strings.
            ui.mount(mon, ui.ItemSlot{ x=0,   y=0, width=64, height=64,
                                       item="minecraft:iron_ingot", count=5,
                                       bg=nil, border=nil })
            ui.mount(mon, ui.ItemSlot{ x=72,  y=0, width=64, height=64,
                                       item="minecraft:iron_ingot", count=12500,
                                       bg=nil, border=nil })
            ui.mount(mon, ui.ItemSlot{ x=144, y=0, width=64, height=64,
                                       item="minecraft:iron_ingot", count=2500000,
                                       bg=nil, border=nil })
            ui.mount(mon, ui.ItemSlot{ x=216, y=0, width=64, height=64,
                                       item="minecraft:iron_ingot", count="CUSTOM",
                                       bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        // Each write is prefixed with the item id (from drawItem's side-effect-free
        // call-log) and the count text. Just assert the count strings appear.
        writes.any { it == "write(5)" } shouldBe true
        writes.any { it == "write(12.5k)" } shouldBe true
        writes.any { it == "write(2.5M)" } shouldBe true
        writes.any { it == "write(CUSTOM)" } shouldBe true
    }

    @Test
    fun `ItemSlot count clips to slot width so it can't spill into neighbors`() {
        // width=16 → 2 cells wide at pxPerCol=8. "12.5k" (5 chars) must truncate
        // to first 2 chars "12" so it fits inside the slot.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=16, height=32,
                                  item="minecraft:iron_ingot",
                                  count=12500, bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        writes.any { it == "write(12)" } shouldBe true
        writes.any { it.contains("12.5k") } shouldBe false
    }

    @Test
    fun `ItemSlot caption clips to slot width so it can't spill into neighbors`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=16, height=44,
                                  item="minecraft:iron_ingot",
                                  caption="VAULT", bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        val writes = monitor.calls.filter { it.startsWith("write(") }
        // slotCols = 16/8 = 2 → "VAULT" (5 chars) truncates to "VA".
        writes.any { it == "write(VA)" } shouldBe true
        writes.any { it.contains("VAULT") } shouldBe false
    }

    @Test
    fun `ItemSlot caption draws one cell row below icon area`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=44,
                                  item="minecraft:iron_ingot",
                                  caption="IRON",
                                  bg=nil, border=nil })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // RecordingMonitor: pxPerCol=8, pxPerRow=12. Height=44, captionPxH=12.
        // capRow = floor((0+44-12)/12) = floor(32/12) = 2.
        // capCols = floor(32/8) = 4. "IRON" length=4 → capStartCol=0.
        val setCursor = monitor.calls.filter { it.startsWith("setCursorPos(") }
        setCursor.any { it == "setCursorPos(0,2)" } shouldBe true
        monitor.calls.any { it == "write(IRON)" } shouldBe true
    }

    @Test
    fun `ItemSlot measure returns size+captionRow when caption set`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local mon = peripheral.find("monitor")
            local a = ui.ItemSlot{ size=32 }
            local b = ui.ItemSlot{ size=32, caption="X" }
            local aw, ah = a:measure(mon)
            local bw, bh = b:measure(mon)
            print(aw .. "x" .. ah .. " " .. bw .. "x" .. bh)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // No caption: 32x32. With caption: height += pxPerRow (12) = 44.
        out.lines shouldContainExactly listOf("32x32 32x44")
    }

    @Test
    fun `ItemSlot clearIcons fires exactly once per render across multiple slots`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.VBox{ padding=0, gap=0, children={
                         ui.ItemSlot{ width=32, height=32, item="minecraft:redstone",
                                      bg=nil, border=nil },
                         ui.ItemSlot{ width=32, height=32, item="minecraft:coal",
                                      bg=nil, border=nil },
                         ui.ItemSlot{ width=32, height=32, item="minecraft:stone",
                                      bg=nil, border=nil },
                     }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it == "clearIcons()" } shouldBe 1
        monitor.calls.count { it.startsWith("drawItem(") } shouldBe 3
    }

    @Test
    fun `ItemSlot visible=false emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=32,
                                  item="minecraft:redstone", visible=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `ItemSlot zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=0, height=32,
                                  item="minecraft:redstone" })
            ui.mount(peripheral.find("monitor"),
                     ui.ItemSlot{ x=0, y=0, width=32, height=0,
                                  item="minecraft:redstone" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }
}
