package com.brewingcoder.oc2.platform.os.commands

import com.brewingcoder.oc2.platform.http.DefaultHttpFetcher
import com.brewingcoder.oc2.platform.http.FetchResult
import com.brewingcoder.oc2.platform.http.HttpFetcher
import com.brewingcoder.oc2.platform.http.NetworkFetchPolicy
import com.brewingcoder.oc2.platform.http.UrlAllowlist
import com.brewingcoder.oc2.platform.os.PathResolver
import com.brewingcoder.oc2.platform.os.ShellCommand
import com.brewingcoder.oc2.platform.os.ShellContext
import com.brewingcoder.oc2.platform.script.ScriptHost
import com.brewingcoder.oc2.platform.storage.MountPaths
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.nio.ByteBuffer

/**
 * `pastebin get <key> [outfile]` — fetch a public paste from pastebin.com.
 * `pastebin run <key>` — fetch + save to `/tmp/<key>.lua` + `run` it.
 *
 * `put` is deliberately NOT implemented: the write API requires an API key,
 * and this is a mod feature, not an account-attached operation. Users who
 * want to share scripts paste them manually on pastebin.com first.
 *
 * Output path for `get` defaults to `<key>.lua` in the shell's cwd. Users
 * can override with a second arg.
 *
 * Allowlist is enforced by [UrlAllowlist] — the key goes through [isSafeToken]
 * and the URL is built against a constant host. No way to coerce the command
 * into hitting an attacker-controlled server.
 */
class PastebinCommand(private val fetcher: HttpFetcher = DefaultHttpFetcher) : ShellCommand {
    override val name = "pastebin"
    override val summary = "fetch a pastebin script: pastebin get <key> [outfile] | pastebin run <key>"

    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (!NetworkFetchPolicy.allowPastebinFetch) {
            ctx.out.println("pastebin: disabled by server config (network.allowPastebinFetch)")
            return 1
        }
        if (args.isEmpty()) {
            ctx.out.println("usage: pastebin get <key> [outfile] | pastebin run <key>")
            return 2
        }
        return when (val sub = args[0]) {
            "get" -> get(args.drop(1), ctx)
            "run" -> runPaste(args.drop(1), ctx)
            else -> {
                ctx.out.println("pastebin: unknown subcommand '$sub' (expected: get, run)")
                2
            }
        }
    }

    private fun get(rest: List<String>, ctx: ShellContext): Int {
        if (rest.isEmpty()) {
            ctx.out.println("usage: pastebin get <key> [outfile]")
            return 2
        }
        val key = rest[0]
        val url = UrlAllowlist.pastebinRaw(key) ?: run {
            ctx.out.println("pastebin: '$key' is not a valid paste key (alphanumeric, up to 64 chars)")
            return 1
        }
        val defaultName = "$key.lua"
        val outArg = rest.getOrNull(1) ?: defaultName
        ctx.out.println("fetching $url …")
        val result = fetcher.fetch(url, NetworkFetchPolicy.fetchTimeoutSeconds, NetworkFetchPolicy.maxFetchBytes)
        return handleFetch(result, ctx, outArg)
    }

    private fun runPaste(rest: List<String>, ctx: ShellContext): Int {
        if (rest.isEmpty()) {
            ctx.out.println("usage: pastebin run <key>")
            return 2
        }
        val key = rest[0]
        val url = UrlAllowlist.pastebinRaw(key) ?: run {
            ctx.out.println("pastebin: '$key' is not a valid paste key")
            return 1
        }
        ctx.out.println("fetching $url …")
        val result = fetcher.fetch(url, NetworkFetchPolicy.fetchTimeoutSeconds, NetworkFetchPolicy.maxFetchBytes)
        val ok = (result as? FetchResult.Ok) ?: run {
            reportErr(result as FetchResult.Err, ctx)
            return 1
        }
        // Save to /tmp/<key>.lua, then delegate to `run`.
        val tmpPath = "tmp/$key.lua"
        val write = writeToMount(ctx.mount, tmpPath, ok.body, ctx)
        if (write != 0) return write
        ctx.out.println("saved ${ok.body.size} bytes to /$tmpPath")
        return RunDelegate.run(tmpPath, ctx)
    }
}

/**
 * `gist get <id> [file] [outfile]` — fetch a single file from a public gist.
 * `gist run <id> [file]` — fetch + save to `/tmp/<id>__<file>` + `run` it.
 *
 * Gists are multi-file. Without [file] the command picks the first `.lua`,
 * then `.js`, then any file — alphabetical within each tier. Explicit [file]
 * is a case-sensitive exact match.
 */
