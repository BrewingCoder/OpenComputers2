package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.network.UpdatePartConfigPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor

/**
 * Per-part configuration screen, opened by empty-hand right-click on an
 * installed adapter face. R1 ships:
 *   - **label**   — per-part name; visible to scripts as `peripheral.find().name`
 *   - **channel** — per-adapter wifi channel (shared by every part on this adapter)
 *
 * UX details:
 *   - Label and channel are free text; restricted server-side to alphanumeric+_.
 *   - "▼" button next to channel shows a dropdown of distinct channels of any
 *     [ComputerBlockEntity] within [NEARBY_RADIUS] blocks. Click one to populate
 *     the channel field. Players can still type a custom channel — the dropdown
 *     is a discovery aid, not a constraint.
 *   - Save fires one [UpdatePartConfigPayload]; server splits into relabelPart +
 *     setChannel.
 *
 * The whole layout + payload + screen lifecycle is designed for additional
 * fields (redstone mode, inventory side filter, fluid throughput cap, etc.)
 * to slot in without rewriting the framework.
 */
class PartConfigScreen(
    private val pos: BlockPos,
    private val face: Direction,
    private val kind: String,
    private val initialLabel: String,
    private val initialChannel: String,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.part_config")) {

    private lateinit var labelBox: EditBox
    private lateinit var channelBox: EditBox

    /** Dropdown buttons — created on demand, removed when the dropdown closes. */
    private val dropdownButtons: MutableList<Button> = mutableListOf()
    private var dropdownOpen: Boolean = false

    override fun init() {
        super.init()
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2

        // Row 1: label
        labelBox = EditBox(font, left + 12, top + 36, PANEL_W - 24, 18, Component.literal("label"))
        labelBox.setMaxLength(UpdatePartConfigPayload.MAX_LABEL_LENGTH)
        labelBox.value = initialLabel
        addRenderableWidget(labelBox)

        // Row 2: channel + dropdown chevron
        channelBox = EditBox(font, left + 12, top + 76, PANEL_W - 44, 18, Component.literal("channel"))
        channelBox.setMaxLength(UpdatePartConfigPayload.MAX_CHANNEL_LENGTH)
        channelBox.value = initialChannel
        addRenderableWidget(channelBox)
        addRenderableWidget(
            Button.builder(Component.literal("▼"), { toggleDropdown() })
                .pos(left + PANEL_W - 28, top + 76)
                .size(18, 18)
                .build()
        )

        // Save / Cancel
        val btnY = top + PANEL_H - 28
        addRenderableWidget(
            Button.builder(Component.literal("Save"), { saveAndClose() })
                .pos(left + 12, btnY)
                .size(60, 20)
                .build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Cancel"), { onClose() })
                .pos(left + PANEL_W - 72, btnY)
                .size(60, 20)
                .build()
        )

        labelBox.setFocused(true)
        setInitialFocus(labelBox)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        graphics.blit(BG_TEXTURE, left, top, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H)
        graphics.drawString(font, "$kind  ·  ${face.serializedName}", left + 12, top + 12, 0xFFFFFF, false)
        graphics.drawString(font, "name:",    left + 12, top + 26, 0xC0C0C0, false)
        graphics.drawString(font, "channel:", left + 12, top + 66, 0xC0C0C0, false)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == 257 /* ENTER */ && (labelBox.isFocused || channelBox.isFocused)) {
            saveAndClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun saveAndClose() {
        PacketDistributor.sendToServer(
            UpdatePartConfigPayload(pos, face, labelBox.value, channelBox.value)
        )
        onClose()
    }

    /** Toggle the nearby-channel dropdown beneath the channel field. */
    private fun toggleDropdown() {
        if (dropdownOpen) {
            closeDropdown()
        } else {
            openDropdown()
        }
    }

    private fun openDropdown() {
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        val channels = nearbyChannels()
        val items = if (channels.isEmpty()) listOf("(none nearby)") else channels
        for ((i, ch) in items.withIndex()) {
            val isPlaceholder = channels.isEmpty()
            val btn = Button.builder(Component.literal(ch)) { _ ->
                if (!isPlaceholder) channelBox.value = ch
                closeDropdown()
            }
                .pos(left + 12, top + 96 + (i * 18))
                .size(PANEL_W - 24, 16)
                .build()
            addRenderableWidget(btn)
            dropdownButtons.add(btn)
        }
        dropdownOpen = true
    }

    private fun closeDropdown() {
        for (b in dropdownButtons) {
            removeWidget(b)
        }
        dropdownButtons.clear()
        dropdownOpen = false
    }

    /**
     * Scan client-loaded [ComputerBlockEntity]s within [NEARBY_RADIUS] blocks
     * of this adapter, return the distinct set of their channels (sorted).
     * Cheap — happens only when the dropdown is opened.
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
        const val PANEL_H = 140
        const val NEARBY_RADIUS = 32
        val BG_TEXTURE: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/gui/part_config.png")
    }
}
