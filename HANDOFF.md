# Session handoff — 2026-04-18, R1 week 1 (diagnostic shell)

## What landed

End-to-end working **diagnostic shell** on the Tier-1 Computer block. Right-click a Computer → terminal opens → type commands → see real I/O against the per-computer mount we built earlier today.

**New files:**

- `platform/os/Shell.kt` — Shell + ShellSession + ShellContext + ShellResult + ShellOutput + ShellCommand + ShellMetadata (Rule D: zero MC imports)
- `platform/os/Tokenizer.kt` — quote-aware whitespace tokenizer (double + single quotes, `\` escapes in double quotes)
- `platform/os/PathResolver.kt` — `cd`/`ls`/`cat`/etc. path resolution against cwd (absolute, relative, `..`, `.`)
- `platform/os/commands/Commands.kt` — all 12 commands + `DefaultCommands.build()` factory
- `network/RunCommandPayload.kt` — client→server, with 16-block anti-grief + 512-char cap
- `network/TerminalOutputPayload.kt` — server→client, carries lines + `clearFirst` flag
- `client/TerminalOutputDispatcher.kt` — single-subscriber map routing payloads to the open Screen

**Modified files:**

- `network/OC2Payloads.kt` — registered both new payloads
- `block/ComputerBlockEntity.kt` — added `executeShellCommand(input): ShellResult` + lazy ShellSession
- `client/screen/ComputerScreen.kt` — input line + blinking cursor + submit-on-Enter + dispatcher registration

## Commands available

| Command | Behavior |
|---|---|
| `help` | list all commands |
| `echo <args...>` | print joined args |
| `pwd` | print working directory |
| `cd [dir]` | change directory (no args → root; `..` supported) |
| `ls [path]` | list directory (default cwd); dirs show with trailing `/` |
| `mkdir <dir>` | create directory |
| `rm <path>` | remove file or directory (recursive) |
| `cat <file>` | print file contents |
| `write <file> <text...>` | write text to file (overwrites) |
| `clear` | wipe terminal buffer |
| `id` | print computer id + channel + location |
| `df` | print capacity / used / free |

Quoting: `write hi.txt "hello world"` works as expected.

## Tests

**82/82 passing.** New coverage:
- TokenizerTest — 8 tests (quotes, escapes, whitespace)
- PathResolverTest — 8 tests (relative, absolute, `..`, escape-root rejection)
- ShellTest — 22 tests (each command + error paths + live metadata)

Run: `./gradlew test`

## Manual test plan (what to do when you launch)

1. `./gradlew runClient` (window is now 1900×1280 from the prior config)
2. Give yourself a Computer: `/give @p oc2:computer 1`
3. Place it, right-click to open the GUI
4. At the prompt (`>`), type:

```
help                         # lists commands
echo hello                   # prints "hello"
pwd                          # prints "/"
id                           # shows computer id (first-ever assignment — watch the server log for "assigned id 0")
df                           # shows 2.00 MiB capacity, tiny used, ~2.00 MiB free
mkdir projects
cd projects
mkdir r1
pwd                          # prints "/projects"
cd r1
pwd                          # prints "/projects/r1"
write hello.txt "hi there"
ls                           # shows hello.txt
cat hello.txt                # prints "hi there"
cd
pwd                          # prints "/"
ls                           # shows projects/
clear                        # wipes buffer
```

5. **Reload test:** `/reload` or F3+A won't do it — actually close the world (Save & Quit) and reopen. The file `hello.txt` should still be at `/projects/r1/hello.txt`. Disk is at `<world>/oc2/computer/<id>/projects/r1/hello.txt` — feel free to poke at it from the host FS.

6. **Multi-computer test:** place two Computers side by side. Confirm each gets a distinct id (check with `id`) and has its own isolated filesystem.

7. **Anti-grief test:** open GUI, walk 20+ blocks away (GUI stays open), type a command. Server rejects it with a warn log; client sees no output. (Working as designed — would otherwise let you command computers across your base.)

## What's broken / known issues

- **No command history.** No up-arrow recall. R1 week 2+ when the script VM shell takes over.
- **No scrollback.** Buffer caps at 256 lines; oldest drops off. Output beyond screen rows scrolls.
- **No tab completion.** Same — belongs in the script-hosted shell.
- **No reflow on window resize.** Terminal rect recomputes but already-wrapped output stays as-is. Minor.
- **Power/reset buttons** still no-op. They print a placeholder to the terminal.

## What comes next (R1 week 2)

Cobalt Lua host:
1. `implementation 'org.squiddev:Cobalt:0.9.x'` — verify on JDK 21
2. `platform/script/ScriptHost` interface
3. `CobaltLuaHost` impl with filesystem binding + output capture + cooperative yield
4. Shell gains `run <file.lua>` command — dispatch to host
5. Tests for `print("hi")`, `fs.list()`, long-running cooperative yield

Then Rhino parallel. Then ROM scripts. That's R1 weeks 2-4. R2 (Sedna / Linux / Control Plane) is a separate ~8-week block after R1 feedback.

## File map

```
src/main/kotlin/com/brewingcoder/oc2/
  platform/
    storage/         # Rule D-clean mount API (landed earlier today)
    os/
      Shell.kt
      Tokenizer.kt
      PathResolver.kt
      commands/Commands.kt
  storage/           # MC-coupled disk impls (landed earlier today)
  network/
    RunCommandPayload.kt
    TerminalOutputPayload.kt
    OC2Payloads.kt   # registrations
  client/
    TerminalOutputDispatcher.kt
    screen/ComputerScreen.kt
  block/
    ComputerBlockEntity.kt   # executeShellCommand() + lazy shell session
```

## Session stats

- Commits to make: ~15 new files, ~4 modified
- Test count: 42 → 82 (40 new)
- Build artifact: `build/libs/oc2-0.0.1.jar` (168 KB)
- Clean build: ~1 second incremental, fully green

Have fun.
