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

    /**
     * Build a small RV64 program that emits [banner] to the UART16550A THR
     * at [uartBase] one byte at a time, polling LSR.THRE between writes so
     * the host can drain each byte before the next overwrites it. Halts in
     * a `j .` loop after the last byte.
     *
     * Used as the default boot image when no kernel is shipped — a freshly
     * placed Control Plane immediately produces visible UART activity instead
     * of spinning silently on illegal-instruction traps. Once a real Linux
     * kernel binary lands in `assets/oc2/control-plane/`, that takes over and
     * this stub becomes the fallback for "no kernel resource found."
     *
     * The poll-then-write pattern is *required*, not just a nice-to-have:
     * Sedna's UART16550A defaults to FIFO-disabled mode, so the THR register
     * is a single-byte slot that subsequent writes overwrite. Without
     * waiting for the host to read (which sets LSR.THRE back), only the
     * last byte of a burst would survive a [ControlPlaneVm.step] drain.
     *
     * Per character:
     * ```
     *   poll:  LBU   t2, 5(t0)        # t2 = LSR
     *          ANDI  t2, t2, 0x20     # mask THRE bit
     *          BEQ   t2, x0, poll     # spin while THRE clear
     *          ADDI  t1, x0, char     # load literal
     *          SB    t1, 0(t0)        # write to THR
     * ```
     *
     * Header is `LUI t0, hi20(uart)`, footer is `JAL x0, 0`. The stub is
     * fully unrolled — no banner-walking loop to debug — and self-contained
     * (no PC-relative addressing). 5 instructions × 4 bytes = 20 bytes per
     * character; for a typical ~21-char banner the program is under 500
     * bytes, trivially small on a 1 MB+ RAM.
     *
     * Same 4 KB-alignment constraint as [helloUartStub] (single-LUI form
     * for the UART). Banner characters must be 7-bit ASCII so the literal
     * fits in ADDI's 12-bit signed immediate without sign-extension surprises.
     */
    fun bannerStub(uartBase: Long, banner: String): ByteArray {
        require(uartBase and 0xFFFL == 0L) {
            "uartBase must be 4KB-aligned for single-LUI form (got 0x${uartBase.toString(16)})"
        }
        val bannerBytes = banner.toByteArray(Charsets.UTF_8)
        require(bannerBytes.none { it == 0.toByte() }) {
            "banner must not contain embedded NULs"
        }
        require(bannerBytes.all { (it.toInt() and 0xFF) <= 0x7F }) {
            "banner must be 7-bit ASCII (no multi-byte UTF-8)"
        }

        val uartHi20 = ((uartBase ushr 12) and 0xFFFFFL).toInt()
        val t0 = 5
        val t1 = 6
        val t2 = 7

        val instructions = mutableListOf<Int>()
        // LUI t0, hi20(uartBase)
        instructions += encodeUType(opcode = 0b0110111, rd = t0, hi20 = uartHi20)
        for (b in bannerBytes) {
            val v = b.toInt() and 0xFF
            // poll: LBU t2, 5(t0)         — load LSR (UART offset 5)
            instructions += encodeIType(opcode = 0b0000011, funct3 = 0b100, rd = t2, rs1 = t0, imm = 5)
            // ANDI t2, t2, 0x20            — isolate THRE bit
            instructions += encodeIType(opcode = 0b0010011, funct3 = 0b111, rd = t2, rs1 = t2, imm = 0x20)
            // BEQ t2, x0, -8               — if THRE clear, branch back to LBU
            instructions += encodeBType(opcode = 0b1100011, funct3 = 0b000, rs1 = t2, rs2 = 0, offset = -8)
            // ADDI t1, x0, byte
            instructions += encodeIType(opcode = 0b0010011, funct3 = 0, rd = t1, rs1 = 0, imm = v)
            // SB t1, 0(t0)
            instructions += encodeSType(opcode = 0b0100011, funct3 = 0, rs1 = t0, rs2 = t1, offset = 0)
        }
        // JAL x0, 0 — `j .`, halt.
        instructions += encodeJType(opcode = 0b1101111, rd = 0, offset = 0)

        val out = ByteArray(instructions.size * 4)
        var i = 0
        for (insn in instructions) {
            out[i++] = (insn and 0xFF).toByte()
            out[i++] = ((insn ushr 8) and 0xFF).toByte()
            out[i++] = ((insn ushr 16) and 0xFF).toByte()
            out[i++] = ((insn ushr 24) and 0xFF).toByte()
        }
        return out
    }

    // RV64 instruction encoders. All return little-endian-writable Ints.
    // Reference: RISC-V Unprivileged ISA spec v20191213 §2.2 / §2.3.

    private fun encodeUType(opcode: Int, rd: Int, hi20: Int): Int {
        return ((hi20 and 0xFFFFF) shl 12) or ((rd and 0x1F) shl 7) or (opcode and 0x7F)
    }

    private fun encodeIType(opcode: Int, funct3: Int, rd: Int, rs1: Int, imm: Int): Int {
        return ((imm and 0xFFF) shl 20) or
            ((rs1 and 0x1F) shl 15) or
            ((funct3 and 0x7) shl 12) or
            ((rd and 0x1F) shl 7) or
            (opcode and 0x7F)
    }

    private fun encodeSType(opcode: Int, funct3: Int, rs1: Int, rs2: Int, offset: Int): Int {
        val imm = offset and 0xFFF
        val immHi = (imm ushr 5) and 0x7F
        val immLo = imm and 0x1F
        return (immHi shl 25) or
            ((rs2 and 0x1F) shl 20) or
            ((rs1 and 0x1F) shl 15) or
            ((funct3 and 0x7) shl 12) or
            (immLo shl 7) or
            (opcode and 0x7F)
    }

    private fun encodeBType(opcode: Int, funct3: Int, rs1: Int, rs2: Int, offset: Int): Int {
        require(offset and 1 == 0) { "BEQ offset must be 2-byte aligned (got $offset)" }
        val o = offset and 0x1FFF
        val imm12 = (o ushr 12) and 1
        val imm10_5 = (o ushr 5) and 0x3F
        val imm4_1 = (o ushr 1) and 0xF
        val imm11 = (o ushr 11) and 1
        return (imm12 shl 31) or
            (imm10_5 shl 25) or
            ((rs2 and 0x1F) shl 20) or
            ((rs1 and 0x1F) shl 15) or
            ((funct3 and 0x7) shl 12) or
            (imm4_1 shl 8) or
            (imm11 shl 7) or
            (opcode and 0x7F)
    }

    private fun encodeJType(opcode: Int, rd: Int, offset: Int): Int {
        require(offset and 1 == 0) { "JAL offset must be 2-byte aligned (got $offset)" }
        val o = offset and 0x1FFFFF
        val imm20 = (o ushr 20) and 1
        val imm10_1 = (o ushr 1) and 0x3FF
        val imm11 = (o ushr 11) and 1
        val imm19_12 = (o ushr 12) and 0xFF
        return (imm20 shl 31) or
            (imm10_1 shl 21) or
            (imm11 shl 20) or
            (imm19_12 shl 12) or
            ((rd and 0x1F) shl 7) or
            (opcode and 0x7F)
    }
}
