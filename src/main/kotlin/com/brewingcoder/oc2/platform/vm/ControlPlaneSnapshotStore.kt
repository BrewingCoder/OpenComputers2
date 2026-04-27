package com.brewingcoder.oc2.platform.vm

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * On-disk store for [ControlPlaneVm] snapshots. One file per VM, named after
 * the same identity the disk image uses (owner UUID or BE UUID), so a
 * Control Plane's full state lives in two paired files:
 *
 *   `<world>/oc2/vm-disks/<id>.img`     — virtio-blk backing file
 *   `<world>/oc2/vm-snapshots/<id>.snap` — Ceres-serialized R5Board state
 *
 * Snapshots are large (RAM-sized — a 64 MB VM produces a ~tens-of-MB file),
 * which is why they don't go in the BE's NBT — chunk save would balloon to
 * unworkable sizes. Storing them as sibling files keeps NBT tiny and lets the
 * filesystem handle large blobs.
 *
 * Writes are atomic via a `.tmp` + rename so a mid-write crash leaves either
 * the previous good snapshot or no file at all — never a torn one. Reads are
 * a straight `readBytes` since the VM constructor consumes the bytes
 * immediately.
 *
 * Pure Rule-D code — no MC imports. The BE wraps it with the
 * world-save-relative path resolution.
 */
class ControlPlaneSnapshotStore(private val rootDir: File) {

    /** Path the snapshot for [name] would live at. Doesn't touch disk. */
    fun fileFor(name: String): File = File(rootDir, "$name$EXTENSION")

    /** True if a snapshot file exists for [name]. */
    fun exists(name: String): Boolean = fileFor(name).isFile

    /**
     * Atomically write [bytes] to the snapshot for [name]. Creates [rootDir]
     * if absent. Implemented as write-to-tmp + atomic rename so a crash mid-
     * write can't leave a torn snapshot file behind.
     */
    fun write(name: String, bytes: ByteArray) {
        rootDir.mkdirs()
        val target = fileFor(name)
        val tmp = File(rootDir, "$name$EXTENSION$TMP_SUFFIX")
        tmp.writeBytes(bytes)
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            // Some filesystems (rare on modern Win/Linux, but possible on
            // network mounts) don't advertise atomic move. Fall back to a
            // best-effort replace — still safer than write-in-place.
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * Read the snapshot for [name], or null if absent. Returns the raw bytes;
     * caller hands them to [ControlPlaneVm]'s `snapshot=` constructor.
     */
    fun read(name: String): ByteArray? {
        val f = fileFor(name)
        return if (f.isFile) f.readBytes() else null
    }

    /**
     * Delete the snapshot for [name]. Returns true if a file was removed.
     * No-op if no snapshot exists. Used when an admin gesture wants a fresh
     * boot (e.g., a `wipe` command, or recovery from a corrupt snapshot).
     */
    fun delete(name: String): Boolean = fileFor(name).delete()

    companion object {
        const val EXTENSION: String = ".snap"
        private const val TMP_SUFFIX: String = ".tmp"
    }
}
