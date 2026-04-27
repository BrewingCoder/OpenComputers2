package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * Tests for [ControlPlaneVm.snapshot]/restore. The first hard requirement of
 * Tier-2 multiplayer is "the VM survives chunk-unload" — Ceres-backed
 * serialization gives us that for free as long as we wire the bytes through
 * the BE save path. These tests prove the platform-side round-trip works.
 */
class ControlPlaneSnapshotTest {

    @AfterEach
    fun releaseMmap() {
        System.gc()
        @Suppress("DEPRECATION") System.runFinalization()
    }

    @Test
    fun `snapshot of a fresh VM is non-empty`() {
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            val bytes = vm.snapshot()
            // Even a fresh VM has CPU + MMU + RAM state; not zero bytes.
            bytes.size shouldBeGreaterThan 0
        }
    }

    @Test
    fun `restore resumes from where snapshot was taken`() {
        // Run a stub to advance cycles + push a byte into the UART, snapshot,
        // then restore into a new VM and verify it picks up where it left off.
        val uartBase = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.uartBase }
        val stub = ControlPlaneBoot.helloUartStub(uartBase, 'Z'.code.toByte())

        val (snap, cyclesAtSnap) = ControlPlaneVm(ramBytes = 1 * 1024 * 1024, bootImage = stub).use { vm ->
            vm.step(50_000)
            // The stub has emitted 'Z' to the UART by now, but we don't drain
            // again — we want the restore path to be working from raw VM state.
            vm.snapshot() to vm.cycles
        }
        cyclesAtSnap shouldBeGreaterThan 0L

        ControlPlaneVm(ramBytes = 1 * 1024 * 1024, snapshot = snap).use { restored ->
            // Cycle counter resumes at the snapshot value (give or take the
            // restore overhead that Sedna doesn't advance).
            restored.cycles shouldBeGreaterThanOrEqual cyclesAtSnap
            // The restored CPU keeps stepping from the same PC (the j-self
            // loop at the end of the stub), so cycles continue accumulating.
            val before = restored.cycles
            restored.step(10_000)
            restored.cycles shouldBeGreaterThan before
        }
    }

    @Test
    fun `snapshot and bootImage are mutually exclusive`() {
        val snap = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.snapshot() }
        val ex = runCatching {
            ControlPlaneVm(
                ramBytes = 1 * 1024 * 1024,
                bootImage = ByteArray(4),
                snapshot = snap,
            )
        }.exceptionOrNull()
        require(ex is IllegalArgumentException) {
            "expected IllegalArgumentException, got ${ex?.javaClass?.simpleName}"
        }
        require((ex.message ?: "").contains("mutually exclusive")) {
            "expected 'mutually exclusive' in message, got '${ex.message}'"
        }
    }

    @Test
    fun `snapshot and bootArgs are mutually exclusive`() {
        val snap = ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { it.snapshot() }
        val ex = runCatching {
            ControlPlaneVm(
                ramBytes = 1 * 1024 * 1024,
                bootArgs = "console=ttyS0",
                snapshot = snap,
            )
        }.exceptionOrNull()
        require(ex is IllegalArgumentException) {
            "expected IllegalArgumentException, got ${ex?.javaClass?.simpleName}"
        }
    }

    @Test
    fun `multiple snapshot calls return independent byte arrays`() {
        ControlPlaneVm(ramBytes = 1 * 1024 * 1024).use { vm ->
            val a = vm.snapshot()
            val b = vm.snapshot()
            // Same VM state → same bytes, but the arrays must be distinct
            // instances so the caller can mutate one without affecting the other.
            (a === b) shouldBe false
            a.size shouldBe b.size
        }
    }
}
