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
 * Unit tests for `ui_v1`'s Icon widget (Lua). Mirror of [JsUiV1IconTest].
 */
class LuaUiV1IconTest {

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
    fun `Icon constructor applies defaults`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local i = ui.Icon{}
            print(i.kind .. " " .. i.x .. " " .. i.y .. " " .. i.width .. " " .. i.height)
            print(i.shape .. " " .. i.color)
            print(tostring(i.bg) .. " " .. tostring(i.border) .. " " .. tostring(i.bits))
            print(tostring(i.visible))
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "Icon 0 0 0 0",
            "rect info",
            "nil nil nil",
            "true",
        )
    }

    @Test
    fun `Icon ctor applies props`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local i = ui.Icon{
                x=5, y=10, width=40, height=30,
                shape="diamond", color="warn",
                bg="bgCard", border="edge",
            }
            print(i.x .. " " .. i.y .. " " .. i.width .. " " .. i.height)
            print(i.shape .. " " .. i.color .. " " .. i.bg .. " " .. i.border)
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf(
            "5 10 40 30",
            "diamond warn bgCard edge",
        )
    }

    @Test
    fun `Icon ctor defensive copy does not alias bits array`() {
        val (env, out) = mkEnv()
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            local src = {{1,0},{0,1}}
            local i = ui.Icon{ shape="bits", bits=src }
            src[1][1] = 999
            print(i.bits[1][1] .. " " .. i.bits[2][2])
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        out.lines shouldContainExactly listOf("1 1")
    }

    @Test
    fun `Icon rect shape emits single drawRect for body`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=30, shape="rect" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 1
        monitor.calls.none { it.startsWith("fillEllipse(") } shouldBe true
        monitor.calls.none { it.startsWith("drawLine(") } shouldBe true
    }

    @Test
    fun `Icon circle shape emits fillEllipse`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=30, shape="circle" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("fillEllipse(") } shouldBe 1
        monitor.calls.none { it.startsWith("drawLine(") } shouldBe true
    }

    @Test
    fun `Icon diamond shape scan-line fills with drawLine per row`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=20, shape="diamond" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawLine(") } shouldBe 20
    }

    @Test
    fun `Icon triangle shape scan-line fills with drawLine per row`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=20, shape="triangle" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawLine(") } shouldBe 20
    }

    @Test
    fun `Icon bits shape draws one drawRect per set bit`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            -- 3 set bits in a 3x3 grid
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=30, height=30, shape="bits",
                              bits={{1,0,0},{0,1,0},{0,0,1}} })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 3
    }

    @Test
    fun `Icon bg adds extra drawRect before shape`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=30, shape="rect", bg="bgCard" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.count { it.startsWith("drawRect(") } shouldBe 2
    }

    @Test
    fun `Icon border emits drawRectOutline after shape`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=30, shape="rect", border="edge" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls.any { it.startsWith("drawRectOutline(") } shouldBe true
    }

    @Test
    fun `Icon visible=false emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=30, shape="rect", visible=false })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Icon zero width or height emits nothing`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=0, height=30, shape="rect" })
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=0, shape="rect" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Icon shape=item emits clearIcons then drawItem with aspect-corrected rect`() {
        // Widget 40x40 is SQUARE in pxbuf coords, which maps to a vertical
        // stripe on screen (pxbuf aspect 20:9). Icon should shrink the wider
        // axis to match, giving a visually-square rendering.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=40, shape="item",
                              item="minecraft:redstone" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // 40x40 widget: width*9=360, height*20=800. 360 < 800, so the widget
        // is too tall — shrink the height: ih = 40*9/20 = 18.
        // Centered vertically: iy = (40 - 18)/2 = 11.
        monitor.calls shouldContainExactly listOf(
            "clearIcons()",
            "drawItem(0,11,40,18,minecraft:redstone)",
        )
    }

    @Test
    fun `Icon shape=item with no item is a no-op`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=40, height=18, shape="item" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly emptyList()
    }

    @Test
    fun `Icon shape=item clearIcons fires exactly once per render across multiple icons`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.VBox{ padding=0, gap=0, children={
                         ui.Icon{ width=40, height=18, shape="item", item="minecraft:redstone" },
                         ui.Icon{ width=40, height=18, shape="item", item="minecraft:coal" },
                         ui.Icon{ width=40, height=18, shape="item", item="minecraft:stone" },
                     }})
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Exactly one clearIcons() call, followed by 3 drawItem() calls.
        monitor.calls.count { it == "clearIcons()" } shouldBe 1
        monitor.calls.count { it.startsWith("drawItem(") } shouldBe 3
        monitor.calls.first { it == "clearIcons()" || it.startsWith("drawItem(") } shouldBe "clearIcons()"
    }

    @Test
    fun `Icon shape=item clearIcons re-arms on the next render`() {
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ width=40, height=18, shape="item", item="minecraft:redstone" })
            ui.render()
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        // Two renders = two clear+drawItem pairs.
        monitor.calls.count { it == "clearIcons()" } shouldBe 2
        monitor.calls.count { it.startsWith("drawItem(") } shouldBe 2
    }

    @Test
    fun `Icon shape=item widget wider than 20-9 aspect shrinks width instead`() {
        // Widget 100x20: width*9=900, height*20=400. 900 > 400 → too wide,
        // shrink width: iw = 20 * 20/9 = 44 (rounded). ix = (100-44)/2 = 28.
        val monitor = RecordingMonitor()
        val (env, _) = mkEnv(monitor)
        val r = CobaltLuaHost().eval(
            """
            local ui = require("ui_v1")
            ui.mount(peripheral.find("monitor"),
                     ui.Icon{ x=0, y=0, width=100, height=20, shape="item",
                              item="minecraft:stone" })
            ui.render()
            """.trimIndent(),
            "test.lua", env,
        )
        r.ok shouldBe true
        monitor.calls shouldContainExactly listOf(
            "clearIcons()",
            "drawItem(28,0,44,20,minecraft:stone)",
        )
    }
}
