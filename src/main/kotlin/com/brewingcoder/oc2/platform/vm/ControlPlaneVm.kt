package com.brewingcoder.oc2.platform.vm

import li.cil.sedna.api.device.BlockDevice
import li.cil.sedna.api.device.PhysicalMemory
import li.cil.sedna.device.memory.Memory
import li.cil.sedna.device.serial.UART16550A
import li.cil.sedna.device.virtio.VirtIOBlockDevice
import li.cil.sedna.riscv.R5Board
import java.io.Closeable
import java.io.File

/**
 * Pure-Kotlin holder for a Sedna [R5Board] + its attached devices. Lives in
 * `platform/vm/` so it's testable without MC on the classpath (Rule D).
 *
 * R1 fixed config (see docs/03-tier2-control-plane.md):
 *   - 64 MB RAM at 0x80000000 (the conventional RISC-V kernel load address)
 *   - UART16550A as the standard output device (where Linux's `console=ttyS0`
 *     lands); host reads its bytes via [ConsoleCapture].
 *   - Optional virtio-blk backed by a per-BE sparse disk image.
 *   - No kernel/initramfs loaded yet — when those land the CPU will start
 *     executing real code; for now it spins on illegal-instruction traps,
 *     which still ticks the cycle counter (proof-of-life).
 *
 * Construction is cheap (allocates RAM + opens the disk file); [step] is the
 * hot path. [close] releases the disk FD and stops the CPU.
 */
class ControlPlaneVm(
    val ramBytes: Int = DEFAULT_RAM_BYTES,
    val ramBase: Long = DEFAULT_RAM_BASE,
    diskFile: File? = null,
    diskSizeBytes: Long = ControlPlaneDisk.DEFAULT_SIZE_BYTES,
    val consoleCapacity: Int = ConsoleCapture.DEFAULT_CAPACITY,
) : Closeable {

    val board: R5Board = R5Board()
    private val ram: PhysicalMemory = Memory.create(ramBytes)
    private val uart: UART16550A = UART16550A()
    val console: ConsoleCapture = ConsoleCapture(consoleCapacity)

    private val diskBlockDevice: BlockDevice? =
        diskFile?.let { ControlPlaneDisk.createOrOpen(it, diskSizeBytes) }
    private val virtioBlock: VirtIOBlockDevice? =
        diskBlockDevice?.let { VirtIOBlockDevice(board.memoryMap, it) }

    /** Capacity of the attached disk in bytes, or 0 if no disk is attached. */
    val diskCapacity: Long get() = diskBlockDevice?.capacity ?: 0L

    init {
        // Devices must be added before initialize() so the device tree includes them.
        board.addDevice(ramBase, ram)
        board.addDevice(uart)
        board.setStandardOutputDevice(uart)
        virtioBlock?.let { board.addDevice(it) }
        board.initialize()
        board.setRunning(true)
    }

    /** Total cycles executed since this VM was constructed. */
    val cycles: Long
        get() = board.cpu.time

    /**
     * Step the CPU forward by [cycleBudget], then drain any UART output the
     * guest emitted into [console]. Returns the new cycle count.
     */
    fun step(cycleBudget: Int): Long {
        if (!board.isRunning) return cycles
        board.step(cycleBudget)
        console.drain(uart)
        return cycles
    }

    /**
     * Snapshot the VM's address-space layout for debug / status surfaces.
     * Cheap — doesn't touch RAM contents.
     */
    fun describe(): String {
        val diskMb = diskCapacity / (1024 * 1024)
        val diskPart = if (diskCapacity > 0) " disk=${diskMb}MB" else " disk=none"
        return "R5Board ram=${ramBytes / (1024 * 1024)}MB" +
            " base=0x${ramBase.toString(16)}" +
            diskPart +
            " cycles=$cycles" +
            " console=${console.byteCount}B"
    }

    /**
     * Stop the CPU and release the disk FD. Safe to call multiple times.
     * After close, [step] is a no-op and the disk-backed file is fully synced.
     */
    override fun close() {
        board.setRunning(false)
        try {
            virtioBlock?.close()
        } catch (_: Exception) {
            // virtio close errors are non-fatal — the underlying block device close is what matters
        }
        try {
            diskBlockDevice?.close()
        } catch (_: Exception) {
            // best-effort
        }
    }

    companion object {
        const val DEFAULT_RAM_BYTES: Int = 64 * 1024 * 1024
        const val DEFAULT_RAM_BASE: Long = 0x80000000L

        /** Default per-server-tick cycle budget. Tunable via config later. */
        const val DEFAULT_CYCLES_PER_TICK: Int = 1_000_000
    }
}
