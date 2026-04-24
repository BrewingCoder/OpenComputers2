package com.brewingcoder.oc2.platform.script

import com.brewingcoder.oc2.platform.Position
import com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral

/**
 * Fake [MonitorPeripheral] for `ui_v1` tests. Captures every text+pixel op
 * as a simple string in [calls], letting tests assert on the draw sequence
 * a widget produced.
 *
 * Line-format is chosen for readability in failure diffs:
 *   `drawRect(10,20,100,30,0xFF121827)`
 *   `setFg(0xFFE6F0FF)`
 *   `write(Hello)`
 *
 * Not thread-safe — tests drive the monitor from one script thread at a time.
 */
class RecordingMonitor(
    override val location: Position = Position(0, 0, 0),
    private val pixelSize: Pair<Int, Int> = DEFAULT_PIXEL_SIZE,
    private val textSize: Pair<Int, Int> = DEFAULT_TEXT_SIZE,
) : MonitorPeripheral {

    val calls: MutableList<String> = mutableListOf()
    private val touchQueue: ArrayDeque<MonitorPeripheral.TouchEvent> = ArrayDeque()
    private var cursorCol: Int = 0
    private var cursorRow: Int = 0

    /** Synthetic touch — test code queues one of these to simulate a user right-click. */
    fun queueTouch(col: Int, row: Int, px: Int, py: Int, player: String = "tester") {
        touchQueue.addLast(MonitorPeripheral.TouchEvent(col, row, px, py, player))
    }

    override fun write(text: String) { calls.add("write($text)") }
    override fun setCursorPos(col: Int, row: Int) {
        cursorCol = col; cursorRow = row
        calls.add("setCursorPos($col,$row)")
    }
    override fun getCursorPos(): Pair<Int, Int> = Pair(cursorCol, cursorRow)
    override fun clear() { calls.add("clear()") }
    override fun getSize(): Pair<Int, Int> = textSize
    override fun setForegroundColor(color: Int) { calls.add("setFg(${hex(color)})") }
    override fun setBackgroundColor(color: Int) { calls.add("setBg(${hex(color)})") }
    override fun pollTouches(): List<MonitorPeripheral.TouchEvent> {
        val snap = touchQueue.toList(); touchQueue.clear(); return snap
    }
    override fun getPixelSize(): Pair<Int, Int> = pixelSize
    override fun clearPixels(argb: Int) { calls.add("clearPixels(${hex(argb)})") }
    override fun setPixel(x: Int, y: Int, argb: Int) { calls.add("setPixel($x,$y,${hex(argb)})") }
    override fun drawRect(x: Int, y: Int, w: Int, h: Int, argb: Int) {
        calls.add("drawRect($x,$y,$w,$h,${hex(argb)})")
    }
    override fun drawRectOutline(x: Int, y: Int, w: Int, h: Int, argb: Int, thickness: Int) {
        calls.add("drawRectOutline($x,$y,$w,$h,${hex(argb)},t=$thickness)")
    }
    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int, argb: Int) {
        calls.add("drawLine($x1,$y1->$x2,$y2,${hex(argb)})")
    }
    override fun drawGradientV(x: Int, y: Int, w: Int, h: Int, topArgb: Int, bottomArgb: Int) {
        calls.add("drawGradientV($x,$y,$w,$h,${hex(topArgb)}->${hex(bottomArgb)})")
    }
    override fun fillCircle(cx: Int, cy: Int, r: Int, argb: Int) {
        calls.add("fillCircle($cx,$cy,$r,${hex(argb)})")
    }
    override fun fillEllipse(cx: Int, cy: Int, rx: Int, ry: Int, argb: Int) {
        calls.add("fillEllipse($cx,$cy,$rx,$ry,${hex(argb)})")
    }
    override fun drawArc(cx: Int, cy: Int, rx: Int, ry: Int, thickness: Int, startDeg: Int, sweepDeg: Int, argb: Int) {
        calls.add("drawArc($cx,$cy,$rx,$ry,t=$thickness,$startDeg..${startDeg + sweepDeg},${hex(argb)})")
    }
    override fun drawItem(x: Int, y: Int, wPx: Int, hPx: Int, itemId: String) {
        calls.add("drawItem($x,$y,$wPx,$hPx,$itemId)")
    }
    override fun drawFluid(x: Int, y: Int, wPx: Int, hPx: Int, fluidId: String) {
        calls.add("drawFluid($x,$y,$wPx,$hPx,$fluidId)")
    }
    override fun drawChemical(x: Int, y: Int, wPx: Int, hPx: Int, chemicalId: String) {
        calls.add("drawChemical($x,$y,$wPx,$hPx,$chemicalId)")
    }
    override fun clearIcons() { calls.add("clearIcons()") }

    companion object {
        val DEFAULT_PIXEL_SIZE: Pair<Int, Int> = Pair(320, 240)
        val DEFAULT_TEXT_SIZE: Pair<Int, Int> = Pair(40, 20)
        private fun hex(argb: Int): String = "0x%08X".format(argb)
    }
}
