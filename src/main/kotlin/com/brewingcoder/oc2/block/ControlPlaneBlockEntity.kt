package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.vm.ControlPlaneVm
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Lower-half BE for [ControlPlaneBlock]. Owns a [ControlPlaneVm] (a Sedna
 * R5Board with 64 MB of RAM and nothing else, for now) and steps it on every
 * server tick.
 *
 * R1 proof-of-life scope:
 *   - VM is constructed lazily on first tick (so we don't pay the 64 MB
 *     allocation for client-side ghost BEs that never tick)
 *   - [tick] runs a fixed cycle budget per server tick; cycle counter
 *     accumulates
 *   - NBT persists only the cycle counter (full Sedna serialization is a
 *     follow-up)
 *   - With no firmware loaded the CPU spins on illegal-instruction traps,
 *     which still increments cycles — that's the proof-of-life signal.
 */
class ControlPlaneBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.CONTROL_PLANE.get(), pos, state) {

    private var vm: ControlPlaneVm? = null

    /** Cycles persisted across saves; the running [vm] tracks live cycles separately. */
    private var persistedCycles: Long = 0L

    /** True once the world side has constructed the VM; client-side BEs leave this false. */
    private val initialised: Boolean
        get() = vm != null

    /** Cheap status string for the right-click message + future status display. */
    fun statusLine(): String {
        val v = vm
        return if (v == null) {
            "Control Plane: VM not yet booted (persisted=$persistedCycles cycles)"
        } else {
            "Control Plane: ${v.describe()}"
        }
    }

    /** Called every server tick by [ControlPlaneBlock.getTicker]. */
    fun tick() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        val v = vm ?: bootVm()
        v.step(ControlPlaneVm.DEFAULT_CYCLES_PER_TICK)
        // Persist just often enough that a sudden world unload doesn't lose
        // observable progress; the chunk save path is the real flush.
        if ((v.cycles and PERSIST_INTERVAL_MASK) == 0L) {
            persistedCycles = v.cycles
            setChanged()
        }
    }

    private fun bootVm(): ControlPlaneVm {
        val v = ControlPlaneVm()
        vm = v
        OpenComputers2.LOGGER.info(
            "control plane @ {} VM booted: {}",
            blockPos,
            v.describe(),
        )
        return v
    }

    override fun setRemoved() {
        // No external resources held by the VM in R1 (no FDs, no threads); GC will reclaim.
        // When we add disk image FDs / kernel threads in later commits, close them here.
        vm = null
        super.setRemoved()
    }

    // ------------------------------------------------------------------
    // NBT persistence (cycles only, for now)
    // ------------------------------------------------------------------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        val live = vm?.cycles ?: persistedCycles
        tag.putLong(NBT_CYCLES, live)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CYCLES)) {
            persistedCycles = tag.getLong(NBT_CYCLES)
        }
    }

    companion object {
        private const val NBT_CYCLES = "cycles"

        /**
         * Persist on cycle counts where the low N bits are zero, so we flush every
         * ~16 ticks at the default 1M cycles/tick budget. Keeps NBT churn off the
         * hot path while still surviving abrupt shutdown with reasonable fidelity.
         */
        private const val PERSIST_INTERVAL_MASK: Long = (1L shl 24) - 1
    }
}
