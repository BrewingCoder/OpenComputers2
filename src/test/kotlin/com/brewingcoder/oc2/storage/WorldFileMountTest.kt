package com.brewingcoder.oc2.storage

import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.platform.storage.WritableMountContract
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

class WorldFileMountTest : WritableMountContract() {

    private val tmpRoots = mutableListOf<Path>()

    override fun newMount(capacity: Long): WritableMount {
        val root = Files.createTempDirectory("oc2-mount-test")
        tmpRoots.add(root)
        return WorldFileMount(root, capacity)
    }

    @AfterEach
    fun cleanup() {
        for (r in tmpRoots) deleteTree(r)
        tmpRoots.clear()
    }

    @Test
    fun `existing files on disk are counted toward capacity`() {
        val root = Files.createTempDirectory("oc2-mount-preload").also { tmpRoots.add(it) }
        Files.writeString(root.resolve("seed.txt"), "12345")  // 5 bytes
        val m = WorldFileMount(root, capacityBytes = 100)
        m.remainingSpace() shouldBe 95L
        m.size("seed.txt") shouldBe 5L
    }

    @Test
    fun `mount survives close-and-reopen`() {
        val root = Files.createTempDirectory("oc2-mount-reopen").also { tmpRoots.add(it) }
        WorldFileMount(root, 1024).openForWrite("persisted.txt").use {
            it.write(ByteBuffer.wrap("see you again".toByteArray()))
        }
        val reopened = WorldFileMount(root, 1024)
        reopened.exists("persisted.txt") shouldBe true
        reopened.size("persisted.txt") shouldBe 13L
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, exc: java.io.IOException?): FileVisitResult {
                Files.delete(dir); return FileVisitResult.CONTINUE
            }
        })
    }
}
