package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.peripheral.EnergyPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral
import com.brewingcoder.oc2.platform.peripheral.FluidPeripheral.FluidSnapshot
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.peripheral.RedstonePeripheral
import com.brewingcoder.oc2.platform.storage.InMemoryMount
import com.brewingcoder.oc2.platform.storage.WritableMount
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RedstoneFluidEnergyBindingTest {

    private class CapturingOut : ShellOutput {
        val lines = mutableListOf<String>()
        override fun println(line: String) { lines.add(line) }
        override fun clear() {}
    }

    private class FakeRedstone(override val name: String, val inputLevel: Int = 0) : RedstonePeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        var outputLevel: Int = 0
        override fun getInput(): Int = inputLevel
        override fun getOutput(): Int = outputLevel
        override fun setOutput(level: Int) { outputLevel = level.coerceIn(0, 15) }
    }

    private class FakeFluid(override val name: String, capacity: Int) : FluidPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        private val buf: MutableList<FluidSnapshot?> = mutableListOf(null)
        private val cap = capacity
        fun set(snap: FluidSnapshot?) { buf[0] = snap }
        override fun tanks(): Int = 1
        override fun getFluid(tank: Int): FluidSnapshot? = if (tank == 1) buf[0] else null
        override fun list(): List<FluidSnapshot?> = buf.toList()
        override fun push(target: FluidPeripheral, amount: Int): Int {
            val src = buf[0] ?: return 0
            val moved = amount.coerceAtMost(src.amount)
            (target as FakeFluid).set(FluidSnapshot(src.id, moved))
            buf[0] = if (src.amount - moved <= 0) null else FluidSnapshot(src.id, src.amount - moved)
            return moved
        }
        override fun pull(source: FluidPeripheral, amount: Int): Int = (source as FakeFluid).push(this, amount)
        override fun destroy(amount: Int): Int {
            val cur = buf[0] ?: return 0
            val killed = amount.coerceAtMost(cur.amount)
            buf[0] = if (cur.amount - killed <= 0) null else FluidSnapshot(cur.id, cur.amount - killed)
            return killed
        }
    }

    private class FakeEnergy(override val name: String, var current: Int, val max: Int) : EnergyPeripheral {
        override val location: com.brewingcoder.oc2.platform.Position = com.brewingcoder.oc2.platform.Position.ORIGIN
        override fun stored(): Int = current
        override fun capacity(): Int = max
        override fun pull(source: EnergyPeripheral, amount: Int): Int {
            val src = source as FakeEnergy
            val moved = minOf(amount, src.current, max - current)
            src.current -= moved
            current += moved
            return moved
        }
        override fun push(target: EnergyPeripheral, amount: Int): Int = (target as FakeEnergy).pull(this, amount)
        override fun destroy(amount: Int): Int {
            val killed = amount.coerceAtMost(current)
            current -= killed
            return killed
        }
    }

    private class FakeEnv(
        override val mount: WritableMount,
        override val cwd: String,
        override val out: ShellOutput,
        private val periphs: List<Peripheral>,
    ) : ScriptEnv {
        override fun findPeripheral(kind: String): Peripheral? = periphs.firstOrNull { it.kind == kind }
        override fun listPeripherals(kind: String?): List<Peripheral> =
            if (kind == null) periphs else periphs.filter { it.kind == kind }
        override val network: NetworkAccess = NetworkAccess.NOOP
    }

    private fun mount(): WritableMount = InMemoryMount(4096)

    // ---------- Redstone ----------

    @Test
    fun `lua redstone read input and write output`() {
        val rs = FakeRedstone("rs", inputLevel = 9)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local r = peripheral.find("redstone")
            print(r.getInput())
            r.setOutput(11)
            print(r.getOutput())
        """.trimIndent(), "rs.lua", FakeEnv(mount(), "", out, listOf(rs)))
        r.ok shouldBe true
        out.lines shouldBe listOf("9", "11")
        rs.outputLevel shouldBe 11
    }

    @Test
    fun `js redstone read input and write output`() {
        val rs = FakeRedstone("rs", inputLevel = 9)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var r = peripheral.find("redstone");
            print(r.getInput());
            r.setOutput(11);
            print(r.getOutput());
        """.trimIndent(), "rs.js", FakeEnv(mount(), "", out, listOf(rs)))
        r.ok shouldBe true
        out.lines shouldBe listOf("9", "11")
        rs.outputLevel shouldBe 11
    }

    // ---------- Fluid ----------

    @Test
    fun `lua fluid push moves between tanks`() {
        val src = FakeFluid("src", 5000).apply { set(FluidSnapshot("minecraft:water", 3000)) }
        val dst = FakeFluid("dst", 5000)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local list = peripheral.list("fluid")
            local s, d
            for _, p in ipairs(list) do if p.name == "src" then s = p else d = p end end
            print(s.push(d, 1500))
            print(s.getFluid(1).amount)
            print(d.getFluid(1).id, d.getFluid(1).amount)
        """.trimIndent(), "fl.lua", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("1500", "1500", "minecraft:water\t1500")
    }

    @Test
    fun `js fluid push moves between tanks`() {
        val src = FakeFluid("src", 5000).apply { set(FluidSnapshot("minecraft:water", 3000)) }
        val dst = FakeFluid("dst", 5000)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var list = peripheral.list("fluid");
            var s, d;
            for (var i = 0; i < list.length; i++) {
              if (list[i].name === "src") s = list[i]; else d = list[i];
            }
            print(s.push(d, 1500));
            print(s.getFluid(1).amount);
            print(d.getFluid(1).id + " " + d.getFluid(1).amount);
        """.trimIndent(), "fl.js", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("1500", "1500", "minecraft:water 1500")
    }

    // ---------- Energy ----------

    @Test
    fun `lua fluid destroy voids without sending anywhere`() {
        val src = FakeFluid("src", 5000).apply { set(FluidSnapshot("minecraft:lava", 1000)) }
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local fl = peripheral.find("fluid")
            print(fl.destroy(400))             -- 400 mB destroyed
            print(fl.getFluid(1).amount)        -- 600 mB left
        """.trimIndent(), "fldestroy.lua", FakeEnv(mount(), "", out, listOf(src)))
        r.ok shouldBe true
        out.lines shouldBe listOf("400", "600")
    }

    @Test
    fun `lua energy destroy voids FE`() {
        val en = FakeEnergy("en", current = 1000, max = 5000)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local e = peripheral.find("energy")
            print(e.destroy(300))   -- 300 FE destroyed
            print(e.stored())       -- 700 FE left
        """.trimIndent(), "endestroy.lua", FakeEnv(mount(), "", out, listOf(en)))
        r.ok shouldBe true
        out.lines shouldBe listOf("300", "700")
    }

    @Test
    fun `lua energy push transfers FE`() {
        val src = FakeEnergy("src", current = 1000, max = 5000)
        val dst = FakeEnergy("dst", current = 0, max = 5000)
        val out = CapturingOut()
        val r = CobaltLuaHost().eval("""
            local list = peripheral.list("energy")
            local s, d
            for _, p in ipairs(list) do if p.name == "src" then s = p else d = p end end
            print(s.push(d, 400))
            print(s.stored(), d.stored())
        """.trimIndent(), "en.lua", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("400", "600\t400")
    }

    @Test
    fun `js energy push transfers FE`() {
        val src = FakeEnergy("src", current = 1000, max = 5000)
        val dst = FakeEnergy("dst", current = 0, max = 5000)
        val out = CapturingOut()
        val r = RhinoJSHost().eval("""
            var list = peripheral.list("energy");
            var s, d;
            for (var i = 0; i < list.length; i++) {
              if (list[i].name === "src") s = list[i]; else d = list[i];
            }
            print(s.push(d, 400));
            print(s.stored() + " " + d.stored());
        """.trimIndent(), "en.js", FakeEnv(mount(), "", out, listOf(src, dst)))
        r.ok shouldBe true
        out.lines shouldBe listOf("400", "600 400")
    }
}
