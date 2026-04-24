package com.brewingcoder.oc2.platform.os.commands

import com.brewingcoder.oc2.platform.os.PathResolver
import com.brewingcoder.oc2.platform.os.Shell
import com.brewingcoder.oc2.platform.os.ShellCommand
import com.brewingcoder.oc2.platform.os.ShellContext
import com.brewingcoder.oc2.platform.peripheral.Peripheral
import com.brewingcoder.oc2.platform.script.CobaltLuaHost
import com.brewingcoder.oc2.platform.script.RhinoJSHost
import com.brewingcoder.oc2.platform.script.ScriptEnv
import com.brewingcoder.oc2.platform.script.ScriptHost
import com.brewingcoder.oc2.platform.os.ShellOutput
import com.brewingcoder.oc2.platform.storage.MountPaths
import com.brewingcoder.oc2.platform.storage.StorageException
import com.brewingcoder.oc2.platform.storage.WritableMount
import java.nio.ByteBuffer

/**
 * Built-in shell commands for the Tier-1 diagnostic shell. These get retired
 * (or moved into ROM scripts) when the script-VM-hosted shell takes over in
 * R1 week 2+. For now they're the entire userspace.
 *
 * Use [DefaultCommands.build] to instantiate the full default [Shell] with all
 * commands registered.
 */
object DefaultCommands {
    fun build(): Shell {
        val luaHost: ScriptHost = CobaltLuaHost()
        val jsHost: ScriptHost = RhinoJSHost()
        val hosts = mapOf("lua" to luaHost, "js" to jsHost)
        // pastebin/gist `run` subcommand delegates to ScriptStarter; it reaches the
        // hosts map via this shared slot. Written once per build().
        RunDelegate.sharedHosts = hosts
        val core: List<ShellCommand> = listOf(
            EchoCommand(), PwdCommand(), CdCommand(), LsCommand(),
            MkdirCommand(), RmCommand(), CatCommand(), WriteCommand(),
            ClearCommand(), IdCommand(), DfCommand(),
            RunCommand(hosts), BgCommand(hosts),
            PsCommand(), JobsCommand(), FgCommand(), KillCommand(), TailCommand(),
            PastebinCommand(), GistCommand(),
        )
        val all = core.toMutableList<ShellCommand>()
        all.add(HelpCommand { all })  // help references the full set
        return Shell(all)
    }
}

class HelpCommand(private val all: () -> Collection<ShellCommand>) : ShellCommand {
    override val name = "help"
    override val summary = "list available commands"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        ctx.out.println("commands:")
        for (cmd in all().sortedBy { it.name }) {
            ctx.out.println("  ${cmd.name.padEnd(8)} ${cmd.summary}")
        }
        return 0
    }
}

class EchoCommand : ShellCommand {
    override val name = "echo"
    override val summary = "print arguments"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        ctx.out.println(args.joinToString(" "))
        return 0
    }
}

class PwdCommand : ShellCommand {
    override val name = "pwd"
    override val summary = "print working directory"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        ctx.out.println("/${ctx.cwd}")
        return 0
    }
}

class CdCommand : ShellCommand {
    override val name = "cd"
    override val summary = "change directory"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        // Bare `cd` jumps to root — no $HOME concept here, root is the closest analogue.
        if (args.isEmpty()) { ctx.setCwd(""); return 0 }
        val target = args[0]
        val resolved = try {
            PathResolver.resolve(ctx.cwd, target)
        } catch (e: StorageException) {
            ctx.out.println("cd: ${e.message}")
            return 1
        }
        if (resolved.isNotEmpty()) {
            if (!ctx.mount.exists(resolved)) {
                ctx.out.println("cd: no such directory: $target")
                return 1
            }
            if (!ctx.mount.isDirectory(resolved)) {
                ctx.out.println("cd: not a directory: $target")
                return 1
            }
        }
        ctx.setCwd(resolved)
        return 0
    }
}

