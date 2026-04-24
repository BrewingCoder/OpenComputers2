package com.brewingcoder.oc2.block

import com.brewingcoder.oc2.client.ClientHandler
import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.phys.BlockHitResult

/**
 * WiFi Extender block. Omnidirectional — no FACING property — so placement
 * doesn't care which way the player faces. Tracks a boolean [ACTIVE] blockstate
 * toggled by the BE's tick() when energy buffer transitions between zero and
 * non-zero; model JSON maps the two states to separate visual variants so the
 * block visibly "lights up" when powered.
 *
 * Right-click (empty hand) → channel config GUI, mirroring Monitor's UX.
 */
class WiFiExtenderBlock(properties: Properties) : BaseEntityBlock(properties) {

    init {
        registerDefaultState(stateDefinition.any().setValue(ACTIVE, false))
    }

    override fun createBlockStateDefinition(
        builder: StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState>,
    ) {
        builder.add(ACTIVE)
    }

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) {
            ClientHandler.openWiFiExtenderConfigScreen(level, pos)
        }
        return InteractionResult.SUCCESS
    }

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        WiFiExtenderBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        blockEntityType: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = createTickerHelper<WiFiExtenderBlockEntity, T>(
        blockEntityType,
        ModBlockEntities.WIFI_EXTENDER.get(),
    ) { _, _, _, be -> be.tick() }

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    companion object {
        val ACTIVE: BooleanProperty = BooleanProperty.create("active")
        val CODEC: MapCodec<WiFiExtenderBlock> = simpleCodec(::WiFiExtenderBlock)
    }
}
