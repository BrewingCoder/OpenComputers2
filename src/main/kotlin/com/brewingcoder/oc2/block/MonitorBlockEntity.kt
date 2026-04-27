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
        internal set

    /**
     * Set the wifi channel for this monitor group. **Must be called on the master**
     * (the network packet handler resolves master from the clicked block). Walks
     * the group to mirror the channel onto every slave so a future master flip
     * inherits the correct channel without the GUI having to re-set it.
     */
    fun setChannelIdForGroup(newChannel: String) {
        if (!isServer) return
        if (!isMaster) return
        if (newChannel == channelId) return
        // Re-register the master under the new channel id.
        ChannelRegistry.unregister(this)
        channelId = newChannel
        ChannelRegistry.register(this)
        // Mirror onto slaves so a future master flip carries the channel forward.
        // Brute-search a box covering the maximum possible group extent — group
        // dimensions are bounded by [groupBlocksWide] × [groupBlocksTall] in both
        // horizontal directions (we don't know which way the slaves extend).
        val lvl = level ?: return
        val r = maxOf(groupBlocksWide, groupBlocksTall)
        for (dx in -r..r) for (dy in -r..r) for (dz in -r..r) {
            val slavePos = blockPos.offset(dx, dy, dz)
            if (slavePos == blockPos) continue
            val slave = lvl.getBlockEntity(slavePos) as? MonitorBlockEntity ?: continue
            if (slave.masterPos != blockPos) continue
            slave.channelId = newChannel
            slave.setChanged()
            slave.sync()
        }
        setChanged()
        sync()
    }

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

    /**
     * HD pixel buffer — ARGB ints, row-major, indexed by `y * pxW + x`. Master-only,
     * null on slaves. Composited UNDER the text grid by the renderer. Default 0 =
     * fully transparent (block face shows through). Lifecycle mirrors [buffer]:
     * allocated on master in [applyGroup], freed on slave demotion.
     */
    private var pixelBuffer: IntArray? = null

    /**
     * Item-icon overlay list — composited ABOVE the text grid so icons occlude text
     * and HD pixels. Master-only, null on slaves. Lifecycle mirrors [pixelBuffer]:
     * allocated lazily on first [drawItem]; nulled on slave demotion and on
     * lease-release (so a new script starts with a blank slate).
     */
    private var iconOverlays: MutableList<IconOverlay>? = null

    /**
     * One icon on the overlay layer. [wPx]×[hPx] are edges in pixel-buffer pixels.
     * [kind] is `"item"` (default) or `"fluid"` — selects registry lookup + renderer branch.
     * [id] is the namespaced registry id (`"minecraft:redstone"`, `"minecraft:water"`).
     */
    data class IconOverlay(val x: Int, val y: Int, val wPx: Int, val hPx: Int, val id: String, val kind: String = "item") {
        /** Back-compat alias for the pre-fluid IconOverlay.itemId field. */
        val itemId: String get() = id
    }

    /** Pixel-buffer dimensions. Computed from group size — see [PX_PER_CELL]. */
    private val pxW: Int get() = totalCols * PX_PER_CELL
    private val pxH: Int get() = totalRows * PX_PER_CELL

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
            // Master-only registration. We assume `isMaster=true` on first load
            // (the default) and let [requestGroupReevaluation] flip + re-register
            // if a master already exists adjacent. This keeps `peripheral.list`
            // returning ONE entry per group instead of N.
            if (isMaster) ChannelRegistry.register(this)
            requestGroupReevaluation()
        }
    }

    override fun setRemoved() {
        if (isServer) {
            OpenComputers2.LOGGER.debug("monitor setRemoved @ {} (isMaster={}, group {}x{})",
                blockPos, isMaster, groupBlocksWide, groupBlocksTall)
            // Only the master is registered; unregistering a slave is a no-op
            // but the registry log noise isn't worth it.
            if (isMaster) ChannelRegistry.unregister(this)
        } else if (isMaster) {
            // Client-side: free the GPU texture for this master's pixel buffer.
            // Schedule on the render thread — setRemoved can fire from any thread.
            val pos = blockPos
            try {
                com.mojang.blaze3d.systems.RenderSystem.recordRenderCall {
                    com.brewingcoder.oc2.client.screen.MonitorPixelTextureCache.release(pos)
                }
            } catch (t: Throwable) {
                OpenComputers2.LOGGER.warn("monitor pixel-texture release schedule failed @ {}", pos, t)
            }
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
     *
     * Applies the resulting group state to EVERY cell in the flood-fill
     * component, not just this seed. Required because MC's `neighborChanged`
     * only reaches cells adjacent to the changed block — distant cells in the
     * same monitor wall would otherwise keep stale `groupH`/`masterPos`
     * state (observed: placing a 4×4 wall left the first-placed row as a
     * 1×4 standalone master while the rest of the wall claimed to be 4×4 of
     * the same master — bezel showed 1×4, not 4×4).
     *
     * Correctness: flood-fill and max-rect search are deterministic from any
     * seed in the same connected component, so applying the computed group to
     * every member is equivalent to each one running its own re-evaluation.
     */
    fun requestGroupReevaluation() {
        if (!isServer) return
        val lvl = level ?: return
        val state = blockState
        val facing = state.getValue(MonitorBlock.FACING).toMergeFacing() ?: return

        val seedPos = Position(blockPos.x, blockPos.y, blockPos.z)
        val result = MonitorMerge.computeComponent(seedPos, facing) { p ->
            val bp = BlockPos(p.x, p.y, p.z)
            val s = lvl.getBlockState(bp)
            s.block === ModBlocks.MONITOR && s.getValue(MonitorBlock.FACING).toMergeFacing() == facing
        }
        OpenComputers2.LOGGER.debug("monitor reevaluate @ {} → group {}x{} master={} ({} members, {} orphans)",
            blockPos, result.mainGroup.width, result.mainGroup.height, result.mainGroup.masterPos,
            result.mainGroup.members.size, result.orphans.size)

        // Apply the main group to every rect member so distant cells get their
        // state corrected in one sweep (instead of waiting for a future
        // neighborChanged that may never come).
        for (memberPos in result.mainGroup.members) {
            val bp = BlockPos(memberPos.x, memberPos.y, memberPos.z)
            val be = lvl.getBlockEntity(bp) as? MonitorBlockEntity ?: continue
            be.applyGroup(result.mainGroup)
        }

        // Orphans: connected to the component but outside the max rect. Each
        // becomes a standalone 1×1 master pointing at itself.
        for (orphanPos in result.orphans) {
            val bp = BlockPos(orphanPos.x, orphanPos.y, orphanPos.z)
            val be = lvl.getBlockEntity(bp) as? MonitorBlockEntity ?: continue
            val orphanGroup = MonitorMerge.MonitorGroup(
                masterPos = orphanPos,
                facing = facing,
                members = setOf(orphanPos),
                width = 1,
                height = 1,
            )
            be.applyGroup(orphanGroup)
        }
    }

    /** Update local group state from a freshly-computed [MonitorMerge.MonitorGroup]. */
    private fun applyGroup(group: MonitorMerge.MonitorGroup) {
        val newMasterPos = BlockPos(group.masterPos.x, group.masterPos.y, group.masterPos.z)
        val newIsMaster = blockPos == newMasterPos

        val sizeChanged = (groupBlocksWide != group.width) || (groupBlocksTall != group.height)
        val masterChanged = (masterPos != newMasterPos)
        val wasMaster = isMaster

        masterPos = newMasterPos
        groupBlocksWide = group.width
        groupBlocksTall = group.height
        isMaster = newIsMaster

        // Master flip = registry update. Channel propagation across slaves
        // is handled in [setChannelIdForGroup] on the master, so a freshly-promoted
        // slave already has the right channelId in its own field.
        if (isServer) {
            if (wasMaster && !newIsMaster) {
                ChannelRegistry.unregister(this)
            } else if (!wasMaster && newIsMaster) {
                ChannelRegistry.register(this)
            }
        }

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
            // Pixel buffer — fresh allocate or resize. Don't copy old pixels
            // across resizes (rare event; HD content rarely outlives a relayout).
            val targetPx = pxW * pxH
            if (pixelBuffer == null || pixelBuffer!!.size != targetPx) {
                pixelBuffer = IntArray(targetPx)  // 0 = transparent
            }
        } else {
            buffer = null
            fgColors = null
            bgColors = null
            pixelBuffer = null
            iconOverlays = null
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
    //
    // Mutating methods MUST acquire the peripheral lease BEFORE marshaling to the
    // server thread — the lease is keyed off ScriptCallerContext (a ThreadLocal set
    // on the worker), which is null on the server thread. Acquiring after the marshal
    // would silently no-op.

    private fun lease() = com.brewingcoder.oc2.platform.script.PeripheralLease.acquireOrThrow(this) {
        // Script ended — clear text buffer and pixel layer so the monitor doesn't
        // show stale content from the previous script's run.
        onServerThread {
            forMaster { master ->
                master.buffer?.forEach { row -> row.fill(' ') }
                master.fgColors?.fill(DEFAULT_FG)
                master.bgColors?.fill(DEFAULT_BG)
                master.pixelBuffer = null
                master.iconOverlays = null
                master.setChanged()
                master.sync()
            }
        }
    }

    override fun write(text: String): Unit { lease(); onServerThread {
        forMaster { it.doWriteText(text) }
    } }

    override fun setCursorPos(col: Int, row: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            master.cursorCol = col.coerceIn(0, master.totalCols - 1)
            master.cursorRow = row.coerceIn(0, master.totalRows - 1)
        }
    } }

    override fun clear(): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.buffer ?: return@forMaster
            for (row in buf) row.fill(' ')
            master.fgColors?.fill(master.currentFg)
            master.bgColors?.fill(master.currentBg)
            master.cursorRow = 0
            master.cursorCol = 0
            master.setChanged()
            master.sync()
        }
    } }

    override fun getSize(): Pair<Int, Int> = onServerThread {
        forMaster { master -> master.totalCols to master.totalRows } ?: (0 to 0)
    }

    override fun getCursorPos(): Pair<Int, Int> = onServerThread {
        forMaster { master -> master.cursorCol to master.cursorRow } ?: (0 to 0)
    }

    override fun setForegroundColor(color: Int): Unit { lease(); onServerThread {
        forMaster { master -> master.currentFg = color }
    } }

    override fun setBackgroundColor(color: Int): Unit { lease(); onServerThread {
        forMaster { master -> master.currentBg = color }
    } }

    override fun pollTouches(): List<TouchEvent> = onServerThread {
        forMaster { master ->
            val drained = master.touchQueue.toList()
            master.touchQueue.clear()
            drained
        } ?: emptyList()
    }

    // ---- HD pixel-buffer API ----

    override fun getPixelSize(): Pair<Int, Int> = onServerThread {
        forMaster { master -> master.pxW to master.pxH } ?: (0 to 0)
    }

    override fun clearPixels(argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            // Allocate if missing. A previous script's lease-release nulls the
            // buffer, but the client texture can still hold stale pixels until
            // the next sync — so a fresh script's clearPixels must always
            // produce a cleared buffer, even when the slate started empty.
            val targetPx = master.pxW * master.pxH
            val buf = master.pixelBuffer?.takeIf { it.size == targetPx }
                ?: IntArray(targetPx).also { master.pixelBuffer = it }
            buf.fill(argb)
            master.setChanged(); master.sync()
        }
    } }

    override fun setPixel(x: Int, y: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            if (x < 0 || y < 0 || x >= master.pxW || y >= master.pxH) return@forMaster
            buf[y * master.pxW + x] = argb
            master.setChanged(); master.sync()
        }
    } }

    override fun drawRect(x: Int, y: Int, w: Int, h: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master -> master.fillRectImpl(x, y, w, h, argb); master.setChanged(); master.sync() }
    } }

    override fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, argb: Int, thickness: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val t = thickness.coerceAtLeast(1)
            // Top, bottom, left, right strips — overlap at corners is fine (same color).
            master.fillRectImpl(x, y, w, t, argb)
            master.fillRectImpl(x, y + h - t, w, t, argb)
            master.fillRectImpl(x, y, t, h, argb)
            master.fillRectImpl(x + w - t, y, t, h, argb)
            master.setChanged(); master.sync()
        }
    } }

    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            // Bresenham
            var x = x1; var y = y1
            val dx = kotlin.math.abs(x2 - x1)
            val dy = kotlin.math.abs(y2 - y1)
            val sx = if (x1 < x2) 1 else -1
            val sy = if (y1 < y2) 1 else -1
            var err = dx - dy
            val w = master.pxW; val h = master.pxH
            while (true) {
                if (x in 0 until w && y in 0 until h) buf[y * w + x] = argb
                if (x == x2 && y == y2) break
                val e2 = err shl 1
                if (e2 > -dy) { err -= dy; x += sx }
                if (e2 < dx) { err += dx; y += sy }
            }
            master.setChanged(); master.sync()
        }
    } }

    override fun drawGradientV(x: Int, y: Int, w: Int, h: Int, topArgb: Int, bottomArgb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            val pxW = master.pxW; val pxH = master.pxH
            val x0 = x.coerceAtLeast(0); val x1 = (x + w).coerceAtMost(pxW)
            val y0 = y.coerceAtLeast(0); val y1 = (y + h).coerceAtMost(pxH)
            if (x1 <= x0 || y1 <= y0 || h <= 0) return@forMaster
            for (yy in y0 until y1) {
                val t = (yy - y).toFloat() / h.toFloat()
                val color = lerpArgb(topArgb, bottomArgb, t.coerceIn(0f, 1f))
                val rowBase = yy * pxW
                for (xx in x0 until x1) buf[rowBase + xx] = color
            }
            master.setChanged(); master.sync()
        }
    } }

    override fun fillCircle(cx: Int, cy: Int, r: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            if (r <= 0) return@forMaster
            val pxW = master.pxW; val pxH = master.pxH
            val r2 = r * r
            val y0 = (cy - r).coerceAtLeast(0); val y1 = (cy + r).coerceAtMost(pxH - 1)
            val x0 = (cx - r).coerceAtLeast(0); val x1 = (cx + r).coerceAtMost(pxW - 1)
            for (yy in y0..y1) {
                val dy = yy - cy
                val rowBase = yy * pxW
                for (xx in x0..x1) {
                    val dx = xx - cx
                    if (dx * dx + dy * dy <= r2) buf[rowBase + xx] = argb
                }
            }
            master.setChanged(); master.sync()
        }
    } }

    override fun fillEllipse(cx: Int, cy: Int, rx: Int, ry: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            if (rx <= 0 || ry <= 0) return@forMaster
            val pxW = master.pxW; val pxH = master.pxH
            val rx2 = (rx.toLong() * rx.toLong())
            val ry2 = (ry.toLong() * ry.toLong())
            val denom = rx2 * ry2
            val y0 = (cy - ry).coerceAtLeast(0); val y1 = (cy + ry).coerceAtMost(pxH - 1)
            val x0 = (cx - rx).coerceAtLeast(0); val x1 = (cx + rx).coerceAtMost(pxW - 1)
            for (yy in y0..y1) {
                val dy = (yy - cy).toLong()
                val rowBase = yy * pxW
                val dyTerm = dy * dy * rx2
                for (xx in x0..x1) {
                    val dx = (xx - cx).toLong()
                    if (dx * dx * ry2 + dyTerm <= denom) buf[rowBase + xx] = argb
                }
            }
            master.setChanged(); master.sync()
        }
    } }

    override fun drawArc(cx: Int, cy: Int, rx: Int, ry: Int, thickness: Int, startDeg: Int, sweepDeg: Int, argb: Int): Unit { lease(); onServerThread {
        forMaster { master ->
            val buf = master.pixelBuffer ?: return@forMaster
            if (rx <= 0 || ry <= 0 || sweepDeg <= 0) return@forMaster
            val t = thickness.coerceIn(1, ry)
            val pxW = master.pxW; val pxH = master.pxH
            // Normalized inner-radius squared. A pixel is "in the ring" when
            // (nx^2 + ny^2) is in [innerR2, 1].
            val innerRadius = (ry - t).toDouble() / ry.toDouble()
            val innerR2 = innerRadius * innerRadius
            val start = ((startDeg % 360) + 360) % 360
            val sweep = sweepDeg.coerceAtMost(360)
            val y0 = (cy - ry).coerceAtLeast(0); val y1 = (cy + ry).coerceAtMost(pxH - 1)
            val x0 = (cx - rx).coerceAtLeast(0); val x1 = (cx + rx).coerceAtMost(pxW - 1)
            val rxD = rx.toDouble(); val ryD = ry.toDouble()
            for (yy in y0..y1) {
                val ny = (yy - cy).toDouble() / ryD
                val ny2 = ny * ny
                val rowBase = yy * pxW
                for (xx in x0..x1) {
                    val nx = (xx - cx).toDouble() / rxD
                    val d2 = nx * nx + ny2
                    if (d2 > 1.0 || d2 < innerR2) continue
                    // Clock angle: 0 = up (ny < 0), clockwise. atan2(nx, -ny) gives
                    // that directly in the [-π, π] range; normalize to [0, 360).
                    var deg = Math.toDegrees(kotlin.math.atan2(nx, -ny))
                    if (deg < 0) deg += 360.0
                    val delta = ((deg - start) % 360.0 + 360.0) % 360.0
                    if (delta > sweep) continue
                    buf[rowBase + xx] = argb
                }
            }
            master.setChanged(); master.sync()
        }
    } }

    override fun drawItem(x: Int, y: Int, wPx: Int, hPx: Int, itemId: String): Unit { lease(); onServerThread {
        forMaster { master ->
            if (wPx <= 0 || hPx <= 0 || itemId.isEmpty()) return@forMaster
            // Accept anything that overlaps the monitor — the client renderer
            // does the final clip. Reject only icons entirely outside the bounds.
            if (x + wPx <= 0 || y + hPx <= 0) return@forMaster
            if (x >= master.pxW || y >= master.pxH) return@forMaster
            val list = master.iconOverlays ?: mutableListOf<IconOverlay>().also { master.iconOverlays = it }
            list.add(IconOverlay(x, y, wPx, hPx, itemId, "item"))
            master.setChanged(); master.sync()
        }
    } }

    override fun drawFluid(x: Int, y: Int, wPx: Int, hPx: Int, fluidId: String): Unit { lease(); onServerThread {
        forMaster { master ->
            if (wPx <= 0 || hPx <= 0 || fluidId.isEmpty()) return@forMaster
            if (x + wPx <= 0 || y + hPx <= 0) return@forMaster
            if (x >= master.pxW || y >= master.pxH) return@forMaster
            val list = master.iconOverlays ?: mutableListOf<IconOverlay>().also { master.iconOverlays = it }
            list.add(IconOverlay(x, y, wPx, hPx, fluidId, "fluid"))
            master.setChanged(); master.sync()
        }
    } }

    override fun drawChemical(x: Int, y: Int, wPx: Int, hPx: Int, chemicalId: String): Unit { lease(); onServerThread {
        forMaster { master ->
            if (wPx <= 0 || hPx <= 0 || chemicalId.isEmpty()) return@forMaster
            if (x + wPx <= 0 || y + hPx <= 0) return@forMaster
            if (x >= master.pxW || y >= master.pxH) return@forMaster
            val list = master.iconOverlays ?: mutableListOf<IconOverlay>().also { master.iconOverlays = it }
            list.add(IconOverlay(x, y, wPx, hPx, chemicalId, "chemical"))
            master.setChanged(); master.sync()
        }
    } }

    override fun clearIcons(): Unit { lease(); onServerThread {
        forMaster { master ->
            if (master.iconOverlays.isNullOrEmpty()) {
                // Force-sync even on empty-to-empty so a stale client list gets reset
                // when this is the first call of a render frame after a reload.
                master.iconOverlays = mutableListOf()
            } else {
                master.iconOverlays!!.clear()
            }
            master.setChanged(); master.sync()
        }
    } }

    /** Internal — clipped rectangle fill into the master's pixelBuffer. No setChanged/sync (caller handles). */
    private fun fillRectImpl(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        val buf = pixelBuffer ?: return
        val x0 = x.coerceAtLeast(0); val x1 = (x + w).coerceAtMost(pxW)
        val y0 = y.coerceAtLeast(0); val y1 = (y + h).coerceAtMost(pxH)
        if (x1 <= x0 || y1 <= y0) return
        for (yy in y0 until y1) {
            val rowBase = yy * pxW
            for (xx in x0 until x1) buf[rowBase + xx] = argb
        }
    }

    /** Linear ARGB lerp. t in [0,1]. */
    private fun lerpArgb(a: Int, b: Int, t: Float): Int {
        val ia = ((a ushr 24) and 0xFF) + (((b ushr 24) and 0xFF) - ((a ushr 24) and 0xFF)) * t
        val ir = ((a ushr 16) and 0xFF) + (((b ushr 16) and 0xFF) - ((a ushr 16) and 0xFF)) * t
        val ig = ((a ushr 8) and 0xFF) + (((b ushr 8) and 0xFF) - ((a ushr 8) and 0xFF)) * t
        val ib = (a and 0xFF) + ((b and 0xFF) - (a and 0xFF)) * t
        return (ia.toInt() shl 24) or (ir.toInt() shl 16) or (ig.toInt() shl 8) or ib.toInt()
    }

    /** Read-only pixel snapshot for the renderer. Returns (width, height, argb[]) or null on slaves. */
    fun pixelSnapshot(): Triple<Int, Int, IntArray>? {
        val buf = pixelBuffer ?: return null
        return Triple(pxW, pxH, buf.copyOf())
    }

    /** Read-only icon-overlay snapshot for the renderer. Returns an empty list when there's nothing to draw. */
    fun iconSnapshot(): List<IconOverlay> = iconOverlays?.toList() ?: emptyList()

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
    fun enqueueTouch(col: Int, row: Int, px: Int, py: Int, playerName: String) {
        forMaster { master ->
            master.touchQueue.addLast(TouchEvent(col, row, px, py, playerName))
            while (master.touchQueue.size > MonitorPeripheral.TOUCH_QUEUE_CAP) {
                master.touchQueue.removeFirst()
            }
            // Also fire as an os.pullEvent("monitor_touch") to any running scripts
            // on this monitor's channel. Polling pollTouches() still works in
            // parallel — both APIs coexist. Event payload includes both cell
            // coords (legacy) and pixel coords (HD hit-testing).
            com.brewingcoder.oc2.event.EventDispatch.fireToChannel(
                master.channelId,
                com.brewingcoder.oc2.platform.script.ScriptEvent(
                    "monitor_touch",
                    listOf(col, row, px, py, playerName),
                ),
            )
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
        fg.fill(currentFg)
        bg.fill(currentBg)
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
        buffer?.let { buf ->
            tag.putString(NBT_BUFFER, buf.joinToString("\n") { row -> String(row) })
        }
        fgColors?.let { tag.putIntArray(NBT_FG, it) }
        bgColors?.let { tag.putIntArray(NBT_BG, it) }
        // Pixel buffer — deflate-compressed bytes. Sparse content (mostly 0)
        // compresses ~50:1; saves a 4×3 monitor at ~25KB instead of 1.2MB.
        pixelBuffer?.let { px ->
            tag.putByteArray(NBT_PX, deflateInts(px))
            tag.putInt(NBT_PX_W, pxW)
            tag.putInt(NBT_PX_H, pxH)
        }
        // Icons — always serialize (even empty list) so a cleared slate replicates
        // to the client. List shape: "x,y,wPx,hPx,id,kind\n...". id can't contain
        // newlines or commas in practice (registry ids are [a-z0-9_./:]). The trailing
        // kind field is optional on load for back-compat with pre-fluid saves.
        iconOverlays?.let { icons ->
            tag.putString(NBT_ICONS, icons.joinToString("\n") { "${it.x},${it.y},${it.wPx},${it.hPx},${it.id},${it.kind}" })
        }
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
        if (tag.contains(NBT_PX)) {
            val savedW = if (tag.contains(NBT_PX_W)) tag.getInt(NBT_PX_W) else pxW
            val savedH = if (tag.contains(NBT_PX_H)) tag.getInt(NBT_PX_H) else pxH
            val expected = pxW * pxH
            val decoded = inflateInts(tag.getByteArray(NBT_PX), savedW * savedH)
            // Drop the saved pixel buffer if dimensions don't match the current
            // group — a resize between save and load. Cheaper than rescaling pixels.
            pixelBuffer = if (decoded != null && decoded.size == expected) decoded else IntArray(expected)
        } else {
            // No NBT_PX in the tag means the server nulled the buffer (script
            // released its lease). Mirror that to the client — otherwise the old
            // pixel content persists visually after the script ends.
            pixelBuffer = null
        }
        if (tag.contains(NBT_ICONS)) {
            val raw = tag.getString(NBT_ICONS)
            iconOverlays = if (raw.isEmpty()) mutableListOf() else raw.split('\n').mapNotNullTo(mutableListOf()) { line ->
                // Split on last comma first to peel off optional kind, then split the rest into 5.
                // New format: "x,y,wPx,hPx,id,kind"  (kind is last so id can contain ':' freely)
                // Old format: "x,y,wPx,hPx,id"      (no kind → default "item")
                val parts = line.split(',', limit = 6)
                if (parts.size < 5) null else try {
                    val kind = if (parts.size >= 6) parts[5] else "item"
                    IconOverlay(parts[0].toInt(), parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4], kind)
                } catch (e: NumberFormatException) { null }
            }
        } else {
            iconOverlays = null
        }
    }

    /** Compress an int[] to bytes via Deflater (4 bytes per int, then deflate). */
    private fun deflateInts(arr: IntArray): ByteArray {
        val raw = java.nio.ByteBuffer.allocate(arr.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (v in arr) raw.putInt(v)
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)
        deflater.setInput(raw.array())
        deflater.finish()
        val out = java.io.ByteArrayOutputStream(arr.size)
        val tmp = ByteArray(8192)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmp)
            out.write(tmp, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    /** Inverse of [deflateInts]. Returns null on decompression failure. */
    private fun inflateInts(bytes: ByteArray, expectedInts: Int): IntArray? = try {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(bytes)
        val raw = ByteArray(expectedInts * 4)
        var off = 0
        val tmp = ByteArray(8192)
        while (!inflater.finished() && off < raw.size) {
            val n = inflater.inflate(tmp)
            if (n == 0) break
            System.arraycopy(tmp, 0, raw, off, n)
            off += n
        }
        inflater.end()
        val buf = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        IntArray(expectedInts) { buf.int }
    } catch (t: Throwable) {
        OpenComputers2.LOGGER.warn("MonitorBlockEntity: pixel buffer inflate failed", t)
        null
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

        /**
         * HD pixel-buffer density per character cell. Pixel buffer dimensions
         * are `groupCols * COLS_PER_BLOCK * PX_PER_CELL` × `groupRows * ROWS_PER_BLOCK * PX_PER_CELL`.
         * 12 px/cell = 240 px per block face, comfortable on 1080p+ at moderate zoom
         * without exploding GPU/NBT memory on huge groups.
         */
        const val PX_PER_CELL = 12

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
        private const val NBT_PX = "px"
        private const val NBT_PX_W = "pxW"
        private const val NBT_PX_H = "pxH"
        private const val NBT_ICONS = "icons"

        /** VS Dark editor.foreground — matches the GUI terminal's text color. */
        const val DEFAULT_FG = 0xFFD4D4D4.toInt()
        /** Dark terminal background — visible so bg-fill pass can be confirmed at a glance. */
        const val DEFAULT_BG = 0xFF1A1A2E.toInt()  // deep navy, clearly non-transparent

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