class LsCommand : ShellCommand {
    override val name = "ls"
    override val summary = "list directory contents (long form)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        val target = if (args.isEmpty()) ctx.cwd else try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("ls: ${e.message}")
            return 1
        }
        try {
            if (target.isNotEmpty() && !ctx.mount.exists(target)) {
                ctx.out.println("ls: no such path: ${args.firstOrNull() ?: target}")
                return 1
            }
            // Pointed at a single file: render one entry.
            if (target.isNotEmpty() && !ctx.mount.isDirectory(target)) {
                ctx.out.println(formatEntry(MountPaths.name(target), isDir = false, size = ctx.mount.size(target)))
                return 0
            }
            val entries = ctx.mount.list(target)
            if (entries.isEmpty()) {
                ctx.out.println("(empty)")
                return 0
            }
            for (entry in entries) {
                val full = MountPaths.join(target, entry)
                val isDir = ctx.mount.isDirectory(full)
                val size = if (isDir) 0L else ctx.mount.size(full)
                ctx.out.println(formatEntry(entry, isDir, size))
            }
            return 0
        } catch (e: StorageException) {
            ctx.out.println("ls: ${e.message}")
            return 1
        }
    }

    private fun formatEntry(name: String, isDir: Boolean, size: Long): String {
        val type = if (isDir) "d" else "-"
        val sizeStr = if (isDir) "-" else formatSize(size)
        val displayName = if (isDir) "$name/" else name
        return "%s %8s  %s".format(type, sizeStr, displayName)
    }

    private fun formatSize(b: Long): String = when {
        b >= 1024L * 1024L -> "%.1fM".format(b / (1024.0 * 1024.0))
        b >= 1024L -> "%.1fK".format(b / 1024.0)
        else -> b.toString()
    }
}

class MkdirCommand : ShellCommand {
    override val name = "mkdir"
    override val summary = "create a directory"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.out.println("mkdir: missing operand"); return 2 }
        val target = try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("mkdir: ${e.message}")
            return 1
        }
        if (target.isEmpty()) { ctx.out.println("mkdir: cannot create root"); return 1 }
        try {
            ctx.mount.makeDirectory(target)
            return 0
        } catch (e: StorageException) {
            ctx.out.println("mkdir: ${e.message}")
            return 1
        }
    }
}

class RmCommand : ShellCommand {
    override val name = "rm"
    override val summary = "remove file or directory"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.out.println("rm: missing operand"); return 2 }
        val target = try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("rm: ${e.message}")
            return 1
        }
        if (target.isEmpty()) { ctx.out.println("rm: cannot remove root"); return 1 }
        if (!ctx.mount.exists(target)) {
            ctx.out.println("rm: no such path: ${args[0]}")
            return 1
        }
        try {
            ctx.mount.delete(target)
            return 0
        } catch (e: StorageException) {
            ctx.out.println("rm: ${e.message}")
            return 1
        }
    }
}

class CatCommand : ShellCommand {
    override val name = "cat"
    override val summary = "print file contents"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.out.println("cat: missing operand"); return 2 }
        val target = try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("cat: ${e.message}")
            return 1
        }
        if (!ctx.mount.exists(target)) {
            ctx.out.println("cat: no such file: ${args[0]}")
            return 1
        }
        if (ctx.mount.isDirectory(target)) {
            ctx.out.println("cat: is a directory: ${args[0]}")
            return 1
        }
        try {
            ctx.mount.openForRead(target).use { ch ->
                val size = ch.size().toInt()
                val buf = ByteBuffer.allocate(size)
                while (buf.hasRemaining()) {
                    val n = ch.read(buf)
                    if (n < 0) break
                }
                val text = String(buf.array(), 0, buf.position(), Charsets.UTF_8)
                if (text.isEmpty()) return 0
                for (line in text.split('\n')) ctx.out.println(line)
            }
            return 0
        } catch (e: StorageException) {
            ctx.out.println("cat: ${e.message}")
            return 1
        }
    }
}

class WriteCommand : ShellCommand {
    override val name = "write"
    override val summary = "write text to a file (overwrites)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.size < 2) {
            ctx.out.println("usage: write <file> <text...>")
            return 2
        }
        val target = try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("write: ${e.message}")
            return 1
        }
        val text = args.drop(1).joinToString(" ")
        try {
            ctx.mount.openForWrite(target).use { ch ->
                val buf = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                while (buf.hasRemaining()) {
                    val n = ch.write(buf)
                    if (n <= 0) {
                        ctx.out.println("write: out of space (${buf.remaining()} bytes unwritten)")
                        return 1
                    }
                }
            }
            return 0
        } catch (e: StorageException) {
            ctx.out.println("write: ${e.message}")
            return 1
        }
    }
}

class ClearCommand : ShellCommand {
    override val name = "clear"
    override val summary = "clear the terminal"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        ctx.out.clear()
        return 0
    }
}

