package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.network.SetMonitorChannelPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Monitor channel config screen. Field + nearby-channel dropdown (▼) — same
 * pattern as [PartConfigScreen]. Server resolves the master from the clicked
 * position and propagates the channel to all blocks in the group via
 * [com.brewingcoder.oc2.block.MonitorBlockEntity.setChannelIdForGroup].
 */
class MonitorConfigScreen(
    private val pos: BlockPos,
    private val initialChannel: String,
    private val groupSize: Pair<Int, Int>,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.monitor_config")) {

    private lateinit var channelBox: EditBox

    /** Channel dropdown buttons — created on demand, removed when the dropdown closes. */
    private val channelDropdownButtons: MutableList<Button> = mutableListOf()
    private var channelDropdownOpen: Boolean = false

    override fun init() {
        super.init()
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2

        channelBox = EditBox(font, left + 12, top + 36, PANEL_W - 44, 18, Component.literal("channel"))
        channelBox.setMaxLength(SetMonitorChannelPayload.MAX_CHANNEL_LENGTH)
        channelBox.value = initialChannel
        addRenderableWidget(channelBox)
        addRenderableWidget(
            Button.builder(Component.literal("▼")) { toggleChannelDropdown() }
                .pos(left + PANEL_W - 28, top + 36).size(18, 18).build()
        )

        val btnY = top + PANEL_H - 28
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { saveAndClose() }
                .pos(left + 12, btnY).size(60, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Cancel")) { onClose() }
                .pos(left + PANEL_W - 72, btnY).size(60, 20).build()
        )

        channelBox.setFocused(true)
        setInitialFocus(channelBox)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        graphics.fill(left, top, left + PANEL_W, top + PANEL_H, 0xFF1A1A1A.toInt())
        graphics.fill(left, top, left + PANEL_W, top + 1, 0xFF555555.toInt())
        graphics.fill(left, top + PANEL_H - 1, left + PANEL_W, top + PANEL_H, 0xFF555555.toInt())
        val (w, h) = groupSize
        graphics.drawString(font, "Monitor  ·  ${w}x${h} group", left + 12, top + 12, 0xFFFFFF, false)
        graphics.drawString(font, "channel:", left + 12, top + 26, 0xC0C0C0, false)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == 257 /* ENTER */ && channelBox.isFocused) {
            saveAndClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun saveAndClose() {
        PacketDistributor.sendToServer(SetMonitorChannelPayload(pos, channelBox.value))
        onClose()
    }

    private fun toggleChannelDropdown() {
        if (channelDropdownOpen) closeChannelDropdown() else openChannelDropdown()
    }

    private fun openChannelDropdown() {
        // Hide the EditBox while dropdown is open — vanilla widget rendering
        // lets its text bleed through dropdown buttons (same fix used in PartConfigScreen).
        channelBox.visible = false
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        val channels = nearbyChannels()
        val items = if (channels.isEmpty()) listOf("(none nearby)") else channels
        for ((i, ch) in items.withIndex()) {
            val isPlaceholder = channels.isEmpty()
            val btn = Button.builder(Component.literal(ch)) {
                if (!isPlaceholder) channelBox.value = ch
                closeChannelDropdown()
            }
                // Drop list DOWNWARD from the channel row.
                .pos(left + 12, top + 56 + (i * 18)).size(PANEL_W - 24, 16).build()
            addRenderableWidget(btn)
            channelDropdownButtons.add(btn)
        }
        channelDropdownOpen = true
    }

    private fun closeChannelDropdown() {
        for (b in channelDropdownButtons) removeWidget(b)
        channelDropdownButtons.clear()
        channelDropdownOpen = false
        channelBox.visible = true
    }

    /**
     * Scan client-loaded [ComputerBlockEntity]s within [NEARBY_RADIUS] blocks
     * of this monitor, return the distinct set of their channels (sorted).
     */
    private fun nearbyChannels(): List<String> {
        val level = Minecraft.getInstance().level ?: return emptyList()
        val out = sortedSetOf<String>()
        val r = NEARBY_RADIUS
        for (dx in -r..r) for (dz in -r..r) for (dy in -r..r) {
            val p = pos.offset(dx, dy, dz)
            val be = level.getBlockEntity(p) as? ComputerBlockEntity ?: continue
            out.add(be.channelId)
        }
        return out.toList()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        const val PANEL_W = 200
        const val PANEL_H = 110
        const val NEARBY_RADIUS = 32
    }
}
