package com.brewingcoder.oc2.platform.vm

import li.cil.sedna.api.memory.MemoryMap
import li.cil.sedna.memory.MemoryMaps
import java.io.InputStream

/**
 * Pure-Kotlin boot-path helper for [ControlPlaneVm]. Lives in `platform/vm/`
 * (Rule D — no MC types) so the boot logic can be exercised without a server.
 *
 * The boot model mirrors a real RISC-V virt machine:
 *   1. RAM, UART, virtio devices are added to the [li.cil.sedna.riscv.R5Board]
 *      (handled by [ControlPlaneVm]).
 *   2. A firmware / kernel image gets loaded into RAM at the address Sedna
 *      will jump to ([li.cil.sedna.riscv.R5Board.getDefaultProgramStart]).
 *   3. Kernel command-line goes through `setBootArguments` so the auto-built
 *      device tree's `chosen/bootargs` string carries it into the guest.
 *   4. `initialize()` builds + writes the device tree + reset vector and
 *      programs the CPU registers (a0=hartid, a1=DTB pointer).
 *
 * For R1 we ship a tiny hand-rolled stub (see [helloUartStub]) that proves
 * the path. A real Linux kernel image drops into the same path once a
 * cross-compiled vmlinux + OpenSBI fw_jump.bin are available — see
 * `docs/03-tier2-control-plane.md` for the kernel build plan.
 */
object ControlPlaneBoot {

    /**
     * Copy [bytes] into [memoryMap] starting at [address]. The address must
     * fall inside an attached RAM region; bytes that overrun the region throw
     * [li.cil.sedna.api.memory.MemoryAccessException].
     */
    fun loadBytes(memoryMap: MemoryMap, address: Long, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        MemoryMaps.store(memoryMap, address, bytes, 0, bytes.size)
    }

    /**
     * Stream a (potentially large) firmware/kernel image into [memoryMap].
     * The stream is closed by Sedna's [MemoryMaps.store] after the last byte
     * is consumed.
     */
    fun loadStream(memoryMap: MemoryMap, address: Long, source: InputStream) {
        MemoryMaps.store(memoryMap, address, source)
    }

    /**
     * Read a classpath resource into a [ByteArray], or `null` if the resource
     * is not present. Used for "drop your kernel here" semantics — if the mod
     * jar contains `assets/oc2/control-plane/<name>` it boots; otherwise the
     * VM falls back to the [helloUartStub].
     */
    fun readResource(path: String): ByteArray? {
        val cl = ControlPlaneBoot::class.java.classLoader ?: return null
        val stream = cl.getResourceAsStream(path) ?: return null
        return stream.use { it.readBytes() }
    }

    /**
     * Build a minimal RV64 program: write [char] (LSB only) to the UART16550A
     * THR at [uartBase], then loop forever (`j .`). 16 bytes total
     * (4 × 32-bit instructions).
     *
     * This is a proof-of-life payload: a successful boot drops [char] into
     * [ConsoleCapture] within the first thousand cycles, distinguishing
     * "real instruction fetch + MMIO store" from "spinning on illegal-
     * instruction traps" (which is what the VM does with no kernel loaded).
     *
     * `WFI` was rejected here because in Sedna's post-`initialize()` state
     * the CPU sits in M-mode with `mstatus.TW=0`, which means WFI is legal —
     * but a future Sedna version that turns TW on for security would trap
     * the WFI as illegal-instruction. `j .` has no such failure mode.
     *
     * Encoding details:
     * - `lui t0, hi20`     : U-type with the upper 20 bits of `uartBase`.
     *   When `uartBase` is 4 KB-aligned (Sedna's allocator guarantees this),
     *   a single LUI gives the full address.
     * - `addi t1, x0, char`: load the character literal into t1.
     * - `sb t1, 0(t0)`     : MMIO store — UART16550A latches it and queues
     *   it for the host's next [ConsoleCapture.drain].
     * - `j .`              : infinite tight loop on the same instruction.
     */
    fun helloUartStub(uartBase: Long, char: Byte): ByteArray {
        require(uartBase and 0xFFFL == 0L) {
            "uartBase must be 4KB-aligned for single-LUI form (got 0x${uartBase.toString(16)})"
        }
        val byte = char.toInt() and 0xFF
        require(byte <= 0x7FF) { "char literal too large for 12-bit immediate: $byte" }

        val hi20 = ((uartBase ushr 12) and 0xFFFFFL).toInt()
        val t0 = 5
        val t1 = 6

        // LUI t0, hi20  →  imm[31:12] | rd | 0110111
        val lui = (hi20 shl 12) or (t0 shl 7) or 0b0110111
        // ADDI t1, x0, byte  →  imm[11:0] | rs1 | 000 | rd | 0010011
        val addi = (byte shl 20) or (0 shl 15) or (0 shl 12) or (t1 shl 7) or 0b0010011
        // SB t1, 0(t0)  →  imm[11:5]=0 | rs2=t1 | rs1=t0 | 000 | imm[4:0]=0 | 0100011
        val sb = (0 shl 25) or (t1 shl 20) or (t0 shl 15) or (0 shl 12) or (0 shl 7) or 0b0100011
        // JAL x0, 0  →  jumps to itself. `0x0000006F` is the canonical encoding.
        val jSelf = 0x0000006F

        return byteArrayOf(
            (lui and 0xFF).toByte(), ((lui ushr 8) and 0xFF).toByte(),
            ((lui ushr 16) and 0xFF).toByte(), ((lui ushr 24) and 0xFF).toByte(),
            (addi and 0xFF).toByte(), ((addi ushr 8) and 0xFF).toByte(),
            ((addi ushr 16) and 0xFF).toByte(), ((addi ushr 24) and 0xFF).toByte(),
            (sb and 0xFF).toByte(), ((sb ushr 8) and 0xFF).toByte(),
            ((sb ushr 16) and 0xFF).toByte(), ((sb ushr 24) and 0xFF).toByte(),
            (jSelf and 0xFF).toByte(), ((jSelf ushr 8) and 0xFF).toByte(),
            ((jSelf ushr 16) and 0xFF).toByte(), ((jSelf ushr 24) and 0xFF).toByte(),
        )
    }
}
