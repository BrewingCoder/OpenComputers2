package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Boot-path tests. The most load-bearing of these is
 * [`stub boot — VM emits the expected byte to UART`][
 *   stub_boot_VM_emits_the_expected_byte_to_UART], which is the first
 * end-to-end test that proves Sedna fetches real instructions, executes
 * them, and the UART16550A queues bytes the host can read. Once a real
 * Linux kernel image lands the same path runs vmlinux instead of the stub.
 */
class ControlPlaneBootTest {

    /** See ControlPlaneVmTest — Windows mmap on the disk needs GC to release. */
    @AfterEach
    fun releaseMmap() {
        System.gc()
        @Suppress("DEPRECATION") System.runFinalization()
    }

    @Test
    fun `helloUartStub produces a 16-byte payload`() {
        val bytes = ControlPlaneBoot.helloUartStub(uartBase = 0x10000000L, char = 'A'.code.toByte())
        bytes.size shouldBe 16
    }

    @Test
    fun `helloUartStub bakes the UART base into the LUI immediate`() {
        // For uartBase=0x10000000 the upper 20 bits are 0x10000.
        // LUI encoding: imm[31:12] | rd[11:7] | opcode[6:0]
        //   imm = 0x10000, rd = t0 (x5), opcode = 0x37
        //   = (0x10000 << 12) | (5 << 7) | 0x37 = 0x100002B7
        val bytes = ControlPlaneBoot.helloUartStub(uartBase = 0x10000000L, char = 'O'.code.toByte())
        // Little-endian: byte 0..3 = 0xB7, 0x02, 0x00, 0x10
        bytes[0] shouldBe 0xB7.toByte()
        bytes[1] shouldBe 0x02.toByte()
        bytes[2] shouldBe 0x00.toByte()
        bytes[3] shouldBe 0x10.toByte()
    }

    @Test
    fun `helloUartStub rejects unaligned uartBase`() {
        val ex = runCatching {
            ControlPlaneBoot.helloUartStub(uartBase = 0x10000123L, char = 'X'.code.toByte())
        }.exceptionOrNull()
        ex.shouldBeIllegalArgumentWithMessageContaining("4KB-aligned")
    }

    @Test
    fun `stub boot — VM emits the expected byte to UART`() {
        // Step 1: build a throwaway VM to discover the runtime UART address.
        // Sedna allocates MMIO addresses deterministically per board config.
        val uartBase = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.uartBase }

        // Step 2: build the boot image with that address baked in.
        val stub = ControlPlaneBoot.helloUartStub(uartBase = uartBase, char = 'O'.code.toByte())

        // Step 3: boot a real VM with that image. After ~5 cycles the SB
        // instruction has fired; we step a generous budget to absorb any
        // Sedna reset-vector preamble before our code runs.
        ControlPlaneVm(
            ramBytes = 1 * 1024 * 1024,
            bootImage = stub,
        ).use { vm ->
            vm.step(50_000)
            val captured = vm.console.snapshotBytes()
            captured.size shouldBeGreaterThan 0
            captured[0] shouldBe 'O'.code.toByte()
        }
    }

    @Test
    fun `stub boot — describe surfaces accumulated console bytes`() {
        val uartBase = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.uartBase }
        val stub = ControlPlaneBoot.helloUartStub(uartBase = uartBase, char = '!'.code.toByte())
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024, bootImage = stub).use { vm ->
            vm.step(50_000)
            // After at least one byte has been latched, describe() reflects it.
            val text = vm.describe()
            text shouldContain "ram=1MB"
            text shouldContain "console=1B"
        }
    }

    @Test
    fun `stub boot — recentLines wraps the captured byte into a one-element list`() {
        val uartBase = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.uartBase }
        // 'X' followed by no newline → one line, content "X".
        val stub = ControlPlaneBoot.helloUartStub(uartBase = uartBase, char = 'X'.code.toByte())
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024, bootImage = stub).use { vm ->
            vm.step(50_000)
            val lines = vm.console.recentLines(8)
            lines shouldHaveSize 1
            lines[0] shouldBe "X"
        }
    }

    @Test
    fun `bootArgs round-trip without throwing`() {
        // We can't read the device tree back from R5Board today, so this test
        // just guarantees the setBootArguments path is wired up — passing a
        // real kernel command line doesn't crash the VM constructor.
        ControlPlaneVm(
            ramBytes = 1 * 1024 * 1024,
            bootArgs = "console=ttyS0 root=/dev/vda rw earlycon",
        ).use { vm ->
            vm.step(10_000)
            // No assertions on bootArgs content — the test is "did the VM build".
            vm.cycles shouldBeGreaterThan 0L
        }
    }
}

private fun Throwable?.shouldBeIllegalArgumentWithMessageContaining(needle: String) {
    require(this is IllegalArgumentException) { "expected IllegalArgumentException, got ${this?.javaClass?.simpleName}" }
    require((message ?: "").contains(needle)) { "expected message containing '$needle', got '${message}'" }
}
