package com.brewingcoder.oc2.platform.vm

import it.unimi.dsi.fastutil.bytes.ByteArrayFIFOQueue
import it.unimi.dsi.fastutil.ints.Int2LongArrayMap
import li.cil.ceres.Ceres
import li.cil.sedna.device.block.SparseBlockDevice
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice
import li.cil.sedna.riscv.R5CPU
import li.cil.sedna.serialization.serializers.AtomicIntegerSerializer
import li.cil.sedna.serialization.serializers.BitSetSerializer
import li.cil.sedna.serialization.serializers.ByteArrayFIFOQueueSerializer
import li.cil.sedna.serialization.serializers.FileSystemFileMapSerializer
import li.cil.sedna.serialization.serializers.Int2LongArrayMapSerializer
import li.cil.sedna.serialization.serializers.R5CPUSerializer
import li.cil.sedna.serialization.serializers.SparseBlockMapSerializer
import java.util.BitSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wires Sedna's hand-rolled Ceres serializers into the global [Ceres] registry.
 * Required before any [li.cil.ceres.BinarySerialization.serialize] call against
 * an [li.cil.sedna.riscv.R5Board], because several of its fields (notably
 * `R5Board.cpu`) are `final` — Ceres can't reassign them, so it needs a
 * registered [li.cil.ceres.api.Serializer] that fills the existing instance
 * in place.
 *
 * The 7 serializers shipped by Sedna are:
 *   - [R5CPUSerializer]            — the CPU itself (registers, MMU, CSRs)
 *   - [AtomicIntegerSerializer]    — virtio queue counters etc.
 *   - [BitSetSerializer]           — guest interrupt-pending bitmaps
 *   - [ByteArrayFIFOQueueSerializer] — UART16550A's tx/rx FIFOs (fastutil)
 *   - [FileSystemFileMapSerializer]  — virtio-9p's open-file map
 *   - [Int2LongArrayMapSerializer]   — sparse block-device pointer map (fastutil)
 *   - [SparseBlockMapSerializer]     — sparse block-device chunk map
 *
 * Idempotent — safe to call from any thread, only the first call does work.
 * Mod load + tests both invoke this on first VM construction.
 */
object SednaSerializerRegistration {

    init {
        // Ceres 0.0.3+22 ships two serializer backends:
        //   - CompiledSerializer  — generates bytecode at runtime via
        //     `sun.misc.Unsafe.defineAnonymousClass`, REMOVED in Java 17+.
        //     Throws NoSuchMethodError on first use.
        //   - ReflectionSerializer — pure java.lang.reflect, works everywhere.
        //
        // The factory picks between them in a static initializer based on the
        // `li.cil.ceres.disableCodeGen` property. We set it here so it lands
        // before any Ceres class is loaded; this object is the first Ceres
        // touchpoint in the mod (every code path goes through `ensure()`).
        System.setProperty("li.cil.ceres.disableCodeGen", "true")
    }

    private val initialized = AtomicBoolean(false)

    fun ensure() {
        if (!initialized.compareAndSet(false, true)) return
        // Initialize Ceres' built-in array serializers (idempotent on Ceres' side).
        Ceres.initialize()
        // Sedna's customs — order doesn't matter, but match the package layout
        // for grep-friendliness.
        Ceres.putSerializer(R5CPU::class.java, R5CPUSerializer())
        Ceres.putSerializer(AtomicInteger::class.java, AtomicIntegerSerializer())
        Ceres.putSerializer(BitSet::class.java, BitSetSerializer())
        Ceres.putSerializer(ByteArrayFIFOQueue::class.java, ByteArrayFIFOQueueSerializer())
        Ceres.putSerializer(VirtIOFileSystemDevice.FileSystemFileMap::class.java, FileSystemFileMapSerializer())
        Ceres.putSerializer(Int2LongArrayMap::class.java, Int2LongArrayMapSerializer())
        Ceres.putSerializer(SparseBlockDevice.SparseBlockMap::class.java, SparseBlockMapSerializer())
    }
}
