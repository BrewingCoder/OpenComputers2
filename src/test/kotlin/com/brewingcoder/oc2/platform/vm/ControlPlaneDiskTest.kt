package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.RandomAccessFile

class ControlPlaneDiskTest {

    /**
     * ByteBufferBlockDevice mmaps the file via FileChannel.map; Java doesn't
     * expose an unmap call, so the OS lock on the file persists until the
     * MappedByteBuffer is GC'd. On Windows that prevents @TempDir from deleting
     * the file. A GC + brief settle releases the mapping in time for cleanup.
     */
    @AfterEach
    fun releaseMmap() {
        System.gc()
        System.runFinalization()
    }

    @Test
    fun `createOrOpen creates a file at the requested size`(@TempDir dir: File) {
        val img = File(dir, "fresh.img")

        ControlPlaneDisk.createOrOpen(img, sizeBytes = 4096L).use { dev ->
            dev.capacity shouldBe 4096L
        }

        img.length() shouldBe 4096L
    }

    @Test
    fun `bytes round-trip across close-and-reopen`(@TempDir dir: File) {
        val img = File(dir, "persist.img")
        val payload = byteArrayOf(0x42, 0x69, 0x66, 0x66, 0x00, 0x55)

        ControlPlaneDisk.createOrOpen(img, 4096L).use { dev ->
            dev.getOutputStream(0L).use { it.write(payload) }
            dev.flush()
        }

        ControlPlaneDisk.createOrOpen(img, 4096L).use { dev ->
            dev.getInputStream(0L).use { stream ->
                val buf = stream.readNBytes(payload.size)
                buf shouldBe payload
            }
        }
    }

    @Test
    fun `existing file shorter than requested size is extended`(@TempDir dir: File) {
        val img = File(dir, "extend.img")
        // Pre-create at 1 KB.
        RandomAccessFile(img, "rw").use { it.setLength(1024L) }
        img.length() shouldBe 1024L

        ControlPlaneDisk.createOrOpen(img, 4096L).use { dev ->
            dev.capacity shouldBe 4096L
        }

        img.length() shouldBeGreaterThanOrEqual 4096L
    }

    @Test
    fun `non-multiple-of-sector-size is rejected`(@TempDir dir: File) {
        val img = File(dir, "bad.img")
        assertThrows<IllegalArgumentException> {
            ControlPlaneDisk.createOrOpen(img, sizeBytes = 1023L)
        }
    }

    @Test
    fun `zero or negative size is rejected`(@TempDir dir: File) {
        val img = File(dir, "bad.img")
        assertThrows<IllegalArgumentException> {
            ControlPlaneDisk.createOrOpen(img, sizeBytes = 0L)
        }
    }
}
