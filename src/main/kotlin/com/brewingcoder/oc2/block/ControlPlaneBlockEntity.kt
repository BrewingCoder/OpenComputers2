package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.vm.ControlPlaneDisk
import com.brewingcoder.oc2.platform.vm.ControlPlaneVm
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.util.UUID

/**
 * Lower-half BE for [ControlPlaneBlock]. Owns a [ControlPlaneVm] (Sedna R5Board
 * + 64 MB RAM + UART standard-output + optional virtio-blk) and steps it on
 * every server tick.
 *
 * Disk image lives at `<world-save>/oc2/vm-disks/<be-uuid>.img`. The UUID is
 * generated on first tick, persisted in NBT so subsequent ticks find the same
 * file. Sparse-allocated 256 MB by default — we don't pay real bytes until the
 * guest writes.
 *
 * R1 proof-of-life scope:
 *   - VM is constructed lazily on first tick (so we don't pay the 64 MB RAM
 *     allocation + disk file open for client-side ghost BEs that never tick).
 *   - [tick] runs a fixed cycle budget per server tick; cycle counter
 *     accumulates and any UART output ends up in [ControlPlaneVm.console].
 *   - With no firmware loaded the CPU spins on illegal-instruction traps,
 *     which still increments cycles and exercises the disk + console plumbing.
 *   - The kernel + initramfs that would actually drive that plumbing land in
 *     a follow-up commit (needs cross-compiled vmlinux + busybox initramfs).
 */
class ControlPlaneBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.CONTROL_PLANE.get(), pos, state) {

    private var vm: ControlPlaneVm? = null

    /** Cycles persisted across saves; the running [vm] tracks live cycles separately. */
    private var persistedCycles: Long = 0L

    /** Stable id used as the disk-image filename. Generated on first tick if absent. */
    private var vmUuid: UUID? = null

    /** Cheap status string for the right-click message + future status display. */
    fun statusLine(): String {
        val v = vm
        if (v == null) {
            return "Control Plane: VM not yet booted (persisted=$persistedCycles cycles)"
        }
        val recent = v.console.recentLines(2).joinToString(" | ")
        val tail = if (recent.isNotEmpty()) " | $recent" else ""
        return "Control Plane: ${v.describe()}$tail"
    }

    /** Called every server tick by [ControlPlaneBlock.getTicker]. */
    fun tick() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        val v = vm ?: bootVm(lvl as ServerLevel)
        v.step(ControlPlaneVm.DEFAULT_CYCLES_PER_TICK)
        // Persist just often enough that a sudden world unload doesn't lose
        // observable progress; the chunk save path is the real flush.
        if ((v.cycles and PERSIST_INTERVAL_MASK) == 0L) {
            persistedCycles = v.cycles
            setChanged()
        }
    }

    private fun bootVm(serverLevel: ServerLevel): ControlPlaneVm {
        val uuid = vmUuid ?: UUID.randomUUID().also {
            vmUuid = it
            setChanged()
        }
        val diskFile = resolveDiskFile(serverLevel, uuid)
        val v = ControlPlaneVm(
            diskFile = diskFile,
            diskSizeBytes = ControlPlaneDisk.DEFAULT_SIZE_BYTES,
        )
        vm = v
        OpenComputers2.LOGGER.info(
            "control plane @ {} VM booted: {} (disk={})",
            blockPos,
            v.describe(),
            diskFile.toPath(),
        )
        return v
    }

    private fun resolveDiskFile(serverLevel: ServerLevel, uuid: UUID): File {
        val root = serverLevel.server.getWorldPath(LevelResource(OpenComputers2.ID))
        val dir = root.resolve("vm-disks").toFile()
        dir.mkdirs()
        return File(dir, "$uuid.img")
    }

    override fun setRemoved() {
        // BE is being unloaded (chunk unload) or destroyed (block break). Either
        // way the VM should release its file descriptor — chunk reload will boot
        // a fresh VM next tick. Disk file persists; gardening orphans is a
        // separate (manual) gesture today.
        vm?.close()
        vm = null
        super.setRemoved()
    }

    // ------------------------------------------------------------------
    // NBT persistence (cycles + vm uuid; disk image is in the world save)
    // ------------------------------------------------------------------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        val live = vm?.cycles ?: persistedCycles
        tag.putLong(NBT_CYCLES, live)
        vmUuid?.let { tag.putUUID(NBT_VM_UUID, it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CYCLES)) {
            persistedCycles = tag.getLong(NBT_CYCLES)
        }
        if (tag.hasUUID(NBT_VM_UUID)) {
            vmUuid = tag.getUUID(NBT_VM_UUID)
        }
    }

    companion object {
        private const val NBT_CYCLES = "cycles"
        private const val NBT_VM_UUID = "vm_uuid"

        /**
         * Persist on cycle counts where the low N bits are zero, so we flush every
         * ~16 ticks at the default 1M cycles/tick budget. Keeps NBT churn off the
         * hot path while still surviving abrupt shutdown with reasonable fidelity.
         */
        private const val PERSIST_INTERVAL_MASK: Long = (1L shl 24) - 1
    }
}
