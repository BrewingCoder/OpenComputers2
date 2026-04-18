package com.brewingcoder.oc2.platform.os

/**
 * Quote-aware whitespace tokenizer. Handles double-quoted strings (with
 * backslash escapes for `"` and `\`) and single-quoted strings (literal, no
 * escapes — same as POSIX sh).
 *
 * Examples:
 *   `echo hello world`         -> [echo, hello, world]
 *   `echo "hello world"`       -> [echo, hello world]
 *   `write foo "line one"`     -> [write, foo, line one]
 *   `echo "she said \"hi\""`   -> [echo, she said "hi"]
 *
 * Pipes/redirects/heredocs are deliberately NOT supported — those belong to
 * the script-VM-hosted shell that replaces this kernel in R1 week 2+.
 */
object Tokenizer {

    fun tokenize(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var hasContent = false
        var state = State.NORMAL
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when (state) {
                State.NORMAL -> when {
                    isShellWhitespace(c) -> {
                        if (hasContent) { out.add(cur.toString()); cur.clear(); hasContent = false }
                    }
                    c == '"'  -> { state = State.IN_DQUOTE; hasContent = true }
                    c == '\'' -> { state = State.IN_SQUOTE; hasContent = true }
                    else -> { cur.append(c); hasContent = true }
                }
                State.IN_DQUOTE -> when (c) {
                    '"' -> state = State.NORMAL
                    '\\' -> {
                        if (i + 1 < input.length) { i++; cur.append(input[i]) }
                    }
                    else -> cur.append(c)
                }
                State.IN_SQUOTE -> when (c) {
                    '\'' -> state = State.NORMAL
                    else -> cur.append(c)
                }
            }
            i++
        }
        if (hasContent) out.add(cur.toString())
        return out
    }

    /**
     * Treat as whitespace:
     *   - everything Java considers whitespace (ASCII space, tabs, newlines, etc.)
     *   - Unicode non-breaking spaces — U+00A0, U+2007, U+202F — which Java
     *     explicitly EXCLUDES but the macOS clipboard sometimes injects when
     *     pasting from rich-text apps. Without this, `> write foo` with an NBSP
     *     would tokenize as one opaque word and fail "command not found".
     */
    private fun isShellWhitespace(c: Char): Boolean =
        c.isWhitespace() || c == '\u00A0' || c == '\u2007' || c == '\u202F'

    private enum class State { NORMAL, IN_DQUOTE, IN_SQUOTE }
}
