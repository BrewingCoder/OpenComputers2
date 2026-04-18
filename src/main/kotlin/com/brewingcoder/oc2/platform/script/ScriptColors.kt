package com.brewingcoder.oc2.platform.script

/**
 * Standard 16-color palette exposed to scripts as the `colors` global.
 * Hex values match CC:Tweaked's defaults (so ports of CC:T scripts pick up
 * the same look) but stored as opaque ARGB ints so they round-trip through
 * [com.brewingcoder.oc2.platform.peripheral.MonitorPeripheral.setForegroundColor]
 * without further encoding.
 *
 * Naming: lowercase camelCase matches CC:T (`colors.lightBlue` not `LIGHT_BLUE`).
 * Both Lua and JS bindings expose the same names.
 */
object ScriptColors {
    val PALETTE: Map<String, Int> = mapOf(
        "white"     to 0xFFF0F0F0.toInt(),
        "orange"    to 0xFFF2B233.toInt(),
        "magenta"   to 0xFFE57FD8.toInt(),
        "lightBlue" to 0xFF99B2F2.toInt(),
        "yellow"    to 0xFFDEDE6C.toInt(),
        "lime"      to 0xFF7FCC19.toInt(),
        "pink"      to 0xFFF2B2CC.toInt(),
        "gray"      to 0xFF4C4C4C.toInt(),
        "lightGray" to 0xFF999999.toInt(),
        "cyan"      to 0xFF4C99B2.toInt(),
        "purple"    to 0xFFB266E5.toInt(),
        "blue"      to 0xFF3366CC.toInt(),
        "brown"     to 0xFF7F664C.toInt(),
        "green"     to 0xFF57A64E.toInt(),
        "red"       to 0xFFCC4C4C.toInt(),
        "black"     to 0xFF111111.toInt(),
    )
}