class GistCommand(private val fetcher: HttpFetcher = DefaultHttpFetcher) : ShellCommand {
    override val name = "gist"
    override val summary = "fetch a gist script: gist get <id> [file] [outfile] | gist run <id> [file]"

    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (!NetworkFetchPolicy.allowGistFetch) {
            ctx.out.println("gist: disabled by server config (network.allowGistFetch)")
            return 1
        }
        if (args.isEmpty()) {
            ctx.out.println("usage: gist get <id> [file] [outfile] | gist run <id> [file]")
            return 2
        }
        return when (val sub = args[0]) {
            "get" -> get(args.drop(1), ctx)
            "run" -> runGist(args.drop(1), ctx)
            else -> {
                ctx.out.println("gist: unknown subcommand '$sub' (expected: get, run)")
                2
            }
        }
    }

    private fun get(rest: List<String>, ctx: ShellContext): Int {
        if (rest.isEmpty()) {
            ctx.out.println("usage: gist get <id> [file] [outfile]")
            return 2
        }
        val id = rest[0]
        val fileArg = rest.getOrNull(1)
        val outArg = rest.getOrNull(2)
        val (picked, body) = resolveAndFetch(id, fileArg, ctx) ?: return 1
        val outPath = outArg ?: picked
        return writeToMount(ctx.mount, outPath, body, ctx).also {
            if (it == 0) ctx.out.println("saved ${body.size} bytes to /${resolvePath(ctx, outPath)} (from gist file '$picked')")
        }
    }

    private fun runGist(rest: List<String>, ctx: ShellContext): Int {
        if (rest.isEmpty()) {
            ctx.out.println("usage: gist run <id> [file]")
            return 2
        }
        val id = rest[0]
        val fileArg = rest.getOrNull(1)
        val (picked, body) = resolveAndFetch(id, fileArg, ctx) ?: return 1
        val tmpName = "${id}__${picked.replace(Regex("[^A-Za-z0-9._-]"), "_")}"
        val tmpPath = "tmp/$tmpName"
        val write = writeToMount(ctx.mount, tmpPath, body, ctx)
        if (write != 0) return write
        ctx.out.println("saved ${body.size} bytes to /$tmpPath (from gist file '$picked')")
        return RunDelegate.run(tmpPath, ctx)
    }

    /**
     * Fetch the gist metadata, pick the right file (by name or by tier), and
     * fetch that file's raw content. Returns (filename, body) or null on any
     * error (already reported to [ctx]).
     */
    private fun resolveAndFetch(id: String, fileArg: String?, ctx: ShellContext): Pair<String, ByteArray>? {
        val metaUrl = UrlAllowlist.gistApi(id) ?: run {
            ctx.out.println("gist: '$id' is not a valid gist id (alphanumeric, up to 64 chars)")
            return null
        }
        ctx.out.println("fetching $metaUrl …")
        val meta = fetcher.fetch(metaUrl, NetworkFetchPolicy.fetchTimeoutSeconds, NetworkFetchPolicy.maxFetchBytes)
        val metaOk = (meta as? FetchResult.Ok) ?: run { reportErr(meta as FetchResult.Err, ctx); return null }

        val files = try {
            GistMeta.parseFiles(String(metaOk.body, Charsets.UTF_8))
        } catch (e: Exception) {
            ctx.out.println("gist: malformed metadata: ${e.message ?: e::class.simpleName}")
            return null
        }
        if (files.isEmpty()) {
            ctx.out.println("gist: no files in gist $id")
            return null
        }
        val chosen = GistMeta.pickFile(files, fileArg) ?: run {
            ctx.out.println("gist: no file '${fileArg ?: "(auto)"}' in gist $id. Available: ${files.keys.sorted().joinToString()}")
            return null
        }
        val rawUrl = UrlAllowlist.gistRawOnAllowedHost(chosen.rawUrl) ?: run {
            ctx.out.println("gist: raw_url host not allowlisted: ${chosen.rawUrl}")
            return null
        }
        ctx.out.println("fetching $rawUrl …")
        val body = fetcher.fetch(rawUrl, NetworkFetchPolicy.fetchTimeoutSeconds, NetworkFetchPolicy.maxFetchBytes)
        val bodyOk = (body as? FetchResult.Ok) ?: run { reportErr(body as FetchResult.Err, ctx); return null }
        return chosen.filename to bodyOk.body
    }
}

