package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.network.OpenCrafterMenuPayload
import com.brewingcoder.oc2.network.UpdatePartConfigPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
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
    private val initialAccessSide: String,
    private val initialOptions: Map<String, String>,
    private val initialData: String,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.part_config")) {

    private lateinit var labelBox: EditBox
    private lateinit var channelBox: EditBox
    private lateinit var dataBox: MultiLineEditBox

    /** Channel dropdown — buttons created on demand, removed when the dropdown closes. */
    private val channelDropdownButtons: MutableList<Button> = mutableListOf()
    private var channelDropdownOpen: Boolean = false

    /** Side dropdown — same pattern as channel dropdown. */
    private val sideDropdownButtons: MutableList<Button> = mutableListOf()
    private var sideDropdownOpen: Boolean = false

    /** Current accessSide selection. Cycle button mutates; Save sends it. */
    private var accessSide: String = initialAccessSide

    /** Mutable per-kind options. Live edits write here; Save serializes to payload. */
    private val options: MutableMap<String, String> = initialOptions.toMutableMap()

    /** Reference to invert toggle (redstone only) so we can update its label on click. */
    private var invertButton: Button? = null

    /** Reference to side-cycle button so we can update its label on cycle. Null when not shown. */
    private var sideButton: Button? = null

    /** Which kinds get a side picker. Capability-backed parts only — redstone/block don't care. */
    private val sideAware: Boolean get() = kind in setOf("inventory", "fluid", "energy")

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
            Button.builder(Component.literal("▼"), { toggleChannelDropdown() })
                .pos(left + PANEL_W - 28, top + 76)
                .size(18, 18)
                .build()
        )

        // Row 3: Side selector (only for capability-backed parts where the
        // side matters — sided IItemHandler/IFluidHandler/IEnergyStorage).
        // One button shows current selection; clicking opens a dropdown of all
        // 7 options (auto + 6 directions).
        if (sideAware) {
            val btn = Button.builder(Component.literal(sideButtonLabel())) { _ -> toggleSideDropdown() }
                .pos(left + 12, top + 116)
                .size(PANEL_W - 24, 18)
                .build()
            sideButton = btn
            addRenderableWidget(btn)
        }

        // Per-kind options. Each kind gets its own block of widgets here.
        // Today: redstone has an "Inverted" toggle, crafter has a "Recipes…"
        // launcher that opens the 18-card slot menu.
        if (kind == "crafter" || kind == "machine_crafter") {
            addRenderableWidget(
                Button.builder(Component.translatable("screen.${OpenComputers2.ID}.part_config.recipes")) { _ ->
                    PacketDistributor.sendToServer(OpenCrafterMenuPayload(pos, face))
                    // Server openMenu sends the open packet → client switches Screen.
                }
                    .pos(left + 12, top + 116)
                    .size(PANEL_W - 24, 18)
                    .build()
            )
        }
        if (kind == "redstone") {
            val btn = Button.builder(Component.literal(invertButtonLabel())) { _ -> toggleInverted() }
                .pos(left + 12, top + 116)
                .size(PANEL_W - 24, 18)
                .build()
            invertButton = btn
            addRenderableWidget(btn)
        }

        // Row 4: free-form `data` blob — multi-line. The engine never parses
        // this; scripts read it as `peripheral.data` and define their own
        // grammar (see `examples/stock.lua`).
        dataBox = MultiLineEditBox(
            font, left + 12, top + DATA_BOX_Y,
            PANEL_W - 24, DATA_BOX_H,
            Component.translatable("screen.${OpenComputers2.ID}.part_config.data_placeholder"),
            Component.literal("data"),
        )
        dataBox.setCharacterLimit(UpdatePartConfigPayload.MAX_DATA_LENGTH)
        dataBox.setValue(initialData)
        addRenderableWidget(dataBox)

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

    private fun invertButtonLabel(): String =
        "Inverted: ${if (options["inverted"] == "true") "ON" else "off"}"

    private fun toggleInverted() {
        val now = options["inverted"] == "true"
        options["inverted"] = if (now) "false" else "true"
        invertButton?.message = Component.literal(invertButtonLabel())
    }

    private fun sideButtonLabel(): String {
        val readable = if (accessSide.isBlank()) "auto" else accessSide
        return "Side: ${readable.uppercase()}  ▼"
    }

    private fun toggleSideDropdown() {
        if (sideDropdownOpen) closeSideDropdown() else openSideDropdown()
    }

    private fun openSideDropdown() {
        if (channelDropdownOpen) closeChannelDropdown()
        // Hide overlapping EditBoxes — vanilla widget rendering lets their
        // text bleed through dropdown button backgrounds. Restored on close.
        labelBox.visible = false
        channelBox.visible = false
        dataBox.visible = false
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        val options = listOf("auto", "north", "south", "east", "west", "up", "down")
        // Pop ABOVE the side row so we don't overrun the Save/Cancel buttons.
        for ((i, opt) in options.withIndex()) {
            val btn = Button.builder(Component.literal(opt.uppercase())) { _ ->
                accessSide = if (opt == "auto") "" else opt
                sideButton?.message = Component.literal(sideButtonLabel())
                closeSideDropdown()
            }
                .pos(left + 12, top + 96 - (i * 18))
                .size(PANEL_W - 24, 16)
                .build()
            addRenderableWidget(btn)
            sideDropdownButtons.add(btn)
        }
        sideDropdownOpen = true
    }

    private fun closeSideDropdown() {
        for (b in sideDropdownButtons) removeWidget(b)
        sideDropdownButtons.clear()
        sideDropdownOpen = false
        labelBox.visible = true
        channelBox.visible = true
        dataBox.visible = true
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        // Texture is 200x170 with the title divider line already baked in. Blit
        // its native size at the top, then fill the remainder of the panel with
        // its solid grey so the data field has a coherent backdrop.
        graphics.blit(BG_TEXTURE, left, top, 0f, 0f, PANEL_W, BG_TEXTURE_H, PANEL_W, BG_TEXTURE_H)
        if (PANEL_H > BG_TEXTURE_H) {
            graphics.fill(left, top + BG_TEXTURE_H, left + PANEL_W, top + PANEL_H, 0xFFC6C6C6.toInt())
        }
        graphics.drawString(font, "$kind  ·  ${face.serializedName}", left + 12, top + 12, 0xFFFFFFFF.toInt(), false)
        graphics.drawString(font, "name:",    left + 12, top + 26, 0xFFC0C0C0.toInt(), false)
        graphics.drawString(font, "channel:", left + 12, top + 66, 0xFFC0C0C0.toInt(), false)
        if (sideAware) graphics.drawString(font, "access side:", left + 12, top + 106, 0xFFC0C0C0.toInt(), false)
        graphics.drawString(font, "data:",    left + 12, top + DATA_BOX_Y - 10, 0xFFC0C0C0.toInt(), false)
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
            UpdatePartConfigPayload(
                pos, face, labelBox.value, channelBox.value, accessSide,
                com.brewingcoder.oc2.platform.parts.PartOptionsCodec.encode(options),
                dataBox.value,
            )
        )
        onClose()
    }

    /** Toggle the nearby-channel dropdown beneath the channel field. */
    private fun toggleChannelDropdown() {
        if (channelDropdownOpen) {
            closeChannelDropdown()
        } else {
            openChannelDropdown()
        }
    }

    private fun openChannelDropdown() {
        if (sideDropdownOpen) closeSideDropdown()
        labelBox.visible = false
        channelBox.visible = false
        dataBox.visible = false
        val left = (width - PANEL_W) / 2
        val top = (height - PANEL_H) / 2
        val channels = nearbyChannels()
        val items = if (channels.isEmpty()) listOf("(none nearby)") else channels
        for ((i, ch) in items.withIndex()) {
            val isPlaceholder = channels.isEmpty()
            val btn = Button.builder(Component.literal(ch)) { _ ->
                if (!isPlaceholder) channelBox.value = ch
                closeChannelDropdown()
            }
                // Pop list ABOVE the channel row so it doesn't collide with the
                // side row below — Y goes upward from there.
                .pos(left + 12, top + 56 - (i * 18))
                .size(PANEL_W - 24, 16)
                .build()
            addRenderableWidget(btn)
            channelDropdownButtons.add(btn)
        }
        channelDropdownOpen = true
    }

    private fun closeChannelDropdown() {
        for (b in channelDropdownButtons) removeWidget(b)
        channelDropdownButtons.clear()
        channelDropdownOpen = false
        labelBox.visible = true
        channelBox.visible = true
        dataBox.visible = true
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
        // Native height of the bundled bg texture (`part_config.png`).
        // We blit it 1:1 at the top and fill the rest of the panel with its
        // solid backdrop colour, so growing PANEL_H doesn't stretch the title
        // divider.
        const val BG_TEXTURE_H = 170
        const val DATA_BOX_Y = 148
        const val DATA_BOX_H = 35
        // Panel height: covers all rows + the data box + Save/Cancel.
        // (DATA_BOX_Y + DATA_BOX_H = 216, plus 36 of footer = 252).
        const val PANEL_H = 252
        const val NEARBY_RADIUS = 32
        val BG_TEXTURE: ResourceLocation =
            ResourceLocation.fromNamespaceAndPath(OpenComputers2.ID, "textures/gui/part_config.png")
    }
}
