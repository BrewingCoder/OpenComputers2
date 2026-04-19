package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.network.LabelPartPayload
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
 * installed adapter face. R1 ships only the **label** field; the layout +
 * payload + screen lifecycle are designed for additional fields (redstone
 * mode, inventory side filter, fluid throughput cap, etc.) to slot in
 * without rewriting the framework.
 *
 * Visual: PNG-backed panel ([BG_TEXTURE]) sized at [PANEL_W] × [PANEL_H],
 * centered on screen. Save button sends [LabelPartPayload] to the server;
 * close button discards.
 *
 * Server-side validation in [LabelPartPayload.handle] cleans the label
 * (alphanumeric + underscore only, 32 chars max). Empty input falls back
 * to the auto-generated name.
 */
class PartConfigScreen(
    private val pos: BlockPos,
    private val face: Direction,
    private val kind: String,
    private val initialLabel: String,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.part_config")) {

    private lateinit var labelBox: EditBox

    override fun init() {
        super.init()
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2

        // Label edit row
        labelBox = EditBox(font, left + 12, top + 38, PANEL_W - 24, 18, Component.literal("label"))
        labelBox.setMaxLength(LabelPartPayload.MAX_LABEL_LENGTH)
        labelBox.value = initialLabel
        labelBox.setFocused(true)
        addRenderableWidget(labelBox)

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

        setInitialFocus(labelBox)
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        // PNG panel
        graphics.blit(BG_TEXTURE, left, top, 0f, 0f, PANEL_W, PANEL_H, PANEL_W, PANEL_H)
        // Title text — kind + face
        val title = "$kind  ·  ${face.serializedName}"
        graphics.drawString(font, title, left + 12, top + 12, 0xFFFFFF, false)
        graphics.drawString(font, "name:", left + 12, top + 28, 0xC0C0C0, false)
        super.render(graphics, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Enter saves when label box is focused. ESC handled by Screen default.
        if (keyCode == 257 /* ENTER */ && labelBox.isFocused) {
            saveAndClose()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    private fun saveAndClose() {
        PacketDistributor.sendToServer(LabelPartPayload(pos, face, labelBox.value))
        onClose()
    }

    /** Pause the game in single-player while this screen is open (vanilla GUI behavior). */
    override fun isPauseScreen(): Boolean = false

    companion object {
        const val PANEL_W = 200
        const val PANEL_H = 90
        val BG_TEXTURE: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/gui/part_config.png")
    }
}
