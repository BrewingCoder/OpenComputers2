package com.brewingcoder.oc2.platform.vm

import li.cil.ceres.BinarySerialization
import li.cil.sedna.api.device.BlockDevice
import li.cil.sedna.api.device.PhysicalMemory
import li.cil.sedna.device.memory.Memory
import li.cil.sedna.device.serial.UART16550A
import li.cil.sedna.device.virtio.VirtIOBlockDevice
import li.cil.sedna.riscv.R5Board
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

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
    bootImage: ByteArray? = null,
    bootArgs: String? = null,
    snapshot: ByteArray? = null,
    snapshotStream: InputStream? = null,
    bannerText: String? = null,
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

    /** MMIO base address Sedna assigned to the UART. Useful for boot-stub assembly. */
    val uartBase: Long
        get() = board.memoryMap.getMemoryRange(uart).orElseThrow {
            IllegalStateException("UART has no memory range — board not initialized")
        }.start

    init {
        // Idempotent — registers Sedna's hand-rolled Ceres serializers exactly
        // once per JVM. Required before any snapshot/restore can run.
        SednaSerializerRegistration.ensure()
        // Devices must be added before initialize() / deserialize so the address
        // space layout matches what the snapshot was taken against.
        board.addDevice(ramBase, ram)
        board.addDevice(uart)
        board.setStandardOutputDevice(uart)
        virtioBlock?.let { board.addDevice(it) }

        require(!(snapshot != null && snapshotStream != null)) {
            "snapshot and snapshotStream are mutually exclusive"
        }
        val restoring = snapshot != null || snapshotStream != null
        if (restoring) {
            // Restore path: Ceres overwrites CPU/MMU/RAM/device state in place
            // from the captured snapshot. We deliberately skip initialize() —
            // it would reset the reset vector and clobber the restored PC —
            // and skip bootImage/bootArgs since the snapshot already encodes
            // whatever the guest wrote into RAM and the device tree.
            require(bootImage == null) { "bootImage and snapshot are mutually exclusive" }
            require(bootArgs == null) { "bootArgs and snapshot are mutually exclusive" }
            if (snapshot != null) {
                BinarySerialization.deserialize(ByteBuffer.wrap(snapshot), R5Board::class.java, board)
            } else {
                // Stream path. Caller owns the underlying InputStream lifetime —
                // we don't close it (could be a network stream the caller wants
                // to keep open after restore, though today it's always a file).
                val dis = if (snapshotStream is DataInputStream) snapshotStream
                else DataInputStream(snapshotStream!!)
                BinarySerialization.deserialize(dis, R5Board::class.java, board)
            }
        } else {
            // Fresh boot path:
            //  - bootArgs → device tree's `chosen/bootargs`
            //  - bootImage → loaded at defaultProgramStart before reset programs the CPU
            //  - bannerText → if no explicit bootImage, synthesize a banner-emitting stub
            //    using the runtime uartBase so a freshly placed Control Plane shows life.
            require(!(bootImage != null && bannerText != null)) {
                "bootImage and bannerText are mutually exclusive"
            }
            bootArgs?.let { board.setBootArguments(it) }
            val effectiveBootImage = bootImage
                ?: bannerText?.let { ControlPlaneBoot.bannerStub(uartBase = uartBase, banner = it) }
            effectiveBootImage?.let {
                ControlPlaneBoot.loadBytes(board.memoryMap, board.defaultProgramStart, it)
            }
            board.initialize()
            board.setRunning(true)
        }
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
     * Capture the VM's running state as an opaque byte array. Includes CPU
     * registers, MMU/TLB state, RAM contents, virtio queue state — everything
     * Ceres can see through the [li.cil.ceres.api.Serialized] annotations
     * Sedna stamps on its own classes.
     *
     * Size scales with [ramBytes] — a 64 MB RAM produces a snapshot in the
     * tens of MB range. Convenient for tests; production callers (the BE
     * save path) should prefer [snapshotTo] which streams directly to disk
     * without buffering the full state in memory first.
     *
     * The returned array is self-contained; pair it with the same RAM size +
     * disk file at restore time and the VM resumes mid-instruction. The disk
     * image itself is NOT in the snapshot — it lives on the host filesystem
     * and survives across snapshot/restore independently.
     */
    fun snapshot(): ByteArray {
        val buffer = BinarySerialization.serialize(board, R5Board::class.java)
        val out = ByteArray(buffer.remaining())
        buffer.get(out)
        return out
    }

    /**
     * Streaming variant of [snapshot]. Writes directly into [out] via
     * [DataOutputStream] so a 64 MB VM doesn't materialize a 64 MB
     * `byte[]` on the heap during the BE save path. Caller owns [out]'s
     * lifetime — we don't close it (the BE wraps it in a `use { }` block).
     */
    fun snapshotTo(out: OutputStream) {
        val dos = if (out is DataOutputStream) out else DataOutputStream(out)
        BinarySerialization.serialize(dos, board, R5Board::class.java)
        dos.flush()
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
