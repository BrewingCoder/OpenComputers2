package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Smoke tests for the Tier-2 Linux VM holder. We don't load a kernel here —
 * the goal is to prove Sedna is on the classpath, the R5Board accepts the
 * default RAM mapping, the cycle counter advances when we [step], and the
 * disk + console plumbing wires through cleanly.
 */
class ControlPlaneVmTest {

    /**
     * See ControlPlaneDiskTest — Windows mmap on the disk-backed
     * ByteBufferBlockDevice doesn't release until GC, which blocks @TempDir
     * cleanup. Force it.
     */
    @AfterEach
    fun releaseMmap() {
        System.gc()
        System.runFinalization()
    }

    @Test
    fun `step advances the cycle counter`() {
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            val before = vm.cycles
            before shouldBe 0L

            val after = vm.step(100_000)

            after shouldBeGreaterThan before
        }
    }

    @Test
    fun `describe surfaces the ram size and base`() {
        ControlPlaneVm(ramBytes = 4 * 1024 * 1024).use { vm ->
            val text = vm.describe()
            text shouldContain "ram=4MB"
            text shouldContain "0x80000000"
            text shouldContain "disk=none"
        }
    }

    @Test
    fun `cycle counter accumulates across steps`() {
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            vm.step(50_000)
            val mid = vm.cycles
            vm.step(50_000)
            val end = vm.cycles
            end shouldBeGreaterThan mid
            mid shouldBeGreaterThan 0L
        }
    }

    @Test
    fun `vm with attached disk reports disk capacity in describe`(@TempDir dir: File) {
        val img = File(dir, "vm.img")
        ControlPlaneVm(
            ramBytes = 1 * 1024 * 1024,
            diskFile = img,
            diskSizeBytes = 8L * 1024L * 1024L, // 8 MB — enough to exercise virtio-blk
        ).use { vm ->
            vm.describe() shouldContain "disk=8MB"
            vm.diskCapacity shouldBe 8L * 1024L * 1024L
        }

        // File survives VM close and matches requested size.
        img.length() shouldBe 8L * 1024L * 1024L
    }

    @Test
    fun `vm without disk has zero disk capacity`() {
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            vm.diskCapacity shouldBe 0L
        }
    }

    @Test
    fun `console capture is empty on a freshly-stepped VM with no firmware`() {
        // No kernel loaded → CPU spins on illegal-instruction traps → no UART output.
        // ControlPlaneBootTest covers the opt-in path where a bootImage is supplied.
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            vm.step(50_000)
            vm.console.byteCount shouldBe 0
        }
    }

    @Test
    fun `close is idempotent`(@TempDir dir: File) {
        val vm = ControlPlaneVm(
            ramBytes = 1 * 1024 * 1024,
            diskFile = File(dir, "idem.img"),
            diskSizeBytes = 4096L,
        )
        vm.step(10_000)
        vm.close()
        vm.close() // second call must not throw
    }
}
