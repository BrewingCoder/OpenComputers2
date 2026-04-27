package com.brewingcoder.oc2.platform.vm

import li.cil.sedna.api.device.PhysicalMemory
import li.cil.sedna.device.memory.Memory
import li.cil.sedna.riscv.R5Board

/**
 * Pure-Kotlin holder for a Sedna [R5Board] + its attached devices. Lives in
 * `platform/vm/` so it's testable without MC on the classpath (Rule D).
 *
 * R1 fixed config (see docs/03-tier2-control-plane.md):
 *   - 64 MB RAM at 0x80000000 (the conventional RISC-V kernel load address)
 *   - No disk, no console, no network bridge yet — those land in subsequent
 *     commits. With no firmware loaded the CPU just spins on illegal-instruction
 *     traps; the cycle counter still ticks, which is enough for proof-of-life.
 *
 * Construction is cheap (allocates 64 MB and a CPU); [step] is the hot path.
 */
class ControlPlaneVm(
    val ramBytes: Int = DEFAULT_RAM_BYTES,
    val ramBase: Long = DEFAULT_RAM_BASE,
) {
    val board: R5Board = R5Board()
    private val ram: PhysicalMemory = Memory.create(ramBytes)

    init {
        board.addDevice(ramBase, ram)
        board.initialize()
        board.setRunning(true)
    }

    /** Total cycles executed since this VM was constructed. */
    val cycles: Long
        get() = board.cpu.time

    /** Step the CPU forward by [cycleBudget]. Returns the new cycle count. */
    fun step(cycleBudget: Int): Long {
        if (!board.isRunning) return cycles
        board.step(cycleBudget)
        return cycles
    }

    /**
     * Snapshot the VM's address-space layout for debug / status surfaces.
     * Cheap — doesn't touch RAM contents.
     */
    fun describe(): String =
        "R5Board ram=${ramBytes / (1024 * 1024)}MB base=0x${ramBase.toString(16)} cycles=$cycles"

    companion object {
        const val DEFAULT_RAM_BYTES: Int = 64 * 1024 * 1024
        const val DEFAULT_RAM_BASE: Long = 0x80000000L

        /** Default per-server-tick cycle budget. Tunable via config later. */
        const val DEFAULT_CYCLES_PER_TICK: Int = 1_000_000
    }
}