class IdCommand : ShellCommand {
    override val name = "id"
    override val summary = "print computer info"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        ctx.out.println("computer id : ${ctx.metadata.computerId}")
        ctx.out.println("channel     : ${ctx.metadata.channelId}")
        ctx.out.println("location    : ${ctx.metadata.location}")
        return 0
    }
}

/**
 * Dispatches `run <file>` to the right [ScriptHost] based on file extension.
 * v0 maps `.lua → CobaltLuaHost`, `.js → RhinoJSHost`. Unknown extensions error
 * cleanly so users get told what's supported.
 *
 * Spawns the script ASYNCHRONOUSLY via the session's [com.brewingcoder.oc2.platform.os.ScriptRunner]
 * — returns immediately so the shell stays responsive. Output appears live as
 * the script prints; the BE tick drains output and pushes to the open terminal.
 *
 * Concurrency: at most one script per computer at a time. Attempting to start
 * a second returns "already running" — use `kill` to abort the active one.
 */
class RunCommand(private val hostsByExt: Map<String, ScriptHost>) : ShellCommand {
    override val name = "run"
    override val summary = "run a script in the foreground (e.g. run hello.lua)"
    override fun run(args: List<String>, ctx: ShellContext): Int =
        ScriptStarter.start(args, ctx, hostsByExt, foreground = true)
}

class BgCommand(private val hostsByExt: Map<String, ScriptHost>) : ShellCommand {
    override val name = "bg"
    override val summary = "run a script in the background (output not shown)"
    override fun run(args: List<String>, ctx: ShellContext): Int =
        ScriptStarter.start(args, ctx, hostsByExt, foreground = false)
}

/** Shared start logic for [RunCommand] + [BgCommand]. */
internal object ScriptStarter {
    fun start(args: List<String>, ctx: ShellContext, hostsByExt: Map<String, ScriptHost>, foreground: Boolean): Int {
        val cmdName = if (foreground) "run" else "bg"
        if (args.isEmpty()) {
            ctx.out.println("usage: $cmdName <file.lua|file.js>")
            return 2
        }
        val target = try {
            PathResolver.resolve(ctx.cwd, args[0])
        } catch (e: StorageException) {
            ctx.out.println("$cmdName: ${e.message}"); return 1
        }
        if (!ctx.mount.exists(target)) { ctx.out.println("$cmdName: no such file: ${args[0]}"); return 1 }
        if (ctx.mount.isDirectory(target)) { ctx.out.println("$cmdName: is a directory: ${args[0]}"); return 1 }
        val ext = MountPaths.name(target).substringAfterLast('.', "").lowercase()
        val host = hostsByExt[ext] ?: run {
            ctx.out.println("$cmdName: no host for extension '.$ext'. supported: ${hostsByExt.keys.sorted().joinToString { ".$it" }}")
            return 1
        }
        val source = try {
            ctx.mount.openForRead(target).use { ch ->
                val size = ch.size().toInt()
                val buf = ByteBuffer.allocate(size)
                while (buf.hasRemaining()) { val n = ch.read(buf); if (n < 0) break }
                String(buf.array(), 0, buf.position(), Charsets.UTF_8)
            }
        } catch (e: StorageException) {
            ctx.out.println("$cmdName: ${e.message}"); return 1
        }
        val chunkName = MountPaths.name(target)
        return if (foreground) {
            val r = ctx.scriptRunner.start(host, source, chunkName, ctx.mount, ctx.cwd, ctx.peripheralFinder, ctx.peripheralLister, ctx.networkAccess)
            when (r) {
                is com.brewingcoder.oc2.platform.os.ScriptRunner.StartResult.Started -> {
                    ctx.out.println("started '$chunkName' (pid=${r.handle.pid}). use `jobs` / `kill` to manage.")
                    0
                }
                is com.brewingcoder.oc2.platform.os.ScriptRunner.StartResult.AlreadyRunning -> {
                    ctx.out.println("run: '${r.current.chunkName}' (pid=${r.current.pid}) already running. `bg` runs in the background instead.")
                    1
                }
            }
        } else {
            val r = ctx.scriptRunner.startBackground(host, source, chunkName, ctx.mount, ctx.cwd, ctx.peripheralFinder, ctx.peripheralLister, ctx.networkAccess)
            ctx.out.println("started '$chunkName' in background (pid=${r.handle.pid}).")
            0
        }
    }
}

