package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Touch-input wiring for monitor blocks.
 *
 * Click model:
 *   - **Left-click** (no sneak) → fires touch event; suppresses block-break chipping
 *   - **Shift + left-click** → vanilla break preserved
 *   - **Right-click on display face** (no sneak) → fires touch event
 *   - **Right-click on non-display face** (no sneak) → opens channel config screen
 *   - **Shift + right-click** → PASS to vanilla (so a monitor in hand places against the
 *     block, and `neighborChanged` auto-merges into the adjacent group — CC:T's model)
 *
 * GAME-bus subscriber, server-side only (touch dispatch).
 */
@EventBusSubscriber(modid = OpenComputers2.ID)
object MonitorTouchHandler {

    // Dedup window for rapid-fire left-clicks on the same monitor cell.
    // Creative mode + held button re-sends START_DESTROY_BLOCK every couple ticks;
    // 150ms (~3 ticks) collapses the repeats without blocking intentional rapid clicks.
    private const val TOUCH_DEDUP_MS = 150L
    private val lastTouch = ConcurrentHashMap<UUID, Long>()

    @SubscribeEvent
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val level = event.level
        val pos = event.pos
        val state = level.getBlockState(pos)
        if (state.block !== ModBlocks.MONITOR) return
        // Shift + left-click → vanilla break (so players can still mine the block deliberately).
        if (event.entity.isShiftKeyDown) return

        // Bare left-click: always suppress break so the monitor doesn't chip.
        event.isCanceled = true

        if (level.isClientSide) return
        // Only fire on the initial press. CLIENT_HOLD fires every tick on the client;
        // STOP/ABORT fire on release. In creative, held left-click re-sends START
        // every few ticks, so we also dedup in fireTouchDeduped.
        if (event.action != PlayerInteractEvent.LeftClickBlock.Action.START) return
        // LeftClickBlock doesn't carry a hitVec -- reconstruct from the player's raycast.
        val hitLoc = raycastBlockHit(event.entity, pos) ?: return
        fireTouchDeduped(level, pos, state, event.entity, hitLoc)
    }

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level
        val pos = event.pos
        val state = level.getBlockState(pos)
        if (state.block !== ModBlocks.MONITOR) return
        val player = event.entity

        // Sneak + right-click → PASS to vanilla so a monitor-in-hand places against the
        // clicked face; neighborChanged auto-merges into the existing group.
        if (player.isShiftKeyDown) return

        val displayFace = state.getValue(MonitorBlock.FACING)
        val clickedFace = event.face

        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS

        if (clickedFace != displayFace) {
            // Non-display face → channel config screen (client-side only).
            if (level.isClientSide) {
                com.brewingcoder.oc2.client.ClientHandler.openMonitorConfigScreen(level, pos)
            }
            return
        }

        // Display face → fire touch on server.
        if (level.isClientSide) return
        fireTouchDeduped(level, pos, state, player, event.hitVec.location)
    }

    /**
     * Drops a touch if the same player already fired one within [TOUCH_DEDUP_MS].
     * Belt-and-suspenders: the Action.START filter already eliminates most spam,
     * but creative-mode held-click can still re-fire START every few ticks.
     */
    private fun fireTouchDeduped(level: Level, pos: BlockPos,
                                 state: net.minecraft.world.level.block.state.BlockState,
                                 player: Player, hitLocation: Vec3) {
        val now = System.currentTimeMillis()
        val last = lastTouch[player.uuid]
        if (last != null && now - last < TOUCH_DEDUP_MS) return
        lastTouch[player.uuid] = now
        fireTouch(level, pos, state, player, hitLocation)
    }

    /**
     * Raycast along the player's look vector and return the precise hit location
     * if it lands on [expectedPos]. LeftClickBlock in NeoForge 1.21 doesn't carry
     * a hitVec, so we recover it from the player's eye + look direction.
     */
    private fun raycastBlockHit(player: Player, expectedPos: BlockPos): Vec3? {
        val reach = player.blockInteractionRange() + 1.0
        val hr = player.pick(reach, 1.0f, false)
        if (hr.type != HitResult.Type.BLOCK) return null
        val bhr = hr as BlockHitResult
        if (bhr.blockPos != expectedPos) return null
        return bhr.location
    }

    private fun fireTouch(level: Level, pos: BlockPos, state: net.minecraft.world.level.block.state.BlockState,
                          player: Player, hitLocation: Vec3) {
        val be = level.getBlockEntity(pos) as? MonitorBlockEntity ?: return
        val master = if (be.isMaster) be
            else (level.getBlockEntity(be.masterPos) as? MonitorBlockEntity) ?: return
        val facing = state.getValue(MonitorBlock.FACING)
        val hit = MonitorBlock.hitToCell(facing, master, hitLocation) ?: return
        master.enqueueTouch(hit.col, hit.row, hit.px, hit.py, player.name.string)
    }
}
