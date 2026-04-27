package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ControlPlaneSnapshotStoreTest {

    @Test
    fun `read returns null when no snapshot exists`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        store.read("absent").shouldBeNull()
    }

    @Test
    fun `write then read round-trips bytes`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        store.write("vm-a", payload)
        store.read("vm-a")?.toList().shouldContainExactly(payload.toList())
    }

    @Test
    fun `write creates the root directory if absent`(@TempDir dir: File) {
        val root = File(dir, "deep/nested/path")
        val store = ControlPlaneSnapshotStore(root)
        root.exists().shouldBeFalse()
        store.write("vm-a", byteArrayOf(0xCA.toByte(), 0xFE.toByte()))
        root.isDirectory.shouldBeTrue()
    }

    @Test
    fun `write is atomic — tmp file is gone after a successful write`(@TempDir dir: File) {
        val root = File(dir, "snaps")
        val store = ControlPlaneSnapshotStore(root)
        store.write("vm-a", ByteArray(64) { it.toByte() })
        // After write, only the .snap should remain — no .snap.tmp leftover.
        root.list()?.toList().shouldContainExactly(listOf("vm-a.snap"))
    }

    @Test
    fun `write replaces an existing snapshot`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        store.write("vm-a", byteArrayOf(1, 1, 1))
        store.write("vm-a", byteArrayOf(9, 9, 9, 9))
        store.read("vm-a")?.toList().shouldContainExactly(listOf<Byte>(9, 9, 9, 9))
    }

    @Test
    fun `exists reflects presence`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        store.exists("vm-a").shouldBeFalse()
        store.write("vm-a", byteArrayOf(1))
        store.exists("vm-a").shouldBeTrue()
    }

    @Test
    fun `delete removes the file and returns true`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        store.write("vm-a", byteArrayOf(1))
        store.delete("vm-a").shouldBeTrue()
        store.exists("vm-a").shouldBeFalse()
    }

    @Test
    fun `delete returns false when the file is absent`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        store.delete("absent").shouldBeFalse()
    }

    @Test
    fun `fileFor surfaces the resolved path without touching disk`(@TempDir dir: File) {
        val root = File(dir, "snaps")
        val store = ControlPlaneSnapshotStore(root)
        store.fileFor("vm-a") shouldBe File(root, "vm-a.snap")
        // fileFor must not create anything.
        root.exists().shouldBeFalse()
    }

    @Test
    fun `writeWith streams bytes atomically`(@TempDir dir: File) {
        val root = File(dir, "snaps")
        val store = ControlPlaneSnapshotStore(root)
        store.writeWith("vm-a") { out ->
            out.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }
        store.read("vm-a")?.toList().shouldContainExactly(listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8))
        // No leftover .tmp file.
        root.list()?.toList().shouldContainExactly(listOf("vm-a.snap"))
    }

    @Test
    fun `writeWith deletes the tmp file when the body throws`(@TempDir dir: File) {
        val root = File(dir, "snaps")
        val store = ControlPlaneSnapshotStore(root)
        // Pre-populate so we can verify the existing snapshot survives a
        // failed write — the whole point of writing to .tmp first.
        store.write("vm-a", byteArrayOf(0xAA.toByte(), 0xBB.toByte()))

        val ex = runCatching {
            store.writeWith("vm-a") { out ->
                out.write(0xCC)
                error("boom")
            }
        }.exceptionOrNull()
        require(ex != null) { "expected the body's exception to propagate" }

        // Original snapshot still on disk.
        store.read("vm-a")?.toList().shouldContainExactly(listOf<Byte>(0xAA.toByte(), 0xBB.toByte()))
        // No tmp leak.
        root.list()?.toList().shouldContainExactly(listOf("vm-a.snap"))
    }

    @Test
    fun `readWith returns null when no snapshot exists`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        val result = store.readWith("absent") { _ -> error("body should not run") }
        result.shouldBeNull()
    }

    @Test
    fun `readWith hands a stream of the file contents`(@TempDir dir: File) {
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        val payload = byteArrayOf(10, 20, 30, 40)
        store.write("vm-a", payload)
        val read = store.readWith("vm-a") { it.readBytes() }
        read?.toList().shouldContainExactly(payload.toList())
    }

    @Test
    fun `round-trips a real VM snapshot`(@TempDir dir: File) {
        // End-to-end smoke: take a real Sedna snapshot, persist via the store,
        // restore through the VM ctor — proves the store doesn't mangle bytes
        // and the resulting file is a valid VM snapshot.
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))
        val (snap, _) = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            vm.step(10_000)
            vm.snapshot() to vm.cycles
        }
        store.write("vm-a", snap)
        val restoredBytes = store.read("vm-a") ?: error("snapshot vanished")
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024, snapshot = restoredBytes).use { restored ->
            // Stepping the restored VM proves it accepted the bytes round-tripped
            // through disk; cycle counter is the cheapest proof-of-life.
            val before = restored.cycles
            restored.step(5_000)
            (restored.cycles > before).shouldBeTrue()
        }
    }

    @Test
    fun `streaming round-trip via writeWith and readWith resumes the VM`(@TempDir dir: File) {
        // Mirrors the production BE save path: snapshotTo → writeWith, then
        // readWith → snapshotStream. Proves the stream-only path is wired
        // end-to-end without ever materializing the snapshot bytes on heap.
        val store = ControlPlaneSnapshotStore(File(dir, "snaps"))

        val cyclesAtSnap = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            vm.step(20_000)
            store.writeWith("vm-a") { out -> vm.snapshotTo(out) }
            vm.cycles
        }
        cyclesAtSnap shouldBeGreaterThan 0L

        val before = store.readWith("vm-a") { stream ->
            ControlPlaneVm(
                ramBytes = 1 * 1024 * 1024,
                snapshotStream = stream,
            ).use { restored ->
                val c = restored.cycles
                restored.step(5_000)
                (restored.cycles > c).shouldBeTrue()
                c
            }
        }
        before shouldBe cyclesAtSnap
    }
}