class PsCommand : ShellCommand {
    override val name = "ps"
    override val summary = "show foreground script (alias for jobs's first row)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        val cur = ctx.scriptRunner.current()
        if (cur == null) ctx.out.println("(no foreground script)")
        else {
            val state = if (cur.isDone()) "done" else "running"
            ctx.out.println("pid=${cur.pid}  $state  ${cur.chunkName}")
        }
        return 0
    }
}

class JobsCommand : ShellCommand {
    override val name = "jobs"
    override val summary = "list every running script (foreground + background)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        val all = ctx.scriptRunner.all()
        if (all.isEmpty()) { ctx.out.println("(no jobs)"); return 0 }
        val fg = ctx.scriptRunner.current()
        for (h in all) {
            val tag = if (h === fg) "fg" else "bg"
            val state = if (h.isDone()) "done" else "running"
            ctx.out.println("[${h.pid}] $tag $state  ${h.chunkName}")
        }
        return 0
    }
}

class TailCommand : ShellCommand {
    override val name = "tail"
    override val summary = "show recent output of any running/finished script (tail [pid] [-n N])"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        // Parse: pid (optional, defaults to most recent) and -n N (default 20)
        var pid: Int? = null
        var n: Int = 20
        var i = 0
        while (i < args.size) {
            when (val a = args[i]) {
                "-n" -> {
                    val v = args.getOrNull(i + 1) ?: run {
                        ctx.out.println("tail: -n requires a number")
                        return 2
                    }
                    n = v.toIntOrNull() ?: run {
                        ctx.out.println("tail: -n value must be a number")
                        return 2
                    }
                    i += 2
                }
                else -> {
                    val parsed = a.toIntOrNull() ?: run {
                        ctx.out.println("tail: pid must be a number, got '$a'")
                        return 2
                    }
                    pid = parsed
                    i += 1
                }
            }
        }
        val all = ctx.scriptRunner.all()
        if (all.isEmpty()) { ctx.out.println("(no scripts to tail)"); return 0 }
        val target = if (pid == null) all.last() else all.firstOrNull { it.pid == pid }
            ?: run {
                ctx.out.println("tail: no script with pid=$pid")
                return 1
            }
        val lines = target.tail()
        val state = if (target.isDone()) "done" else "running"
        ctx.out.println("[${target.pid}] ${target.chunkName} ($state) — last ${minOf(n, lines.size)} of ${lines.size}")
        val from = maxOf(0, lines.size - n)
        for (j in from until lines.size) ctx.out.println(lines[j])
        return 0
    }
}

class FgCommand : ShellCommand {
    override val name = "fg"
    override val summary = "promote a background script to foreground (only when no fg)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) { ctx.out.println("usage: fg <pid>"); return 2 }
        val pid = args[0].toIntOrNull() ?: run { ctx.out.println("fg: pid must be a number"); return 2 }
        val ok = ctx.scriptRunner.moveToForeground(pid)
        if (!ok) {
            ctx.out.println("fg: can't promote — pid not in background or foreground busy")
            return 1
        }
        ctx.out.println("promoted pid=$pid to foreground")
        return 0
    }
}

class KillCommand : ShellCommand {
    override val name = "kill"
    override val summary = "kill foreground (or kill <pid> for any)"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        if (args.isEmpty()) {
            val killed = ctx.scriptRunner.kill()
            ctx.out.println(if (killed) "killed." else "(no foreground script to kill)")
            return if (killed) 0 else 1
        }
        val pid = args[0].toIntOrNull() ?: run { ctx.out.println("kill: pid must be a number"); return 2 }
        val killed = ctx.scriptRunner.killByPid(pid)
        ctx.out.println(if (killed) "killed pid=$pid." else "kill: no such pid (or already done)")
        return if (killed) 0 else 1
    }
}

class DfCommand : ShellCommand {
    override val name = "df"
    override val summary = "show disk usage"
    override fun run(args: List<String>, ctx: ShellContext): Int {
        val cap = ctx.mount.capacity()
        val free = ctx.mount.remainingSpace()
        val used = (cap - free).coerceAtLeast(0L)
        ctx.out.println("capacity:  ${formatBytes(cap)}")
        ctx.out.println("used:      ${formatBytes(used)}")
        ctx.out.println("free:      ${formatBytes(free)}")
        return 0
    }
    private fun formatBytes(n: Long): String = when {
        n == Long.MAX_VALUE -> "unlimited"
        n >= 1024L * 1024L -> "%.2f MiB".format(n / (1024.0 * 1024.0))
        n >= 1024L -> "%.2f KiB".format(n / 1024.0)
        else -> "$n B"
    }
}
