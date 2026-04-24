package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.parts.PeripheralResolver
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.diag.ServerLoadedComputers
import com.brewingcoder.oc2.platform.network.NetworkAccess
import com.brewingcoder.oc2.platform.network.NetworkInboxes
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
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
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

    /**
     * Power state. Newly placed computers are OFF — the screen shows a "press
     * power" prompt and shell commands are rejected. Power state survives
     * save/load (the player can shut down a script-running computer, walk away,
     * come back later, and it'll still be off).
     */
    var powered: Boolean = false
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

    /**
     * Ring buffer of recent script output lines, kept server-side for
     * diagnostics. Read by [ServerLoadedComputers.consoleOf] (oc2-debug uses
     * this to verify scripts ran without opening every computer's GUI).
     * Bounded to avoid unbounded growth on long-running scripts.
     */
    private val recentOutput: ArrayDeque<String> = ArrayDeque()

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
            registerWithDiagnostics()
        }
    }

    override fun setRemoved() {
        if (registryShouldTrack) {
            ChannelRegistry.unregister(this)
            if (computerId != ID_UNASSIGNED) ServerLoadedComputers.unregister(computerId)
        }
        // Kill EVERY script (foreground + every background) so they can't keep
        // calling into external mods after the world is being torn down — that
        // surfaces as ugly NPE log spam from those mods. Foreground-only kill
        // (which the power-off path uses) isn't enough here.
        scriptRunner.killAll()
        super.setRemoved()
    }

    private fun registerWithDiagnostics() {
        val dim = level?.dimension()?.location()?.toString() ?: "?"
        ServerLoadedComputers.register(
            ServerLoadedComputers.ComputerInfo(
                id = computerId,
                dimension = dim,
                x = blockPos.x, y = blockPos.y, z = blockPos.z,
                channelId = channelId,
            ),
            outputProvider = { synchronized(recentOutput) { recentOutput.toList() } },
            writeFile = { path, content -> writeFileToMount(path, content) },
            executeCommand = { cmd -> executeShellCommand(cmd, originator = null).lines },
        )
    }

    /**
     * Write [content] (UTF-8) to [path] in this computer's mount, creating
     * parent directories as needed. Used by oc2-debug's `write_computer_file`
     * — saves the tester from `cp`-ing into the world save directory.
     */
    private fun writeFileToMount(path: String, content: String) {
        val mount = rootMount()
        // Ensure parent dirs exist; "" path means root, makeDirectory("") would no-op.
        val lastSlash = path.lastIndexOf('/')
        if (lastSlash > 0) {
            mount.makeDirectory(path.substring(0, lastSlash))
        }
        mount.openForWrite(path).use { ch ->
            val buf = java.nio.ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
            while (buf.hasRemaining()) {
                val n = ch.write(buf)
                if (n <= 0) error("mount refused write — out of capacity?")
            }
        }
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
        // Drain EVERY handle's output queue each tick. Foreground's lines route
        // to the terminal; background's lines are drained-and-dropped (the
        // tail buffer captures them for `tail <pid>`). When a bg script crashes,
        // the error message gets surfaced in the foreground terminal so the
        // developer sees the failure without needing to know about `tail`.
        val foreground = scriptRunner.current()
        val crashLines = mutableListOf<String>()
        for (h in scriptRunner.all()) {
            val items = h.drainOutput()
            if (h !== foreground) {
                // bg: discard items, but if it just finished with a real error,
                // bubble a banner into the foreground stream once.
                if (h.isDone() && bgAnnounced.add(h.pid)) {
                    val result = h.result() ?: continue
                    if (!result.ok && result.errorMessage != ScriptRunHandle.KILLED) {
                        val msg = result.errorMessage ?: "unknown"
                        crashLines.add("[bg pid=${h.pid} ${h.chunkName}] crashed: $msg")
                    }
                }
                continue
            }
            // Foreground draining (unchanged).
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
            if (h.isDone()) {
                val result = h.result()
                if (result != null && !result.ok) {
                    val msg = result.errorMessage ?: "unknown"
                    val banner = if (msg == ScriptRunHandle.KILLED) "[killed]" else "[script error] $msg"
                    lines.add(banner)
                }
            }
            if (lines.isNotEmpty() || clearFirst) {
                sendOutputToOriginator(lines, clearFirst)
                recordToRecent(lines, clearFirst)
            }
            if (h.isDone()) scriptOriginator = null
        }
        // Surface bg crashes to the foreground terminal so the user actually
        // sees them — analogous to a Java unhandled exception printing to stderr.
        if (crashLines.isNotEmpty()) {
            sendOutputToOriginator(crashLines, clearFirst = false)
            recordToRecent(crashLines, clearFirst = false)
        }
        // Sweep finished handles in one pass after draining.
        (scriptRunner as? BeScriptRunner)?.clearIfDone()
        // Cull announced-pid set against currently-known handles to avoid leaking.
        val livePids = scriptRunner.all().map { it.pid }.toSet()
        bgAnnounced.retainAll(livePids)
    }

    /** Pids of background scripts whose crash has already been announced. Prevents repeats. */
    private val bgAnnounced: MutableSet<Int> = mutableSetOf()

    private fun recordToRecent(lines: List<String>, clearFirst: Boolean) {
        synchronized(recentOutput) {
            if (clearFirst) recentOutput.clear()
            for (line in lines) {
                recentOutput.addLast(line)
                while (recentOutput.size > RECENT_OUTPUT_CAP) recentOutput.removeFirst()
            }
        }
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
        val provider = OC2ServerContext.get(server).storageProvider
        val writable = provider.rootMountFor(ensureComputerId())
        // Overlay the shared ROM at /rom/ so scripts see library files in-JAR.
        val mount = com.brewingcoder.oc2.platform.storage.UnionMount(writable, provider.romMount())
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
        if (!powered) return ShellResult(listOf("(computer is off — press Power)"), false, 1)
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
            peripheralLister = { kind -> listPeripheralsOnChannel(kind) },
            networkAccess = networkAccess(),
            scriptRunner = scriptRunner,
        ).also { shellSession = it }
        return SHELL.execute(input, session)
    }

    /**
     * Event queue of the currently-running script, or null if none. Event
     * sources (monitor touches, network messages, etc.) fan out via this.
     */
    fun runningScriptEvents(): com.brewingcoder.oc2.platform.script.ScriptEventQueue? =
        scriptRunner.current()?.takeIf { !it.isDone() }?.eventQueue

    /**
     * Toggle power. Powering off kills any in-flight script and clears the
     * recent-output buffer (the next power-on starts with a clean terminal).
     * Powering on is a no-op beyond setting the flag — the shell becomes
     * available for input again.
     */
    fun setPowered(on: Boolean) {
        if (powered == on) return
        powered = on
        if (!on) {
            scriptRunner.kill()
            synchronized(recentOutput) { recentOutput.clear() }
            // Tell the open client screen to wipe its terminal too.
            sendOutputToOriginator(emptyList(), clearFirst = true)
        }
        OpenComputers2.LOGGER.info("computer @ {} powered {}", blockPos, if (on) "ON" else "OFF")
        setChanged()
        sync()
    }

    /**
     * Soft reset — kill the running script + wipe both server and client
     * terminal buffers. Power state stays unchanged. No-op when off.
     */
    fun reset() {
        if (!powered) return
        scriptRunner.kill()
        synchronized(recentOutput) { recentOutput.clear() }
        sendOutputToOriginator(listOf("[reset]"), clearFirst = true)
        OpenComputers2.LOGGER.info("computer @ {} reset", blockPos)
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
        return PeripheralResolver.resolve(match)
    }

    private fun listPeripheralsOnChannel(kind: String?): List<Peripheral> =
        ChannelRegistry.listOnChannel(channelId, kind).mapNotNull { PeripheralResolver.resolve(it) }

    /**
     * Broadcast [message] to every other [ComputerBlockEntity] on [channel] (or
     * the host's own channel if null). Self-exclusion + unassigned-id filtering
     * happens here so script callers don't need to know the rules.
     */
    fun networkSend(message: String, channel: String? = null) {
        val targetChannel = channel ?: channelId
        val senderId = ensureComputerId()
        val msg = NetworkInboxes.Message(from = senderId, body = message)
        for (r in ChannelRegistry.listOnChannel(targetChannel, kind = "computer")) {
            val cbe = r as? ComputerBlockEntity ?: continue
            if (cbe === this) continue
            if (cbe.computerId == ID_UNASSIGNED) continue
            NetworkInboxes.deliver(cbe.computerId, msg)
        }
    }

    fun networkRecv(): NetworkInboxes.Message? = NetworkInboxes.pop(ensureComputerId())
    fun networkPeek(): NetworkInboxes.Message? = NetworkInboxes.peek(ensureComputerId())
    fun networkSize(): Int = NetworkInboxes.size(ensureComputerId())

    /** [NetworkAccess] view backed by this BE — passed into the script env. */
    private fun networkAccess(): NetworkAccess = object : NetworkAccess {
        override fun id(): Int = ensureComputerId()
        override fun send(message: String, channel: String?) = networkSend(message, channel)
        override fun recv(): NetworkInboxes.Message? = networkRecv()
        override fun peek(): NetworkInboxes.Message? = networkPeek()
        override fun size(): Int = networkSize()
    }

    /** Reassign the channel; updates the registry. */
    fun setChannel(newChannel: String) {
        if (newChannel == channelId) return
        if (registryShouldTrack) ChannelRegistry.unregister(this)
        channelId = newChannel
        if (registryShouldTrack) {
            ChannelRegistry.register(this)
            if (computerId != ID_UNASSIGNED) registerWithDiagnostics()
        }
        setChanged()  // marks chunk dirty so NBT gets persisted
        sync()        // pushes the new channel to the client BE so the GUI re-opens with it
    }

    // ---------- client sync ----------

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        saveAdditional(tag, registries)
        return tag
    }

    override fun handleUpdateTag(tag: CompoundTag, registries: HolderLookup.Provider) {
        loadAdditional(tag, registries)
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener>? =
        ClientboundBlockEntityDataPacket.create(this)

    /**
     * Push the latest BE state to clients tracking this chunk. flag 2 means
     * "clients only, no neighbor cascade" — same lesson as Adapter/Monitor
     * (avoid save-time cascades that hung the server in earlier work).
     */
    private fun sync() {
        val lvl = level ?: return
        if (lvl.isClientSide) return
        lvl.sendBlockUpdated(blockPos, blockState, blockState, 2)
    }

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
        tag.putBoolean(NBT_POWERED, powered)
        if (computerId != ID_UNASSIGNED) tag.putInt(NBT_COMPUTER_ID, computerId)
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CHANNEL)) {
            channelId = tag.getString(NBT_CHANNEL)
        }
        if (tag.contains(NBT_POWERED)) {
            powered = tag.getBoolean(NBT_POWERED)
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
        /** Lines retained in the diagnostics ring buffer per computer. */
        const val RECENT_OUTPUT_CAP: Int = 200
        private const val NBT_CHANNEL = "channelId"
        private const val NBT_COMPUTER_ID = "computerId"
        private const val NBT_POWERED = "powered"

        /** Stateless command registry, shared across every computer in the world. */
        private val SHELL: Shell = DefaultCommands.build()
    }
}
