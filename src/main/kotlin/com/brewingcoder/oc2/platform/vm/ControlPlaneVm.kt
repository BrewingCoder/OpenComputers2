package com.brewingcoder.oc2.platform.vm

import li.cil.ceres.BinarySerialization
import li.cil.sedna.api.device.BlockDevice
import li.cil.sedna.api.device.PhysicalMemory
import li.cil.sedna.device.block.ByteBufferBlockDevice
import li.cil.sedna.device.memory.Memory
import li.cil.sedna.device.serial.UART16550A
import li.cil.sedna.device.virtio.VirtIOBlockDevice
import li.cil.sedna.memory.MemoryMaps
import li.cil.sedna.riscv.R5Board
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    firmware: BuiltinFirmware? = null,
) : Closeable {

    val board: R5Board = R5Board()
    private val ram: PhysicalMemory = Memory.create(ramBytes)
    private val uart: UART16550A = UART16550A()
    val console: ConsoleCapture = ConsoleCapture(consoleCapacity)

    /**
     * Firmware-backed read-only block devices (vda=bootfs, vdb=rootfs). The
     * persistent player disk, when present, becomes vdc — the writable upper
     * layer of the rootfs overlay built up by bootfs's `/sbin/init`.
     *
     * Order matters: Sedna's auto-numbering of virtio-blk devices follows the
     * order they're added to the board. Firmware disks must be added before
     * the player disk so vda/vdb stay stable across mod versions.
     */
    private val firmwareBlockDevices: List<BlockDevice> = if (firmware != null && firmware.isAvailable) {
        listOf(
            firmware.bootfsStream().use { ByteBufferBlockDevice.createFromStream(it, true) },
            firmware.rootfsStream().use { ByteBufferBlockDevice.createFromStream(it, true) },
        )
    } else {
        emptyList()
    }
    private val firmwareVirtioBlocks: List<VirtIOBlockDevice> =
        firmwareBlockDevices.map { VirtIOBlockDevice(board.memoryMap, it) }

    private val diskBlockDevice: BlockDevice? =
        diskFile?.let { ControlPlaneDisk.createOrOpen(it, diskSizeBytes) }
    private val virtioBlock: VirtIOBlockDevice? =
        diskBlockDevice?.let { VirtIOBlockDevice(board.memoryMap, it) }

    /** Capacity of the attached player disk in bytes, or 0 if no disk is attached. */
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
        // Firmware disks first (vda, vdb), then player disk (vdc) — ordering
        // matches the kernel cmdline `root=/dev/vda` minux's bootfs expects.
        firmwareVirtioBlocks.forEach { board.addDevice(it) }
        virtioBlock?.let { board.addDevice(it) }

        // Wire interrupt sources to the PLIC. Sedna's InterruptSourceProvider
        // emits an interrupts-extended property per device whose entries are
        // (controller phandle, irq id) pairs — but only for interrupts whose
        // controller is non-null. By default, UART16550A and VirtIOBlockDevice
        // construct their Interrupt object with controller=null, so the
        // emitted property is empty and Linux's virtio_blk fails to bind:
        //   "virtio-mmio: error -ENXIO: IRQ index 0 not found"
        //   "VFS: Cannot open root device '/dev/vda' or unknown-block(0,0)"
        // Wire each interrupt to the board's PLIC with a unique id.
        val plic = board.interruptController
        uart.interrupt.set(IRQ_UART, plic)
        firmwareVirtioBlocks.forEachIndexed { idx, vb ->
            vb.interrupt.set(IRQ_VIRTIO_FIRST + idx, plic)
        }
        virtioBlock?.let {
            it.interrupt.set(IRQ_VIRTIO_FIRST + firmwareVirtioBlocks.size, plic)
        }

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
            //  - firmware (when available) → OpenSBI at ramBase, kernel at ramBase + KERNEL_OFFSET.
            //    Boot args default to BuiltinFirmware.DEFAULT_BOOT_ARGS unless caller overrides.
            //  - bootImage → loaded at defaultProgramStart before reset programs the CPU.
            //  - bannerText → if no firmware/bootImage, synthesize a banner-emitting stub.
            require(!(firmware != null && firmware.isAvailable && bootImage != null)) {
                "firmware and bootImage are mutually exclusive"
            }
            require(!(bootImage != null && bannerText != null)) {
                "bootImage and bannerText are mutually exclusive"
            }
            if (firmware != null && firmware.isAvailable) {
                board.setBootArguments(bootArgs ?: BuiltinFirmware.DEFAULT_BOOT_ARGS)
                val sbiBytes = firmware.openSbiStream().use { it.readBytes() }
                patchOpenSbiFwNextArg1(sbiBytes)
                MemoryMaps.store(board.memoryMap, ramBase, sbiBytes, 0, sbiBytes.size)
                firmware.kernelImageStream().use {
                    MemoryMaps.store(board.memoryMap, ramBase + BuiltinFirmware.KERNEL_OFFSET, it)
                }
            } else {
                bootArgs?.let { board.setBootArguments(it) }
                val effectiveBootImage = bootImage
                    ?: bannerText?.let { ControlPlaneBoot.bannerStub(uartBase = uartBase, banner = it) }
                effectiveBootImage?.let {
                    ControlPlaneBoot.loadBytes(board.memoryMap, board.defaultProgramStart, it)
                }
            }
            board.initialize()
            if (firmware != null && firmware.isAvailable) {
                relocateDtbForOpenSbiHeadroom()
                dumpBootDiagnostics()
            }
            board.setRunning(true)
        }
    }

    private fun dumpBootDiagnostics() {
        try {
            // Walk last 16 MB looking for DTB magic (0xd00dfeed). After
            // relocateDtbForOpenSbiHeadroom() the DTB lives ~8 MB from the
            // end (we moved it back so the FDT fixmap fits in RAM).
            val tail = ByteArray(16 * 1024 * 1024)
            MemoryMaps.load(board.memoryMap, ramBase + ramBytes - tail.size, tail, 0, tail.size)
            var dtbOffset = -1
            for (i in 0..tail.size - 4) {
                if (tail[i] == 0xd0.toByte() && tail[i + 1] == 0x0d.toByte() &&
                    tail[i + 2] == 0xfe.toByte() && tail[i + 3] == 0xed.toByte()
                ) {
                    dtbOffset = i; break
                }
            }

            val patchBytes = ByteArray(8)
            MemoryMaps.load(board.memoryMap, ramBase + OPENSBI_FW_NEXT_ARG1_OFFSET, patchBytes, 0, 8)
            val patchHex = patchBytes.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

            // Readback flash[0x10..0x17] so we can verify whether the boot
            // stub's DTB pointer was successfully patched. If this still
            // matches the original (memEnd - dtbSize) address, the flash
            // ByteBuffer write didn't take.
            val flashPtrBytes = ByteArray(8)
            MemoryMaps.load(board.memoryMap, FLASH_DTB_PTR_ADDR, flashPtrBytes, 0, 8)
            val flashDtbPtr = ByteBuffer.wrap(flashPtrBytes).order(ByteOrder.LITTLE_ENDIAN).long

            // Read the kernel image header at ramBase + KERNEL_OFFSET. This both
            // verifies the kernel was actually loaded and lets us confirm the
            // RISC-V Image magic is intact (offset 0x30 should be "RISCV\0\0\0").
            val kernelHeader = ByteArray(64)
            MemoryMaps.load(board.memoryMap, ramBase + 0x200000L, kernelHeader, 0, kernelHeader.size)
            val kernelHeaderHex = kernelHeader.joinToString(" ") { "%02x".format(it.toInt() and 0xff) }

            val dumpDir = File(System.getProperty("user.home"), "oc2-boot-diag")
            dumpDir.mkdirs()

            // Read the *full* DTB (header field at offset 4 is totalsize, BE u32).
            // Earlier we only kept tail.size - dtbOffset, which truncated the
            // strings block when the DTB extended past our 32 KB tail window.
            var dtbTotalSize = -1
            if (dtbOffset >= 0) {
                dtbTotalSize = ((tail[dtbOffset + 4].toInt() and 0xff) shl 24) or
                    ((tail[dtbOffset + 5].toInt() and 0xff) shl 16) or
                    ((tail[dtbOffset + 6].toInt() and 0xff) shl 8) or
                    (tail[dtbOffset + 7].toInt() and 0xff)
                val dtbBytes = ByteArray(dtbTotalSize)
                MemoryMaps.load(
                    board.memoryMap,
                    ramBase + ramBytes - tail.size + dtbOffset,
                    dtbBytes, 0, dtbTotalSize
                )
                File(dumpDir, "sedna.dtb").writeBytes(dtbBytes)
            }

            File(dumpDir, "boot-info.txt").writeText(buildString {
                appendLine("ramBase=0x${ramBase.toString(16)}")
                appendLine("ramBytes=$ramBytes")
                appendLine("uartBase=0x${uartBase.toString(16)}")
                appendLine("dtbOffsetInTail=$dtbOffset (tail starts at 0x${(ramBase + ramBytes - tail.size).toString(16)})")
                if (dtbOffset >= 0) {
                    appendLine("dtbAddr=0x${(ramBase + ramBytes - tail.size + dtbOffset).toString(16)}")
                    appendLine("dtbTotalSize=$dtbTotalSize")
                }
                appendLine("fw_next_arg1[0..7]=$patchHex (expected: 13 85 04 00 67 80 00 00)")
                appendLine("flash[0x10] dtbPtr=0x${flashDtbPtr.toString(16)} (post-relocation, expected to match dtbAddr)")
                appendLine("kernelHeader[0..63]=$kernelHeaderHex")
                val magic = String(kernelHeader, 0x30, 8, Charsets.US_ASCII)
                    .replace(" ", "\\0")
                appendLine("kernelHeaderMagic@0x30=\"$magic\" (expected: \"RISCV\\0\\0\\0\")")
            })
        } catch (t: Throwable) {
            System.err.println("[oc2 boot-diag] dump failed: ${t.message}")
        }
    }

    /** Total cycles executed since this VM was constructed. */
    val cycles: Long
        get() = board.cpu.time

    /**
     * Step the CPU forward by [cycleBudget], draining UART output between
     * sub-steps so the guest's transmit FIFO doesn't stay full for the
     * entire tick. Returns the new cycle count.
     *
     * Why the inner loop: Sedna's UART16550A has a 16-byte transmit FIFO,
     * but OpenSBI's `uart8250_putc` busy-waits on `LSR.THRE` after every
     * 16-byte burst. With a single `board.step(cycleBudget) → drain` pair,
     * the FIFO fills in the first ~50K cycles, then the CPU spins in the
     * polling loop for the remaining ~950K cycles before we ever pull the
     * FIFO into [console]. Splitting into [DRAIN_CHUNKS_PER_STEP] sub-steps
     * lets the host drain the FIFO mid-step, so the guest can keep printing
     * without idling — measured ~16× UART throughput improvement.
     */
    fun step(cycleBudget: Int): Long {
        if (!board.isRunning) return cycles
        val chunk = (cycleBudget / DRAIN_CHUNKS_PER_STEP).coerceAtLeast(1)
        var remaining = cycleBudget
        while (remaining > 0 && board.isRunning) {
            val n = minOf(remaining, chunk)
            board.step(n)
            console.drain(uart)
            maybeRecordTransition()
            remaining -= n
        }
        maybeDumpConsole()
        return cycles
    }

    /**
     * Per-chunk transition tracer. Cheap to run (one reflective field read
     * per CPU core register we care about). Writes a sample to
     * `~/oc2-boot-diag/transitions.txt` only when (priv, satp, scause)
     * changes vs the last sample — so an idle steady state doesn't spam
     * the file. Catches the M→S handoff and the kernel's per-instruction
     * register state during the brief boot window where 10M-cycle sampling
     * would miss everything interesting.
     */
    private var lastTransPriv: Int = -2
    private var lastTransSatp: Long = -1L
    private var lastTransScause: Long = -1L
    private var transFileInitialized: Boolean = false
    private fun maybeRecordTransition() {
        try {
            val cpu = board.cpu
            val priv = readIntField(cpu, "priv") ?: return
            val satp = readLongField(cpu, "satp") ?: 0L
            val scause = readLongField(cpu, "scause") ?: 0L
            if (priv == lastTransPriv && satp == lastTransSatp && scause == lastTransScause) return
            lastTransPriv = priv
            lastTransSatp = satp
            lastTransScause = scause
            val pc = readLongField(cpu, "pc") ?: 0L
            val sepc = readLongField(cpu, "sepc") ?: 0L
            val stval = readLongField(cpu, "stval") ?: 0L
            val stvec = readLongField(cpu, "stvec") ?: 0L
            val mcause = readLongField(cpu, "mcause") ?: 0L
            val mepc = readLongField(cpu, "mepc") ?: 0L
            val xs = readLongArrayField(cpu, "x")
            val regs = if (xs != null && xs.size >= 32) {
                "ra=0x%x sp=0x%x a0=0x%x a1=0x%x t0=0x%x s0=0x%x".format(
                    xs[1], xs[2], xs[10], xs[11], xs[5], xs[8]
                )
            } else "x[]=?"
            val dumpDir = File(System.getProperty("user.home"), "oc2-boot-diag")
            if (!dumpDir.isDirectory) return
            val file = File(dumpDir, "transitions.txt")
            if (!transFileInitialized) {
                file.writeText("# transitions: priv/satp/scause changes\n")
                transFileInitialized = true
            }
            file.appendText(
                "cyc=%-12d pc=0x%016x priv=%d satp=0x%x scause=0x%x sepc=0x%x stval=0x%x stvec=0x%x mcause=0x%x mepc=0x%x %s\n".format(
                    cycles, pc, priv, satp, scause, sepc, stval, stvec, mcause, mepc, regs
                )
            )
            // When satp transitions to non-zero, also dump the first 256 bytes
            // of the PT it points at — for tracking trampoline_pg_dir's
            // population status over time.
            if (satp != 0L) {
                val rootPa = (satp and 0xfffffffffffL) shl 12
                val sample = ByteArray(256)
                MemoryMaps.load(board.memoryMap, rootPa, sample, 0, 256)
                val nonZero = sample.any { it.toInt() != 0 }
                file.appendText(
                    "  pt_root@0x%x nonZero=%s%s\n".format(
                        rootPa, nonZero,
                        if (nonZero) " head=" + sample.take(16).joinToString(" ") { "%02x".format(it.toInt() and 0xff) } else ""
                    )
                )
            }
        } catch (_: Throwable) { /* swallow; diagnostic is best-effort */ }
    }

    private var lastConsoleDumpCycles: Long = 0L

    private fun maybeDumpConsole() {
        // Cheap continuous-tail of UART output to ~/oc2-boot-diag/console.log so
        // we can read what the guest is printing without right-clicking the BE.
        // Sample every ~10M cycles (~half-second of healthy execution at 20M
        // cyc/s), so the file overhead stays trivial.
        val now = cycles
        if (now - lastConsoleDumpCycles < 10_000_000L) return
        lastConsoleDumpCycles = now
        try {
            val dumpDir = File(System.getProperty("user.home"), "oc2-boot-diag")
            if (!dumpDir.isDirectory) return
            File(dumpDir, "console.log").writeBytes(console.snapshotBytes())
            appendCpuState(File(dumpDir, "cpu-state.txt"), now)
        } catch (_: Throwable) {
            // best effort; don't let diagnostics tear down the VM thread
        }
    }

    /**
     * Append a one-line CPU state sample (PC, mcause/mepc/mtval, priv, key
     * registers) to [file]. Used by [maybeDumpConsole] every ~10M cycles so we
     * can see what the kernel is doing post-OpenSBI handoff when nothing
     * appears on the UART.
     *
     * Reads private fields on Sedna's R5CPUTemplate via reflection. The field
     * names are stable across the 1.x line and we only catch + swallow any
     * NoSuchField error so a future Sedna version doesn't tear down the VM.
     */
    private fun appendCpuState(file: File, atCycle: Long) {
        val cpu = board.cpu
        val pc = readLongField(cpu, "pc") ?: return
        val mcause = readLongField(cpu, "mcause") ?: 0L
        val mepc = readLongField(cpu, "mepc") ?: 0L
        val mtval = readLongField(cpu, "mtval") ?: 0L
        val mstatus = readLongField(cpu, "mstatus") ?: 0L
        val mtvec = readLongField(cpu, "mtvec") ?: 0L
        val medeleg = readLongField(cpu, "medeleg") ?: 0L
        // S-mode trap CSRs — the kernel runs in S-mode after OpenSBI's mret,
        // so M-mode CSRs are stale post-handoff. scause/sepc/stval are how
        // we tell whether the kernel hit a real S-mode trap (and stvec tells
        // us where it would have routed it — e.g. .Lsecondary_park during
        // early boot).
        val scause = readLongField(cpu, "scause") ?: 0L
        val sepc = readLongField(cpu, "sepc") ?: 0L
        val stval = readLongField(cpu, "stval") ?: 0L
        val stvec = readLongField(cpu, "stvec") ?: 0L
        val satp = readLongField(cpu, "satp") ?: 0L
        val wfi = readBooleanField(cpu, "waitingForInterrupt") ?: false
        val priv = readIntField(cpu, "priv") ?: -1
        val xs = readLongArrayField(cpu, "x")
        val regs = if (xs != null && xs.size >= 32) {
            // ra=x1, sp=x2, gp=x3, tp=x4, t0=x5, a0=x10, a1=x11, s0=x8
            "ra=0x%x sp=0x%x a0=0x%x a1=0x%x t0=0x%x s0=0x%x".format(
                xs[1], xs[2], xs[10], xs[11], xs[5], xs[8]
            )
        } else "x[]=?"
        val mline = "M[cause=0x%x epc=0x%x tval=0x%x tvec=0x%x medeleg=0x%x]".format(
            mcause, mepc, mtval, mtvec, medeleg
        )
        val sline = "S[cause=0x%x epc=0x%x tval=0x%x tvec=0x%x satp=0x%x]".format(
            scause, sepc, stval, stvec, satp
        )
        val wfiTag = if (wfi) " wfi=1" else ""
        val line = "cyc=%-12d pc=0x%016x priv=%d mstatus=0x%x %s %s%s %s\n".format(
            atCycle, pc, priv, mstatus, mline, sline, wfiTag, regs
        )
        // Append (don't overwrite) so we get a trace, not a snapshot. Capped
        // implicitly by `maybeDumpConsole` running every 10M cycles → ~120
        // lines/min on a healthy VM, so a 1MB file holds ~2 hours of history.
        file.appendText(line)
        maybeDumpPageTable(satp)
    }

    private var pageTableDumped: Boolean = false

    /**
     * One-shot SV48 page-table walker. Fires the first time we observe a
     * non-zero satp with mode=SV48 (mode=9). Dumps every valid leaf PTE with
     * its VA range, PA target, and flags to ~/oc2-boot-diag/page-table.txt.
     *
     * Used to diagnose why the Linux kernel parks at low-VA 0x802000a8 with
     * scause=0xc (instruction page fault) — we expect to see whether the
     * kernel built a high-VA mapping at 0xffffffff80200000 or a low-VA
     * identity mapping at 0x80200000, and what their flags are.
     */
    private fun maybeDumpPageTable(satp: Long) {
        if (pageTableDumped || satp == 0L) return
        val mode = ((satp ushr 60) and 0xfL).toInt()
        if (mode != 9) return // only SV48 for now
        pageTableDumped = true
        try {
            val asid = (satp ushr 44) and 0xffffL
            val rootPpn = satp and 0xfffffffffffL
            val rootPa = rootPpn shl 12
            val sb = StringBuilder()
            sb.appendLine("satp=0x%x  mode=SV48  asid=0x%x  root_ppn=0x%x  root_paddr=0x%x".format(
                satp, asid, rootPpn, rootPa
            ))
            sb.appendLine("# format: Lk  VA range  -> PA  size  flags(DAGUXWRV)")
            // Hex dump of first 256 bytes (32 PTEs) of root PT — sanity-check
            // whether Sedna's MemoryMaps.load actually returns the table bytes
            // we expect, or zeros (= empty PT) or garbage (= reading wrong addr).
            val rootSample = ByteArray(256)
            MemoryMaps.load(board.memoryMap, rootPa, rootSample, 0, 256)
            sb.appendLine("# root PT raw bytes [0x%x..0x%x]:".format(rootPa, rootPa + 256))
            for (row in 0 until 16) {
                val off = row * 16
                val hex = (0 until 16).joinToString(" ") { "%02x".format(rootSample[off + it].toInt() and 0xff) }
                sb.appendLine("  +0x%03x  %s".format(off, hex))
            }
            // Also dump 256 bytes around the kernel image header at ramBase + 0x200000
            // — if this looks like RISC-V Image magic, MemoryMaps.load is reading
            // the right region; if zero, Sedna's bus mapping is broken.
            val kernSample = ByteArray(64)
            MemoryMaps.load(board.memoryMap, ramBase + 0x200000L, kernSample, 0, 64)
            sb.appendLine("# kernel header at 0x%x (expect RISCV magic at +0x30):".format(ramBase + 0x200000L))
            for (row in 0 until 4) {
                val off = row * 16
                val hex = (0 until 16).joinToString(" ") { "%02x".format(kernSample[off + it].toInt() and 0xff) }
                sb.appendLine("  +0x%02x   %s".format(off, hex))
            }
            walkPageTableLevel(sb, level = 3, tablePa = rootPa, vaPrefix = 0L)
            // Scan all of RAM in 4KB-aligned chunks looking for any page that
            // looks like a populated SV48 page table — i.e. has at least 2
            // valid PTEs (V=1) whose target PPN lands inside RAM. If we find
            // such pages, satp is pointing at the wrong physical address.
            sb.appendLine("# RAM scan for populated PT-shaped pages (strict: bits 54-63=0, RSW=0, A or D set):")
            val ramStart = ramBase
            val ramEnd = ramBase + ramBytes
            val ramStartPpn = ramStart ushr 12
            val ramEndPpn = ramEnd ushr 12
            val pageBuf = ByteArray(4096)
            var foundCount = 0
            var paddr = ramStart
            while (paddr < ramEnd && foundCount < 32) {
                MemoryMaps.load(board.memoryMap, paddr, pageBuf, 0, 4096)
                val pbb = ByteBuffer.wrap(pageBuf).order(ByteOrder.LITTLE_ENDIAN)
                var validCount = 0
                val firstFew = StringBuilder()
                for (i in 0 until 512) {
                    val pte = pbb.getLong(i * 8)
                    if ((pte and 1L) == 0L) continue
                    // Strict PTE shape: top 10 bits (54-63) zero, RSW (8-9) zero.
                    // Filters out ASCII-text false positives like 0x20717269 ("irq ")
                    // — its byte 7 is 0x00 (passes top-bits check) but RSW=01 (fails).
                    if ((pte ushr 54) != 0L) continue
                    if ((pte ushr 8) and 0x3L != 0L) continue
                    val ppn = (pte ushr 10) and 0xfffffffffffL
                    val targets = ppn in ramStartPpn..ramEndPpn
                    val rwx = ((pte ushr 1) and 0x7L).toInt()
                    val isLeaf = rwx != 0
                    // Real leaf PTEs in early kernel boot have A (bit 6) set
                    // because the CPU has touched them. Non-leaf PTEs have rwx=0.
                    val plausible = if (isLeaf) (pte and (1L shl 6)) != 0L else true
                    if (targets && plausible) {
                        validCount++
                        if (validCount <= 6) {
                            if (firstFew.isNotEmpty()) firstFew.append(' ')
                            firstFew.append("[%d]=0x%x(%s)".format(i, pte, if (isLeaf) "L" else "n"))
                        }
                    }
                }
                if (validCount >= 1) {
                    sb.appendLine("  paddr=0x%x  validPTEs=%d  %s".format(paddr, validCount, firstFew))
                    foundCount++
                }
                paddr += 4096
            }
            if (foundCount == 0) sb.appendLine("  (no populated PT pages found in RAM)")
            else if (foundCount >= 32) sb.appendLine("  (capped at 32; may be more)")
            // Full dump of the page Sedna's satp is one off from — we found a
            // real-looking partial L1 here. Want to see every PTE in this page
            // to understand the structure.
            sb.appendLine("# full PTE dump of 0x80488000 (one page below satp PPN):")
            val sus = ByteArray(4096)
            MemoryMaps.load(board.memoryMap, 0x80488000L, sus, 0, 4096)
            val sbb = ByteBuffer.wrap(sus).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 512) {
                val pte = sbb.getLong(i * 8)
                if (pte == 0L) continue
                sb.appendLine("  [%3d] = 0x%016x".format(i, pte))
            }
            // Also dump trampoline_pg_dir at PA 0x80572000 (per System.map)
            // — checking whether it has a low-VA identity entry that the
            // kernel relies on for the post-csrw-satp instruction fetch.
            sb.appendLine("# full PTE dump of 0x80572000 (trampoline_pg_dir per System.map):")
            val tmp = ByteArray(4096)
            MemoryMaps.load(board.memoryMap, 0x80572000L, tmp, 0, 4096)
            val tbb = ByteBuffer.wrap(tmp).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until 512) {
                val pte = tbb.getLong(i * 8)
                if (pte == 0L) continue
                sb.appendLine("  [%3d] = 0x%016x".format(i, pte))
            }
            val outFile = File(File(System.getProperty("user.home"), "oc2-boot-diag"), "page-table.txt")
            outFile.writeText(sb.toString())
        } catch (t: Throwable) {
            System.err.println("[oc2 boot-diag] page-table walk failed: ${t.message}")
        }
    }

    private fun walkPageTableLevel(sb: StringBuilder, level: Int, tablePa: Long, vaPrefix: Long) {
        val table = ByteArray(4096)
        MemoryMaps.load(board.memoryMap, tablePa, table, 0, 4096)
        val bb = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
        val shift = 12 + level * 9
        for (idx in 0 until 512) {
            val pte = bb.getLong(idx * 8)
            if ((pte and 1L) == 0L) continue // V=0, skip
            val ppn = (pte ushr 10) and 0xfffffffffffL
            // VA contribution from this level (9 bits). At root (level=3),
            // sign-extend to 64 bits if bit 47 is set (idx >= 0x100).
            val rawBits = idx.toLong() shl shift
            val vaSx = if (level == 3 && idx >= 0x100) {
                rawBits or 0xffff000000000000UL.toLong()
            } else {
                vaPrefix or rawBits
            }
            val rwx = ((pte ushr 1) and 0x7L).toInt()
            val isLeaf = rwx != 0
            if (isLeaf) {
                val pa = ppn shl 12
                val pageSize = 1L shl shift
                val flagStr = buildString {
                    append(if ((pte and (1L shl 7)) != 0L) 'D' else '-')
                    append(if ((pte and (1L shl 6)) != 0L) 'A' else '-')
                    append(if ((pte and (1L shl 5)) != 0L) 'G' else '-')
                    append(if ((pte and (1L shl 4)) != 0L) 'U' else '-')
                    append(if ((pte and (1L shl 3)) != 0L) 'X' else '-')
                    append(if ((pte and (1L shl 2)) != 0L) 'W' else '-')
                    append(if ((pte and (1L shl 1)) != 0L) 'R' else '-')
                    append(if ((pte and (1L shl 0)) != 0L) 'V' else '-')
                }
                val sizeStr = when (level) {
                    3 -> "512G"
                    2 -> "1G  "
                    1 -> "2M  "
                    0 -> "4K  "
                    else -> "?   "
                }
                sb.appendLine("L%d  VA 0x%016x..0x%016x  -> PA 0x%016x  %s  %s".format(
                    level, vaSx, vaSx + pageSize - 1, pa, sizeStr, flagStr
                ))
            } else {
                // Non-leaf: PPN points to next-level table.
                val nextPa = ppn shl 12
                walkPageTableLevel(sb, level - 1, nextPa, vaSx)
            }
        }
    }

    private fun readLongField(target: Any, name: String): Long? = findField(target.javaClass, name)?.let {
        it.isAccessible = true; it.getLong(target)
    }

    private fun readIntField(target: Any, name: String): Int? = findField(target.javaClass, name)?.let {
        it.isAccessible = true; it.getInt(target)
    }

    private fun readLongArrayField(target: Any, name: String): LongArray? = findField(target.javaClass, name)?.let {
        it.isAccessible = true; it.get(target) as? LongArray
    }

    private fun readBooleanField(target: Any, name: String): Boolean? = findField(target.javaClass, name)?.let {
        it.isAccessible = true; it.getBoolean(target)
    }

    private fun findField(start: Class<*>, name: String): java.lang.reflect.Field? {
        var c: Class<*>? = start
        while (c != null && c != Any::class.java) {
            try { return c.getDeclaredField(name) } catch (_: NoSuchFieldException) { /* climb */ }
            c = c.superclass
        }
        return null
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
        val firmwarePart = if (firmwareBlockDevices.isNotEmpty()) " fw=yes" else ""
        return "R5Board ram=${ramBytes / (1024 * 1024)}MB" +
            " base=0x${ramBase.toString(16)}" +
            firmwarePart +
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
        for (vb in firmwareVirtioBlocks) {
            try { vb.close() } catch (_: Exception) { /* best-effort */ }
        }
        for (bd in firmwareBlockDevices) {
            try { bd.close() } catch (_: Exception) { /* best-effort */ }
        }
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

    /**
     * Patch OpenSBI's `fw_next_arg1` to return the runtime DTB pointer instead
     * of the compile-time `FW_JUMP_FDT_ADDR=0x80F00000` constant.
     *
     * Sedna's flash boot stub correctly seeds `a1` with the DTB address it placed
     * at end-of-RAM (~0x83FFExxx for our 64 MB layout). OpenSBI's `_start`
     * saves that boot `a1` into callee-saved `s1` at offset 0x204, then calls
     * `fw_next_arg1` at 0x20c — the result is stored as `next_arg1` in the
     * scratch struct and becomes the kernel's `a1` on `mret` to S-mode.
     *
     * Built with the default `objects.mk`, `fw_next_arg1` ignores `s1` and
     * returns the hard-coded `0x80F00000`. The kernel reads garbage as its
     * DTB, finds no console node, and silent-trap-loops — no UART output even
     * after 22M+ cycles.
     *
     * The fix overwrites `fw_next_arg1`'s 10-byte body with `mv a0, s1; ret`
     * (8 bytes; remaining 2 bytes of the original epilogue are dead code) so
     * OpenSBI returns the boot `a1` (real DTB pointer) to the kernel.
     *
     * Sanity-checked against the original `c.lui a0, 1` opcode prefix so a
     * future OpenSBI rebuild that shifts symbols won't silently mispatch.
     */
    /**
     * Relocate the DTB Sedna placed at end-of-RAM into a roomy slack region so
     * OpenSBI's libfdt fixups (`fdt_chosen`, `fdt_cpu_fixup`, `fdt_reserved_memory_fixup`,
     * etc.) have headroom to expand the tree in place.
     *
     * **Why this exists**: `R5Board.initialize()` places the DTB at
     * `(memEnd - dtbSize) & ~7L`, leaving exactly zero bytes of slack between
     * the DTB and the end of RAM. OpenSBI then calls `fdt_open_into` with
     * `totalsize = dtbSize` and starts inserting nodes — the writes overrun
     * the end of RAM and trap with `mcause=0x7` (Store/AMO access fault),
     * `mtval` ≈ `0x84000000 + small` (just past 64 MB RAM end).
     *
     * **The fix**: read the DTB Sedna placed (at the address it baked into
     * the flash boot stub at 0x1010), copy it to a `FDT_RELOC_SIZE`-byte
     * region at the very end of RAM, bump its in-header `totalsize` field
     * to `FDT_RELOC_SIZE` so libfdt sees the slack, and rewrite the boot
     * stub's DTB pointer. After this the OpenSBI handoff sees a relocated
     * DTB with ~62 KB of growth room.
     *
     * Both `fdt_chosen` (called from `fw_platform_init`) AND the kernel's
     * `a1` see the new pointer because:
     *  - the boot stub at 0x1000 loads `a1` from flash[0x10] (which we
     *    rewrite here), and
     *  - our `patchOpenSbiFwNextArg1` already returns `s1` (= boot `a1`) as
     *    the kernel's `a1`.
     */
    private fun relocateDtbForOpenSbiHeadroom() {
        try {
            // Read the DTB pointer Sedna baked into flash[0x10..0x17] (LE u64).
            // The flash boot stub at FLASH_ADDRESS=0x1000 is:
            //   0x1000: auipc t0, 0
            //   0x1004: ld a1, 16(t0)   # loads from 0x1010 into a1
            //   0x1008: ld t0, 24(t0)   # loads from 0x1018 into t0 (entry pt)
            //   0x100c: jalr x0, 0(t0)
            val ptrBytes = ByteArray(8)
            MemoryMaps.load(board.memoryMap, FLASH_DTB_PTR_ADDR, ptrBytes, 0, 8)
            val origDtbAddr = ByteBuffer.wrap(ptrBytes).order(ByteOrder.LITTLE_ENDIAN).long

            // DTB header: BE u32 0xd00dfeed at offset 0, BE u32 totalsize at offset 4.
            val header = ByteArray(8)
            MemoryMaps.load(board.memoryMap, origDtbAddr, header, 0, 8)
            val magic = ((header[0].toInt() and 0xff) shl 24) or
                ((header[1].toInt() and 0xff) shl 16) or
                ((header[2].toInt() and 0xff) shl 8) or
                (header[3].toInt() and 0xff)
            require(magic == 0xd00dfeed.toInt()) {
                "DTB magic mismatch at 0x${origDtbAddr.toString(16)}: got 0x${"%08x".format(magic)}"
            }
            val totalSize = ((header[4].toInt() and 0xff) shl 24) or
                ((header[5].toInt() and 0xff) shl 16) or
                ((header[6].toInt() and 0xff) shl 8) or
                (header[7].toInt() and 0xff)
            require(totalSize in 1 until FDT_RELOC_SIZE) {
                "DTB totalsize=$totalSize bytes does not fit in $FDT_RELOC_SIZE-byte slack region"
            }

            // Copy the DTB out to a Java byte[] before writing — defensive in
            // case orig and new ranges overlap. With ramBytes = 64 MB, dtbSize
            // ≈ 4 KB, FDT_RELOC_SIZE = 64 KB: the new region [memEnd-64K, memEnd-64K+totalSize]
            // and the original [memEnd-totalSize, memEnd] do not overlap, but
            // future RAM-size shrinks could make them overlap and this still works.
            val dtbBytes = ByteArray(totalSize)
            MemoryMaps.load(board.memoryMap, origDtbAddr, dtbBytes, 0, totalSize)

            // Bump in-header totalsize so libfdt's fdt_open_into sees the full
            // slack window. Without this it would honour the original size and
            // refuse to grow the tree.
            dtbBytes[4] = ((FDT_RELOC_SIZE ushr 24) and 0xff).toByte()
            dtbBytes[5] = ((FDT_RELOC_SIZE ushr 16) and 0xff).toByte()
            dtbBytes[6] = ((FDT_RELOC_SIZE ushr 8) and 0xff).toByte()
            dtbBytes[7] = (FDT_RELOC_SIZE and 0xff).toByte()

            // Fix Sedna's broken memory size in the DTB. MUST happen before
            // the store below — it modifies dtbBytes in-place.
            //
            // Sedna emits memory@80000000.reg = <0 0x80000000 0 0x4000008>,
            // i.e. 64 MB + 8 bytes. Linux rounds the over-by-8 up to a full
            // 2MB page, builds a vmemmap entry for VA → PA 0x84000000, and
            // when something later loads through that mapping it traps with
            // scause=5 (load access fault) because PA 0x84000000 is past the
            // end of physical RAM.
            //
            // Find the size cell in the DTB and overwrite the +8 with 0.
            // 8-byte size value (big-endian, with #size-cells=2, #address-cells=2).
            // Sedna emits ramBytes + 8 as the size; we want exactly ramBytes.
            // Build the expected pattern dynamically based on configured ramBytes.
            val sednaSize = ramBytes.toLong() + 8L
            val patchPattern = byteArrayOf(
                ((sednaSize ushr 56) and 0xff).toByte(),
                ((sednaSize ushr 48) and 0xff).toByte(),
                ((sednaSize ushr 40) and 0xff).toByte(),
                ((sednaSize ushr 32) and 0xff).toByte(),
                ((sednaSize ushr 24) and 0xff).toByte(),
                ((sednaSize ushr 16) and 0xff).toByte(),
                ((sednaSize ushr 8) and 0xff).toByte(),
                (sednaSize and 0xff).toByte(),
            )
            var fixed = 0
            outer@ for (off in 0..(dtbBytes.size - patchPattern.size)) {
                for (k in patchPattern.indices) {
                    if (dtbBytes[off + k] != patchPattern[k]) continue@outer
                }
                dtbBytes[off + 7] = 0
                fixed++
            }
            if (fixed == 0) {
                System.err.println("[oc2 boot-diag] DTB memsize patch: pattern not found; check Sedna DTB format")
            }

            // Also patch the ORIGINAL DTB in place at origDtbAddr — OpenSBI's
            // libfdt fixups may read from there, and if anything still
            // references it (e.g. cached pointers) we want it consistent.
            val origDtbBytes = ByteArray(totalSize)
            MemoryMaps.load(board.memoryMap, origDtbAddr, origDtbBytes, 0, totalSize)
            outer2@ for (off in 0..(origDtbBytes.size - patchPattern.size)) {
                for (k in patchPattern.indices) {
                    if (origDtbBytes[off + k] != patchPattern[k]) continue@outer2
                }
                origDtbBytes[off + 7] = 0
                MemoryMaps.store(board.memoryMap, origDtbAddr + off, origDtbBytes, off, patchPattern.size)
            }

            // New DTB lives 8 MB before end of RAM, NOT in the last 64KB.
            //
            // Linux's `create_fdt_early_page_table` builds an early FDT
            // mapping covering 4 MB starting from (DTB_PA & ~PMD_MASK).
            // If DTB_PA is at end-of-RAM minus a small offset, the fixmap
            // mapping spans past end of RAM and the kernel access-faults
            // when validating the DTB (e.g. crc32 in fdt_check_full).
            //
            // Place DTB 8 MB before end of RAM, 2MB-aligned, so the 4 MB
            // FDT fixmap window stays well within RAM.
            val pmdMask = (2L * 1024 * 1024) - 1
            val newDtbAddr = (ramBase + ramBytes - 8L * 1024 * 1024) and pmdMask.inv()
            MemoryMaps.store(board.memoryMap, newDtbAddr, dtbBytes, 0, totalSize)

            // Patch flash[0x10..0x17] — the boot stub now loads newDtbAddr
            // into a1. OpenSBI _start saves a1 → s1, libfdt fixups operate on
            // newDtbAddr, and patchOpenSbiFwNextArg1 returns s1 to the kernel
            // as its a1.
            //
            // Sedna's FlashMemoryDevice is constructed read-only (R5Board calls
            // `new FlashMemoryDevice(256)` which delegates to the 1-arg ctor →
            // 2-arg ctor with `readonly=true`). FlashMemoryDevice.store() bails
            // out early on the readonly flag, so MemoryMaps.store() against the
            // flash region is a silent no-op — the prior attempt produced a
            // perfectly relocated DTB that nobody pointed at.
            //
            // The buffer itself is plain RW java.nio.ByteBuffer though;
            // FlashMemoryDevice.getData() is public and load() reads from the
            // same buffer, so writing via reflection is visible to the boot
            // stub's `ld a1, 16(t0)` at boot time.
            val flashField = board.javaClass.getDeclaredField("flash").apply { isAccessible = true }
            val flash = flashField.get(board)
            val flashBuffer = flash.javaClass.getMethod("getData").invoke(flash) as ByteBuffer
            // FlashMemoryDevice's 1-int ctor sets LITTLE_ENDIAN already, but
            // setting it again is a free no-op and documents intent.
            flashBuffer.order(ByteOrder.LITTLE_ENDIAN)
            // FLASH_DTB_PTR_ADDR is the *bus* address (flash starts at 0x1000,
            // DTB ptr at offset 0x10 → bus addr 0x1010). The buffer is rooted
            // at flash start, so we subtract the base.
            flashBuffer.putLong((FLASH_DTB_PTR_ADDR - FLASH_BASE_ADDR).toInt(), newDtbAddr)
        } catch (t: Throwable) {
            System.err.println("[oc2 boot-diag] DTB relocation failed: ${t.message}")
        }
    }

    private fun patchOpenSbiFwNextArg1(sbi: ByteArray) {
        val offset = OPENSBI_FW_NEXT_ARG1_OFFSET.toInt()
        require(offset + 8 <= sbi.size) {
            "OpenSBI binary too small (${sbi.size} bytes) to contain fw_next_arg1 at 0x${offset.toString(16)}"
        }
        val b0 = sbi[offset].toInt() and 0xff
        val b1 = sbi[offset + 1].toInt() and 0xff
        require(b0 == 0x05 && b1 == 0x65) {
            "OpenSBI fw_next_arg1 prologue mismatch at offset 0x${offset.toString(16)}: " +
                "expected 05 65 (c.lui a0, 1), got ${"%02x".format(b0)} ${"%02x".format(b1)}. " +
                "Rebuild may have shifted the symbol; re-disassemble fw_jump.elf and update OPENSBI_FW_NEXT_ARG1_OFFSET."
        }
        val patch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x00048513)
            putInt(0x00008067)
        }.array()
        System.arraycopy(patch, 0, sbi, offset, patch.size)
    }

    companion object {
        const val DEFAULT_RAM_BYTES: Int = 64 * 1024 * 1024
        const val DEFAULT_RAM_BASE: Long = 0x80000000L

        /** Default per-server-tick cycle budget. Tunable via config later. */
        const val DEFAULT_CYCLES_PER_TICK: Int = 1_000_000

        /**
         * Offset of `fw_next_arg1` symbol inside `fw_jump.bin`. Determined by
         * disassembling `fw_jump.elf` from the OpenSBI 1.3.1 build (minux
         * MINUX_REF=8d2d3c4f9). If the firmware build pipeline changes
         * (different OpenSBI version, different generic-platform link script,
         * extra .text symbols added before fw_next_arg1), this constant must
         * be re-derived. The `patchOpenSbiFwNextArg1` sanity check throws
         * loudly if the prologue at this offset doesn't match.
         */
        const val OPENSBI_FW_NEXT_ARG1_OFFSET: Long = 0x588L

        /**
         * Bus address of Sedna's flash boot stub (FLASH_ADDRESS in
         * `R5Board`). The stub lives at 0x1000-0x10FF, with the DTB pointer
         * at offset 0x10.
         */
        const val FLASH_BASE_ADDR: Long = 0x1000L

        /**
         * Bus address of the DTB pointer inside Sedna's flash boot stub. The
         * stub at FLASH_BASE_ADDR does `ld a1, 16(t0)` (where
         * `t0=auipc(0)=0x1000`), so the load targets
         * `0x1000 + 0x10 = 0x1010`. Used by [relocateDtbForOpenSbiHeadroom].
         */
        const val FLASH_DTB_PTR_ADDR: Long = FLASH_BASE_ADDR + 0x10L

        /**
         * Slack region size for the DTB after relocation. 64 KB is comfortably
         * larger than the ~3-4 KB Sedna emits, with room for OpenSBI's libfdt
         * fixups to grow the tree in place. Must be a multiple of 8 so the
         * relocated DTB stays 8-aligned (libfdt requires 8-byte alignment).
         */
        const val FDT_RELOC_SIZE: Int = 64 * 1024

        /**
         * Number of sub-steps per [step] call, with a [console] drain between
         * each. 16 chosen to match Sedna's UART16550A transmit-FIFO depth so
         * the guest never has to spin more than one FIFO-fill per chunk while
         * waiting for the host to read its output. See [step]'s kdoc for the
         * full rationale.
         */
        const val DRAIN_CHUNKS_PER_STEP: Int = 16

        /** PLIC IRQ assigned to the 16550A UART. */
        const val IRQ_UART: Int = 1

        /** PLIC IRQ assigned to the first virtio-mmio device (vda). Subsequent
         *  virtio devices get sequential IRQs (vdb=3, vdc=4, ...). PLIC has
         *  31 lines available, so the four devices we register fit easily. */
        const val IRQ_VIRTIO_FIRST: Int = 2
    }
}
