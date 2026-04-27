package com.brewingcoder.oc2.platform.os

/**
 * Parser for `/startup.cfg` — the per-Computer boot autorun manifest.
 *
 * Format (intentionally close to fstab/systemd: human-edited, line-oriented):
 *   - One entry per line. The first token is the script path; the rest are
 *     passed through as args (Lua `arg` / JS `arguments`).
 *   - Lines starting with `#` are comments (leading whitespace tolerated).
 *   - Blank lines are skipped.
 *   - Tokenization is shell-style — see [Tokenizer]. Use double or single
 *     quotes for paths/args containing spaces.
 *
 * Every entry runs as a **background** script (`bg`) on Computer load.
 * Foreground startup is intentionally not supported — startup-time scripts
 * shouldn't fight the open shell for the terminal. Use `fg <pid>` later if
 * you want to inspect output.
 *
 * Rule D: pure platform code, no MC dependency. Tested in [StartupConfigTest].
 */
object StartupConfig {

    /**
     * One line of `startup.cfg`. [command] is the script path (e.g.
     * `reactor_ui.lua` or `subdir/foo.js`); [args] are pass-through args.
     */
    data class Entry(val command: String, val args: List<String>) {
        /** Reconstructed shell-style invocation suitable for `bg <invocation>`. */
        val invocation: String
            get() = (listOf(command) + args).joinToString(" ") { quoteIfNeeded(it) }

        private fun quoteIfNeeded(s: String): String =
            if (s.any { it.isWhitespace() || it == '"' || it == '\'' }) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
            else s
    }

    /**
     * Parse the contents of a `startup.cfg` file. Always returns a list — never
     * throws — so a malformed line just becomes whatever the [Tokenizer] makes
     * of it (the eventual `bg` command will surface the real error).
     */
    fun parse(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        for (rawLine in text.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val tokens = Tokenizer.tokenize(rawLine)
            if (tokens.isEmpty()) continue
            out.add(Entry(tokens[0], tokens.drop(1)))
        }
        return out
    }
}
