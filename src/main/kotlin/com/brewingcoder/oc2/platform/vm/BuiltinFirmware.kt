package com.brewingcoder.oc2.platform.vm

import java.io.InputStream

/**
 * Locates the four RISC-V firmware blobs we ship in the mod jar and exposes
 * them as fresh [InputStream]s for [ControlPlaneVm] to load on boot.
 *
 * Layout in the jar (rooted at `data/oc2/control-plane/firmware/`):
 * - `fw_jump.bin`     — OpenSBI firmware, loaded at RAM base (0x80000000).
 * - `Image`           — Linux kernel (RISC-V64 LE), loaded at RAM base + 0x200000.
 * - `bootfs.squashfs` — virtio-blk vda. Tiny initramfs that mounts the overlay
 *   then `chroot`s to `/newroot`. Read-only.
 * - `rootfs.cramfs`   — virtio-blk vdb. Buildroot userland (busybox + lua + codecs).
 *   Read-only; player writes land on vdc, the per-BE persistent disk, layered on
 *   top via overlayfs.
 *
 * Blobs are produced by `tools/buildroot/build.sh` and copied into
 * `src/main/resources/data/oc2/control-plane/firmware/` as a build step. When
 * absent (e.g. dev builds without buildroot run), [isAvailable] returns false
 * and [ControlPlaneVm] falls back to the bannerStub path so the BE still ticks
 * usefully.
 *
 * Pure-Kotlin / Rule-D — no MC types touched. Tests can substitute a fake
 * [ResourceLocator] that points at fixture blobs on disk.
 */
class BuiltinFirmware private constructor(
    private val locator: ResourceLocator,
) {

    /** All four blobs present in the classpath. Cheap — one `getResource` per file. */
    val isAvailable: Boolean by lazy {
        REQUIRED_PATHS.all { locator.exists(it) }
    }

    /** Open a fresh stream over `fw_jump.bin`. Caller (or [java.io.InputStream.use]) closes. */
    fun openSbiStream(): InputStream = require(PATH_OPENSBI)

    /** Open a fresh stream over the Linux kernel `Image`. */
    fun kernelImageStream(): InputStream = require(PATH_KERNEL)

    /** Open a fresh stream over `bootfs.squashfs` (virtio-blk vda). */
    fun bootfsStream(): InputStream = require(PATH_BOOTFS)

    /** Open a fresh stream over `rootfs.cramfs` (virtio-blk vdb). */
    fun rootfsStream(): InputStream = require(PATH_ROOTFS)

    private fun require(path: String): InputStream =
        locator.open(path)
            ?: throw IllegalStateException("firmware resource missing: $path (rebuild via tools/buildroot/build.sh)")

    /** Indirection so unit tests can inject blobs without touching the classpath. */
    interface ResourceLocator {
        fun exists(path: String): Boolean
        fun open(path: String): InputStream?
    }

    companion object {
        /** Loaded at `ramBase`. OpenSBI's FW_JUMP mode hands off to the kernel above. */
        const val PATH_OPENSBI: String = "data/oc2/control-plane/firmware/fw_jump.bin"

        /** Loaded at `ramBase + KERNEL_OFFSET`. RISC-V virt convention. */
        const val PATH_KERNEL: String = "data/oc2/control-plane/firmware/Image"

        /** virtio-blk vda — small initramfs running the overlay setup. */
        const val PATH_BOOTFS: String = "data/oc2/control-plane/firmware/bootfs.squashfs"

        /** virtio-blk vdb — userland (cramfs, read-only). */
        const val PATH_ROOTFS: String = "data/oc2/control-plane/firmware/rootfs.cramfs"

        /**
         * Kernel load offset above `ramBase`. OpenSBI fw_jump.bin built for the
         * RISC-V virt machine jumps to `ramBase + 0x200000` after initialization;
         * the kernel must be exactly there. Don't change unless you also rebuild
         * OpenSBI with a matching `FW_JUMP_ADDR`.
         */
        const val KERNEL_OFFSET: Long = 0x200000L

        /**
         * Default kernel command line.
         *
         * - `earlycon` (no value) — walks /chosen/stdout-path and binds the
         *   8250 driver to whatever UART the DTB advertises. Sedna populates
         *   stdout-path correctly, and OpenSBI's banner already proves the
         *   same UART works at the hardware level, so the 8250 probe should
         *   succeed. We previously tried `earlycon=sbi`, which routes through
         *   sbi_console_putchar — that requires CONFIG_RISCV_SBI_V01 in the
         *   kernel build, and we've seen zero kernel printk under it, so we
         *   suspect the kernel was built without legacy-SBI console support.
         *
         * - `console=ttyS0` — once the regular 8250 driver loads, bind
         *   printk to Sedna's UART16550A (compatible="ns16550a" in the DTB).
         *
         * - `root=/dev/vda ro` — vda is `bootfs.squashfs`, a read-only
         *   initramfs whose `/sbin/init` mounts the cramfs userland from
         *   vdb and the writable player disk from vdc, then chroots to
         *   the overlay. `rw` here would be a lie (squashfs is RO) and
         *   makes the kernel log a remount complaint.
         */
        const val DEFAULT_BOOT_ARGS: String =
            "earlycon console=ttyS0 root=/dev/vda ro no5lvl mem=64M"

        private val REQUIRED_PATHS = listOf(PATH_OPENSBI, PATH_KERNEL, PATH_BOOTFS, PATH_ROOTFS)

        /**
         * Default locator that reads from this mod's classpath. Returns null
         * for [open] when the resource is missing, so [isAvailable] is the
         * single source of truth for "do we have firmware".
         */
        fun fromClasspath(): BuiltinFirmware {
            val cl = BuiltinFirmware::class.java.classLoader
            return BuiltinFirmware(object : ResourceLocator {
                override fun exists(path: String): Boolean = cl.getResource(path) != null
                override fun open(path: String): InputStream? = cl.getResourceAsStream(path)
            })
        }

        /** Test seam — caller supplies a custom locator (e.g. backed by `Files.newInputStream`). */
        fun fromLocator(locator: ResourceLocator): BuiltinFirmware = BuiltinFirmware(locator)
    }
}
