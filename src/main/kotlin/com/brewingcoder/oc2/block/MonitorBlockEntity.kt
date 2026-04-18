package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.platform.ChannelRegistrant
import com.brewingcoder.oc2.platform.ChannelRegistry
import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.monitor.MonitorMerge
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral.TouchEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Monitor block entity. Owns:
 *   - wifi-channel registration (so scripts on the same channel can find it)
 *   - group state — am I a master or a slave, what are the group bounds
 *   - a text buffer (master only) — the displayed character grid
 *   - NBT serialization of all the above
 *   - server→client sync via [getUpdatePacket]
 *
 * Group lifecycle:
 *   - On placement / on neighbor change → [requestGroupReevaluation]
 *   - On removal → vanilla `neighborChanged` propagates to neighbors, each of
 *     them re-evaluates and may shrink/split their group
 *
 * Master/slave:
 *   - Master holds the entire group's text buffer and renders the full screen
 *     surface (its [com.brewingcoder.oc2.client.screen.MonitorRenderer] draws
 *     a wide quad spanning all member block faces)
 *   - Slaves render nothing — they're spatial reservations only
 *
 * v0 simplifications:
 *   - Wall-mount facings only (N/S/E/W); ceiling/floor monitors land in M2
 *   - Monochrome text — no per-cell color yet (M2)
 *   - Each block face = a [COLS_PER_BLOCK]×[ROWS_PER_BLOCK] character grid
 */
