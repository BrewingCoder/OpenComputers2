package com.brewingcoder.oc2.platform.vm

import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

/**
 * Smoke tests for the Tier-2 Linux VM holder. We don't load a kernel here —
 * the goal is to prove Sedna is on the classpath, the R5Board accepts the
 * default RAM mapping, and the cycle counter advances when we [step].
 */
class ControlPlaneVmTest {

    @Test
    fun `step advances the cycle counter`() {
        val vm = ControlPlaneVm(ramBytes = 1 * 1024 * 1024) // 1 MB is plenty for a no-firmware spin
        val before = vm.cycles
        before shouldBe 0L

        val after = vm.step(100_000)

        after shouldBeGreaterThan before
    }

    @Test
    fun `describe surfaces the ram size and base`() {
        val vm = ControlPlaneVm(ramBytes = 4 * 1024 * 1024)
        val text = vm.describe()
        text shouldContain "ram=4MB"
        text shouldContain "0x80000000"
    }

    @Test
    fun `cycle counter accumulates across steps`() {
        val vm = ControlPlaneVm(ramBytes = 1 * 1024 * 1024)
        vm.step(50_000)
        val mid = vm.cycles
        vm.step(50_000)
        val end = vm.cycles
        end shouldBeGreaterThan mid
        mid shouldBeGreaterThan 0L
    }
}