/** Shared delegation to `RunCommand` — looks up the host map from the ctx's scriptRunner indirectly. */
internal object RunDelegate {
    /**
     * Launch a freshly-written script at [tmpPath] (relative to cwd). Uses the
     * hosts-by-extension map shared with [RunCommand] / [BgCommand]. Requires
     * the hosts to have been captured at [DefaultCommands.build] time — we
     * look them up via [PastebinCommand.sharedHosts], set by [DefaultCommands].
     */
    fun run(tmpPath: String, ctx: ShellContext): Int {
        val hosts = sharedHosts ?: run {
            ctx.out.println("pastebin/gist: script hosts not wired — is DefaultCommands.build() the entry point?")
            return 1
        }
        return ScriptStarter.start(listOf(tmpPath), ctx, hosts, foreground = true)
    }

    @Volatile
    internal var sharedHosts: Map<String, ScriptHost>? = null
}

/** Thin gist JSON projection — only the fields the command needs. */
internal object GistMeta {
    data class File(val filename: String, val rawUrl: String)

    fun parseFiles(json: String): Map<String, File> {
        val gson = com.google.gson.Gson()
        val root = gson.fromJson(json, com.google.gson.JsonObject::class.java) ?: return emptyMap()
        val filesNode = root["files"]?.let { if (it.isJsonObject) it.asJsonObject else null } ?: return emptyMap()
        val out = LinkedHashMap<String, File>()
        for ((name, v) in filesNode.entrySet()) {
            if (!v.isJsonObject) continue
            val obj = v.asJsonObject
            val raw = obj["raw_url"]?.takeIf { it.isJsonPrimitive }?.asString ?: continue
            val fname = obj["filename"]?.takeIf { it.isJsonPrimitive }?.asString ?: name
            out[fname] = File(fname, raw)
        }
        return out
    }

    /**
     * If [explicit] is given, case-sensitive exact match.
     * Otherwise: first `.lua` alphabetical, else first `.js` alphabetical,
     * else first file alphabetical.
     */
    fun pickFile(files: Map<String, File>, explicit: String?): File? {
        if (explicit != null) return files[explicit]
        val sorted = files.values.sortedBy { it.filename }
        sorted.firstOrNull { it.filename.endsWith(".lua", ignoreCase = true) }?.let { return it }
        sorted.firstOrNull { it.filename.endsWith(".js", ignoreCase = true) }?.let { return it }
        return sorted.firstOrNull()
    }
}

// ---- shared helpers ----

internal fun writeToMount(mount: WritableMount, path: String, body: ByteArray, ctx: ShellContext): Int {
    val resolved = try {
        PathResolver.resolve(ctx.cwd, path)
    } catch (e: StorageException) {
        ctx.out.println("error: ${e.message}")
        return 1
    }
    // Ensure parent dir exists (e.g. /tmp).
    val parent = MountPaths.parent(resolved)
    if (parent.isNotEmpty() && !mount.exists(parent)) {
        try {
            mount.makeDirectory(parent)
        } catch (e: StorageException) {
            ctx.out.println("error: could not create $parent: ${e.message}")
            return 1
        }
    }
    return try {
        mount.openForWrite(resolved).use { ch ->
            val buf = ByteBuffer.wrap(body)
            while (buf.hasRemaining()) ch.write(buf)
        }
        0
    } catch (e: StorageException) {
        ctx.out.println("error: ${e.message}")
        1
    }
}

internal fun resolvePath(ctx: ShellContext, path: String): String =
    try { PathResolver.resolve(ctx.cwd, path) } catch (_: StorageException) { path }

internal fun reportErr(err: FetchResult.Err, ctx: ShellContext) {
    val prefix = when (err.kind) {
        FetchResult.Err.Kind.DISABLED -> "disabled"
        FetchResult.Err.Kind.BAD_KEY -> "bad key"
        FetchResult.Err.Kind.HTTP_STATUS -> "http error"
        FetchResult.Err.Kind.TOO_LARGE -> "too large"
        FetchResult.Err.Kind.TIMEOUT -> "timeout"
        FetchResult.Err.Kind.NETWORK -> "network"
    }
    ctx.out.println("fetch failed ($prefix): ${err.message}")
}

internal fun handleFetch(result: FetchResult, ctx: ShellContext, outPath: String): Int {
    return when (result) {
        is FetchResult.Ok -> {
            val code = writeToMount(ctx.mount, outPath, result.body, ctx)
            if (code == 0) ctx.out.println("saved ${result.body.size} bytes to /${resolvePath(ctx, outPath)}")
            code
        }
        is FetchResult.Err -> {
            reportErr(result, ctx)
            1
        }
    }
}
