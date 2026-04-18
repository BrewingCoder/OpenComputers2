package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.network.SetChannelPayload
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor
import kotlin.math.max

/**
 * The Computer GUI. Opened by right-clicking a Computer block.
 *
 * v0 layout:
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ [⏻ power] [↻ reset]   Channel: [editbox          ]    │
 *   ├─────────────────────────────────────────────────────────┤
 *   │ OC2 v0.0.1                                              │
 *   │ No OS installed.                                        │
 *   │ Ready.                                                  │
 *   │ _                                                        │
 *   │                                                          │
 *   └─────────────────────────────────────────────────────────┘
 *
 * Channel edit sends [SetChannelPayload] on focus loss / Enter. Power and
 * reset are wired but no-op until the VM lands (R1).
 *
 * Full-window layout (not a fixed 256x256 GUI texture). Sized to roughly 80%
 * of the available game window.
 */
class ComputerScreen(
    private val be: ComputerBlockEntity,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.computer")) {

    private lateinit var channelBox: EditBox

    private val terminalLines = mutableListOf(
        "OC2 v0.0.1",
        "No OS installed.",
        "Ready.",
        "_",
    )

    override fun init() {
        super.init();

        // Layout
        val pad = 12
        val toolbarH = 24
        val contentW = (width * 0.85).toInt().coerceAtLeast(MIN_W)
        val contentH = (height * 0.80).toInt().coerceAtLeast(MIN_H)
        val left = (width - contentW) / 2
        val top = (height - contentH) / 2

        // Power button (top-left)
        addRenderableWidget(
            ImageButton(
                left + pad, top + pad - 2,
                ICON_SIZE, ICON_SIZE,
                POWER_SPRITES,
                { _ -> onPower() },
                Component.translatable("screen.${OpenComputers2.ID}.computer.power"),
            )
        )

        // Reset button
        addRenderableWidget(
            ImageButton(
                left + pad + ICON_SIZE + 4, top + pad - 2,
                ICON_SIZE, ICON_SIZE,
                RESET_SPRITES,
                { _ -> onReset() },
                Component.translatable("screen.${OpenComputers2.ID}.computer.reset"),
            )
        )

        // Channel label position is computed at render time; the EditBox sits to its right.
        val editBoxX = left + contentW - pad - CHANNEL_BOX_W
        channelBox = EditBox(
            font, editBoxX, top + pad - 2,
            CHANNEL_BOX_W, ICON_SIZE,
            Component.translatable("screen.${OpenComputers2.ID}.computer.channel"),
        ).also {
            it.value = be.channelId
            it.setMaxLength(SetChannelPayload.MAX_CHANNEL_LENGTH)
            it.setResponder(::onChannelEdited)
        }
        addRenderableWidget(channelBox)

        // Cache layout for render()
        terminalRect = Rect(
            left + pad,
            top + pad + toolbarH,
            contentW - pad * 2,
            contentH - pad * 2 - toolbarH,
        )
    }

    private var terminalRect: Rect = Rect(0, 0, 0, 0)

    private fun onPower() {
        OpenComputers2.LOGGER.info("[client] power button — no-op until VM lands")
        appendTerminalLine("(power button — VM not implemented yet)")
    }

    private fun onReset() {
        OpenComputers2.LOGGER.info("[client] reset button — no-op until VM lands")
        appendTerminalLine("(reset button — VM not implemented yet)")
    }

    /** Called whenever the channel EditBox value changes. We send the packet only when the
     *  user presses Enter or moves focus away — see [keyPressed] and [removed]. */
    private fun onChannelEdited(newValue: String) {
        // No-op intentionally; we send the update on Enter / unfocus to avoid
        // packet-spam-per-keystroke.
    }

    private fun sendChannelUpdate() {
        val current = channelBox.value.trim().ifBlank { ComputerBlockEntity.DEFAULT_CHANNEL }
        if (current == be.channelId) return
        OpenComputers2.LOGGER.info("[client] sending SetChannel for {} -> '{}'", be.blockPos, current)
        PacketDistributor.sendToServer(SetChannelPayload(be.blockPos, current))
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Enter inside the channel box → submit
        if (channelBox.isFocused && (keyCode == 257 /* ENTER */ || keyCode == 335 /* NUMPAD_ENTER */)) {
            sendChannelUpdate()
            channelBox.isFocused = false
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        // Send pending channel changes when the screen closes
        sendChannelUpdate()
        super.removed()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)

        // Frame: dark filled rect for the panel, terminal area filled black with green border
        graphics.fill(terminalRect.x - 2, terminalRect.y - 2,
            terminalRect.x + terminalRect.w + 2, terminalRect.y + terminalRect.h + 2,
            COLOR_TERMINAL_BORDER)
        graphics.fill(terminalRect.x, terminalRect.y,
            terminalRect.x + terminalRect.w, terminalRect.y + terminalRect.h,
            COLOR_TERMINAL_BG)

        // Channel label (to the left of the EditBox)
        val labelText = Component.literal("Channel:")
        val labelX = channelBox.x - font.width(labelText) - 4
        val labelY = channelBox.y + (channelBox.height - font.lineHeight) / 2
        graphics.drawString(font, labelText, labelX, labelY, COLOR_LABEL, false)

        // Terminal lines, top-to-bottom, 1px padding
        val tx = terminalRect.x + 6
        val ty = terminalRect.y + 6
        terminalLines.forEachIndexed { i, line ->
            val y = ty + i * (font.lineHeight + 2)
            if (y + font.lineHeight > terminalRect.y + terminalRect.h - 4) return@forEachIndexed
            graphics.drawString(font, line, tx, y, COLOR_TERMINAL_TEXT, false)
        }

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    /** Append a line to the terminal view (client-only; for stub feedback). */
    private fun appendTerminalLine(line: String) {
        terminalLines.add(line)
        // simple cap so we don't grow without bound
        while (terminalLines.size > MAX_TERMINAL_LINES) terminalLines.removeAt(0)
    }

    /** Don't pause the game when the GUI is open — we're a "casual interaction" UI. */
    override fun isPauseScreen(): Boolean = false

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    companion object {
        private const val MIN_W = 320
        private const val MIN_H = 180
        private const val ICON_SIZE = 20
        private const val CHANNEL_BOX_W = 140
        private const val MAX_TERMINAL_LINES = 256

        // CRT monitor color palette
        private const val COLOR_TERMINAL_BG = 0xFF000000.toInt()
        private const val COLOR_TERMINAL_BORDER = 0xFF0F4D0F.toInt()
        private const val COLOR_TERMINAL_TEXT = 0xFF33FF66.toInt()
        private const val COLOR_LABEL = 0xFFCCCCCC.toInt()

        private fun guiTex(name: String) =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/gui/$name.png")

        private val POWER_SPRITES = WidgetSprites(
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "widget/power"),
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "widget/power_highlighted"),
        )
        private val RESET_SPRITES = WidgetSprites(
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "widget/reset"),
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "widget/reset_highlighted"),
        )
    }
}
