package com.brewingcoder.oc2.platform.parts

/**
 * Tiny string codec for [Part.options]. Format: `key1=value1;key2=value2`.
 * Keys + values may not contain `=` or `;` — `_` substitution on encode keeps
 * the format stable (and the inputs are programmer-controlled, so no escaping
 * complexity needed).
 *
 * Used both for NBT persistence (single string field) and the network payload
 * (single STRING_UTF8 codec instead of a custom map codec).
 */
internal object PartOptionsCodec {
    fun encode(opts: Map<String, String>): String =
        opts.entries.joinToString(";") {
            "${it.key.sanitize()}=${it.value.sanitize()}"
        }

    fun decode(s: String): Map<String, String> {
        if (s.isBlank()) return emptyMap()
        val out = mutableMapOf<String, String>()
        for (pair in s.split(';')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            out[pair.substring(0, idx)] = pair.substring(idx + 1)
        }
        return out
    }

    private fun String.sanitize(): String = this.replace('=', '_').replace(';', '_')
}
