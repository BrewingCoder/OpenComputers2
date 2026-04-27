package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.control.ControlPlaneRegistry
import com.brewingcoder.oc2.platform.peripheral.ControlPlanePeripheral
import com.brewingcoder.oc2.platform.vm.ControlPlaneDisk
import com.brewingcoder.oc2.platform.vm.ControlPlaneSnapshotStore
import com.brewingcoder.oc2.platform.vm.ControlPlaneVm
import com.brewingcoder.oc2.storage.OC2ServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

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
    BlockEntity(ModBlockEntities.CONTROL_PLANE.get(), pos, state),
    ControlPlanePeripheral, ChannelRegistrant {

    private var vm: ControlPlaneVm? = null

    /** Cycles persisted across saves; the running [vm] tracks live cycles separately. */
    private var persistedCycles: Long = 0L

    /** Stable id used as the disk-image filename. Generated on first tick if absent. */
    private var vmUuid: UUID? = null

    /**
     * Whether the VM should be ticking. Defaults to `true` so freshly placed
     * Control Planes boot immediately. Toggled via [togglePower]. Powering off
     * snapshots and closes the live VM (releasing RAM + disk FD); powering on
     * lets the next [tick] lazy-boot a fresh VM that restores from the
     * snapshot (so RAM contents survive the power cycle — true suspend/resume).
     */
    private var powered: Boolean = true

    /**
     * WiFi channel for `peripheral.find()` discovery from a Computer. Same
     * default + same trim/length rules as Computer/WiFiExtender so a freshly
     * placed Control Plane is immediately discoverable on the `default`
     * channel without configuration.
     */
    override var channelId: String = DEFAULT_CHANNEL
        private set

    override val location: Position
        get() = Position(blockPos.x, blockPos.y, blockPos.z)

    override val kind: String
        get() = REGISTRANT_KIND

    override val name: String
        get() = "controlplane@${blockPos.x},${blockPos.y},${blockPos.z}"

    /** Server-only — client BEs are visual stand-ins and don't touch the registry. */
    private val registryShouldTrack: Boolean
        get() = level?.isClientSide == false

    override fun onLoad() {
        super.onLoad()
        if (registryShouldTrack) ChannelRegistry.register(this)
    }

    /** Cheap status string for the right-click message + future status display. */
    fun statusLine(): String = onServerThread {
        val v = vm
        val powerLabel = if (powered) "ON" else "OFF"
        if (v == null) {
            return@onServerThread "Control Plane [$powerLabel]: VM not booted (persisted=$persistedCycles cycles)"
        }
        val recent = v.console.recentLines(2).joinToString(" | ")
        val tail = if (recent.isNotEmpty()) " | $recent" else ""
        "Control Plane [$powerLabel]: ${v.describe()}$tail"
    }

    /**
     * Reassign channel. Empty/blank channels coerce to [DEFAULT_CHANNEL] so the
     * Control Plane never falls off the registry entirely. Caller is responsible
     * for `setChanged` flush; we do it here too because most callers (config GUI)
     * forget.
     */
    fun setChannel(newChannel: String) {
        val cleaned = newChannel.trim().take(MAX_CHANNEL_LENGTH).ifBlank { DEFAULT_CHANNEL }
        if (cleaned == channelId) return
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        channelId = cleaned
        if (registryShouldTrack) ChannelRegistry.register(this)
        setChanged()
        OpenComputers2.LOGGER.info("control plane @ {} channel -> '{}'", blockPos, channelId)
    }

    // ------------------------------------------------------------------
    // ControlPlanePeripheral implementation
    // ------------------------------------------------------------------
    //
    // Methods marshal onto the server thread because both [vm] and [powered]
    // are mutated on the server tick. Even though Long/Boolean reads are atomic
    // on the JVM, marshaling gives consistent visibility across power cycles
    // (e.g. cycles() reading after togglePower() returns 0, not the stale live
    // counter from the closed VM).

    override fun cycles(): Long = onServerThread { vm?.cycles ?: persistedCycles }

    override fun isPowered(): Boolean = onServerThread { powered }

    /**
     * Flip the powered flag, with the side effect of closing the live VM if
     * we're powering off. Returns the new powered state. Block's use handler
     * still calls this; scripts reach it via `peripheral.find("controlplane"):togglePower()`.
     */
    override fun togglePower(): Boolean = onServerThread {
        powered = !powered
        if (!powered) {
            // Drain any final UART output before closing so the status line
            // we'll print to chat reflects the last guest activity.
            persistSnapshotIfRunning()
            vm?.close()
            vm = null
        }
        setChanged()
        powered
    }

    override fun consoleTail(maxLines: Int): List<String> = onServerThread {
        vm?.console?.recentLines(maxLines.coerceAtLeast(0)) ?: emptyList()
    }

    override fun consoleClear() {
        onServerThread { vm?.console?.clear() }
    }

    override fun diskCapacity(): Long = onServerThread { vm?.diskCapacity ?: 0L }

    override fun describe(): String = onServerThread {
        val powerLabel = if (powered) "ON" else "OFF"
        val v = vm ?: return@onServerThread "ControlPlane[$powerLabel, not booted]"
        "ControlPlane[$powerLabel, ${v.describe()}]"
    }

    /**
     * Marshal [body] onto the server thread. Reads + writes against [vm] and
     * [powered] need to be coherent with the tick loop, so worker-thread script
     * calls submit a Supplier and block up to 5s. Same pattern as MonitorBlockEntity.
     */
    private fun <T> onServerThread(body: () -> T): T {
        val server = level?.server
        if (server == null || server.isSameThread) return body()
        return server.submit(Supplier(body)).get(5, TimeUnit.SECONDS)
    }

    /** Called every server tick by [ControlPlaneBlock.getTicker]. */
    fun tick() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        if (!powered) return
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
        val diskFile = resolveDiskFile(serverLevel)
        val identity = vmIdentity(serverLevel)
        val store = snapshotStore(serverLevel)
        val snapshot = runCatching { store.read(identity) }.getOrElse { ex ->
            // Stale or partial snapshot file shouldn't brick the BE — log and
            // fall through to a fresh boot. The bad file stays on disk so an
            // op can investigate; admins can wipe it manually.
            OpenComputers2.LOGGER.warn(
                "control plane @ {} snapshot read failed ({}); booting fresh",
                blockPos,
                ex.message,
            )
            null
        }
        val v = try {
            ControlPlaneVm(
                diskFile = diskFile,
                diskSizeBytes = ControlPlaneDisk.DEFAULT_SIZE_BYTES,
                snapshot = snapshot,
            )
        } catch (ex: Exception) {
            // Restore failed (corrupt snapshot, format drift across mod versions).
            // Same policy as the read failure: log, leave the bad file alone for
            // forensics, fall through to a fresh boot.
            if (snapshot != null) {
                OpenComputers2.LOGGER.warn(
                    "control plane @ {} snapshot restore failed ({}); booting fresh",
                    blockPos,
                    ex.message,
                )
                ControlPlaneVm(
                    diskFile = diskFile,
                    diskSizeBytes = ControlPlaneDisk.DEFAULT_SIZE_BYTES,
                )
            } else {
                throw ex
            }
        }
        vm = v
        val origin = if (snapshot != null) "restored" else "fresh"
        OpenComputers2.LOGGER.info(
            "control plane @ {} VM booted ({}): {} (disk={})",
            blockPos,
            origin,
            v.describe(),
            diskFile.toPath(),
        )
        return v
    }

    /**
     * If a VM is running, capture its state into the snapshot store. Called
     * from [setRemoved] (chunk unload / block break) and [togglePower] (off).
     * Best-effort — a failed snapshot must not throw out of those paths,
     * because an unloading chunk can't recover from an exception there.
     */
    private fun persistSnapshotIfRunning() {
        val v = vm ?: return
        val serverLevel = level as? ServerLevel ?: return
        try {
            val bytes = v.snapshot()
            val identity = vmIdentity(serverLevel)
            snapshotStore(serverLevel).write(identity, bytes)
        } catch (ex: Exception) {
            OpenComputers2.LOGGER.warn(
                "control plane @ {} snapshot write failed: {}",
                blockPos,
                ex.message,
            )
        }
    }

    private fun snapshotStore(serverLevel: ServerLevel): ControlPlaneSnapshotStore {
        val root = serverLevel.server.getWorldPath(LevelResource(OpenComputers2.ID))
        return ControlPlaneSnapshotStore(root.resolve("vm-snapshots").toFile())
    }

    /**
     * Identity used as the snapshot filename — owner UUID if registered,
     * otherwise the per-BE fallback UUID. Mirrors [resolveDiskFile]'s naming
     * so a Control Plane's disk image and snapshot share the same prefix.
     * Keep these two in sync: an owner-keyed disk paired with a be-keyed
     * snapshot would silently desync state from RAM.
     */
    private fun vmIdentity(serverLevel: ServerLevel): String {
        val owner = lookupOwner(serverLevel)
        return if (owner != null) {
            "owner-$owner"
        } else {
            val fallback = vmUuid ?: UUID.randomUUID().also {
                vmUuid = it
                setChanged()
            }
            "be-$fallback"
        }
    }

    /**
     * Disk image lives under `<world>/oc2/vm-disks/`. Filename anchors to:
     *   1. **Owner UUID** (preferred) — the player who placed the Control Plane,
     *      looked up via [ControlPlaneRegistry] from this block's location. This
     *      means the disk follows the player: break + replace at the same coords
     *      reuses the same image, and a single player only ever has one disk.
     *   2. **Per-BE UUID** (fallback) — for orphan blocks placed without an
     *      owner (e.g. /setblock, dispenser, pre-registry worlds). Generated on
     *      first tick, NBT-persisted at "vm_uuid".
     *
     * The registry is the single source of truth for ownership; the per-BE
     * UUID exists only so non-owned VMs still get a stable disk path.
     */
    private fun resolveDiskFile(serverLevel: ServerLevel): File {
        val root = serverLevel.server.getWorldPath(LevelResource(OpenComputers2.ID))
        val dir = root.resolve("vm-disks").toFile()
        dir.mkdirs()
        // Share identity with the snapshot store so disk + snapshot stay paired.
        return File(dir, "${vmIdentity(serverLevel)}.img")
    }

    private fun lookupOwner(serverLevel: ServerLevel): UUID? {
        val registry = runCatching { OC2ServerContext.get(serverLevel.server).controlPlanes }.getOrNull() ?: return null
        val location = ControlPlaneRegistry.Location(
            dimension = serverLevel.dimension().location().toString(),
            x = blockPos.x, y = blockPos.y, z = blockPos.z,
        )
        return registry.ownerAt(location)
    }

    override fun setRemoved() {
        // BE is being unloaded (chunk unload) or destroyed (block break).
        // Snapshot before close so chunk reload restores RAM state — without
        // this, every chunk unload would silently reset the running guest.
        // Disk file persists either way; gardening orphans is a separate
        // (manual) gesture today.
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        if (powered) persistSnapshotIfRunning()
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
        tag.putBoolean(NBT_POWERED, powered)
        tag.putString(NBT_CHANNEL, channelId)
        vmUuid?.let { tag.putUUID(NBT_VM_UUID, it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CYCLES)) {
            persistedCycles = tag.getLong(NBT_CYCLES)
        }
        // Default-true preserves the prior behavior for blocks placed before
        // power state existed — they keep ticking after the upgrade.
        powered = if (tag.contains(NBT_POWERED)) tag.getBoolean(NBT_POWERED) else true
        if (tag.contains(NBT_CHANNEL)) {
            channelId = tag.getString(NBT_CHANNEL).ifBlank { DEFAULT_CHANNEL }
        }
        if (tag.hasUUID(NBT_VM_UUID)) {
            vmUuid = tag.getUUID(NBT_VM_UUID)
        }
    }

    companion object {
        const val REGISTRANT_KIND: String = "controlplane"
        const val DEFAULT_CHANNEL: String = "default"
        const val MAX_CHANNEL_LENGTH: Int = 32

        private const val NBT_CYCLES = "cycles"
        private const val NBT_VM_UUID = "vm_uuid"
        private const val NBT_POWERED = "powered"
        private const val NBT_CHANNEL = "channelId"

        /**
         * Persist on cycle counts where the low N bits are zero, so we flush every
         * ~16 ticks at the default 1M cycles/tick budget. Keeps NBT churn off the
         * hot path while still surviving abrupt shutdown with reasonable fidelity.
         */
        private const val PERSIST_INTERVAL_MASK: Long = (1L shl 24) - 1
    }
}
