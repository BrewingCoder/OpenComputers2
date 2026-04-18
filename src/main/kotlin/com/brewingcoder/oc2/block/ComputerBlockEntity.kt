package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.network.TerminalOutputPayload
import com.brewingcoder.oc2.platform.os.Shell
import com.brewingcoder.oc2.platform.os.ShellMetadata
import com.brewingcoder.oc2.platform.os.ShellResult
import com.brewingcoder.oc2.platform.os.ShellSession
import com.brewingcoder.oc2.platform.os.commands.DefaultCommands
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.ScriptRunHandle
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID
import com.brewingcoder.oc2.platform.storage.WritableMount
import com.brewingcoder.oc2.storage.OC2ServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState

/**
 * Persistent state for a single Computer block.
 *
 * v0 holds only the wifi channel ID. As R1 lands this will gain:
 *   - script bound to this computer
 *   - VM kind (lua | js)
 *   - filesystem handle
 *   - bound screen reference (if any)
 *   - power state (on/off)
 *
 * Lifecycle (server-side only — client BE never registers, see [registryShouldTrack]):
 *   - onLoad()      → registered with ChannelRegistry
 *   - setRemoved()  → unregistered from ChannelRegistry
 *   - tick()        → called each server tick (heartbeat / scheduler tick later)
 *
 * Implements [ChannelRegistrant] (Rule B in docs/11) so the registry never
 * has to know about BlockEntity.
 */
class ComputerBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.COMPUTER.get(), pos, state),
    ChannelRegistrant {

    /** Wifi channel this Computer publishes on. Matches with adapters of the same channel. */
    override var channelId: String = DEFAULT_CHANNEL
        private set

    /** Block position translated to platform-layer [Position] — exposed via [ChannelRegistrant]. */
    override val location: Position
        get() = Position(blockPos.x, blockPos.y, blockPos.z)

    /**
     * Persistent identity used to locate this computer's filesystem on disk
     * (`<world>/oc2/computer/<id>/`). Assigned lazily on first server-side
     * filesystem access via [ensureComputerId]; persisted to NBT once assigned
     * so the same bytes follow this BE across save/load cycles.
     *
     * `-1` means "not yet assigned." A freshly placed computer carries -1 until
     * something (the VM, a debug command, etc.) actually needs storage.
     */
    var computerId: Int = ID_UNASSIGNED
        private set

    /** Memoized per-computer root mount; reset to null on save/load and on removal. */
    private var rootMountCache: WritableMount? = null

    /** Lazy shell session bound to this computer's mount + live metadata. */
    private var shellSession: ShellSession? = null

    /** Per-BE async script runner; created lazily with the session. */
    private val scriptRunner: BeScriptRunner = BeScriptRunner()

    /** Player UUID who initiated the currently-tracked script (for output routing). */
    private var scriptOriginator: UUID? = null

    private var tickCounter: Int = 0

    /** Server-side BEs are the source of truth; client BEs are visual only. */
    private val registryShouldTrack: Boolean
        get() = level?.isClientSide == false

    override fun onLoad() {
        super.onLoad()
        if (registryShouldTrack) {
            ChannelRegistry.register(this)
            // Eager storage provisioning: assign an id and touch the root mount
            // on first server-side load so the disk directory exists from the
            // moment the computer is placed (not lazily on first shell command).
            // Reload of an existing world is ~free — id is already in NBT and
            // Files.createDirectories is idempotent.
            ensureComputerId()
            rootMount()
        }
    }

    override fun setRemoved() {
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        super.setRemoved()
    }

    /** Called every server tick — wired up by ComputerBlock.getTicker. */
    fun tick() {
        tickCounter++
        if (tickCounter % HEARTBEAT_TICKS == 0) {
            OpenComputers2.LOGGER.debug(
                "computer @ {} alive on channel '{}' ({} ticks)",
                blockPos, channelId, tickCounter,
            )
        }
        // Drain script output every tick, push to the originating player.
        // Live updates work — the player sees print() output as it happens
        // rather than batched at script end.
        drainScriptOutput()
    }

    private fun drainScriptOutput() {
        val handle = scriptRunner.current() ?: return
        val items = handle.drainOutput()
        if (items.isEmpty() && !handle.isDone()) return

        val lines = mutableListOf<String>()
        var clearFirst = false
        for (item in items) {
            when (item) {
                is ScriptRunHandle.OutputItem.Clear -> {
                    clearFirst = true
                    lines.clear()
                }
                is ScriptRunHandle.OutputItem.Line -> lines.add(item.text)
            }
        }
        if (handle.isDone()) {
            val result = handle.result()
            if (result != null && !result.ok) {
                val msg = result.errorMessage ?: "unknown"
                val banner = if (msg == ScriptRunHandle.KILLED) "[killed]" else "[script error] $msg"
                lines.add(banner)
            }
            scriptRunner.clearIfDone()
        }
        if (lines.isNotEmpty() || clearFirst) {
            sendOutputToOriginator(lines, clearFirst)
        }
        if (handle.isDone()) scriptOriginator = null
    }

    private fun sendOutputToOriginator(lines: List<String>, clearFirst: Boolean) {
        val originatorId = scriptOriginator ?: return
        val server = level?.server ?: return
        val player = server.playerList.getPlayer(originatorId) as? ServerPlayer ?: return
        PacketDistributor.sendToPlayer(player, TerminalOutputPayload(blockPos, lines, clearFirst))
    }

    /**
     * Assigns a [computerId] from the world-scoped ID assigner if this BE doesn't
     * already have one. Idempotent. Server-side only.
     *
     * Returns the (possibly newly assigned) ID. Assignment marks the chunk dirty
     * so the new ID gets persisted on the next save.
     */
    fun ensureComputerId(): Int {
        if (computerId != ID_UNASSIGNED) return computerId
        val lvl = level
            ?: error("ensureComputerId() called off-level")
        val server = lvl.server
            ?: error("ensureComputerId() called off-server (level=$lvl)")
        // Crash-safe: assignComputerIdAt is idempotent per (dim, pos), so even if
        // this BE's NBT got lost in a crash, the next call here returns the same id
        // we originally assigned to this block — its files survive.
        val dim = lvl.dimension().location().toString()
        computerId = OC2ServerContext.get(server).assignComputerIdAt(dim, blockPos)
        OpenComputers2.LOGGER.info("computer @ {} resolved id {}", blockPos, computerId)
        setChanged()
        return computerId
    }

    /**
     * Lazily resolves the per-computer writable filesystem root. First call
     * triggers [ensureComputerId] if needed. Server-side only.
     *
     * Returned mount is cached for the life of this BE instance — match the
     * single-instance-per-folder requirement that capacity tracking depends on.
     */
    fun rootMount(): WritableMount {
        rootMountCache?.let { return it }
        val server = level?.server
            ?: error("rootMount() called off-server (level=$level)")
        val mount = OC2ServerContext.get(server).storageProvider.rootMountFor(ensureComputerId())
        rootMountCache = mount
        return mount
    }

    /**
     * Run one shell command on behalf of a player. Server-side only — called
     * from the [com.brewingcoder.oc2.network.RunCommandPayload] handler after
     * its anti-grief checks pass.
     *
     * Output is returned synchronously; the caller (the payload handler) ships
     * it back as a [com.brewingcoder.oc2.network.TerminalOutputPayload].
     */
    fun executeShellCommand(input: String, originator: UUID? = null): ShellResult {
        // Track the originator so any newly-spawned script's output gets routed
        // back to them. Only update if the previous originator is gone — keeps
        // a still-running script's output flowing to its initiator if a different
        // player peeks via right-click.
        if (originator != null && scriptRunner.current()?.isDone() != false) {
            scriptOriginator = originator
        }
        val session = shellSession ?: ShellSession(
            mount = rootMount(),
            metadataProvider = {
                ShellMetadata(
                    computerId = ensureComputerId(),
                    channelId = channelId,
                    location = "(${blockPos.x}, ${blockPos.y}, ${blockPos.z})",
                )
            },
            peripheralFinder = { kind -> findPeripheralOnChannel(kind) },
            scriptRunner = scriptRunner,
        ).also { shellSession = it }
        return SHELL.execute(input, session)
    }

    /**
     * `peripheral.find(kind)` lookup: find a registrant on this computer's
     * channel matching [kind]. Returns null if no match (e.g. no monitor on
     * the same wifi channel as this computer).
     *
     * Implementation: relies on [ChannelRegistry] which is the single source
     * of truth for "what's on channel X". Devices that implement [Peripheral]
     * (currently only [com.brewingcoder.oc2.block.MonitorBlockEntity]) are
     * findable; ones that don't return null even on a match.
     */
    private fun findPeripheralOnChannel(kind: String): Peripheral? {
        val match = ChannelRegistry.findOnChannel(channelId, kind) ?: return null
        return match as? Peripheral
    }

    /** Reassign the channel; updates the registry. */
    fun setChannel(newChannel: String) {
        if (newChannel == channelId) return
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        channelId = newChannel
        if (registryShouldTrack) ChannelRegistry.register(this)
        setChanged()  // marks chunk dirty so NBT gets persisted
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
        if (computerId != ID_UNASSIGNED) tag.putInt(NBT_COMPUTER_ID, computerId)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CHANNEL)) {
            channelId = tag.getString(NBT_CHANNEL)
        }
        if (tag.contains(NBT_COMPUTER_ID)) {
            computerId = tag.getInt(NBT_COMPUTER_ID)
            // invalidate any cached mount so we re-resolve via the (possibly new) provider
            rootMountCache = null
        }
    }

    companion object {
        const val DEFAULT_CHANNEL = "default"
        const val HEARTBEAT_TICKS = 100  // 5s at 20 TPS
        const val ID_UNASSIGNED: Int = -1
        private const val NBT_CHANNEL = "channelId"
        private const val NBT_COMPUTER_ID = "computerId"

        /** Stateless command registry, shared across every computer in the world. */
        private val SHELL: Shell = DefaultCommands.build()
    }
}
