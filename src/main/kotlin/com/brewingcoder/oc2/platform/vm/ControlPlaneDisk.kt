package com.brewingcoder.oc2.platform.vm

import li.cil.sedna.api.device.BlockDevice
import li.cil.sedna.device.block.ByteBufferBlockDevice
import java.io.File
import java.io.RandomAccessFile

/**
 * File-backed disk image used as virtio-blk for the Control Plane VM.
 *
 * Allocates a sparse file at [createOrOpen]'s `file` path, sized to `sizeBytes`,
 * then wraps it in Sedna's [ByteBufferBlockDevice]. The returned [BlockDevice]
 * is what [li.cil.sedna.device.virtio.VirtIOBlockDevice] consumes.
 *
 * Sparse allocation (RandomAccessFile.setLength on a fresh file) keeps disk
 * footprint small until the guest actually writes — a 256 MB disk that's only
 * 12 MB used costs 12 MB on disk under any modern FS that supports sparse files.
 *
 * Pure Rule-D code — no MC imports. Tests can hit it directly.
 */
object ControlPlaneDisk {
    /** Sector size virtio-blk advertises. Sedna pins 512; we mirror it. */
    const val SECTOR_BYTES: Long = 512L

    /** Default per-Control-Plane disk size: 256 MB. Tunable via config later. */
    const val DEFAULT_SIZE_BYTES: Long = 256L * 1024L * 1024L

    /**
     * Returns a [BlockDevice] backed by [file] sized to exactly [sizeBytes].
     * Creates parent directories, the file itself, and (sparsely) extends it
     * to size if it's smaller than [sizeBytes]. Never shrinks an existing file —
     * shrinking would silently destroy guest data, which is not something we
     * want to ever do without an explicit "wipe" gesture.
     */
    fun createOrOpen(
        file: File,
        sizeBytes: Long = DEFAULT_SIZE_BYTES,
        readOnly: Boolean = false,
    ): BlockDevice {
        require(sizeBytes > 0) { "sizeBytes must be > 0 (got $sizeBytes)" }
        require(sizeBytes % SECTOR_BYTES == 0L) {
            "sizeBytes must be a multiple of $SECTOR_BYTES (got $sizeBytes)"
        }
        file.parentFile?.mkdirs()
        val needsExtend = !file.exists() || file.length() < sizeBytes
        if (needsExtend) {
            RandomAccessFile(file, "rw").use { raf -> raf.setLength(sizeBytes) }
        }
        return ByteBufferBlockDevice.createFromFile(file, sizeBytes, readOnly)
    }
}
