package com.brewingcoder.oc2.client.screen

import com.brewingcoder.oc2.OpenComputers2
import com.brewingcoder.oc2.block.ComputerBlockEntity
import com.brewingcoder.oc2.client.OC2ClientConfig
import com.brewingcoder.oc2.client.TerminalOutputDispatcher
import com.brewingcoder.oc2.network.RunCommandPayload
import com.brewingcoder.oc2.network.SetChannelPayload
import com.brewingcoder.oc2.network.TerminalOutputPayload
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ImageButton
import net.minecraft.client.gui.components.WidgetSprites
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.network.PacketDistributor

/**
 * The Computer GUI. Opened by right-clicking a Computer block.
 *
 * Input model: the channel EditBox at the top handles its own key events when
 * focused. When the channel box is NOT focused, printable characters and
 * control keys drive the terminal input line at the bottom. Enter submits:
 *   1. locally echoes `> <input>` to the buffer (immediate feedback)
 *   2. sends a [RunCommandPayload] to the server
 *   3. server runs the shell, replies with [TerminalOutputPayload]
 *   4. output lines appended to the buffer via [TerminalOutputDispatcher]
 *
 * Power and reset buttons are still no-op stubs — they'll light up when the
 * VM scheduler lands (R1 week 2+).
 */
class ComputerScreen(
    private val be: ComputerBlockEntity,
) : Screen(Component.translatable("screen.${OpenComputers2.ID}.computer")) {

    private lateinit var channelBox: EditBox

    /** Terminal output buffer (lines). Capped at [MAX_TERMINAL_LINES]. */
    private val terminalLines = mutableListOf(
        "OC2 v0.0.1 — diagnostic shell",
        "type 'help' for commands",
        "",
    )

    /** Current input line being typed. Submitted on Enter. */
    private var inputLine: String = ""

    /** Drag-select state in absolute (line, col) coordinates against [terminalLines]. */
    private data class TermPos(val line: Int, val col: Int) : Comparable<TermPos> {
        override fun compareTo(other: TermPos): Int =
            compareValuesBy(this, other, { it.line }, { it.col })
    }
    private var selectionAnchor: TermPos? = null
    private var selectionFocus: TermPos? = null

    /**
     * Scroll position, measured in rows above the bottom (newest line).
     *  - 0 = anchored to the bottom (default; auto-scrolls with new output)
     *  - N > 0 = N rows above the bottom; new output increments N to keep
     *    the user's view stable until they explicitly scroll back to bottom
     *  - clamped to maxScroll() so we never reveal "above the top"
     */
    private var scrollOffset: Int = 0

    /** Cached row count of the visible output area, set in [render]. */
    private var visibleOutputRows: Int = 1

    override fun init() {
        super.init()

        val pad = 12
        val toolbarH = 24
        val contentW = (width * 0.85).toInt().coerceAtLeast(MIN_W)
        val contentH = (height * 0.80).toInt().coerceAtLeast(MIN_H)
        val left = (width - contentW) / 2
        val top = (height - contentH) / 2

        addRenderableWidget(
            ImageButton(
                left + pad, top + pad - 2,
                ICON_SIZE, ICON_SIZE,
                POWER_SPRITES,
                { _ -> onPower() },
                Component.translatable("screen.${OpenComputers2.ID}.computer.power"),
            )
        )

        addRenderableWidget(
            ImageButton(
                left + pad + ICON_SIZE + 4, top + pad - 2,
                ICON_SIZE, ICON_SIZE,
                RESET_SPRITES,
                { _ -> onReset() },
                Component.translatable("screen.${OpenComputers2.ID}.computer.reset"),
            )
        )

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

        terminalRect = Rect(
            left + pad,
            top + pad + toolbarH,
            contentW - pad * 2,
            contentH - pad * 2 - toolbarH,
        )

        // Subscribe to output from our BE. Unregister in [removed].
        TerminalOutputDispatcher.register(be.blockPos, ::onTerminalOutput)
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

    private fun onChannelEdited(newValue: String) {
        // send on Enter/unfocus only (see [keyPressed], [removed])
    }

    private fun sendChannelUpdate() {
        val current = channelBox.value.trim().ifBlank { ComputerBlockEntity.DEFAULT_CHANNEL }
        if (current == be.channelId) return
        OpenComputers2.LOGGER.info("[client] sending SetChannel for {} -> '{}'", be.blockPos, current)
        PacketDistributor.sendToServer(SetChannelPayload(be.blockPos, current))
    }

    override fun charTyped(codePoint: Char, modifiers: Int): Boolean {
        if (channelBox.isFocused) return super.charTyped(codePoint, modifiers)
        if (codePoint.code < 0x20 || codePoint.code == 0x7F) return false
        if (inputLine.length >= RunCommandPayload.MAX_COMMAND_LENGTH) return true
        inputLine += codePoint
        return true
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        // Channel EditBox focused: Enter submits the channel change.
        if (channelBox.isFocused && (keyCode == KEY_ENTER || keyCode == KEY_NUMPAD_ENTER)) {
            sendChannelUpdate()
            channelBox.isFocused = false
            return true
        }
        if (channelBox.isFocused) return super.keyPressed(keyCode, scanCode, modifiers)

        // Cmd-V / Ctrl-V — paste. hasControlDown() is platform-aware (Cmd on macOS).
        if (keyCode == KEY_V && Screen.hasControlDown()) {
            pasteFromClipboard()
            return true
        }
        // Cmd-C / Ctrl-C — copy. Priority order:
        //   1. drag-selected range (if anchor != focus)
        //   2. current input line (if non-empty)
        //   3. entire terminal output buffer (fallback)
        if (keyCode == KEY_C && Screen.hasControlDown()) {
            val toCopy = extractSelectionText() ?: when {
                inputLine.isNotEmpty() -> inputLine
                else -> terminalLines.joinToString("\n")
            }
            if (toCopy.isNotEmpty()) {
                Minecraft.getInstance().keyboardHandler.clipboard = toCopy
            }
            return true
        }

        when (keyCode) {
            KEY_ENTER, KEY_NUMPAD_ENTER -> {
                submitCommand()
                return true
            }
            KEY_BACKSPACE -> {
                if (inputLine.isNotEmpty()) inputLine = inputLine.dropLast(1)
                return true
            }
            KEY_PAGE_UP -> {
                scrollBy(visibleOutputRows - 1)  // page-1 row of overlap, like a real terminal
                return true
            }
            KEY_PAGE_DOWN -> {
                scrollBy(-(visibleOutputRows - 1))
                return true
            }
            KEY_HOME -> {
                scrollOffset = maxScroll()
                return true
            }
            KEY_END -> {
                scrollOffset = 0
                return true
            }
            KEY_ESCAPE -> {
                // Preserve default close-on-escape UX
                return super.keyPressed(keyCode, scanCode, modifiers)
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        // Left click inside the terminal area starts a new selection.
        if (button == 0) {
            val pos = mouseToTermPos(mouseX, mouseY)
            if (pos != null) {
                selectionAnchor = pos
                selectionFocus = pos
                return true
            }
        }
        return super.mouseClicked(mouseX, mouseY, button)
    }

    override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, dragX: Double, dragY: Double): Boolean {
        // Extend the active selection while the left button is held.
        if (button == 0 && selectionAnchor != null) {
            mouseToTermPos(mouseX, mouseY)?.let {
                selectionFocus = it
                return true
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY)
    }

    /** Translate a GUI-coord click into an absolute (line, col) in [terminalLines], or null if outside the text grid. */
    private fun mouseToTermPos(mouseX: Double, mouseY: Double): TermPos? {
        val tx = terminalRect.x + 6
        val ty = terminalRect.y + 6
        if (mouseX < tx || mouseX > terminalRect.x + terminalRect.w ||
            mouseY < ty || mouseY > terminalRect.y + terminalRect.h) {
            return null
        }
        val rowH = TERMINAL.cellH + 1
        val cellW = TERMINAL.width("X").coerceAtLeast(1)
        val visibleRow = ((mouseY - ty) / rowH).toInt().coerceIn(0, visibleOutputRows - 1)
        val col = ((mouseX - tx) / cellW).toInt().coerceAtLeast(0)
        val total = terminalLines.size
        val endExclusive = (total - scrollOffset).coerceAtLeast(0)
        val startInclusive = (endExclusive - visibleOutputRows).coerceAtLeast(0)
        val absLine = (startInclusive + visibleRow).coerceIn(0, (total - 1).coerceAtLeast(0))
        return TermPos(absLine, col)
    }

    /**
     * Extract the text within the current selection, or null if none / collapsed
     * (anchor == focus — a click without drag). Multi-line selections include the
     * tail of the start line, full intermediate lines, and the head of the end line.
     */
    private fun extractSelectionText(): String? {
        val a = selectionAnchor ?: return null
        val b = selectionFocus ?: return null
        if (a == b) return null
        val (start, end) = if (a <= b) (a to b) else (b to a)
        if (terminalLines.isEmpty()) return null
        val sb = StringBuilder()
        for (line in start.line..end.line) {
            val text = terminalLines.getOrNull(line) ?: continue
            val from = if (line == start.line) start.col.coerceAtMost(text.length) else 0
            val to = if (line == end.line) end.col.coerceAtMost(text.length) else text.length
            if (to > from) sb.append(text, from, to)
            if (line < end.line) sb.append('\n')
        }
        return sb.toString().ifEmpty { null }
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, deltaX: Double, deltaY: Double): Boolean {
        // Only scroll when the cursor is over the terminal area — leaves
        // headroom for future widgets to consume scroll events themselves.
        if (mouseX < terminalRect.x || mouseX > terminalRect.x + terminalRect.w ||
            mouseY < terminalRect.y || mouseY > terminalRect.y + terminalRect.h) {
            return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY)
        }
        scrollBy(deltaY.toInt() * SCROLL_LINES_PER_NOTCH)
        return true
    }

    /** Positive [rows] scrolls toward older content (up); negative scrolls toward newer. */
    private fun scrollBy(rows: Int) {
        scrollOffset = (scrollOffset + rows).coerceIn(0, maxScroll())
    }

    private fun maxScroll(): Int = (terminalLines.size - visibleOutputRows).coerceAtLeast(0)

    /**
     * Paste clipboard contents into the input line. Multi-line pastes submit each
     * line as its own command in order — useful for dumping a batch of shell
     * commands to set up a session, or a multi-line [RunCommandPayload] sequence
     * pasted from an editor.
     */
    private fun pasteFromClipboard() {
        val raw = Minecraft.getInstance().keyboardHandler.clipboard ?: return
        if (raw.isEmpty()) return
        val sanitized = raw.replace("\r\n", "\n").replace("\r", "\n")
        val lines = sanitized.split("\n")
        // Lines 0..n-1 get submitted; the tail (possibly empty) fills inputLine.
        for (i in 0 until lines.size - 1) {
            inputLine += lines[i]
            submitCommand()  // clears inputLine + sends
        }
        // Append the final line fragment (no trailing newline → becomes live input).
        val trailing = lines.last()
        val room = (RunCommandPayload.MAX_COMMAND_LENGTH - inputLine.length).coerceAtLeast(0)
        inputLine += trailing.take(room)
    }

    private fun submitCommand() {
        val trimmed = inputLine
        inputLine = ""
        scrollOffset = 0  // jump to bottom — user just acted, show them the result
        if (trimmed.isBlank()) {
            // still show a blank prompt so the user sees their Enter landed
            appendTerminalLine(PROMPT)
            return
        }
        appendTerminalLine("$PROMPT$trimmed")
        PacketDistributor.sendToServer(RunCommandPayload(be.blockPos, trimmed))
    }

    /** Called from the network thread (actually the main client thread via enqueueWork). */
    private fun onTerminalOutput(payload: TerminalOutputPayload) {
        if (payload.clearFirst) {
            terminalLines.clear()
            scrollOffset = 0
        }
        for (line in payload.lines) appendTerminalLine(line)
    }

    override fun removed() {
        sendChannelUpdate()
        TerminalOutputDispatcher.unregister(be.blockPos)
        super.removed()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)

        graphics.fill(terminalRect.x - 2, terminalRect.y - 2,
            terminalRect.x + terminalRect.w + 2, terminalRect.y + terminalRect.h + 2,
            COLOR_TERMINAL_BORDER)
        graphics.fill(terminalRect.x, terminalRect.y,
            terminalRect.x + terminalRect.w, terminalRect.y + terminalRect.h,
            COLOR_TERMINAL_BG)

        val labelText = Component.literal("Channel:")
        val labelX = channelBox.x - font.width(labelText) - 4
        val labelY = channelBox.y + (channelBox.height - font.lineHeight) / 2
        graphics.drawString(font, labelText, labelX, labelY, COLOR_LABEL, false)

        val tx = terminalRect.x + 6
        val ty = terminalRect.y + 6
        val rowH = TERMINAL.cellH + 1
        val maxRows = ((terminalRect.h - 12) / rowH).coerceAtLeast(1)

        // Reserve the last row for the input prompt.
        val outputRows = (maxRows - 1).coerceAtLeast(1)
        visibleOutputRows = outputRows  // exposed to scroll/keyPress logic

        // Compute the slice of terminalLines visible right now, given scrollOffset
        // (rows above the bottom). scrollOffset=0 means the most recent outputRows lines.
        val total = terminalLines.size
        val endExclusive = (total - scrollOffset).coerceAtLeast(0)
        val startInclusive = (endExclusive - outputRows).coerceAtLeast(0)
        val visibleOutput = if (total == 0) emptyList()
                            else terminalLines.subList(startInclusive, endExclusive)

        // Selection highlight — drawn UNDER text so glyphs read on top of the selection color.
        renderSelectionHighlight(graphics, tx, ty, rowH, startInclusive)

        TERMINAL.drawLines(graphics, tx, ty, visibleOutput, COLOR_TERMINAL_TEXT)

        // Scrollbar — only when there's overflow.
        if (total > outputRows) drawScrollbar(graphics, total, outputRows)

        // Input line with blinking cursor — always at the bottom row.
        val inputY = ty + outputRows * rowH
        val cursorVisible = (System.currentTimeMillis() / 500L) % 2L == 0L
        val inputText = "$PROMPT$inputLine" + if (cursorVisible) CURSOR else " "
        TERMINAL.drawLine(graphics, tx, inputY, inputText, COLOR_TERMINAL_TEXT)

        // "scrolled up" hint — when not at bottom, append a faint marker so the
        // user knows their input will still go through but is hidden below
        if (scrollOffset > 0) {
            val marker = "[scrolled up — End to return]"
            val mx = terminalRect.x + terminalRect.w - TERMINAL.width(marker) - SCROLLBAR_WIDTH - 8
            TERMINAL.drawLine(graphics, mx, inputY, marker, COLOR_SCROLL_HINT)
        }

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    /** Paint translucent rectangles over selected cells, one per visible row of the selection. */
    private fun renderSelectionHighlight(graphics: GuiGraphics, tx: Int, ty: Int, rowH: Int, startInclusive: Int) {
        val a = selectionAnchor ?: return
        val b = selectionFocus ?: return
        if (a == b) return
        val (start, end) = if (a <= b) (a to b) else (b to a)
        val cellW = TERMINAL.width("X").coerceAtLeast(1)
        for (visibleRow in 0 until visibleOutputRows) {
            val absLine = startInclusive + visibleRow
            if (absLine < start.line || absLine > end.line) continue
            val text = terminalLines.getOrNull(absLine) ?: continue
            val startCol = if (absLine == start.line) start.col.coerceAtMost(text.length) else 0
            val endCol = if (absLine == end.line) end.col.coerceAtMost(text.length) else text.length
            if (endCol <= startCol) continue
            val rectX = tx + startCol * cellW
            val rectY = ty + visibleRow * rowH
            val rectW = (endCol - startCol) * cellW
            graphics.fill(rectX, rectY, rectX + rectW, rectY + rowH, COLOR_SELECTION)
        }
    }

    private fun drawScrollbar(graphics: GuiGraphics, total: Int, outputRows: Int) {
        val sx = terminalRect.x + terminalRect.w - SCROLLBAR_WIDTH - 2
        val sy = terminalRect.y + 4
        val sh = terminalRect.h - 8
        // Track
        graphics.fill(sx, sy, sx + SCROLLBAR_WIDTH, sy + sh, COLOR_SCROLL_TRACK)
        // Thumb
        val thumbHeight = (sh * outputRows / total).coerceAtLeast(SCROLLBAR_MIN_THUMB)
        val maxScroll = (total - outputRows).coerceAtLeast(1)
        val travel = (sh - thumbHeight).coerceAtLeast(0)
        val thumbY = sy + ((maxScroll - scrollOffset) * travel / maxScroll)
        graphics.fill(sx, thumbY, sx + SCROLLBAR_WIDTH, thumbY + thumbHeight, COLOR_SCROLL_THUMB)
    }

    private fun appendTerminalLine(line: String) {
        terminalLines.add(line)
        // Anchor user's view: if they're scrolled up reading, push their offset
        // by 1 so what they see stays put (otherwise new output would scroll their content).
        if (scrollOffset > 0) scrollOffset++
        // Drop oldest lines past the configured cap. If the user scrolled into
        // dropped territory, decrement so they don't end up scrolled past the top.
        val cap = OC2ClientConfig.maxTerminalLines.get()
        while (terminalLines.size > cap) {
            terminalLines.removeAt(0)
            if (scrollOffset > 0) scrollOffset--
        }
        // Final clamp — handles the edge case where the cap dropped enough lines
        // to push the user past the new top.
        scrollOffset = scrollOffset.coerceIn(0, maxScroll())
    }

    override fun isPauseScreen(): Boolean = false

    /**
     * Don't blur the world behind us. Default Screen.renderBackground applies a
     * blur + dim when opened in-game; we want a clean unmodified world view so
     * the player can still see what's around them while operating the Computer.
     */
    override fun renderBackground(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // intentionally empty — no blur, no dim
    }

    private data class Rect(val x: Int, val y: Int, val w: Int, val h: Int)

    companion object {
        private const val MIN_W = 320
        private const val MIN_H = 180
        private const val ICON_SIZE = 20
        private const val CHANNEL_BOX_W = 140

        private const val PROMPT = "> "
        private const val CURSOR = "_"

        private const val SCROLLBAR_WIDTH = 3
        private const val SCROLLBAR_MIN_THUMB = 8
        private const val SCROLL_LINES_PER_NOTCH = 3

        // GLFW key codes
        private const val KEY_ENTER = 257
        private const val KEY_NUMPAD_ENTER = 335
        private const val KEY_BACKSPACE = 259
        private const val KEY_ESCAPE = 256
        private const val KEY_V = 86
        private const val KEY_C = 67
        private const val KEY_PAGE_UP = 266
        private const val KEY_PAGE_DOWN = 267
        private const val KEY_HOME = 268
        private const val KEY_END = 269

        /**
         * Terminal text renderer — MSDF JetBrains Mono. Vector-quality at any
         * GUI scale, sharp edges. Atlas + metadata baked from JBMono Regular
         * via msdf-atlas-gen v1.3 (see `tools/build_msdf_atlas.sh`).
         *
         * Old bitmap renderer (Spleen 5x8) kept in `TerminalRenderer.kt` for
         * fallback/comparison; switch back by changing this constant.
         */
        private val TERMINAL = MsdfTerminalRenderer(pxPerEm = 5.5f)

        // Visual Studio Dark+ palette — the canonical not-so-black + not-so-white
        // editor theme. Reference: VS Code default dark theme tokens.
        private const val COLOR_TERMINAL_BG = 0xFF1E1E1E.toInt()       // editor.background
        private const val COLOR_TERMINAL_BORDER = 0xFF3F3F46.toInt()   // panel.border
        private const val COLOR_TERMINAL_TEXT = 0xFFD4D4D4.toInt()     // editor.foreground
        private const val COLOR_LABEL = 0xFFCCCCCC.toInt()             // foreground (toolbar labels)
        private const val COLOR_SCROLL_TRACK = 0xFF252526.toInt()      // scrollbar.background
        private const val COLOR_SCROLL_THUMB = 0xFF686868.toInt()      // scrollbarSlider.background
        private const val COLOR_SCROLL_HINT = 0xFF858585.toInt()       // descriptionForeground
        private const val COLOR_SELECTION = 0xAA264F78.toInt()         // editor.selectionBackground (translucent)

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
