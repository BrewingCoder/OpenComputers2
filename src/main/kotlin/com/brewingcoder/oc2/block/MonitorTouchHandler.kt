package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.OpenComputers2
import net.minecraft.world.InteractionResult
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.common.EventBusSubscriber
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

/**
 * Touch-input wiring for monitor blocks.
 *
 * Click model (CC:Tweaked-aligned):
 *   - **Right-click** monitor → fires touch event (cell coords sent to script via `pollTouches`)
 *   - **Left-click** → does nothing (cancelled — preserves the screen against accidental breaks)
 *   - **Shift + left-click** → vanilla break behavior preserved (so players can still mine)
 *
 * Two event subscribers:
 *   1. [PlayerInteractEvent.LeftClickBlock] — cancels unshifted left-clicks so the screen survives
 *      casual click-spam. (Without this we'd lose the screen the moment a player taps it.)
 *   2. [PlayerInteractEvent.RightClickBlock] — fires the touch event on right-click and consumes
 *      the click (suppresses placement/use of held items). Players add to walls by targeting an
 *      empty face NEXT TO the monitor instead of the monitor itself.
 *
 * GAME-bus subscriber, server-side only.
 */
@EventBusSubscriber(modid = OpenComputers2.ID)
object MonitorTouchHandler {

    @SubscribeEvent
    fun onLeftClickBlock(event: PlayerInteractEvent.LeftClickBlock) {
        val level = event.level
        if (level.isClientSide) return
        val pos = event.pos
        val state = level.getBlockState(pos)
        if (state.block !== ModBlocks.MONITOR) return
        // Shift+left-click → vanilla break (so players can still mine the block deliberately).
        if (event.entity.isShiftKeyDown) return
        // Bare left-click → suppress break. No touch action — that's bound to right-click below.
        event.isCanceled = true
    }

    @SubscribeEvent
    fun onRightClickBlock(event: PlayerInteractEvent.RightClickBlock) {
        val level = event.level
        if (level.isClientSide) return
        val pos = event.pos
        val state = level.getBlockState(pos)
        if (state.block !== ModBlocks.MONITOR) return
        val player = event.entity

        val be = level.getBlockEntity(pos) as? MonitorBlockEntity ?: return
        val master = if (be.isMaster) be
            else (level.getBlockEntity(be.masterPos) as? MonitorBlockEntity) ?: return

        val facing = state.getValue(MonitorBlock.FACING)
        // RightClickBlock.hitVec is a BlockHitResult; .location gives the Vec3
        val cell = MonitorBlock.hitToCell(facing, master, event.hitVec.location) ?: return
        master.enqueueTouch(cell.first, cell.second, player.name.string)

        // Consume the click so the held item (if any) doesn't run its usual action
        // (e.g. so a stack of monitors in hand doesn't auto-place when touching the screen).
        event.isCanceled = true
        event.cancellationResult = InteractionResult.SUCCESS
    }
}