class MonitorBlockEntity(pos: BlockPos, state: BlockState) :
    BlockEntity(ModBlockEntities.MONITOR.get(), pos, state),
    ChannelRegistrant,
    MonitorPeripheral {

    override var channelId: String = DEFAULT_CHANNEL
        private set

    override val location: Position
        get() = Position(blockPos.x, blockPos.y, blockPos.z)

    /** Resolve the diamond: ChannelRegistrant defaults to "computer", MonitorPeripheral to "monitor". */
    override val kind: String get() = "monitor"

    /** True when this BE is the master of its group (or a 1×1 standalone). */
    var isMaster: Boolean = true
        private set

    /** Position of the group's master. Equal to [blockPos] when [isMaster]. */
    var masterPos: BlockPos = pos
        private set

    /** Group dimensions in monitor-blocks. */
    var groupBlocksWide: Int = 1
        private set
    var groupBlocksTall: Int = 1
        private set

    /** Character buffer — master-only. `null` on slaves. Indexed [row][col]. */
    private var buffer: Array<CharArray>? = null

    /**
     * Foreground color per cell (ARGB int). Flat array indexed by row*cols+col.
     * Same lifecycle as [buffer] — null on slaves, allocated on master with the buffer.
     * Default cell color = [DEFAULT_FG]; only differs when scripts call setForegroundColor.
     */
    private var fgColors: IntArray? = null

    /**
     * Background color per cell. `0` (fully transparent) means "no bg fill" —
     * the underlying monitor panel texture shows through. The renderer skips
     * bg quads for transparent cells, saving vertices on a mostly-empty screen.
     */
    private var bgColors: IntArray? = null

    /** Cursor position used by `write` — master-only. */
    private var cursorRow: Int = 0
    private var cursorCol: Int = 0

    /** Current colors that subsequent [doWriteText] calls stamp into the buffer. */
    private var currentFg: Int = DEFAULT_FG
    private var currentBg: Int = DEFAULT_BG

    /** ANSI escape parser state — tracks position inside CSI sequences across writes. */
    private var ansiState: AnsiState = AnsiState.NORMAL
    private val ansiParams: StringBuilder = StringBuilder()

    private enum class AnsiState { NORMAL, AFTER_ESC, IN_CSI }

    /** Pending touch events drained by `pollTouches()`. Master only. */
    private val touchQueue: ArrayDeque<TouchEvent> = ArrayDeque()

    private val isServer: Boolean
        get() = level?.isClientSide == false

    private val totalCols: Int get() = groupBlocksWide * COLS_PER_BLOCK
    private val totalRows: Int get() = groupBlocksTall * ROWS_PER_BLOCK

    // ---------- lifecycle ----------

    override fun onLoad() {
        super.onLoad()
        if (isServer) {
            ChannelRegistry.register(this)
            requestGroupReevaluation()
        }
    }

    override fun setRemoved() {
        if (isServer) {
            OpenComputers2.LOGGER.debug("monitor setRemoved @ {} (isMaster={}, group {}x{})",
                blockPos, isMaster, groupBlocksWide, groupBlocksTall)
            ChannelRegistry.unregister(this)
        }
        // Don't propagate group re-eval here. Vanilla fires `neighborChanged` on
        // the IMMEDIATE neighbors of the removed block; that's enough for the
        // adjacent monitors to re-eval their groups. Distant blocks (e.g. far
        // corner of a 4×4) may keep stale state until the next chunk reload —
        // acceptable tradeoff to avoid the cascade-during-unload that hung world
        // saves (observed: 9-BE wall × cross-chunk propagation = multi-minute hang).
        super.setRemoved()
    }

    /**
     * Recompute this monitor's group based on the current world state. Called
     * on placement and whenever a neighbor changes.
     */
    fun requestGroupReevaluation() {
        if (!isServer) return
        val lvl = level ?: return
        val state = blockState
        val facing = state.getValue(MonitorBlock.FACING).toMergeFacing() ?: return

        val seedPos = Position(blockPos.x, blockPos.y, blockPos.z)
        val group = MonitorMerge.computeGroup(seedPos, facing) { p ->
            val bp = BlockPos(p.x, p.y, p.z)
            val s = lvl.getBlockState(bp)
            s.block === ModBlocks.MONITOR && s.getValue(MonitorBlock.FACING).toMergeFacing() == facing
        }
        OpenComputers2.LOGGER.debug("monitor reevaluate @ {} → group {}x{} master={}",
            blockPos, group.width, group.height, group.masterPos)
        applyGroup(group)
    }

    /** Update local group state from a freshly-computed [MonitorMerge.MonitorGroup]. */
    private fun applyGroup(group: MonitorMerge.MonitorGroup) {
        val newMasterPos = BlockPos(group.masterPos.x, group.masterPos.y, group.masterPos.z)
        val newIsMaster = blockPos == newMasterPos

        val sizeChanged = (groupBlocksWide != group.width) || (groupBlocksTall != group.height)
        val masterChanged = (masterPos != newMasterPos)

        masterPos = newMasterPos
        groupBlocksWide = group.width
        groupBlocksTall = group.height
        isMaster = newIsMaster

        if (newIsMaster) {
            // Resize / (re)allocate the buffer + color arrays if any of:
            //   - the buffer doesn't exist (fresh install)
            //   - the group dimensions changed (re-merge)
            //   - colors are missing (NBT loaded from a pre-color save — upgrade path)
            // Without the colors-missing case, a monitor placed before the per-cell-color
            // change had `buffer != null` but `fgColors == null`, causing doWriteText to
            // silently return early.
            if (buffer == null || sizeChanged || fgColors == null || bgColors == null) {
                val newBuffer = Array(totalRows) { CharArray(totalCols) { ' ' } }
                val newFg = IntArray(totalRows * totalCols) { DEFAULT_FG }
                val newBg = IntArray(totalRows * totalCols) { DEFAULT_BG }
                val old = buffer
                val oldFg = fgColors
                val oldBg = bgColors
                if (old != null) {
                    val copyRows = minOf(old.size, newBuffer.size)
                    val oldCols = old[0].size
                    for (r in 0 until copyRows) {
                        val copyCols = minOf(oldCols, newBuffer[r].size)
                        System.arraycopy(old[r], 0, newBuffer[r], 0, copyCols)
                        if (oldFg != null) {
                            for (c in 0 until copyCols) {
                                newFg[r * totalCols + c] = oldFg[r * oldCols + c]
                                if (oldBg != null) newBg[r * totalCols + c] = oldBg[r * oldCols + c]
                            }
                        }
                    }
                }
                buffer = newBuffer
                fgColors = newFg
                bgColors = newBg
                cursorRow = cursorRow.coerceAtMost(totalRows - 1)
                cursorCol = cursorCol.coerceAtMost(totalCols - 1)
            }
        } else {
            buffer = null
            fgColors = null
            bgColors = null
            cursorRow = 0
            cursorCol = 0
        }

        if (sizeChanged || masterChanged) {
            OpenComputers2.LOGGER.info("monitor @ {} state change: size {}x{} → {}x{}, master {} → {}",
                blockPos,
                if (sizeChanged) "$groupBlocksWide" else "${group.width}",  // already updated above
                if (sizeChanged) "$groupBlocksTall" else "${group.height}",
                group.width, group.height,
                if (masterChanged) "?" else newMasterPos, newMasterPos,
            )
            setChanged()
            sync()
            // Don't cascade — see [setRemoved] comment. Each affected block re-evals
            // via its own `neighborChanged` when adjacent state changes.
        }
    }

    // ---------- script-facing API ----------

    // ---------- MonitorPeripheral implementation ----------
    // Wraps the master's buffer so scripts on any monitor in the group see one surface.
    //
    // ALL methods marshal to the server thread via [onServerThread]. Required because:
    //   - Scripts now run on a worker thread (the BeScriptRunner)
    //   - Methods touch level.sendBlockUpdated, which isn't thread-safe and silently
    //     fails when called off-thread (observed: m.write from worker = no client
    //     update → buttons don't appear on the monitor)
    //   - Even read-only methods need consistent visibility against tick-thread mutations

    override fun write(text: String) = onServerThread {
        forMaster { it.doWriteText(text) }
        Unit
    }

    override fun setCursorPos(col: Int, row: Int) = onServerThread {
        forMaster { master ->
            master.cursorCol = col.coerceIn(0, master.totalCols - 1)
            master.cursorRow = row.coerceIn(0, master.totalRows - 1)
        }
        Unit
    }

    override fun clear() = onServerThread {
        forMaster { master ->
            val buf = master.buffer ?: return@forMaster
            for (row in buf) row.fill(' ')
            master.fgColors?.fill(DEFAULT_FG)
            master.bgColors?.fill(DEFAULT_BG)
            master.cursorRow = 0
            master.cursorCol = 0
            master.setChanged()
            master.sync()
        }
        Unit
    }

    override fun getSize(): Pair<Int, Int> = onServerThread {
        forMaster { master -> master.totalCols to master.totalRows } ?: (0 to 0)
    }

    override fun getCursorPos(): Pair<Int, Int> = onServerThread {
        forMaster { master -> master.cursorCol to master.cursorRow } ?: (0 to 0)
    }

    override fun setForegroundColor(color: Int) = onServerThread {
        forMaster { master -> master.currentFg = color }
        Unit
    }

    override fun setBackgroundColor(color: Int) = onServerThread {
        forMaster { master -> master.currentBg = color }
        Unit
    }

    override fun pollTouches(): List<TouchEvent> = onServerThread {
        forMaster { master ->
            val drained = master.touchQueue.toList()
            master.touchQueue.clear()
            drained
        } ?: emptyList()
    }

    /**
     * Run [body] on the server thread, blocking the caller until it completes.
     * If we're already on the server thread, runs inline. If level/server isn't
     * available (test), runs inline (tests use FakeMonitor; this path is for
     * unusual cases like the BE being unloaded).
     *
     * 5-second timeout — if the server thread is wedged, fail loudly rather than
     * deadlocking the worker forever.
     */
    private fun <T> onServerThread(body: () -> T): T {
        val server = level?.server
        if (server == null || server.isSameThread) return body()
        return server.submit(Supplier(body)).get(5, TimeUnit.SECONDS)
    }

    /**
     * Server-only: enqueue a touch event on the group's master. Called by
     * [MonitorBlock.useWithoutItem] after computing (col, row) from the hit
     * position. Drops oldest if the queue exceeds [MonitorPeripheral.TOUCH_QUEUE_CAP].
     */
    fun enqueueTouch(col: Int, row: Int, playerName: String) {
        forMaster { master ->
            master.touchQueue.addLast(TouchEvent(col, row, playerName))
            while (master.touchQueue.size > MonitorPeripheral.TOUCH_QUEUE_CAP) {
                master.touchQueue.removeFirst()
            }
        }
    }

    /** Read-only snapshot of the buffer for the renderer. Empty list on slaves. */
    fun bufferSnapshot(): List<String> {
        val buf = buffer ?: return emptyList()
        return buf.map { String(it) }
    }

    /**
     * Full render-state snapshot — chars + colors. Each cell's fg/bg is in flat
     * row-major order: index = row * totalCols + col. Renderer uses this in one shot.
     * Returns null on slaves (the master holds the truth).
     */
    fun renderSnapshot(): RenderSnapshot? {
        val buf = buffer ?: return null
        val fg = fgColors ?: return null
        val bg = bgColors ?: return null
        return RenderSnapshot(
            rows = buf.map { String(it) },
            fg = fg.copyOf(),
            bg = bg.copyOf(),
            cols = totalCols,
        )
    }

    data class RenderSnapshot(
        val rows: List<String>,
        val fg: IntArray,
        val bg: IntArray,
        val cols: Int,
    )

    private fun doWriteText(text: String) {
        val buf = buffer ?: return
        val fg = fgColors ?: return
        val bg = bgColors ?: return
        var dirty = false
        for (ch in text) {
            when (ansiState) {
                AnsiState.NORMAL -> when (ch) {
                    '\u001B' -> ansiState = AnsiState.AFTER_ESC
                    '\n' -> { newline(); dirty = true }
                    '\r' -> { cursorCol = 0; dirty = true }
                    else -> {
                        if (isPrintable(ch)) {
                            if (cursorCol >= totalCols) newline()
                            val idx = cursorRow * totalCols + cursorCol
                            buf[cursorRow][cursorCol] = ch
                            fg[idx] = currentFg
                            bg[idx] = currentBg
                            cursorCol++
                            dirty = true
                        }
                    }
                }
                AnsiState.AFTER_ESC -> {
                    if (ch == '[') ansiState = AnsiState.IN_CSI
                    else ansiState = AnsiState.NORMAL  // unknown intro byte — drop
                }
                AnsiState.IN_CSI -> {
                    if (ch in '0'..'9' || ch == ';') {
                        ansiParams.append(ch)
                    } else {
                        // Final byte — dispatch on the letter, e.g. 'm' for SGR
                        if (handleCsi(ch, ansiParams.toString())) dirty = true
                        ansiParams.clear()
                        ansiState = AnsiState.NORMAL
                    }
                }
            }
        }
        if (dirty) {
            setChanged()
            sync()
        }
    }

    /** Dispatch a CSI sequence (`\033[<params><final>`). Returns true if anything visible changed. */
    private fun handleCsi(final: Char, params: String): Boolean {
        val args = if (params.isEmpty()) emptyList() else params.split(';').map { it.toIntOrNull() ?: 0 }
        return when (final) {
            'm' -> { applySgr(args); false }                          // SGR — color/style; no buffer change
            'J' -> handleEd(args.firstOrNull() ?: 0)                  // ED — erase display
            'H', 'f' -> {                                             // CUP — cursor position (1-indexed)
                cursorRow = ((args.getOrNull(0) ?: 1) - 1).coerceIn(0, totalRows - 1)
                cursorCol = ((args.getOrNull(1) ?: 1) - 1).coerceIn(0, totalCols - 1)
                false
            }
            'A' -> { cursorRow = (cursorRow - (args.firstOrNull() ?: 1)).coerceAtLeast(0); false }
            'B' -> { cursorRow = (cursorRow + (args.firstOrNull() ?: 1)).coerceAtMost(totalRows - 1); false }
            'C' -> { cursorCol = (cursorCol + (args.firstOrNull() ?: 1)).coerceAtMost(totalCols - 1); false }
            'D' -> { cursorCol = (cursorCol - (args.firstOrNull() ?: 1)).coerceAtLeast(0); false }
            else -> false  // unsupported — drop silently
        }
    }

    /** SGR — Select Graphic Rendition. Applies to subsequent writes. */
    private fun applySgr(args: List<Int>) {
        if (args.isEmpty()) {
            currentFg = DEFAULT_FG; currentBg = DEFAULT_BG; return
        }
        var i = 0
        while (i < args.size) {
            when (val n = args[i]) {
                0 -> { currentFg = DEFAULT_FG; currentBg = DEFAULT_BG }
                in 30..37 -> currentFg = ANSI_PALETTE[n - 30]            // standard fg
                in 90..97 -> currentFg = ANSI_PALETTE[n - 90 + 8]        // bright fg
                39 -> currentFg = DEFAULT_FG                              // default fg
                in 40..47 -> currentBg = ANSI_PALETTE[n - 40]            // standard bg
                in 100..107 -> currentBg = ANSI_PALETTE[n - 100 + 8]     // bright bg
                49 -> currentBg = DEFAULT_BG                              // default bg
                38 -> if (i + 4 < args.size && args[i + 1] == 2) {       // 24-bit fg: 38;2;r;g;b
                    currentFg = packArgb(args[i + 2], args[i + 3], args[i + 4]); i += 4
                }
                48 -> if (i + 4 < args.size && args[i + 1] == 2) {       // 24-bit bg
                    currentBg = packArgb(args[i + 2], args[i + 3], args[i + 4]); i += 4
                }
                // anything else: ignore (no italics/underline support yet)
            }
            i++
        }
    }

    /** ED — erase display. Mode 2 = full clear (the only one we support in v0). */
    private fun handleEd(mode: Int): Boolean {
        if (mode != 2) return false
        val buf = buffer ?: return false
        val fg = fgColors ?: return false
        val bg = bgColors ?: return false
        for (row in buf) row.fill(' ')
        fg.fill(DEFAULT_FG)
        bg.fill(DEFAULT_BG)
        cursorRow = 0; cursorCol = 0
        return true
    }

    private fun packArgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    /** Match the atlas charset baked by `tools/build_msdf_atlas.sh`. Out-of-range chars drop silently. */
    private fun isPrintable(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x20..0x7E) ||           // ASCII printable
               (c in 0xA0..0xFF) ||           // Latin-1 supplement
               (c in 0x2500..0x257F)          // Box-drawing characters
    }

    private fun newline() {
        cursorCol = 0
        cursorRow++
        if (cursorRow >= totalRows) {
            // Scroll: shift all rows up by one, blank the last row
            val buf = buffer ?: return
            for (r in 0 until totalRows - 1) buf[r] = buf[r + 1]
            buf[totalRows - 1] = CharArray(totalCols) { ' ' }
            cursorRow = totalRows - 1
        }
    }

    /** Run [body] on the group's master BE. Returns null if the master isn't loaded. */
    private inline fun <T> forMaster(body: (MonitorBlockEntity) -> T): T? {
        val target: MonitorBlockEntity = if (isMaster) this
            else (level?.getBlockEntity(masterPos) as? MonitorBlockEntity) ?: return null
        return body(target)
    }

    // ---------- NBT + sync ----------

    override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        tag.putString(NBT_CHANNEL, channelId)
        tag.putBoolean(NBT_IS_MASTER, isMaster)
        tag.putLong(NBT_MASTER_POS, masterPos.asLong())
        tag.putInt(NBT_GROUP_W, groupBlocksWide)
        tag.putInt(NBT_GROUP_H, groupBlocksTall)
        tag.putInt(NBT_CURSOR_C, cursorCol)
        tag.putInt(NBT_CURSOR_R, cursorRow)
        tag.putInt(NBT_CUR_FG, currentFg)
        tag.putInt(NBT_CUR_BG, currentBg)
        // Buffer as a single \n-joined string (master only).
        buffer?.let { tag.putString(NBT_BUFFER, it.joinToString("\n") { row -> String(row) }) }
        fgColors?.let { tag.putIntArray(NBT_FG, it) }
        bgColors?.let { tag.putIntArray(NBT_BG, it) }
    }

    override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        if (tag.contains(NBT_CHANNEL)) channelId = tag.getString(NBT_CHANNEL)
        if (tag.contains(NBT_IS_MASTER)) isMaster = tag.getBoolean(NBT_IS_MASTER)
        if (tag.contains(NBT_MASTER_POS)) masterPos = BlockPos.of(tag.getLong(NBT_MASTER_POS))
        if (tag.contains(NBT_GROUP_W)) groupBlocksWide = tag.getInt(NBT_GROUP_W)
        if (tag.contains(NBT_GROUP_H)) groupBlocksTall = tag.getInt(NBT_GROUP_H)
        if (tag.contains(NBT_CURSOR_C)) cursorCol = tag.getInt(NBT_CURSOR_C)
        if (tag.contains(NBT_CURSOR_R)) cursorRow = tag.getInt(NBT_CURSOR_R)
        if (tag.contains(NBT_CUR_FG)) currentFg = tag.getInt(NBT_CUR_FG)
        if (tag.contains(NBT_CUR_BG)) currentBg = tag.getInt(NBT_CUR_BG)
        if (tag.contains(NBT_BUFFER)) {
            val rows = tag.getString(NBT_BUFFER).split('\n')
            val buf = Array(totalRows) { r ->
                val src = rows.getOrNull(r) ?: ""
                CharArray(totalCols) { c -> src.getOrNull(c) ?: ' ' }
            }
            buffer = buf
        }
        if (tag.contains(NBT_FG)) {
            val arr = tag.getIntArray(NBT_FG)
            // size mismatch (e.g. group resized) — fall back to defaults
            fgColors = if (arr.size == totalRows * totalCols) arr else IntArray(totalRows * totalCols) { DEFAULT_FG }
        }
        if (tag.contains(NBT_BG)) {
            val arr = tag.getIntArray(NBT_BG)
            bgColors = if (arr.size == totalRows * totalCols) arr else IntArray(totalRows * totalCols) { DEFAULT_BG }
        }
    }

    override fun getUpdatePacket(): Packet<ClientGamePacketListener> =
        ClientboundBlockEntityDataPacket.create(this)

    override fun getUpdateTag(registries: HolderLookup.Provider): CompoundTag {
        val tag = CompoundTag()
        saveAdditional(tag, registries)
        return tag
    }

    /**
     * Push current state to clients tracking this BE's chunk. Uses flag `2`
     * (UPDATE_CLIENTS only) — explicitly skipping `UPDATE_NEIGHBORS` (`1`).
     *
     * Why no neighbor updates: my [MonitorBlock.neighborChanged] hook calls
     * [requestGroupReevaluation], and this BE's [propagateGroupReevaluation]
     * already directly notifies group members. If sync also triggered
     * neighborChanged, every sync would cascade through 4 neighbors → each
     * re-eval → each sync → exponential blowup that hung world saves
     * (observed: 9-BE group hung MC for 15 minutes during chunk save).
     */
    private fun sync() {
        val lvl = level ?: return
        if (!isServer) return
        lvl.sendBlockUpdated(blockPos, blockState, blockState, 2)
    }

    companion object {
        const val DEFAULT_CHANNEL = "default"

        /**
         * Per-block character grid dimensions. Computed to match what physically
         * fits at our em-to-world-units scale (1/12 wu per em):
         *   - cols: 0.6em advance × 20 = 12em = 1.0 wu = 1 block. ✓
         *   - rows: 1.32em line height × 9 = 11.88em ≈ 0.99 wu ≈ 1 block. ✓
         * 10 rows would overflow the bottom of each block by ~10%.
         */
        const val COLS_PER_BLOCK = 20
        const val ROWS_PER_BLOCK = 9

        private const val NBT_CHANNEL = "channelId"
        private const val NBT_IS_MASTER = "isMaster"
        private const val NBT_MASTER_POS = "masterPos"
        private const val NBT_GROUP_W = "groupW"
        private const val NBT_GROUP_H = "groupH"
        private const val NBT_CURSOR_C = "cursorCol"
        private const val NBT_CURSOR_R = "cursorRow"
        private const val NBT_BUFFER = "buffer"
        private const val NBT_FG = "fg"
        private const val NBT_BG = "bg"
        private const val NBT_CUR_FG = "curFg"
        private const val NBT_CUR_BG = "curBg"

        /** VS Dark editor.foreground — matches the GUI terminal's text color. */
        const val DEFAULT_FG = 0xFFD4D4D4.toInt()
        /** Transparent — no per-cell bg fill, panel texture shows through. */
        const val DEFAULT_BG = 0x00000000

        /**
         * 16-color ANSI palette — xterm/VS Code dark defaults. Indexed 0..15:
         *   0..7   = standard colors (black, red, green, yellow, blue, magenta, cyan, white)
         *   8..15  = bright variants (same order, brighter)
         * Used by [applySgr] to translate `\033[31m` (red fg) → fg color.
         */
        val ANSI_PALETTE: IntArray = intArrayOf(
            0xFF000000.toInt(), 0xFFCD0000.toInt(), 0xFF00CD00.toInt(), 0xFFCDCD00.toInt(),
            0xFF0000EE.toInt(), 0xFFCD00CD.toInt(), 0xFF00CDCD.toInt(), 0xFFE5E5E5.toInt(),
            0xFF7F7F7F.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF5C5CFF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )

        fun Direction.toMergeFacing(): MonitorMerge.Facing? = when (this) {
            Direction.NORTH -> MonitorMerge.Facing.NORTH
            Direction.SOUTH -> MonitorMerge.Facing.SOUTH
            Direction.EAST -> MonitorMerge.Facing.EAST
            Direction.WEST -> MonitorMerge.Facing.WEST
            else -> null  // up/down — not supported in v0
        }
    }
}
